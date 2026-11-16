package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

public record ShareAdjustmentDraftApiRequest(
        Integer expectedRevision,
        String shareNote
) {
    public ShareAdjustmentDraftApiRequest(Integer expectedRevision) {
        this(expectedRevision, null);
    }
}
