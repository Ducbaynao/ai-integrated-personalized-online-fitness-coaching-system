package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.domain.GoalProposalTarget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record GoalProposalTargetResponse(
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
        String notes
) {
    public static GoalProposalTargetResponse fromDomain(GoalProposalTarget domain) {
        return new GoalProposalTargetResponse(
                domain.id(),
                domain.metricDefinitionId(),
                domain.metricCode(),
                domain.metricDisplayName(),
                domain.exerciseVariationId(),
                domain.startValue(),
                domain.targetValue(),
                domain.targetMinValue(),
                domain.targetMaxValue(),
                domain.unitId(),
                domain.unitCode(),
                domain.unitSymbol(),
                domain.targetRepetitions(),
                domain.targetDate(),
                domain.notes()
        );
    }
}
