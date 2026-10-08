package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionPersistencePort;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecution;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WorkoutExecutionQueryService implements WorkoutExecutionQueryUseCase {
    private final WorkoutExecutionPersistencePort store;
    private final WorkoutExecutionAccessPolicy access;

    public WorkoutExecutionQueryService(WorkoutExecutionPersistencePort store, WorkoutExecutionAccessPolicy access) {
        this.store = store;
        this.access = access;
    }

    @Override
    public Optional<WorkoutExecution> current(UUID actorId) {
        if (actorId == null) throw invalid();
        access.requireStudentMutation(actorId, actorId);
        return store.findCurrent(actorId);
    }

    @Override
    public WorkoutExecution detail(UUID actorId, UUID executionId) {
        if (actorId == null || executionId == null) throw invalid();
        WorkoutExecution execution = store.find(executionId).orElseThrow(() ->
                new WorkoutExecutionFailure(WorkoutExecutionError.WORKOUT_EXECUTION_NOT_FOUND,
                        "Workout execution was not found"));
        access.requireRead(actorId, execution.studentId(), execution.performedStartedAt());
        return execution;
    }

    @Override
    public List<WorkoutExecution> history(UUID actorId, UUID studentId, int limit, int offset) {
        if (actorId == null || studentId == null || limit < 1 || limit > 100 || offset < 0) throw invalid();
        List<WorkoutExecution> history = store.findHistory(studentId, limit, offset);
        for (WorkoutExecution execution : history)
            access.requireRead(actorId, studentId, execution.performedStartedAt());
        return history;
    }

    private static WorkoutExecutionFailure invalid() {
        return new WorkoutExecutionFailure(WorkoutExecutionError.VALIDATION_FAILED, "Invalid workout execution query");
    }
}
