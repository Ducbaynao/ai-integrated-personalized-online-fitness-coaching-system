package com.fitnesscoaching.platform.modules.coaching.application.port.out;

import com.fitnesscoaching.platform.modules.coaching.domain.CoachingQrState.QrCodeRecord;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingQrState.QrRole;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CoachingQrStore {
    void lockOwner(UUID userId);

    Optional<QrCodeRecord> findActiveByOwnerAndRole(UUID userId, QrRole role);

    Optional<QrCodeRecord> findById(UUID qrId);

    Optional<QrCodeRecord> findActiveByPublicCode(String publicCode);

    Optional<QrCodeRecord> insertIfAbsentActive(UUID userId, QrRole role, String publicCode, int payloadVersion, Instant issuedAt);

    boolean revokeActive(UUID qrId, Instant revokedAt, String reason);

    QrCodeRecord insertRotatedActive(UUID userId, QrRole role, String publicCode, int payloadVersion, long newVersion, Instant issuedAt);
}
