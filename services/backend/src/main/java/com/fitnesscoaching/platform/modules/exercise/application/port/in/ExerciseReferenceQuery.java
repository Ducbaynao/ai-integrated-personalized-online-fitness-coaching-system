package com.fitnesscoaching.platform.modules.exercise.application.port.in;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExerciseReferenceQuery {
    List<AuthoringReference> lockAuthoringReferences(Collection<UUID> variationIds);
    Optional<HistoricalReference> resolveHistorical(UUID variationId);

    record AuthoringReference(UUID variationId, UUID exerciseId) {}
    record HistoricalReference(UUID variationId, UUID exerciseId, String exerciseName, String variationName,
                               PresentationState state, UUID canonicalExerciseId, String canonicalExerciseName) {}
    enum PresentationState { ACTIVE, ARCHIVED, UNAVAILABLE }
}
