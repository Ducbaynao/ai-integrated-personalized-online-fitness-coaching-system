package com.fitnesscoaching.platform.modules.notification;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Notification-owned transactional write boundary for a committed source transition. */
public interface LifecycleNotificationWriter {
    record Changed(UUID recipient, UUID notificationId) {}
    /** Stable identity of one committed aggregate transition, not a retry command. */
    static UUID transitionId(String entityType, UUID entityId, long version, String transition) {
        return UUID.nameUUIDFromBytes((entityType + ":" + entityId + ":" + version + ":" + transition)
                .getBytes(StandardCharsets.UTF_8));
    }
    void committed(UUID sourceEventId, String eventType, String entityType, UUID entityId,
                   UUID studentId, UUID trainerId, UUID periodId, UUID actorId, UUID recipientId);

    /** Captured period of the original pending notice; absent for pre-V45 proposals. */
    Optional<UUID> sourcePeriod(String entityType, UUID entityId);
}
