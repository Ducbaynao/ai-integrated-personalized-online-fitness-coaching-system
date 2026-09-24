package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalTargetCommand;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateGoalTargetRequest(
        Integer metricDefinitionId,
        String metricCode,
        UUID exerciseVariationId,
        BigDecimal startValue,
        BigDecimal targetValue,
        BigDecimal targetMinValue,
        BigDecimal targetMaxValue,
        Short unitId,
        String unitCode,
        @Positive(message = "Target repetitions must be positive")
        Integer targetRepetitions,
        LocalDate targetDate,
        @Size(max = 2000, message = "Notes must not exceed 2000 characters")
        String notes
) {
    public CreateGoalTargetCommand toCommand() {
        return new CreateGoalTargetCommand(
                metricDefinitionId,
                metricCode,
                exerciseVariationId,
                startValue,
                targetValue,
                targetMinValue,
                targetMaxValue,
                unitId,
                unitCode,
                targetRepetitions,
                targetDate,
                notes
        );
    }
}
