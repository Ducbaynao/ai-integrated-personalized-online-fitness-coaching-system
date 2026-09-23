package com.fitnesscoaching.platform.modules.goal.application.port.out;

import java.util.Optional;

public interface GoalCatalogPort {

    Optional<GoalTypeCatalogView> findGoalTypeById(short id);

    Optional<GoalTypeCatalogView> findGoalTypeByCode(String code);

    Optional<MetricDefinitionCatalogView> findMetricDefinitionById(int id);

    Optional<MetricDefinitionCatalogView> findMetricDefinitionByCode(String code);

    Optional<MeasurementUnitCatalogView> findMeasurementUnitById(short id);

    Optional<MeasurementUnitCatalogView> findMeasurementUnitByCode(String code);

    record GoalTypeCatalogView(short id, String code, String name, boolean isActive) {
    }

    record MetricDefinitionCatalogView(
            int id,
            String code,
            String displayName,
            Short defaultUnitId,
            String defaultUnitDimension,
            boolean isActive
    ) {
    }

    record MeasurementUnitCatalogView(
            short id,
            String code,
            String symbol,
            String dimension
    ) {
    }
}
