package com.fitnesscoaching.platform.modules.trainer.application.model;

import java.util.List;

public record TrainerDirectoryPage(List<TrainerDirectoryItem> items, int page, int size) {
    public TrainerDirectoryPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
