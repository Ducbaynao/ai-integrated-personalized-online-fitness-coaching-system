package com.fitnesscoaching.platform.modules.coaching.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CoachingSharing {
    private CoachingSharing() {}

    public record Permission(
            UUID id,
            UUID relationshipId,
            UUID studentId,
            UUID trainerId,
            DataScope dataScope,
            SharingDecision decision,
            DataAccessLevel accessLevel,
            Instant historyFrom,
            Instant historyUntil,
            Instant validFrom,
            Instant validUntil,
            UUID grantedBy,
            Instant revokedAt,
            String revokeReason,
            long version,
            Instant createdAt
    ) {}

    public record AuthorityRequest(
            UUID actorId,
            UUID studentId,
            DataScope dataScope,
            DataAccessLevel requiredLevel
    ) {}

    public record HistoricalAuthorityRequest(
            UUID actorId,
            UUID studentId,
            DataScope dataScope,
            DataAccessLevel requiredLevel,
            Instant resourceOccurredAt
    ) {}

    public enum AuthorityReason {
        ALLOWED,
        USER_NOT_FOUND,
        ACCOUNT_UNAVAILABLE,
        TRAINER_CAPABILITY_UNAVAILABLE,
        TRAINER_NOT_ELIGIBLE,
        RELATIONSHIP_NOT_ACTIVE,
        PERIOD_NOT_EFFECTIVE,
        PERMISSION_NOT_GRANTED,
        ACCESS_LEVEL_INSUFFICIENT,
        HISTORY_WINDOW_NOT_GRANTED,
        RESOURCE_OUTSIDE_HISTORY_WINDOW,
        INVALID_REQUEST
    }

    public record AuthorityDecision(
            boolean allowed,
            AuthorityReason reason,
            UUID relationshipId,
            long relationshipVersion,
            UUID periodId,
            UUID permissionId,
            long permissionVersion,
            DataAccessLevel grantedLevel,
            Instant historyFrom,
            Instant historyUntil
    ) {
        public static AuthorityDecision denied(AuthorityReason reason) {
            return new AuthorityDecision(false, reason, null, -1, null, null, -1, null, null, null);
        }
    }

    public record AuthorityContext(
            UUID relationshipId,
            long relationshipVersion,
            UUID periodId,
            Permission permission
    ) {}

    public enum PermissionPresentationState {
        NOT_CONFIGURED,
        ALLOWED,
        DENIED,
        EXPIRED,
        REVOKED
    }

    public record PermissionSummaryItem(
            DataScope dataScope,
            PermissionPresentationState state,
            SharingDecision decision,
            DataAccessLevel accessLevel,
            UUID permissionId,
            Long version,
            Instant historyFrom,
            Instant historyUntil,
            Instant validFrom,
            Instant validUntil
    ) {}

    public record PermissionSummary(
            UUID relationshipId,
            String relationshipStatus,
            Instant evaluatedAt,
            List<PermissionSummaryItem> items
    ) {
        public PermissionSummary {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
