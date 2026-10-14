package com.fitnesscoaching.platform.modules.ai.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity representing an AI Run record in fitness.ai_runs.
 */
public record AiRun(
        UUID id,
        UUID requestedBy,
        UUID studentId,
        UUID coachingPeriodId,
        String requestType,
        AiRunStatus status,
        UUID modelVersionId,
        UUID promptVersionId,
        UUID correlationId,
        String purpose,
        boolean evaluationOnly,
        String inputPayload,
        String outputPayload,
        String errorCode,
        String errorMessage,
        Integer inputTokenCount,
        Integer outputTokenCount,
        Integer latencyMs,
        Instant queuedAt,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
    public static Builder builder() {
        return new Builder();
    }

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    public static class Builder {
        private UUID id;
        private UUID requestedBy;
        private UUID studentId;
        private UUID coachingPeriodId;
        private String requestType = "F01_GENERAL_KNOWLEDGE";
        private AiRunStatus status = AiRunStatus.QUEUED;
        private UUID modelVersionId;
        private UUID promptVersionId;
        private UUID correlationId;
        private String purpose = "BUSINESS_ASSISTANCE";
        private boolean evaluationOnly = false;
        private String inputPayload = "{}";
        private String outputPayload;
        private String errorCode;
        private String errorMessage;
        private Integer inputTokenCount;
        private Integer outputTokenCount;
        private Integer latencyMs;
        private Instant queuedAt;
        private Instant startedAt;
        private Instant completedAt;
        private Instant createdAt;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder requestedBy(UUID requestedBy) {
            this.requestedBy = requestedBy;
            return this;
        }

        public Builder studentId(UUID studentId) {
            this.studentId = studentId;
            return this;
        }

        public Builder coachingPeriodId(UUID coachingPeriodId) {
            this.coachingPeriodId = coachingPeriodId;
            return this;
        }

        public Builder requestType(String requestType) {
            this.requestType = requestType;
            return this;
        }

        public Builder status(AiRunStatus status) {
            this.status = status;
            return this;
        }

        public Builder modelVersionId(UUID modelVersionId) {
            this.modelVersionId = modelVersionId;
            return this;
        }

        public Builder promptVersionId(UUID promptVersionId) {
            this.promptVersionId = promptVersionId;
            return this;
        }

        public Builder correlationId(UUID correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder purpose(String purpose) {
            this.purpose = purpose;
            return this;
        }

        public Builder evaluationOnly(boolean evaluationOnly) {
            this.evaluationOnly = evaluationOnly;
            return this;
        }

        public Builder inputPayload(String inputPayload) {
            this.inputPayload = inputPayload;
            return this;
        }

        public Builder outputPayload(String outputPayload) {
            this.outputPayload = outputPayload;
            return this;
        }

        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder inputTokenCount(Integer inputTokenCount) {
            this.inputTokenCount = inputTokenCount;
            return this;
        }

        public Builder outputTokenCount(Integer outputTokenCount) {
            this.outputTokenCount = outputTokenCount;
            return this;
        }

        public Builder latencyMs(Integer latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder queuedAt(Instant queuedAt) {
            this.queuedAt = queuedAt;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public AiRun build() {
            return new AiRun(
                    id,
                    requestedBy,
                    studentId,
                    coachingPeriodId,
                    requestType,
                    status,
                    modelVersionId,
                    promptVersionId,
                    correlationId,
                    purpose,
                    evaluationOnly,
                    inputPayload,
                    outputPayload,
                    errorCode,
                    errorMessage,
                    inputTokenCount,
                    outputTokenCount,
                    latencyMs,
                    queuedAt != null ? queuedAt : Instant.now(),
                    startedAt,
                    completedAt,
                    createdAt != null ? createdAt : Instant.now()
            );
        }
    }
}
