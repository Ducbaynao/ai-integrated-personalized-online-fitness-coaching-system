package com.fitnesscoaching.platform.modules.coaching.application.port.in;

import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.Permission;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.PermissionSummary;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CoachingSharingUseCase {
    List<Permission> list(UUID actorId, UUID relationshipId, int page, int size);

    PermissionSummary summary(UUID actorId, UUID relationshipId);

    Permission grantOrReplace(UUID actorId, UUID relationshipId, DataScope dataScope,
                              SharingDecision decision, DataAccessLevel accessLevel,
                              Instant historyFrom, Instant historyUntil, Instant validUntil,
                              Long expectedPermissionVersion, UUID commandKey);

    Permission revoke(UUID actorId, UUID relationshipId, UUID permissionId,
                      long expectedPermissionVersion, String reason, UUID commandKey);
}
