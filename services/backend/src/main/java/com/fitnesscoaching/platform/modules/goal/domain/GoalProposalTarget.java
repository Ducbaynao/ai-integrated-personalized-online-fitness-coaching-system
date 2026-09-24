package com.fitnesscoaching.platform.modules.goal.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record GoalProposalTarget(
        UUID id,
        UUID goalProposalId,
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
}
