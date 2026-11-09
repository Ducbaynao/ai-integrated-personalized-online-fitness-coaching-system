package com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RecordMeasurementRequest(
        UUID studentId,
        @NotBlank(message = "Metric code is required")
        String metricCode,
        BigDecimal numericValue,
        String textValue,
        Boolean booleanValue,
        String unitCode,
        Instant measuredAt,
        String methodCode,
        @Size(max = 1000, message = "Notes cannot exceed 1000 characters")
        String notes
) {}
