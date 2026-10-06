package com.fitnesscoaching.platform.modules.user.application.model;

import java.util.List;

public record UserDisplaySummaryPage(List<UserDisplaySummary> items, int page, int size) {
    public UserDisplaySummaryPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
