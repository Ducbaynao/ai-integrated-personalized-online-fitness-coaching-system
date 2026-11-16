package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import java.util.List;

public record F03DraftDiff(
        boolean hasChanges,
        String changeType,
        String summary,
        List<FieldDiff> fieldDiffs
) {
    public record FieldDiff(
            String field,
            String changeType,
            String originalValue,
            String customizedValue,
            String description
    ) {}

    public static F03DraftDiff noChanges(String changeType) {
        return new F03DraftDiff(false, changeType, "No customization applied relative to original proposal", List.of());
    }
}
