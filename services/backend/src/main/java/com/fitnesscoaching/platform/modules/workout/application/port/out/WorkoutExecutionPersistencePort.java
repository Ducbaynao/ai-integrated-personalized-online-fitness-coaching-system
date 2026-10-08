package com.fitnesscoaching.platform.modules.workout.application.port.out;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionSnapshotUseCase.FrozenExecutionSnapshot;
import com.fitnesscoaching.platform.modules.workout.domain.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkoutExecutionPersistencePort {
    void lockStudent(UUID studentId);
    Optional<Receipt> lockReceipt(UUID actorId, String commandKey);
    boolean hasInProgress(UUID studentId);
    WorkoutExecution create(FrozenExecutionSnapshot snapshot, UUID actorId);
    WorkoutExecution lockExecution(UUID executionId);
    Optional<WorkoutExecution> find(UUID executionId);
    Optional<WorkoutExecution> findCurrent(UUID studentId);
    List<WorkoutExecution> findHistory(UUID studentId, int limit, int offset);
    Optional<SetIdentity> findSet(UUID clientSetId);
    void insertSet(UUID executionId, UUID exerciseId, SetExecution set, long expectedVersion, Instant at);
    void updateSet(UUID executionId, UUID exerciseId, SetExecution set, long expectedVersion, Instant at);
    void substitute(UUID executionId, UUID exerciseId, UUID actualVariationId, String reason,
                    long expectedVersion, Instant at);
    void terminal(UUID executionId, WorkoutExecutionStatus from, WorkoutExecutionStatus to, UUID actorId,
                  long expectedVersion, BigDecimal overallRpe, String note, Instant at);
    void saveReceipt(Receipt receipt);
    Instant databaseNow();

    record SetIdentity(UUID executionId, UUID exerciseId, SetExecution set) {}
    record Receipt(UUID actorId, String commandKey, String commandName, String payloadHash,
                   UUID plannedWorkoutId, UUID executionId, String executionStatus, String plannedStatus,
                   Long resultingVersion, Instant effectiveAt, String responseJson, Instant createdAt) {}
}
