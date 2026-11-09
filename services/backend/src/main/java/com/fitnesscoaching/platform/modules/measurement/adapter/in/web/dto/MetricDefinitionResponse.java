package com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto;

import java.math.BigDecimal;

public record MetricDefinitionResponse(
        int id,
        String code,
        String displayName,
        String description,
        String valueType,
        String collectionType,
        short defaultUnitId,
        String defaultUnitCode,
        String defaultUnitSymbol,
        String dimension,
        BigDecimal validMin,
        BigDecimal validMax,
        Integer staleAfterDays,
        boolean isActive
) {}
