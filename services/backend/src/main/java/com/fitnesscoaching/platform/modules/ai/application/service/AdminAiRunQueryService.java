package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AdminAiRunQueryUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunKnowledgeChunk;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiValidationResult;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AdminAiRunQueryService implements AdminAiRunQueryUseCase {

    private final AiRunRepository runRepository;
    private final AiProvenanceRepository provenanceRepository;
    private final AuditService auditService;
    private final UserAccountStatusQuery accountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final UserPermissionQuery userPermissionQuery;

    public AdminAiRunQueryService(
            AiRunRepository runRepository,
            AiProvenanceRepository provenanceRepository,
            AuditService auditService,
            UserAccountStatusQuery accountStatusQuery,
            UserRoleQuery userRoleQuery,
            UserPermissionQuery userPermissionQuery
    ) {
        this.runRepository = runRepository;
        this.provenanceRepository = provenanceRepository;
        this.auditService = auditService;
        this.accountStatusQuery = accountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.userPermissionQuery = userPermissionQuery;
    }

    @Override
    public AdminAiRunPageDto listRuns(UUID actorId, int page, int size, AiRunStatus status, String requestType) {
        validateAdminActor(actorId);

        int sanitizedPage = Math.max(0, page);
        int sanitizedSize = Math.max(1, Math.min(100, size));
        int offset = sanitizedPage * sanitizedSize;

        List<AiRun> runs = runRepository.findRuns(offset, sanitizedSize, status, requestType);
        long total = runRepository.countRuns(status, requestType);
        int totalPages = sanitizedSize > 0 ? (int) Math.ceil((double) total / sanitizedSize) : 0;

        List<AdminAiRunSummaryDto> dtos = runs.stream()
                .map(this::toSummaryDto)
                .toList();

        return new AdminAiRunPageDto(dtos, total, sanitizedPage, sanitizedSize, totalPages);
    }

    @Override
    @Transactional
    public AdminAiRunDetailDto getRunDetail(UUID actorId, UUID runId) {
        validateAdminActor(actorId);

        if (runId == null) {
            throw new IllegalArgumentException("Run ID must not be null");
        }

        AiRun run = runRepository.findById(runId)
                .orElseThrow(() -> new AiRunNotFoundException("AI Run not found with ID: " + runId));

        List<AiValidationResult> validationResults = provenanceRepository.findValidationResultsByRunId(runId);
        List<AiRunKnowledgeChunk> chunkReferences = provenanceRepository.findKnowledgeChunkReferencesByRunId(runId);

        // Record governance audit log for privileged operational inspection
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(actorId)
                .actorRole("ADMIN")
                .action("VIEW_AI_RUN_DETAIL")
                .targetType("AI_RUN")
                .targetId(runId)
                .metadataJson("{\"requestType\":\"" + run.requestType() + "\",\"status\":\"" + run.status().name() + "\"}")
                .occurredAt(Instant.now())
                .build());

        List<AdminAiValidationDto> validationDtos = validationResults.stream()
                .map(v -> new AdminAiValidationDto(
                        v.id(),
                        v.validatorCode(),
                        v.passed(),
                        v.severity(),
                        v.details(),
                        v.createdAt()
                ))
                .toList();

        List<AdminAiRunChunkDto> chunkDtos = chunkReferences.stream()
                .map(c -> new AdminAiRunChunkDto(
                        c.knowledgeChunkId(),
                        c.rank(),
                        c.similarityScore()
                ))
                .toList();

        Integer totalTokens = calculateTotalTokens(run.inputTokenCount(), run.outputTokenCount());

        return new AdminAiRunDetailDto(
                run.id(),
                run.requestType(),
                run.status().name(),
                run.purpose(),
                run.correlationId(),
                run.queuedAt(),
                run.startedAt(),
                run.completedAt(),
                run.latencyMs(),
                run.errorCode(),
                run.errorMessage(),
                run.inputTokenCount(),
                run.outputTokenCount(),
                totalTokens,
                run.evaluationOnly(),
                run.createdAt(),
                validationDtos,
                chunkDtos
        );
    }

    private AdminAiRunSummaryDto toSummaryDto(AiRun run) {
        Integer totalTokens = calculateTotalTokens(run.inputTokenCount(), run.outputTokenCount());
        return new AdminAiRunSummaryDto(
                run.id(),
                run.requestType(),
                run.status().name(),
                run.purpose(),
                run.correlationId(),
                run.queuedAt(),
                run.startedAt(),
                run.completedAt(),
                run.latencyMs(),
                run.errorCode(),
                run.errorMessage(),
                run.inputTokenCount(),
                run.outputTokenCount(),
                totalTokens,
                run.evaluationOnly(),
                run.createdAt()
        );
    }

    private Integer calculateTotalTokens(Integer input, Integer output) {
        if (input == null && output == null) {
            return null;
        }
        int total = 0;
        if (input != null) {
            total += input;
        }
        if (output != null) {
            total += output;
        }
        return total;
    }

    private void validateAdminActor(UUID actorId) {
        if (actorId == null) {
            throw new AccessDeniedException("Authenticated actor ID is required");
        }
        AccountStatus status = accountStatusQuery.getAccountStatus(actorId)
                .orElseThrow(() -> new AccountUnavailableException("Actor account does not exist"));
        if (status != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Actor account is not active");
        }
        boolean isAdmin = userRoleQuery.hasActiveRole(actorId, "ADMIN");
        boolean hasRunViewPerm = userPermissionQuery.hasActivePermission(actorId, "AI_RUN_VIEW");
        boolean hasConfigPerm = userPermissionQuery.hasActivePermission(actorId, "SYSTEM_CONFIG_MANAGE");

        if (!isAdmin && !hasRunViewPerm && !hasConfigPerm) {
            throw new AccessDeniedException("User lacks ADMIN or AI_RUN_VIEW authority to access AI run telemetry");
        }
    }
}
