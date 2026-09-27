package com.fitnesscoaching.platform.modules.exercise.application.port.in;

import com.fitnesscoaching.platform.modules.exercise.application.model.ExerciseCatalogPage;
import com.fitnesscoaching.platform.modules.exercise.application.model.ExerciseFilterMetadata;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseCatalogDetail;

import java.util.UUID;

public interface ExerciseCatalogUseCase {

    ExerciseCatalogPage search(ExerciseCatalogQuery query);

    ExerciseCatalogDetail getDetail(UUID exerciseId);

    ExerciseFilterMetadata getFilterMetadata();
}
