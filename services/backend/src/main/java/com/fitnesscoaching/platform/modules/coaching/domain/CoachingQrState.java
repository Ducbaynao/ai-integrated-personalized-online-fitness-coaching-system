package com.fitnesscoaching.platform.modules.coaching.domain;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CoachingQrState {
    private CoachingQrState() {}

    public enum QrRole {
        STUDENT,
        TRAINER
    }

    public record QrCodeRecord(
            UUID id,
            UUID userId,
            QrRole role,
            String publicCode,
            int payloadVersion,
            String status,
            long version,
            Instant issuedAt,
            Instant revokedAt,
            String revokeReason
    ) {}

    public record QrIdentity(
            UUID qrId,
            QrRole role,
            String publicCode,
            String qrUri,
            int payloadVersion,
            String displayName,
            Instant issuedAt,
            long version
    ) {}

    public record IssueResult(QrIdentity identity, boolean created) {}

    public record TrainerSummary(
            TrainerVerificationStatus verificationStatus,
            List<String> specialties,
            BigDecimal yearsExperience,
            boolean isAcceptingStudents
    ) {
        public TrainerSummary {
            specialties = specialties == null ? List.of() : List.copyOf(specialties);
        }
    }

    public record RelationshipState(
            String status,
            UUID relationshipId,
            String direction,
            Long version,
            boolean canInitiate
    ) {}

    public record QrProfilePreview(
            UUID counterpartyId,
            QrRole targetRole,
            String displayName,
            String avatarUrl,
            TrainerSummary trainerSummary,
            RelationshipState relationshipState
    ) {}
}
