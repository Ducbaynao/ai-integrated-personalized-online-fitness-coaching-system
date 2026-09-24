package com.fitnesscoaching.platform.modules.goal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record GoalTarget(
        UUID id,
        UUID goalVersionId,
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
}
