package com.fitnesscoaching.platform.modules.coaching.application.port.out;

import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityContext;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.Permission;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Receipt;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Relationship;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoachingSharingStore {
    Instant transitionTime();

    void lockStudent(UUID studentId);

    void lockAuthorityBoundary(UUID studentId, UUID trainerId);

    Optional<Relationship> relationship(UUID relationshipId);

    List<Permission> permissions(UUID relationshipId, int limit, int offset);

    List<Permission> latestPermissionsAt(UUID relationshipId, Instant at);

    Optional<Permission> permission(UUID permissionId);

    Optional<Permission> effectivePermission(UUID relationshipId, DataScope scope, Instant at);

    Optional<AuthorityContext> authorityContext(UUID trainerId, UUID studentId, DataScope scope, Instant at);

    Permission createPermission(Relationship relationship, DataScope scope, SharingDecision decision,
                                DataAccessLevel accessLevel, Instant historyFrom, Instant historyUntil,
                                Instant validFrom, Instant validUntil, UUID grantedBy, long version);

    Permission revokePermission(Permission permission, UUID actorId, Instant at, String reason);

    Optional<Receipt> receipt(UUID actorId, UUID commandKey);

    void saveReceipt(UUID actorId, UUID commandKey, String commandName, String payloadHash, String responseJson);
}
