package com.fitnesscoaching.platform.modules.measurement.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementUnitReferenceQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcMeasurementUnitReferenceAdapter implements MeasurementUnitReferenceQuery {
    private final JdbcTemplate jdbc;

    public JdbcMeasurementUnitReferenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Short, UnitReference> resolveAll(Collection<Short> unitIds) {
        if (unitIds == null || unitIds.isEmpty()) return Map.of();
        List<Short> distinctIds = unitIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Map.of();

        String placeholders = String.join(",", java.util.Collections.nCopies(distinctIds.size(), "?"));
        Map<Short, UnitReference> references = new LinkedHashMap<>();
        jdbc.query("SELECT id,code,symbol,dimension FROM fitness.measurement_units WHERE id IN ("
                        + placeholders + ")",
                rs -> {
                    short id = rs.getShort("id");
                    references.put(id, new UnitReference(id, rs.getString("code"), rs.getString("symbol"),
                            rs.getString("dimension")));
                }, distinctIds.toArray());
        return Map.copyOf(references);
    }
}
