package com.fitnesscoaching.platform.modules.exercise.application.port.in;

import java.util.List;

public record ExerciseCatalogQuery(
        String query,
        List<String> categoryCodes,
        List<String> muscleGroupCodes,
        List<String> equipmentCodes,
        List<String> tagCodes,
        List<String> difficulties,
        List<String> movementPatterns,
        int page,
        int size
) {
}
