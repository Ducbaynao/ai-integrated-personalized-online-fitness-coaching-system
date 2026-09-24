package com.fitnesscoaching.platform.modules.goal.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateGoalTargetCommand(
        Integer metricDefinitionId,
        String metricCode,
        UUID exerciseVariationId,
        BigDecimal startValue,
        BigDecimal targetValue,
        BigDecimal targetMinValue,
        BigDecimal targetMaxValue,
        Short unitId,
        String unitCode,
        Integer targetRepetitions,
        LocalDate targetDate,
        String notes
) {
}
