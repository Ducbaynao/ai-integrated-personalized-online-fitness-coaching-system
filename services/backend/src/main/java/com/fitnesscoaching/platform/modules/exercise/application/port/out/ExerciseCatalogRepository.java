package com.fitnesscoaching.platform.modules.exercise.application.port.out;

import com.fitnesscoaching.platform.modules.exercise.application.model.ExerciseCatalogPage;
import com.fitnesscoaching.platform.modules.exercise.application.model.ExerciseFilterMetadata;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseCatalogQuery;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseCatalogDetail;

import java.util.Optional;
import java.util.UUID;

public interface ExerciseCatalogRepository {

    ExerciseCatalogPage search(ExerciseCatalogQuery query);

    Optional<ExerciseCatalogDetail> findActiveDetail(UUID exerciseId);

    ExerciseFilterMetadata findFilterMetadata();
}
