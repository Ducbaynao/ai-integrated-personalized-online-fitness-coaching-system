package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.application.model.AdminExercisePage;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminExercisePageResponse(
        List<Item> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
    public static AdminExercisePageResponse fromDomain(AdminExercisePage page) {
        return new AdminExercisePageResponse(page.items().stream().map(Item::fromDomain).toList(),
                page.page(), page.size(), page.totalItems(), page.totalPages());
    }

    public record Item(
            UUID id, String code, String name, String categoryCode, String difficulty,
            String movementPattern, String status, long version, UUID canonicalReplacementId,
            List<String> muscleGroupCodes, List<String> equipmentCodes, boolean mediaAvailable,
            int variationCount, Instant updatedAt
    ) {
        static Item fromDomain(AdminExerciseSummary item) {
            return new Item(item.id(), item.code(), item.name(), item.categoryCode(), item.difficulty(),
                    item.movementPattern(), item.status().name(), item.version(), item.canonicalReplacementId(),
                    item.muscleGroupCodes(), item.equipmentCodes(), item.mediaAvailable(),
                    item.variationCount(), item.updatedAt());
        }
    }
}
