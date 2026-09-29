package com.fitnesscoaching.platform.modules.exercise.application.port.in;

import com.fitnesscoaching.platform.modules.exercise.application.model.AdminExercisePage;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExercise;

import java.util.UUID;

public interface AdminExerciseCatalogUseCase {

    AdminExercisePage search(AdminExerciseQuery query);

    AdminExercise getDetail(UUID adminUserId, UUID exerciseId);

    AdminExercise createDraft(UUID adminUserId, ExerciseDraftData draft);

    AdminExercise updateDraft(UUID adminUserId, UUID exerciseId, long expectedVersion, ExerciseDraftData draft);

    AdminExercise activate(UUID adminUserId, UUID exerciseId, long expectedVersion);

    AdminExercise archive(UUID adminUserId, UUID exerciseId, long expectedVersion, String reason);

    AdminExercise setCanonicalReplacement(
            UUID adminUserId,
            UUID exerciseId,
            long expectedVersion,
            UUID targetExerciseId,
            String reason
    );
}
