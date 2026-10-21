package com.fitnesscoaching.platform.modules.coaching.application.port.in;

import java.util.UUID;

/** Coaching-owned read authority for a notification's relationship/resume deep link. */
public interface CoachingNotificationAccessQuery {
    boolean canRead(UUID actor, String entityType, UUID entityId);
}
