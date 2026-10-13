package com.fitnesscoaching.platform.modules.content.application.port.out;

import com.fitnesscoaching.platform.modules.content.domain.KnowledgeProcessingRun;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeProcessingRunRepository {

    KnowledgeProcessingRun enqueue(UUID versionId, String runType, String operationKey, String statisticsJson, Instant createdAt);

    Optional<KnowledgeProcessingRun> findById(UUID id);

    Optional<KnowledgeProcessingRun> findByOperationKey(String operationKey);

    List<KnowledgeProcessingRun> findQueuedRuns(int limit);

    Optional<KnowledgeProcessingRun> claimNextEligibleRun(String workerId, Duration leaseDuration);

    boolean markSuccess(UUID id, UUID claimToken, String statisticsJson, Instant completedAt);

    boolean markFailed(UUID id, UUID claimToken, String errorMessage, boolean retryable, Duration retryDelay, Instant now);

    boolean renewLease(UUID id, UUID claimToken, Duration additionalDuration);

    int reapExpiredRuns();
}
