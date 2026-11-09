package com.fitnesscoaching.platform.modules.measurement.application.service;

import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.MetricDefinitionResponse;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class MetricCatalogService {

    private final NamedParameterJdbcTemplate jdbc;

    public MetricCatalogService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MetricDefinitionResponse> listActiveMetrics() {
        String sql = """
                SELECT md.id, md.code, md.display_name, md.description, md.value_type,
                       md.collection_type, md.default_unit_id, md.valid_min, md.valid_max,
                       md.stale_after_days, md.is_active,
                       mu.code AS unit_code, mu.symbol AS unit_symbol, mu.dimension
                FROM fitness.metric_definitions md
                JOIN fitness.measurement_units mu ON mu.id = md.default_unit_id
                WHERE md.is_active = true
                ORDER BY md.code ASC
                """;

        return jdbc.query(sql, Map.of(), (rs, rowNum) -> new MetricDefinitionResponse(
                rs.getInt("id"),
                rs.getString("code"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("value_type"),
                rs.getString("collection_type"),
                rs.getShort("default_unit_id"),
                rs.getString("unit_code"),
                rs.getString("unit_symbol"),
                rs.getString("dimension"),
                rs.getBigDecimal("valid_min"),
                rs.getBigDecimal("valid_max"),
                rs.getObject("stale_after_days") != null ? rs.getInt("stale_after_days") : null,
                rs.getBoolean("is_active")
        ));
    }
}
