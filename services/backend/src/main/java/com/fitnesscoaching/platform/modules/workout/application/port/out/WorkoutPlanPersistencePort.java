package com.fitnesscoaching.platform.modules.workout.application.port.out;

import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlan;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionAdjustment;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkoutPlanPersistencePort {
    UUID findPlanStudent(UUID planId);
    UUID findOccurrenceStudent(UUID occurrenceId);
    void lockStudentAndActor(UUID studentId, UUID actorId);
    Optional<Receipt> lockReceipt(UUID actorId, String commandKey);
    WorkoutPlan lockPlan(UUID planId);
    OpenVersion lockOpenVersion(UUID planId);
    List<UUID> exerciseVariationIds(UUID versionId);
    Instant databaseNow();
    boolean hasAnotherActivePlan(UUID studentId, UUID excludingPlanId);
    void activate(UUID planId, UUID versionId, UUID actorId, long expectedVersion, Instant at);
    void transition(UUID planId, UUID actorId, long expectedVersion, WorkoutPlanStatus from,
                    WorkoutPlanStatus to, String reason, Instant at);
    UUID publish(UUID planId, OpenVersion previous, UUID actorId, long expectedVersion,
                 Instant boundary, String reason, String summary, List<WorkoutSessionTemplate> sessions);
    Successor createStudentSuccessor(UUID sourcePlanId, UUID sourceVersionId, UUID studentId,
                                     UUID coachingPeriodId, String name, Instant at);
    Occurrence lockOccurrence(UUID occurrenceId);
    UUID appendAdjustment(Occurrence occurrence, UUID actorId, WorkoutSessionAdjustment.Type type,
                          UUID plannedSessionExerciseId, UUID replacementVariationId,
                          String beforeJson, String afterJson, String reason, long expectedVersion, Instant at);
    void saveReceipt(Receipt receipt);

    record OpenVersion(UUID id, int versionNumber, Instant effectiveFrom) {}
    record Occurrence(UUID id, UUID studentId, UUID planId, UUID planVersionId, long version) {}
    record Successor(UUID planId, UUID versionId) {}
    record Receipt(UUID actorId, String commandKey, String commandName, String payloadHash,
                   UUID planId, UUID planVersionId, UUID plannedWorkoutId, String status,
                   Long resultingVersion, Instant effectiveAt, String responseJson, Instant createdAt) {}
}
