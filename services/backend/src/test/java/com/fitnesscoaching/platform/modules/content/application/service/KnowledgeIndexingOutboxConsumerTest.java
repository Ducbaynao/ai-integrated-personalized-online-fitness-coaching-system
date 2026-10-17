package com.fitnesscoaching.platform.modules.content.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeProcessingRunRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeVersionRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeProcessingRun;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexingOutboxConsumerTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VERSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CLAIM_TOKEN = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-10-11T15:00:00Z");

    @Mock private KnowledgeProcessingRunRepository processingRunRepository;
    @Mock private KnowledgeVersionRepository versionRepository;
    @Mock private com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeDocumentRepository documentRepository;
    @Mock private com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeChunkRepository chunkRepository;
    @Mock private com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeEmbeddingRepository embeddingRepository;
    @Mock private com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort embeddingModelPort;

    private KnowledgeChunker chunker;
    private ObjectMapper objectMapper;
    private KnowledgeIndexingOutboxConsumer consumer;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        objectMapper = new ObjectMapper();
        chunker = new KnowledgeChunker(objectMapper);
        PlatformTransactionManager txManager = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };

        KnowledgeIndexingOutboxWorker worker = new KnowledgeIndexingOutboxWorker(
                processingRunRepository,
                versionRepository,
                documentRepository,
                chunkRepository,
                embeddingRepository,
                chunker,
                embeddingModelPort,
                objectMapper,
                clock,
                txManager
        );
        consumer = new KnowledgeIndexingOutboxConsumer(worker);
    }

    @Test
    void processNextRun_activeVersion_recordsIndexingMetadataWithRealEmbeddings() {
        UUID docId = UUID.randomUUID();
        KnowledgeProcessingRun run = new KnowledgeProcessingRun(
                RUN_ID, VERSION_ID, "REINDEX", "RUNNING", null, "{}", null,
                "INDEX:" + VERSION_ID, CLAIM_TOKEN, NOW, "worker-1", 1, 3, null, NOW.plus(Duration.ofMinutes(5)),
                NOW, null, NOW
        );
        when(processingRunRepository.claimNextEligibleRun(eq("worker-1"), any(Duration.class)))
                .thenReturn(Optional.of(run));

        KnowledgeVersion activeVersion = KnowledgeVersion.builder()
                .id(VERSION_ID)
                .knowledgeDocumentId(docId)
                .versionLabel("1.1.0")
                .status(KnowledgeStatus.ACTIVE)
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .contentPath("derivatives/who-2020/01-scope.vi.md")
                .content("# Scope\n\nContent paragraph for indexing.")
                .build();
        when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(activeVersion));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(
                com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument.builder()
                        .id(docId)
                        .documentCode("WHO-PA-SB-2020-VI-01")
                        .sourceId("WHO-PA-SB-2020")
                        .title("WHO Physical Activity")
                        .build()
        ));
        when(embeddingModelPort.provider()).thenReturn("simulated");
        when(embeddingModelPort.modelCode()).thenReturn("simulated/deterministic-1536");
        when(embeddingModelPort.modelSpace()).thenReturn("simulated/deterministic-1536/1.0.0");
        when(embeddingModelPort.versionLabel()).thenReturn("1.0.0");
        when(embeddingModelPort.dimension()).thenReturn(1536);
        when(embeddingModelPort.modelVersionId()).thenReturn(UUID.randomUUID());
        when(embeddingModelPort.generateBatch(any())).thenReturn(List.of(new float[1536]));
        when(processingRunRepository.markSuccess(eq(RUN_ID), eq(CLAIM_TOKEN), any(), eq(NOW)))
                .thenReturn(true);

        boolean processed = consumer.processNextRun("worker-1");

        assertThat(processed).isTrue();
        ArgumentCaptor<String> statsCaptor = ArgumentCaptor.forClass(String.class);
        verify(processingRunRepository).markSuccess(eq(RUN_ID), eq(CLAIM_TOKEN), statsCaptor.capture(), eq(NOW));

        String stats = statsCaptor.getValue();
        assertThat(stats).contains("REAL_RETRIEVAL_INDEXING");
        assertThat(stats).contains("\"real_embeddings\":false");
        assertThat(stats).contains("\"embedding_mode\":\"SIMULATED_DETERMINISTIC\"");
        assertThat(stats).contains("\"embedding_provider\":\"simulated\"");
        assertThat(stats).contains("\"index_ready\":true");
    }

    @Test
    void processNextRun_archivedVersion_failsJobAndBlocksEligibility() {
        KnowledgeProcessingRun run = new KnowledgeProcessingRun(
                RUN_ID, VERSION_ID, "REINDEX", "RUNNING", null, "{}", null,
                "INDEX:" + VERSION_ID, CLAIM_TOKEN, NOW, "worker-1", 1, 3, null, NOW.plus(Duration.ofMinutes(5)),
                NOW, null, NOW
        );
        when(processingRunRepository.claimNextEligibleRun(eq("worker-1"), any(Duration.class)))
                .thenReturn(Optional.of(run));

        KnowledgeVersion archivedVersion = KnowledgeVersion.builder()
                .id(VERSION_ID)
                .versionLabel("1.1.0")
                .status(KnowledgeStatus.ARCHIVED)
                .build();
        when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(archivedVersion));
        when(processingRunRepository.markFailed(eq(RUN_ID), eq(CLAIM_TOKEN), any(), eq(false), eq(null), eq(NOW)))
                .thenReturn(true);

        boolean processed = consumer.processNextRun("worker-1");

        assertThat(processed).isTrue();
        verify(processingRunRepository).markFailed(
                eq(RUN_ID),
                eq(CLAIM_TOKEN),
                eq("Ineligible for indexing: version status is ARCHIVED (requires ACTIVE)"),
                eq(false),
                eq(null),
                eq(NOW)
        );
    }

    @Test
    void processNextRun_whenMarkSuccessFailsDueToFencing_doesNotLogCompletionAndLogsFencingRejection() {
        ch.qos.logback.classic.Logger workerLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KnowledgeIndexingOutboxWorker.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        workerLogger.addAppender(appender);

        try {
            UUID docId = UUID.randomUUID();
            mockSuccessfulIndexingDependencies(docId);
            KnowledgeProcessingRun run = new KnowledgeProcessingRun(
                    RUN_ID, VERSION_ID, "REINDEX", "RUNNING", null, "{}", null,
                    "INDEX:" + VERSION_ID, CLAIM_TOKEN, NOW, "worker-1", 1, 3, null, NOW.plus(Duration.ofMinutes(5)),
                    NOW, null, NOW
            );
            when(processingRunRepository.claimNextEligibleRun(eq("worker-1"), any(Duration.class)))
                    .thenReturn(Optional.of(run));

            KnowledgeVersion activeVersion = createActiveVersion(docId);
            when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(activeVersion));
            // Simulate fencing rejection (stale token or lease expired)
            when(processingRunRepository.markSuccess(eq(RUN_ID), eq(CLAIM_TOKEN), any(), eq(NOW)))
                    .thenReturn(false);

            boolean processed = consumer.processNextRun("worker-1");

            assertThat(processed).isTrue();

            // Crucial: markFailed must NEVER be called upon fencing rejection
            org.mockito.Mockito.verify(processingRunRepository, org.mockito.Mockito.never())
                    .markFailed(any(), any(), any(), org.mockito.Mockito.anyBoolean(), any(), any());

            boolean hasCompletedLog = appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("Completed real knowledge indexing outbox run"));
            boolean hasFencingRejectionLog = appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("Fencing rejected markSuccess") ||
                            event.getFormattedMessage().contains("Completion rejected by claim fencing"));

            assertThat(hasCompletedLog)
                    .as("Must NOT log completion success when markSuccess was rejected by claim fencing")
                    .isFalse();
            assertThat(hasFencingRejectionLog)
                    .as("Must log explicit fencing rejection")
                    .isTrue();
        } finally {
            workerLogger.detachAppender(appender);
        }
    }

    @Test
    void processNextRun_whenMarkSuccessSucceeds_logsCompletionSuccessfullyWithoutContradictoryWarnings() {
        ch.qos.logback.classic.Logger workerLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KnowledgeIndexingOutboxWorker.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        workerLogger.addAppender(appender);

        try {
            UUID docId = UUID.randomUUID();
            mockSuccessfulIndexingDependencies(docId);
            KnowledgeProcessingRun run = new KnowledgeProcessingRun(
                    RUN_ID, VERSION_ID, "REINDEX", "RUNNING", null, "{}", null,
                    "INDEX:" + VERSION_ID, CLAIM_TOKEN, NOW, "worker-1", 1, 3, null, NOW.plus(Duration.ofMinutes(5)),
                    NOW, null, NOW
            );
            when(processingRunRepository.claimNextEligibleRun(eq("worker-1"), any(Duration.class)))
                    .thenReturn(Optional.of(run));

            KnowledgeVersion activeVersion = createActiveVersion(docId);
            when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(activeVersion));
            when(processingRunRepository.markSuccess(eq(RUN_ID), eq(CLAIM_TOKEN), any(), eq(NOW)))
                    .thenReturn(true);

            boolean processed = consumer.processNextRun("worker-1");

            assertThat(processed).isTrue();

            boolean hasCompletedLog = appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("Completed real knowledge indexing outbox run"));
            boolean hasFencingRejectionLog = appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("Fencing rejected") ||
                            event.getFormattedMessage().contains("rejected by claim fencing"));

            assertThat(hasCompletedLog)
                    .as("Must log completion success when markSuccess succeeds")
                    .isTrue();
            assertThat(hasFencingRejectionLog)
                    .as("Must NOT log fencing rejection when markSuccess succeeds")
                    .isFalse();
        } finally {
            workerLogger.detachAppender(appender);
        }
    }

    @Test
    void processPendingRuns_batchReturnSemantics_countsProcessedRunsEvenWhenFencingRejectsCompletion() {
        UUID docId = UUID.randomUUID();
        mockSuccessfulIndexingDependencies(docId);
        UUID run1Id = UUID.randomUUID();
        UUID run2Id = UUID.randomUUID();
        KnowledgeProcessingRun run1 = new KnowledgeProcessingRun(
                run1Id, VERSION_ID, "REINDEX", "RUNNING", null, "{}", null,
                "INDEX:1", CLAIM_TOKEN, NOW, "batch-worker-1", 1, 3, null, NOW.plus(Duration.ofMinutes(5)),
                NOW, null, NOW
        );
        KnowledgeProcessingRun run2 = new KnowledgeProcessingRun(
                run2Id, VERSION_ID, "REINDEX", "RUNNING", null, "{}", null,
                "INDEX:2", CLAIM_TOKEN, NOW, "batch-worker-1", 1, 3, null, NOW.plus(Duration.ofMinutes(5)),
                NOW, null, NOW
        );

        when(processingRunRepository.claimNextEligibleRun(any(), any(Duration.class)))
                .thenReturn(Optional.of(run1))
                .thenReturn(Optional.of(run2))
                .thenReturn(Optional.empty());

        KnowledgeVersion activeVersion = createActiveVersion(docId);
        when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(activeVersion));

        // run1 succeeds, run2 is rejected by fencing
        when(processingRunRepository.markSuccess(eq(run1Id), any(), any(), eq(NOW))).thenReturn(true);
        when(processingRunRepository.markSuccess(eq(run2Id), any(), any(), eq(NOW))).thenReturn(false);

        int processedCount = consumer.processPendingRuns(5);

        // Semantics: 2 jobs were claimed and processed by the worker
        assertThat(processedCount).isEqualTo(2);

        // Fencing rejection must not trigger markFailed
        org.mockito.Mockito.verify(processingRunRepository, org.mockito.Mockito.never())
                .markFailed(eq(run2Id), any(), any(), org.mockito.Mockito.anyBoolean(), any(), any());
    }

    private KnowledgeVersion createActiveVersion(UUID docId) {
        return KnowledgeVersion.builder()
                .id(VERSION_ID)
                .knowledgeDocumentId(docId)
                .versionLabel("1.1.0")
                .status(KnowledgeStatus.ACTIVE)
                .technicalReadiness(TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .contentPath("derivatives/who-2020/01-scope.vi.md")
                .content("# Scope\n\nContent paragraph for indexing.")
                .build();
    }

    private void mockSuccessfulIndexingDependencies(UUID docId) {
        when(documentRepository.findById(docId)).thenReturn(Optional.of(
                com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument.builder()
                        .id(docId)
                        .documentCode("WHO-PA-SB-2020-VI-01")
                        .sourceId("WHO-PA-SB-2020")
                        .title("WHO Physical Activity")
                        .build()
        ));
        when(embeddingModelPort.provider()).thenReturn("simulated");
        when(embeddingModelPort.modelCode()).thenReturn("simulated/deterministic-1536");
        when(embeddingModelPort.modelSpace()).thenReturn("simulated/deterministic-1536/1.0.0");
        when(embeddingModelPort.versionLabel()).thenReturn("1.0.0");
        when(embeddingModelPort.dimension()).thenReturn(1536);
        when(embeddingModelPort.modelVersionId()).thenReturn(UUID.randomUUID());
        when(embeddingModelPort.generateBatch(any())).thenReturn(List.of(new float[1536]));
    }
}
