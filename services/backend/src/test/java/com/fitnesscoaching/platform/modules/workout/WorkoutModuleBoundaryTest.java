package com.fitnesscoaching.platform.modules.workout;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionSnapshotQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutSchedulingUseCase;
import com.fitnesscoaching.platform.modules.workout.application.service.WorkoutPlanCommandService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WorkoutModuleBoundaryTest {
    @Test void workoutApplicationDoesNotDependOnCoachingOrExerciseAdaptersOrRepositories() {
        assertThat(Arrays.stream(WorkoutPlanCommandService.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .noneMatch(name -> name.contains(".adapter.") || name.endsWith("Repository"));
    }

    @Test void downstreamB05AndB06BoundariesAreWorkoutOwnedInterfaces() {
        assertThat(WorkoutExecutionSnapshotQuery.class.isInterface()).isTrue();
        assertThat(WorkoutSchedulingUseCase.class.isInterface()).isTrue();
        assertThat(WorkoutExecutionSnapshotQuery.class.getPackageName()).contains("modules.workout.application.port.in");
        assertThat(WorkoutSchedulingUseCase.class.getPackageName()).contains("modules.workout.application.port.in");
    }
}
