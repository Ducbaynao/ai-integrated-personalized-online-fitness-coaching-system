package com.fitnesscoaching.platform.modules.content.application.port.in;

import com.fitnesscoaching.platform.modules.content.domain.ReviewDecision;

import java.util.UUID;

public interface ContentLifecycleUseCase {

    void reviewVersion(UUID actorId, UUID versionId, ReviewDecision decision, String notes);

    void publishVersion(UUID actorId, UUID versionId);

    void archiveVersion(UUID actorId, UUID versionId, String reason);
}
