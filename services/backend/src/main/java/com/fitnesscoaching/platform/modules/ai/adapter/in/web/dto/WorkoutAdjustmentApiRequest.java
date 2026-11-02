package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import java.util.List;
import java.util.UUID;

public record WorkoutAdjustmentApiRequest(
        UUID studentId,
        String commandKey,
        F03WorkoutAdjustmentPayload.TargetScope scope,
        F03WorkoutAdjustmentPayload.TargetRef target,
        F03WorkoutAdjustmentPayload.ChangeType requestedChangeType,
        String userReason,
        List<String> physicalLimitations,
        List<String> equipmentAvailable,
        Boolean allowLoadIncrease
) {}
