package com.fitnesscoaching.platform.modules.notification;

import java.util.UUID;

/** Notification-owned write boundary; callers must already be in the message transaction. */
public interface ChatNotificationWriter {
    UUID messageCommitted(UUID recipientId, UUID messageId, UUID periodId,
                          UUID studentId, UUID trainerId);
}
