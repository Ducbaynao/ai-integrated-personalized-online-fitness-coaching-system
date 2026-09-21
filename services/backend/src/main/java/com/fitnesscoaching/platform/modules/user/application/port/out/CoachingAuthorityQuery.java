package com.fitnesscoaching.platform.modules.user.application.port.out;

import java.util.UUID;

/**
 * Consumer-owned outbound query port for checking coaching authority.
 * Implemented by the trainer module to evaluate coaching eligibility
 * without coupling the user module to trainer internals.
 */
public interface CoachingAuthorityQuery {

    boolean canCoach(UUID userId);
}
