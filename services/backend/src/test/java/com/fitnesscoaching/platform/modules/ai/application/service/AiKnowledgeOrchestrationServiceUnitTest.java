package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiFeatureDisabledException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiFeatureControlUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiRunF01UseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CompleteF01AiRunCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CreateF01AiRunCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F01AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase;
import com.fitnesscoaching.platform.modules.content.application.service.F01RunEvidenceTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiKnowledgeOrchestrationServiceUnitTest {

    @Mock
    private AiRunF01UseCase aiRunUseCase;

    @Mock
    private F01AiServiceClient aiServiceClient;

    @Mock
    private AiProvenanceRepository provenanceRepository;

    @Mock
    private KnowledgeRetrievalUseCase retrievalUseCase;

    @Mock
    private F01RunEvidenceTracker evidenceTracker;

    @Mock
    private F01RuntimeOutputValidator runtimeValidator;

    @Mock
    private AiFeatureControlUseCase featureControlUseCase;

    private ObjectMapper objectMapper;
    private AiServiceProperties aiProperties;
    private AiKnowledgeOrchestrationService service;

    private final UUID actorId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aiProperties = new AiServiceProperties(true, "simulated", "http://localhost:8000", "key", 5000, 25000);
        service = new AiKnowledgeOrchestrationService(
                aiRunUseCase,
                aiServiceClient,
                provenanceRepository,
                retrievalUseCase,
                evidenceTracker,
                objectMapper,
                aiProperties,
                runtimeValidator,
                featureControlUseCase
        );
    }

    @Test
    @DisplayName("queryKnowledge rejects new requests immediately when F01 is disabled by admin")
    void queryKnowledge_whenDisabled_rejectsImmediatelyWithoutCreatingRun() {
        when(featureControlUseCase.isF01Enabled()).thenReturn(false);

        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Khuyến nghị tập luyện của WHO là gì?",
                "vi",
                null,
                UUID.randomUUID()
        );

        assertThatThrownBy(() -> service.queryKnowledge(actorId, request))
                .isInstanceOf(AiFeatureDisabledException.class)
                .hasMessageContaining("currently disabled by administrator configuration");

        verify(aiRunUseCase, never()).createF01RunContext(any(), any());
        verify(aiServiceClient, never()).queryKnowledge(any());
    }

    @Test
    @DisplayName("queryKnowledge completes AI Run but blocks delivery if F01 was disabled during in-flight generation")
    void queryKnowledge_disabledInFlight_completesRunSafelyThenBlocksDelivery() {
        // Step 0: Initially enabled
        // Pre-delivery check: Disabled
        when(featureControlUseCase.isF01Enabled()).thenReturn(true, false);

        F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                "Khuyến nghị tập luyện của WHO là gì?",
                "vi",
                null,
                UUID.randomUUID()
        );

        AiRun mockRun = AiRun.builder()
                .id(runId)
                .requestedBy(actorId)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .status(AiRunStatus.QUEUED)
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .createdAt(Instant.now())
                .build();

        F01InternalContextEnvelope.ActorContext actorContext = new F01InternalContextEnvelope.ActorContext(actorId, "STUDENT");
        F01InternalContextEnvelope.KnowledgeScopeContext knowledgeScope = new F01InternalContextEnvelope.KnowledgeScopeContext(
                "ACTIVE",
                List.of("WHO-PA-SB-2020-VI-05")
        );

        F01InternalContextEnvelope envelope = new F01InternalContextEnvelope(
                runId,
                F01InternalContextEnvelope.F01_REQUEST_TYPE,
                F01InternalContextEnvelope.SCHEMA_VERSION,
                request.correlationId(),
                actorContext,
                F01InternalContextEnvelope.GENERAL_PURPOSE,
                F01InternalContextEnvelope.ALLOWED_ACTIONS,
                "2026-10-23T10:00:00Z",
                "Khuyến nghị tập luyện của WHO là gì?",
                "vi",
                null,
                knowledgeScope
        );

        when(aiRunUseCase.createF01RunContext(eq(actorId), any(CreateF01AiRunCommand.class)))
                .thenReturn(new AiRunF01UseCase.CreatedF01RunContext(mockRun, envelope));

        F01KnowledgeQueryResponse mockServiceResponse = new F01KnowledgeQueryResponse(
                runId,
                envelope.correlationId(),
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "WHO khuyến nghị 150-300 phút aerobic mỗi tuần.",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "2026-10-23T10:00:05Z",
                new F01KnowledgeQueryResponse.UsageDto(100, 50, 150, 1200),
                null,
                null
        );

        when(aiServiceClient.queryKnowledge(envelope)).thenReturn(mockServiceResponse);
        when(runtimeValidator.validate(mockServiceResponse, envelope))
                .thenReturn(new F01RuntimeOutputValidator.ValidationResult(true, null));

        // When executed, it should persist terminal status but throw AiFeatureDisabledException before delivery
        assertThatThrownBy(() -> service.queryKnowledge(actorId, request))
                .isInstanceOf(AiFeatureDisabledException.class)
                .hasMessageContaining("disabled by administrator during processing");

        // Verify that the run lifecycle was safely completed in DB rather than left orphaned
        verify(aiRunUseCase).completeF01Run(any(CompleteF01AiRunCommand.class));
    }
}
