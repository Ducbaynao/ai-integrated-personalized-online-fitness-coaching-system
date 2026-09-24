package com.fitnesscoaching.platform.modules.goal.application.port.out;

import java.util.UUID;

/**
 * Consumer-owned port for verifying student identity, capability, and profile
 * without coupling the goal module to student module internals.
 */
public interface GoalStudentAuthorityPort {

    /**
     * Verifies that the given userId belongs to an ACTIVE account,
     * possesses an active STUDENT role, and has a registered student profile.
     * Throws appropriate domain exceptions if verification fails.
     */
    void verifyStudentCanManageGoals(UUID studentId);
}
