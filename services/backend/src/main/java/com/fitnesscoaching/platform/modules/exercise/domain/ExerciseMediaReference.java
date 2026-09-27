package com.fitnesscoaching.platform.modules.exercise.domain;

import java.util.UUID;

public record ExerciseMediaReference(
        UUID mediaId,
        String purpose,
        int sortOrder,
        String contentType,
        Long sizeBytes,
        boolean available
) {
}
