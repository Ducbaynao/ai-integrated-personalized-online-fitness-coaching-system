package com.fitnesscoaching.platform.modules.coaching.domain;

import java.time.Instant;
import java.util.UUID;

public final class CoachingState {
    private CoachingState() {}

    public record Relationship(UUID id, UUID studentId, UUID trainerId, String status,
                               UUID requestedBy, Instant requestedAt, Instant acceptedAt,
                               Instant startedAt, Instant endedAt, long version) {
        public String directionFor(UUID actorId) {
            return requestedBy.equals(actorId) ? "OUTGOING" : "INCOMING";
        }
        public boolean participant(UUID actorId) {
            return studentId.equals(actorId) || trainerId.equals(actorId);
        }
        public UUID counterparty(UUID actorId) {
            return studentId.equals(actorId) ? trainerId : studentId;
        }
    }

    public record Resume(UUID id, UUID relationshipId, UUID requestedBy, String status,
                         long version, Instant requestedAt, UUID decidedBy, Instant decidedAt) {}

    public record Period(UUID id, UUID studentId, String mode, UUID relationshipId,
                         UUID trainerId, Instant startedAt, Instant endedAt) {}

    public record History(UUID id, UUID relationshipId, String fromStatus, String toStatus,
                          UUID changedBy, String reason, Instant changedAt) {}

    public record Outcome(Relationship relationship, Resume resume, Period currentPeriod) {}

    public record Receipt(String commandName, String payloadHash, String responseJson) {}
}
