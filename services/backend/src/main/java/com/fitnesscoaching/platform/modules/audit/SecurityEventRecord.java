package com.fitnesscoaching.platform.modules.audit;

import java.time.Instant;
import java.util.UUID;

public record SecurityEventRecord(
        UUID userId,
        String eventType,
        String severity,
        String ipAddress,
        String userAgent,
        UUID deviceId,
        String detailsJson,
        Instant occurredAt
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID userId;
        private String eventType;
        private String severity = "INFO";
        private String ipAddress;
        private String userAgent;
        private UUID deviceId;
        private String detailsJson = "{}";
        private Instant occurredAt;

        public Builder userId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder severity(String severity) {
            this.severity = severity != null ? severity : "INFO";
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder deviceId(UUID deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public Builder detailsJson(String detailsJson) {
            this.detailsJson = detailsJson != null ? detailsJson : "{}";
            return this;
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public SecurityEventRecord build() {
            return new SecurityEventRecord(
                    userId, eventType, severity, ipAddress, userAgent, deviceId, detailsJson, occurredAt
            );
        }
    }
}
