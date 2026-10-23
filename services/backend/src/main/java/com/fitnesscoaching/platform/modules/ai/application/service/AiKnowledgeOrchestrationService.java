package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
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
import com.fitnesscoaching.platform.modules.ai.domain.AiRunKnowledgeChunk;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiValidationResult;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase;
import com.fitnesscoaching.platform.modules.content.application.service.F01RunEvidenceTracker;
import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private final KnowledgeRetrievalUseCase retrievalUseCase;
    private final F01RunEvidenceTracker evidenceTracker;
    private final ObjectMapper objectMapper;
    private final AiServiceProperties aiProperties;
    private final F01RuntimeOutputValidator runtimeValidator;

    public AiKnowledgeOrchestrationService(
            AiRunF01UseCase aiRunUseCase,
            F01AiServiceClient aiServiceClient,
            AiProvenanceRepository provenanceRepository,
            KnowledgeRetrievalUseCase retrievalUseCase,
            F01RunEvidenceTracker evidenceTracker,
            ObjectMapper objectMapper,
            AiServiceProperties aiProperties,
            F01RuntimeOutputValidator runtimeValidator
    ) {
        this.aiRunUseCase = aiRunUseCase;
        this.aiServiceClient = aiServiceClient;
        this.provenanceRepository = provenanceRepository;
        this.retrievalUseCase = retrievalUseCase;
        this.evidenceTracker = evidenceTracker;
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

        try {
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

                        // Strict evidence grounding: verify all citations against the original evidence set of THIS AI Run
                        // and verify current database eligibility (active, technical verification passed, not deleted)
                        if (serviceResponse.citations() != null && !serviceResponse.citations().isEmpty()) {
                            List<RetrievedKnowledgeChunk> originalEvidence = evidenceTracker.getRunEvidence(run.id());

                            for (F01KnowledgeQueryResponse.CitationDto citation : serviceResponse.citations()) {
                                int sectionIdx = citation.locator() != null && citation.locator().sectionIndex() != null
                                        ? citation.locator().sectionIndex()
                                        : 0;
                                Optional<RetrievedKnowledgeChunk> matchedOpt = originalEvidence.stream()
                                        .filter(c -> c.documentCode().equals(citation.documentId())
                                                && c.versionLabel().equals(citation.version())
                                                && c.sectionIndex() == sectionIdx)
                                        .findFirst();

                                if (matchedOpt.isEmpty()) {
                                    log.warn("AI response contained citation outside original run evidence set: run={}, doc={}, version={}, section={}",
                                            run.id(), citation.documentId(), citation.version(), sectionIdx);
                                    terminalStatus = AiRunStatus.REJECTED_BY_VALIDATOR;
                                    errorCode = "UNVERIFIED_CITATION";
                                    errorMessage = "Citation is not grounded in the original evidence set supplied for this run: "
                                            + citation.documentId() + " (v" + citation.version() + ", section " + sectionIdx + ")";
                                    serviceResponse = null;
                                    validationPassed = false;
                                    validationDetail = "UNGROUNDED_CITATION_NOT_IN_RUN_EVIDENCE: " + citation.documentId() + " v" + citation.version();
                                    break;
                                }

                                RetrievedKnowledgeChunk matched = matchedOpt.get();

                                // Complete citation validation against matched evidence (P2 review finding)
                                if (citation.sourceId() != null && !citation.sourceId().isBlank()
                                        && !citation.sourceId().equals(matched.sourceId())) {
                                    log.warn("Citation sourceId mismatch: expected {} but was {}", matched.sourceId(), citation.sourceId());
                                    terminalStatus = AiRunStatus.REJECTED_BY_VALIDATOR;
                                    errorCode = "UNVERIFIED_CITATION";
                                    errorMessage = "Citation sourceId does not match evidence: " + citation.sourceId();
                                    serviceResponse = null;
                                    validationPassed = false;
                                    validationDetail = "CITATION_SOURCE_ID_MISMATCH";
                                    break;
                                }

                                if (citation.locator() != null && citation.locator().sourcePath() != null && !citation.locator().sourcePath().isBlank()
                                        && !citation.locator().sourcePath().equals(matched.contentPath())) {
                                    log.warn("Citation sourcePath mismatch: expected {} but was {}", matched.contentPath(), citation.locator().sourcePath());
                                    terminalStatus = AiRunStatus.REJECTED_BY_VALIDATOR;
                                    errorCode = "UNVERIFIED_CITATION";
                                    errorMessage = "Citation locator sourcePath does not match evidence: " + citation.locator().sourcePath();
                                    serviceResponse = null;
                                    validationPassed = false;
                                    validationDetail = "CITATION_SOURCE_PATH_MISMATCH";
                                    break;
                                }

                                if (citation.quoteOrExcerpt() != null && !citation.quoteOrExcerpt().isBlank()) {
                                    String quote = citation.quoteOrExcerpt().trim();
                                    if (quote.endsWith("...")) {
                                        quote = quote.substring(0, quote.length() - 3).trim();
                                    }
                                    String normContent = matched.content().toLowerCase().replaceAll("[^\\p{L}\\p{Nd}\\s]", " ").replaceAll("\\s+", " ").trim();
                                    String normQuote = quote.toLowerCase().replaceAll("[^\\p{L}\\p{Nd}\\s]", " ").replaceAll("\\s+", " ").trim();
                                    if (!normQuote.isBlank() && !normContent.contains(normQuote)) {
                                        log.warn("Citation quoteOrExcerpt not found in chunk content for run {}", run.id());
                                        terminalStatus = AiRunStatus.REJECTED_BY_VALIDATOR;
                                        errorCode = "UNVERIFIED_CITATION";
                                        errorMessage = "Citation quoteOrExcerpt is not supported by the retrieved chunk content.";
                                        serviceResponse = null;
                                        validationPassed = false;
                                        validationDetail = "CITATION_QUOTE_NOT_IN_CHUNK_CONTENT";
                                        break;
                                    }
                                }

                                boolean eligible = retrievalUseCase.isChunkEligible(citation.documentId(), citation.version(), sectionIdx);
                                if (!eligible) {
                                    log.warn("AI response citation matched run evidence but is no longer active/eligible in database: run={}, doc={}, version={}, section={}",
                                            run.id(), citation.documentId(), citation.version(), sectionIdx);
                                    terminalStatus = AiRunStatus.REJECTED_BY_VALIDATOR;
                                    errorCode = "INELIGIBLE_CITATION";
                                    errorMessage = "Citation references knowledge that is no longer active or eligible in database: "
                                            + citation.documentId() + " (v" + citation.version() + ", section " + sectionIdx + ")";
                                    serviceResponse = null;
                                    validationPassed = false;
                                    validationDetail = "CITATION_INELIGIBLE_IN_DATABASE: " + citation.documentId() + " v" + citation.version();
                                    break;
                                }
                            }
                        }
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

                // Persist knowledge chunk citations in ai_run_knowledge_chunks from original evidence
                if (serviceResponse.citations() != null && !serviceResponse.citations().isEmpty()) {
                    List<RetrievedKnowledgeChunk> originalEvidence = evidenceTracker.getRunEvidence(run.id());
                    List<AiRunKnowledgeChunk> chunkRefs = new ArrayList<>();
                    int rank = 1;
                    for (F01KnowledgeQueryResponse.CitationDto citation : serviceResponse.citations()) {
                        int sectionIdx = citation.locator() != null && citation.locator().sectionIndex() != null
                                ? citation.locator().sectionIndex()
                                : 0;
                        Optional<RetrievedKnowledgeChunk> matched = originalEvidence.stream()
                                .filter(c -> c.documentCode().equals(citation.documentId())
                                        && c.versionLabel().equals(citation.version())
                                        && c.sectionIndex() == sectionIdx)
                                .findFirst();
                        if (matched.isPresent()) {
                            chunkRefs.add(new AiRunKnowledgeChunk(
                                    run.id(),
                                    matched.get().chunkId(),
                                    rank++,
                                    BigDecimal.valueOf(matched.get().similarityScore())
                            ));
                        }
                    }
                    if (!chunkRefs.isEmpty()) {
                        provenanceRepository.saveKnowledgeChunkReferences(run.id(), chunkRefs);
                    }
                }

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

                // Pre-delivery revalidation: verify actor is still ACTIVE and retains STUDENT or TRAINER capability
                // before releasing AI generated answer and citations to the caller.
                aiRunUseCase.revalidateActorAuthorization(actorId);

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

                // Pre-delivery revalidation: verify actor is still ACTIVE and retains STUDENT or TRAINER capability
                aiRunUseCase.revalidateActorAuthorization(actorId);

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
        } finally {
            if (run != null && run.id() != null) {
                evidenceTracker.clearRunEvidence(run.id());
            }
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
