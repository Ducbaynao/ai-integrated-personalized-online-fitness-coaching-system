package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

public record AcceptAdjustmentApiRequest(
        String commandKey,
        String decisionNote,
        Integer expectedRevision,
        Long expectedTargetVersion
) {}
