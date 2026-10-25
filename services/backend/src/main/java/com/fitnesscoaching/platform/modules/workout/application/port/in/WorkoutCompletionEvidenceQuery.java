package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Workout-owned actual completion facts; callers must not infer completion from missing logs. */
public interface WorkoutCompletionEvidenceQuery {
    List<Completion> completed();
    List<Completion> completedForStudent(UUID studentId);

    record Completion(UUID executionId, UUID studentId, long version,
                      Instant performedStartAt, Instant performedEndAt) {}
}
