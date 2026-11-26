package com.fitnesscoaching.platform.modules.user.application.port.in;

import java.util.UUID;

/** Account-owned cutoff; callers never inspect Account persistence themselves. */
public interface AccountErasureFence {
    boolean isActiveWithoutErasure(UUID userId);
    /** Hold a shared Account row lock until the caller's domain transaction ends. */
    boolean lockActiveWithoutErasure(UUID userId);
    boolean lockCleanupAuthorized(UUID userId, UUID requestId);
}
