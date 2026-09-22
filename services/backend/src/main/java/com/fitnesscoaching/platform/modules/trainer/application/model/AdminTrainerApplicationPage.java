package com.fitnesscoaching.platform.modules.trainer.application.model;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;

import java.util.List;

public record AdminTrainerApplicationPage(
        List<TrainerApplication> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
    public AdminTrainerApplicationPage {
        items = items != null ? List.copyOf(items) : List.of();
    }
}
