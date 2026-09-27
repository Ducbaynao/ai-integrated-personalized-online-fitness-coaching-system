package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseMediaReference;

import java.util.UUID;

public record ExerciseMediaReferenceResponse(
        UUID mediaId,
        String purpose,
        int sortOrder,
        String contentType,
        Long sizeBytes,
        boolean available
) {
    public static ExerciseMediaReferenceResponse fromDomain(ExerciseMediaReference media) {
        return new ExerciseMediaReferenceResponse(
                media.mediaId(),
                media.purpose(),
                media.sortOrder(),
                media.contentType(),
                media.sizeBytes(),
                media.available()
        );
    }
}
