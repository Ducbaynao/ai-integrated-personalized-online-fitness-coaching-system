package com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record CorrectMeasurementRequest(
        BigDecimal numericValue,
        String textValue,
        Boolean booleanValue,
        String unitCode,
        Instant measuredAt,
        @NotBlank(message = "Correction reason is mandatory")
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        String reason,
        @Size(max = 1000, message = "Notes cannot exceed 1000 characters")
        String notes
) {}
