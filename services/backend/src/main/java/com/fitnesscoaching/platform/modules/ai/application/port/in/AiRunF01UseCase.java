package com.fitnesscoaching.platform.modules.ai.application.port.in;

import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;

import java.time.Instant;
import java.util.UUID;

public interface AiRunF01UseCase {

    /**
     * Initializes an AI Run for F01 knowledge inquiry under the caller's verified identity.
     * Enforces that the actor has an active STUDENT or TRAINER role.
     * Enforces active Exercise reference when exerciseId is provided.
     */
    AiRun createF01Run(UUID actorId, CreateF01AiRunCommand command);

    /**
     * Retrieves an AI Run by ID, enforcing that only the authenticated owner can access it.
     */
    AiRun getF01Run(UUID actorId, UUID runId);

    /**
     * Transitions status from expectedStatus to newStatus, with terminal protection.
     */
    AiRun transitionRunStatus(UUID runId, AiRunStatus expectedStatus, AiRunStatus newStatus, Instant startedAt, Instant completedAt);

    /**
     * Records terminal completion of the run with sanitized diagnostics and provenance.
     */
    AiRun completeF01Run(CompleteF01AiRunCommand command);
}
