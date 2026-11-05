package com.fitnesscoaching.platform.modules.measurement.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementUnitReferenceQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
public class JdbcMeasurementUnitReferenceAdapter implements MeasurementUnitReferenceQuery {
    private final JdbcTemplate jdbc;

    public JdbcMeasurementUnitReferenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Short, UnitReference> resolveAll(Collection<Short> unitIds) {
        if (unitIds == null || unitIds.isEmpty()) return Map.of();
        List<Short> distinctIds = unitIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Map.of();

        String placeholders = String.join(",", Collections.nCopies(distinctIds.size(), "?"));
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

    @Override
    public Map<String, UnitDetails> resolveByCodes(Collection<String> unitCodes) {
        if (unitCodes == null || unitCodes.isEmpty()) return Map.of();
        List<String> upperCodes = unitCodes.stream()
                .filter(Objects::nonNull)
                .map(String::toUpperCase)
                .distinct()
                .toList();
        if (upperCodes.isEmpty()) return Map.of();

        String placeholders = String.join(",", Collections.nCopies(upperCodes.size(), "?"));
        Map<String, UnitDetails> details = new LinkedHashMap<>();
        jdbc.query("SELECT id, code, symbol, dimension, base_unit_code, multiplier_to_base, offset_to_base "
                        + "FROM fitness.measurement_units WHERE UPPER(code) IN (" + placeholders + ")",
                rs -> {
                    String code = rs.getString("code").toUpperCase();
                    details.put(code, new UnitDetails(
                            rs.getShort("id"),
                            code,
                            rs.getString("symbol"),
                            rs.getString("dimension"),
                            rs.getString("base_unit_code"),
                            rs.getBigDecimal("multiplier_to_base"),
                            rs.getBigDecimal("offset_to_base")
                    ));
                }, upperCodes.toArray());
        return Map.copyOf(details);
    }
}
