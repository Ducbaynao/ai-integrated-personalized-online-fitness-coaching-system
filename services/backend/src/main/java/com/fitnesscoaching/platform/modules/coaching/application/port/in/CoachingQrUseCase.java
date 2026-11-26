package com.fitnesscoaching.platform.modules.coaching.application.port.in;

import com.fitnesscoaching.platform.modules.coaching.domain.CoachingQrState.*;

import java.util.UUID;

public interface CoachingQrUseCase {
    QrIdentity getMyQr(UUID actorId, QrRole role);

    IssueResult issueMyQr(UUID actorId, QrRole role);

    QrIdentity rotateMyQr(UUID actorId, QrRole role, long expectedVersion, UUID commandKey);

    QrProfilePreview resolveQr(UUID actorId, String publicCode, QrRole expectedTargetRole, int payloadVersion);
}
