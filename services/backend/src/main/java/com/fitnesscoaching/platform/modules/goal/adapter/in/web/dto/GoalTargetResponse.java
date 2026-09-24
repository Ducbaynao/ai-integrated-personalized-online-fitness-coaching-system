package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.domain.GoalTarget;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record GoalTargetResponse(
        UUID id,
        int metricDefinitionId,
        String metricCode,
        String metricDisplayName,
        UUID exerciseVariationId,
        BigDecimal startValue,
        BigDecimal targetValue,
        BigDecimal targetMinValue,
        BigDecimal targetMaxValue,
        short unitId,
        String unitCode,
        String unitSymbol,
        Integer targetRepetitions,
        LocalDate targetDate,
        String notes,
        Instant createdAt
) {
    public static GoalTargetResponse fromDomain(GoalTarget target) {
        if (target == null) {
            return null;
        }
        return new GoalTargetResponse(
                target.id(),
                target.metricDefinitionId(),
                target.metricCode(),
                target.metricDisplayName(),
                target.exerciseVariationId(),
                target.startValue(),
                target.targetValue(),
                target.targetMinValue(),
                target.targetMaxValue(),
                target.unitId(),
                target.unitCode(),
                target.unitSymbol(),
                target.targetRepetitions(),
                target.targetDate(),
                target.notes(),
                target.createdAt()
        );
    }
}
