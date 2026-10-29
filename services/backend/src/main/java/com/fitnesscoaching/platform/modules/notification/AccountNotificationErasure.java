package com.fitnesscoaching.platform.modules.notification;

import java.util.UUID;

/** Notification-owned effects; caller owns the surrounding database transaction. */
public interface AccountNotificationErasure {
    void revokePushAndHideInbox(UUID userId);
    void minimizeAfterCleanup(UUID userId, UUID requestId);
}
