package com.fitnesscoaching.platform.modules.ai.application.port.out;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.ChangeType;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetRef;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetScope;
import com.fitnesscoaching.platform.modules.ai.domain.F03SourceSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface F03AiServiceClient {

    String generateAdjustmentJson(F03ServiceRequest request);

    record BaselinePrescription(
            UUID exerciseVariationId,
            String exerciseName,
            String movementPattern,
            String equipmentType,
            BigDecimal targetLoad,
            String loadUnit,
            Integer loadUnitId,
            Integer targetSets,
            Integer targetRepsMin,
            Integer targetRepsMax,
            Integer durationSeconds
    ) {}

    record F03ServiceRequest(
            UUID studentId,
            UUID coachingPeriodId,
            String coachingMode,
            TargetScope scope,
            TargetRef targetRef,
            ChangeType requestedChangeType,
            String userReason,
            List<String> physicalLimitations,
            List<String> equipmentAvailable,
            boolean allowLoadIncrease,
            TrainingContinuityState continuityState,
            BaselinePrescription baselinePrescription,
            F03SourceSnapshot sourceSnapshot
    ) {}
}
