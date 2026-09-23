package com.fitnesscoaching.platform.modules.goal.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalCatalogPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class GoalCatalogPersistenceAdapter implements GoalCatalogPort {

    private final JdbcTemplate jdbcTemplate;

    public GoalCatalogPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<GoalTypeCatalogView> GOAL_TYPE_ROW_MAPPER = (rs, rowNum) ->
            new GoalTypeCatalogView(
                    rs.getShort("id"),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getBoolean("is_active")
            );

    private static final RowMapper<MetricDefinitionCatalogView> METRIC_ROW_MAPPER = (rs, rowNum) -> {
        short defaultUnitId = rs.getShort("default_unit_id");
        Short unitIdObj = rs.wasNull() ? null : defaultUnitId;
        return new MetricDefinitionCatalogView(
                rs.getInt("id"),
                rs.getString("code"),
                rs.getString("display_name"),
                unitIdObj,
                rs.getString("default_unit_dimension"),
                rs.getBoolean("is_active")
        );
    };

    private static final RowMapper<MeasurementUnitCatalogView> UNIT_ROW_MAPPER = (rs, rowNum) ->
            new MeasurementUnitCatalogView(
                    rs.getShort("id"),
                    rs.getString("code"),
                    rs.getString("symbol"),
                    rs.getString("dimension")
            );

    @Override
    public Optional<GoalTypeCatalogView> findGoalTypeById(short id) {
        String sql = "SELECT id, code, name, is_active FROM fitness.goal_types WHERE id = ?";
        List<GoalTypeCatalogView> results = jdbcTemplate.query(sql, GOAL_TYPE_ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<GoalTypeCatalogView> findGoalTypeByCode(String code) {
        String sql = "SELECT id, code, name, is_active FROM fitness.goal_types WHERE code = ?";
        List<GoalTypeCatalogView> results = jdbcTemplate.query(sql, GOAL_TYPE_ROW_MAPPER, code);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<MetricDefinitionCatalogView> findMetricDefinitionById(int id) {
        String sql = """
                SELECT m.id, m.code, m.display_name, m.default_unit_id, u.dimension as default_unit_dimension, m.is_active
                FROM fitness.metric_definitions m
                LEFT JOIN fitness.measurement_units u ON u.id = m.default_unit_id
                WHERE m.id = ?
                """;
        List<MetricDefinitionCatalogView> results = jdbcTemplate.query(sql, METRIC_ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<MetricDefinitionCatalogView> findMetricDefinitionByCode(String code) {
        String sql = """
                SELECT m.id, m.code, m.display_name, m.default_unit_id, u.dimension as default_unit_dimension, m.is_active
                FROM fitness.metric_definitions m
                LEFT JOIN fitness.measurement_units u ON u.id = m.default_unit_id
                WHERE m.code = ?
                """;
        List<MetricDefinitionCatalogView> results = jdbcTemplate.query(sql, METRIC_ROW_MAPPER, code);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<MeasurementUnitCatalogView> findMeasurementUnitById(short id) {
        String sql = "SELECT id, code, symbol, dimension FROM fitness.measurement_units WHERE id = ?";
        List<MeasurementUnitCatalogView> results = jdbcTemplate.query(sql, UNIT_ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<MeasurementUnitCatalogView> findMeasurementUnitByCode(String code) {
        String sql = "SELECT id, code, symbol, dimension FROM fitness.measurement_units WHERE code = ?";
        List<MeasurementUnitCatalogView> results = jdbcTemplate.query(sql, UNIT_ROW_MAPPER, code);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
