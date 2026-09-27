package com.fitnesscoaching.platform.modules.exercise.adapter.in.web;

import com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto.ExerciseCatalogPageResponse;
import com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto.ExerciseDetailResponse;
import com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto.ExerciseFilterMetadataResponse;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseCatalogQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseCatalogUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@PreAuthorize("hasAnyRole('STUDENT', 'TRAINER')")
public class ExerciseCatalogController {

    private final ExerciseCatalogUseCase exerciseCatalogUseCase;

    public ExerciseCatalogController(ExerciseCatalogUseCase exerciseCatalogUseCase) {
        this.exerciseCatalogUseCase = exerciseCatalogUseCase;
    }

    @GetMapping("/api/v1/exercises")
    public ResponseEntity<ExerciseCatalogPageResponse> searchExercises(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) List<String> categoryCodes,
            @RequestParam(required = false) List<String> muscleGroupCodes,
            @RequestParam(required = false) List<String> equipmentCodes,
            @RequestParam(required = false) List<String> tagCodes,
            @RequestParam(required = false) List<String> difficulties,
            @RequestParam(required = false) List<String> movementPatterns,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        ExerciseCatalogQuery catalogQuery = new ExerciseCatalogQuery(
                query,
                categoryCodes,
                muscleGroupCodes,
                equipmentCodes,
                tagCodes,
                difficulties,
                movementPatterns,
                page,
                size
        );
        return ResponseEntity.ok(ExerciseCatalogPageResponse.fromDomain(exerciseCatalogUseCase.search(catalogQuery)));
    }

    @GetMapping("/api/v1/exercises/filter-metadata")
    public ResponseEntity<ExerciseFilterMetadataResponse> getFilterMetadata() {
        return ResponseEntity.ok(ExerciseFilterMetadataResponse.fromDomain(exerciseCatalogUseCase.getFilterMetadata()));
    }

    @GetMapping("/api/v1/exercises/{exerciseId}")
    public ResponseEntity<ExerciseDetailResponse> getExerciseDetail(@PathVariable UUID exerciseId) {
        return ResponseEntity.ok(ExerciseDetailResponse.fromDomain(exerciseCatalogUseCase.getDetail(exerciseId)));
    }
}
