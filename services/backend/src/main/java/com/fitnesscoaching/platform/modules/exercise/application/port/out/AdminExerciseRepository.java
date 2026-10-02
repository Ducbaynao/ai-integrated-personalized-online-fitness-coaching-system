package com.fitnesscoaching.platform.modules.exercise.application.port.out;

import com.fitnesscoaching.platform.modules.exercise.application.model.AdminExercisePage;
import com.fitnesscoaching.platform.modules.exercise.application.model.AdminExerciseFormMetadata;
import com.fitnesscoaching.platform.modules.exercise.application.model.CanonicalReplacementExercise;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.AdminExerciseQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseDraftData;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExercise;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseLifecycleStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminExerciseRepository {

    AdminExercisePage search(AdminExerciseQuery query);

    AdminExerciseFormMetadata findFormMetadata();

    Optional<AdminExercise> findById(UUID exerciseId);

    Optional<AdminExercise> findByIdForUpdate(UUID exerciseId);

    Optional<CanonicalReplacementExercise> findCanonicalReplacementExerciseById(UUID exerciseId);

    AdminExercise createDraft(UUID exerciseId, UUID actorId, ExerciseDraftData data, Instant now);

    boolean updateDraft(UUID exerciseId, long expectedVersion, ExerciseDraftData data, Instant now);

    boolean transition(UUID exerciseId, long expectedVersion, ExerciseLifecycleStatus from,
                       ExerciseLifecycleStatus to, Instant now);

    List<String> findDraftReferenceProblems(ExerciseDraftData data);

    List<String> findActivationProblems(UUID exerciseId);

    boolean isCanonicalTarget(UUID exerciseId);

    boolean hasCanonicalReplacement(UUID exerciseId);

    boolean setCanonicalReplacement(UUID sourceId, long expectedVersion, UUID targetId,
                                    UUID actorId, String reason, Instant now);

    boolean clearCanonicalReplacement(UUID sourceId, long expectedVersion, Instant now);
}
