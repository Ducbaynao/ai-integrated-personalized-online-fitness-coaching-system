package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiFeatureDisabledException;
import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiTimeoutException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiKnowledgeQueryUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiRunF01UseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CompleteF01AiRunCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CreateF01AiRunCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F01AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiValidationResult;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrator for F01 General Fitness Knowledge inquiries.
 * Coordinates request creation, state transitions, external AI invocation (without holding DB transactions),
 * strict output validation against contract invariants, and terminal run completion with provenance.
 */
@Service
public class AiKnowledgeOrchestrationService implements AiKnowledgeQueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(AiKnowledgeOrchestrationService.class);

    private final AiRunF01UseCase aiRunUseCase;
    private final F01AiServiceClient aiServiceClient;
    private final AiProvenanceRepository provenanceRepository;
    private final ObjectMapper objectMapper;
    private final AiServiceProperties aiProperties;
    private final F01RuntimeOutputValidator runtimeValidator;

    public AiKnowledgeOrchestrationService(
            AiRunF01UseCase aiRunUseCase,
            F01AiServiceClient aiServiceClient,
            AiProvenanceRepository provenanceRepository,
            ObjectMapper objectMapper,
            AiServiceProperties aiProperties,
            F01RuntimeOutputValidator runtimeValidator
    ) {
        this.aiRunUseCase = aiRunUseCase;
        this.aiServiceClient = aiServiceClient;
        this.provenanceRepository = provenanceRepository;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
        this.runtimeValidator = runtimeValidator;
    }

    @Override
    public F01KnowledgeQueryResponse queryKnowledge(UUID actorId, F01KnowledgeQueryApiRequest request) {
        // 0. Feature-disable check: when disabled, reject before creating Run or calling AI
        if (Boolean.FALSE.equals(aiProperties.enabled())) {
            throw new AiFeatureDisabledException("AI general fitness knowledge assistance is currently disabled by administrator configuration.");
        }

        CreateF01AiRunCommand cmd = new CreateF01AiRunCommand(
                request.question(),
                request.language(),
                request.exerciseId(),
                request.correlationId()
        );

        // 1. Transaction 1: Create AI Run and persist initial context snapshot
        AiRunF01UseCase.CreatedF01RunContext runContext = aiRunUseCase.createF01RunContext(actorId, cmd);
        AiRun run = runContext.run();
        F01InternalContextEnvelope envelope = runContext.envelope();

        // 2. Transaction 2: Transition from QUEUED to RUNNING
        Instant startedAt = Instant.now();
        aiRunUseCase.transitionRunStatus(run.id(), AiRunStatus.QUEUED, AiRunStatus.RUNNING, startedAt, null);

        // 3. Invoke external AI Service (OUTSIDE ANY DATABASE TRANSACTION)
        long startMs = System.currentTimeMillis();
        F01KnowledgeQueryResponse serviceResponse = null;
        AiRunStatus terminalStatus = AiRunStatus.FAILED;
        String errorCode = null;
        String errorMessage = null;

        try {
            serviceResponse = aiServiceClient.queryKnowledge(envelope);
        } catch (AiTimeoutException ex) {
            log.warn("AI service timeout for run {}: {}", run.id(), ex.getMessage());
            terminalStatus = AiRunStatus.FAILED;
            errorCode = "TIMEOUT";
            errorMessage = ex.getMessage();
        } catch (AiProviderException ex) {
            log.warn("AI provider failure for run {}: {}", run.id(), ex.getMessage());
            terminalStatus = AiRunStatus.FAILED;
            errorCode = "MODEL_ERROR";
            errorMessage = ex.getMessage();
        } catch (AiOutputValidationException ex) {
            log.warn("AI output validation failure for run {}: {}", run.id(), ex.getMessage());
            terminalStatus = AiRunStatus.REJECTED_BY_VALIDATOR;
            errorCode = "VALIDATION_FAILED";
            errorMessage = ex.getMessage();
        } catch (Exception ex) {
            log.error("Unexpected failure during AI orchestration for run {}", run.id(), ex);
            terminalStatus = AiRunStatus.FAILED;
            errorCode = "PROCESSING_ERROR";
            errorMessage = "An unexpected processing error occurred.";
        }

        long latencyMs = System.currentTimeMillis() - startMs;

        // 4. Validate AI service response against contract rules & allowed sources
        boolean validationPassed = false;
        String validationDetail = "PASSED";

        if (serviceResponse != null) {
            if (!run.id().equals(serviceResponse.runId())) {
                log.error("AI service returned mismatched runId: expected {} but was {}", run.id(), serviceResponse.runId());
                terminalStatus = AiRunStatus.FAILED;
                errorCode = "PROCESSING_ERROR";
                errorMessage = "Mismatched runId returned by AI service.";
                serviceResponse = null;
                validationPassed = false;
                validationDetail = "Mismatched runId";
            } else {
                F01RuntimeOutputValidator.ValidationResult valResult = runtimeValidator.validate(serviceResponse, envelope);
                if (!valResult.isValid()) {
                    log.warn("AI service output failed contract invariants for run {}: {}", run.id(), valResult.failureReason());
                    terminalStatus = AiRunStatus.REJECTED_BY_VALIDATOR;
                    errorCode = "VALIDATION_FAILED";
                    errorMessage = "AI service output violated contract invariants: " + valResult.failureReason();
                    serviceResponse = null;
                    validationPassed = false;
                    validationDetail = valResult.failureReason();
                } else {
                    terminalStatus = mapToTerminalStatus(serviceResponse.runStatus());
                    validationPassed = true;
                    validationDetail = "PASSED";
                }
            }
        } else {
            validationPassed = false;
            validationDetail = errorCode != null ? errorCode : "FAILURE";
        }

        // 5. Transaction 3: Complete AI Run and persist provenance
        Instant completedAt = Instant.now();
        if (serviceResponse != null) {
            // Persist validation result
            provenanceRepository.saveValidationResults(run.id(), List.of(
                    new AiValidationResult(
                            UUID.randomUUID(),
                            run.id(),
                            "F01_SCHEMA_DOMAIN_VALIDATOR",
                            true,
                            "INFO",
                            "{\"result\": \"PASSED\"}",
                            completedAt
                    )
            ));

            String outputPayloadJson = toJson(serviceResponse);
            aiRunUseCase.completeF01Run(new CompleteF01AiRunCommand(
                    run.id(),
                    terminalStatus,
                    outputPayloadJson,
                    null,
                    null,
                    serviceResponse.usage() != null ? serviceResponse.usage().promptTokens() : null,
                    serviceResponse.usage() != null ? serviceResponse.usage().completionTokens() : null,
                    serviceResponse.usage() != null ? serviceResponse.usage().totalTokens() : null,
                    (int) latencyMs,
                    completedAt
            ));

            return serviceResponse;
        } else {
            // Failure / Rejected completion
            provenanceRepository.saveValidationResults(run.id(), List.of(
                    new AiValidationResult(
                            UUID.randomUUID(),
                            run.id(),
                            "F01_SCHEMA_DOMAIN_VALIDATOR",
                            false,
                            "HIGH",
                            "{\"errorCode\": \"" + errorCode + "\", \"detail\": \"" + validationDetail + "\"}",
                            completedAt
                    )
            ));

            aiRunUseCase.completeF01Run(new CompleteF01AiRunCommand(
                    run.id(),
                    terminalStatus,
                    null,
                    errorCode,
                    errorMessage,
                    null,
                    null,
                    null,
                    (int) latencyMs,
                    completedAt
            ));

            String asOf = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
            return new F01KnowledgeQueryResponse(
                    run.id(),
                    envelope.correlationId(),
                    terminalStatus.name(),
                    null, // resultType is not present when FAILED or REJECTED_BY_VALIDATOR without BLOCKED
                    errorCode,
                    null,
                    null,
                    null,
                    null,
                    asOf,
                    null,
                    new F01KnowledgeQueryResponse.ErrorDto(errorCode, errorMessage),
                    null
            );
        }
    }

    private AiRunStatus mapToTerminalStatus(String statusStr) {
        if ("SUCCEEDED".equalsIgnoreCase(statusStr)) {
            return AiRunStatus.SUCCEEDED;
        }
        if ("REJECTED_BY_VALIDATOR".equalsIgnoreCase(statusStr)) {
            return AiRunStatus.REJECTED_BY_VALIDATOR;
        }
        return AiRunStatus.FAILED;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }
}
