package com.fitnesscoaching.platform.modules.user.application.port.in;

import java.util.UUID;

public interface UserRoleQuery {

    boolean hasActiveRole(UUID userId, String roleCode);

    /**
     * Lock account then TRAINER role rows for a coaching decision in the caller's transaction.
     * Implementations must serialize non-key writers without blocking FK KEY SHARE checks.
     */
    void lockTrainerCapability(UUID userId);
}
