package com.fitnesscoaching.platform.modules.progress;

import java.util.UUID;

/** Progress-owned partial minimization. Neither method attests all Progress data. */
public interface ProgressAccountErasure {
    void minimizeOwnedConnectionIdentifiers(UUID userId);
    void tombstoneStudentMeasurements(UUID userId, UUID requestId);
}
