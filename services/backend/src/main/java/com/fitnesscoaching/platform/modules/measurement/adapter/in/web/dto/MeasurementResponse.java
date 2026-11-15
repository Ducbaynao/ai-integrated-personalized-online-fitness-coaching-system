package com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MeasurementResponse(
        UUID id,
        UUID studentId,
        int metricDefinitionId,
        String metricCode,
        String metricDisplayName,
        BigDecimal numericValue,
        String textValue,
        Boolean booleanValue,
        Short unitId,
        String unitCode,
        String unitSymbol,
        Instant measuredAt,
        String sourceCode,
        String sourceName,
        String sourceKind,
        String methodCode,
        String methodName,
        UUID enteredBy,
        BigDecimal confidenceScore,
        BigDecimal qualityScore,
        String validationStatus,
        UUID supersededById,
        String notes,
        Instant createdAt
) {}
