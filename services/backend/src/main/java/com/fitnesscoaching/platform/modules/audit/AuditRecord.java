package com.fitnesscoaching.platform.modules.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditRecord(
        UUID actorUserId,
        String actorRole,
        String action,
        String targetType,
        UUID targetId,
        UUID requestId,
        String ipAddress,
        String userAgent,
        String beforeDataJson,
        String afterDataJson,
        String metadataJson,
        Instant occurredAt
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID actorUserId;
        private String actorRole;
        private String action;
        private String targetType;
        private UUID targetId;
        private UUID requestId;
        private String ipAddress;
        private String userAgent;
        private String beforeDataJson;
        private String afterDataJson;
        private String metadataJson = "{}";
        private Instant occurredAt;

        public Builder actorUserId(UUID actorUserId) {
            this.actorUserId = actorUserId;
            return this;
        }

        public Builder actorRole(String actorRole) {
            this.actorRole = actorRole;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder targetType(String targetType) {
            this.targetType = targetType;
            return this;
        }

        public Builder targetId(UUID targetId) {
            this.targetId = targetId;
            return this;
        }

        public Builder requestId(UUID requestId) {
            this.requestId = requestId;
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

        public Builder beforeDataJson(String beforeDataJson) {
            this.beforeDataJson = beforeDataJson;
            return this;
        }

        public Builder afterDataJson(String afterDataJson) {
            this.afterDataJson = afterDataJson;
            return this;
        }

        public Builder metadataJson(String metadataJson) {
            this.metadataJson = metadataJson != null ? metadataJson : "{}";
            return this;
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public AuditRecord build() {
            return new AuditRecord(
                    actorUserId, actorRole, action, targetType, targetId,
                    requestId, ipAddress, userAgent, beforeDataJson, afterDataJson,
                    metadataJson, occurredAt
            );
        }
    }
}
