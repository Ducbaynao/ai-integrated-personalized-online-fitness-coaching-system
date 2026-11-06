package com.fitnesscoaching.platform.modules.measurement.application.service;

import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementProgressQuery;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementError;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementFailure;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
public class MeasurementProgressService implements MeasurementProgressQuery {

    private final NamedParameterJdbcTemplate jdbc;
    private final MeasurementAccessPolicy accessPolicy;

    public MeasurementProgressService(
            NamedParameterJdbcTemplate jdbc,
            MeasurementAccessPolicy accessPolicy
    ) {
        this.jdbc = jdbc;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public MeasurementProgressSnapshot query(
            UUID actorId,
            UUID studentId,
            String metricCode,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    ) {
        return query(actorId, studentId, metricCode != null ? Set.of(metricCode) : null, fromDate, toDate, timezone, asOf);
    }

    @Override
    public MeasurementProgressSnapshot query(
            UUID actorId,
            UUID studentId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    ) {
        return query(actorId, studentId, (Set<String>) null, fromDate, toDate, timezone, asOf);
    }

    @Override
    public MeasurementProgressSnapshot query(
            UUID actorId,
            UUID studentId,
            Set<String> metricCodes,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    ) {
        if (actorId == null || studentId == null || fromDate == null || toDate == null
                || timezone == null || asOf == null || fromDate.isAfter(toDate)) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED,
                    "Invalid measurement progress query parameters");
        }

        Instant windowStartInstant = fromDate.atStartOfDay(timezone).toInstant();
        Instant windowEndInstant = toDate.plusDays(1).atStartOfDay(timezone).toInstant();

        // 1. Evaluate authorization and time bounds
        MeasurementAccessPolicy.MeasurementAccessDecision access = accessPolicy.evaluateAccess(actorId, studentId);
        Instant historyFrom = access.historyFrom();
        Instant historyUntil = access.historyUntil();

        Instant effectiveStart = (historyFrom != null && historyFrom.isAfter(windowStartInstant))
                ? historyFrom : windowStartInstant;
        Instant effectiveEnd = (historyUntil != null && historyUntil.isBefore(windowEndInstant))
                ? historyUntil : windowEndInstant;

        Instant queryUpper = effectiveEnd.isAfter(asOf) ? asOf : effectiveEnd;

        // Check if query window is completely outside authorized bounds
        boolean completelyOutOfScope = effectiveStart.isAfter(queryUpper);

        if (completelyOutOfScope) {
            List<MetricCatalogRow> metricCatalog = fetchMetricCatalog(metricCodes, Set.of());
            MeasurementSummary summary = new MeasurementSummary(
                    0,
                    0,
                    0,
                    access.isTimeRestricted(),
                    effectiveStart,
                    queryUpper,
                    false,
                    "MEASUREMENT_HISTORY_RESTRICTED"
            );
            String watermark = calculateSourceWatermark(
                    fromDate, toDate, timezone,
                    access.isSelf(), historyFrom, historyUntil,
                    false, summary.availabilityReason(),
                    List.of(), metricCatalog, List.of()
            );
            return new MeasurementProgressSnapshot(
                    studentId, fromDate, toDate, timezone, asOf, summary, List.of(), watermark
            );
        }

        // 2. Query valid measurement facts
        List<MeasurementObservationFact> observations = fetchObservations(
                studentId, effectiveStart, windowEndInstant, historyUntil, asOf, metricCodes, timezone
        );

        // 3. Assess historical reconstruction limits
        boolean hasHistoricalLimits = checkHistoricalReconstructionLimits(
                studentId, effectiveStart, windowEndInstant, historyUntil, asOf, metricCodes
        );

        // If historical reconstruction is limited, update the status on observations
        if (hasHistoricalLimits && !observations.isEmpty()) {
            observations = observations.stream()
                    .map(o -> new MeasurementObservationFact(
                            o.id(), o.studentId(), o.metricDefinitionId(), o.metricCode(), o.metricDisplayName(),
                            o.measuredAt(), o.measuredLocalDate(), o.numericValue(), o.textValue(), o.booleanValue(),
                            o.unitId(), o.unitCode(), o.unitSymbol(), o.measurementBatchId(),
                            o.sourceId(), o.sourceCode(), o.sourceName(), o.sourceKind(),
                            o.methodId(), o.methodCode(), o.methodName(),
                            o.enteredBy(), o.integrationConnectionId(), o.externalRecordId(),
                            o.confidenceScore(), o.qualityScore(), o.validationStatus(),
                            o.supersededById(), o.createdAt(), "HISTORICAL_RECONSTRUCTION_LIMITED"
                    ))
                    .toList();
        }

        // 4. Compute summary
        int totalObservations = observations.size();
        int distinctMetrics = (int) observations.stream().map(MeasurementObservationFact::metricCode).distinct().count();
        int distinctDays = (int) observations.stream().map(MeasurementObservationFact::measuredLocalDate).distinct().count();

        boolean isPartiallyRestricted = access.isTimeRestricted() &&
                (effectiveStart.isAfter(windowStartInstant) || (historyUntil != null && historyUntil.isBefore(windowEndInstant)));

        String availabilityReason = null;
        if (totalObservations == 0) {
            availabilityReason = isPartiallyRestricted ? "MEASUREMENT_HISTORY_RESTRICTED" : "NO_MEASUREMENTS_RECORDED";
        } else if (isPartiallyRestricted) {
            availabilityReason = "MEASUREMENT_HISTORY_PARTIALLY_RESTRICTED";
        }

        MeasurementSummary summary = new MeasurementSummary(
                totalObservations,
                distinctMetrics,
                distinctDays,
                access.isTimeRestricted(),
                effectiveStart,
                queryUpper,
                hasHistoricalLimits,
                availabilityReason
        );

        // 5. Scoped catalog dependencies for source watermark
        Set<Integer> observationMetricIds = new HashSet<>();
        Set<Short> relevantUnitIds = new HashSet<>();
        for (var obs : observations) {
            observationMetricIds.add(obs.metricDefinitionId());
            if (obs.unitId() != null) {
                relevantUnitIds.add(obs.unitId());
            }
        }
        List<MetricCatalogRow> metricCatalog = fetchMetricCatalog(metricCodes, observationMetricIds);
        for (var m : metricCatalog) {
            if (m.defaultUnitId() != null) {
                relevantUnitIds.add(m.defaultUnitId());
            }
        }
        List<UnitCatalogRow> unitCatalog = fetchUnitCatalog(relevantUnitIds);

        // 6. Compute composite source watermark
        String sourceWatermark = calculateSourceWatermark(
                fromDate, toDate, timezone,
                access.isSelf(), historyFrom, historyUntil,
                hasHistoricalLimits, availabilityReason,
                unitCatalog, metricCatalog, observations
        );

        return new MeasurementProgressSnapshot(
                studentId, fromDate, toDate, timezone, asOf, summary, observations, sourceWatermark
        );
    }

    private List<MeasurementObservationFact> fetchObservations(
            UUID studentId,
            Instant effectiveStart,
            Instant windowEndInstant,
            Instant historyUntil,
            Instant asOf,
            Set<String> metricCodes,
            ZoneId timezone
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.student_id, m.measurement_batch_id, m.metric_definition_id,
                       md.code AS metric_code, md.display_name AS metric_display_name,
                       m.numeric_value, m.text_value, m.boolean_value,
                       m.unit_id, mu.code AS unit_code, mu.symbol AS unit_symbol,
                       m.measured_at,
                       m.source_id, ms.code AS source_code, ms.name AS source_name, ms.source_kind,
                       m.method_id, mm.code AS method_code, mm.name AS method_name,
                       m.entered_by, m.integration_connection_id, m.external_record_id,
                       m.confidence_score, m.quality_score, m.validation_status,
                       m.superseded_by_id, m.created_at
                FROM fitness.measurements m
                JOIN fitness.metric_definitions md ON md.id = m.metric_definition_id
                LEFT JOIN fitness.measurement_units mu ON mu.id = m.unit_id
                JOIN fitness.measurement_sources ms ON ms.id = m.source_id
                LEFT JOIN fitness.measurement_methods mm ON mm.id = m.method_id
                WHERE m.student_id = :studentId
                  AND m.validation_status = 'VALID'
                  AND m.superseded_by_id IS NULL
                  AND m.erased_at IS NULL
                  AND m.measured_at >= :effectiveStart
                  AND m.measured_at < :windowEndInstant
                  AND m.measured_at <= :asOf
                  AND m.created_at <= :asOf
                """);

        var params = new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("effectiveStart", Timestamp.from(effectiveStart))
                .addValue("windowEndInstant", Timestamp.from(windowEndInstant))
                .addValue("asOf", Timestamp.from(asOf));

        if (historyUntil != null) {
            sql.append(" AND m.measured_at <= :historyUntil ");
            params.addValue("historyUntil", Timestamp.from(historyUntil));
        }

        if (metricCodes != null && !metricCodes.isEmpty()) {
            sql.append(" AND md.code IN (:metricCodes) ");
            params.addValue("metricCodes", metricCodes);
        }

        sql.append(" ORDER BY m.measured_at ASC, m.created_at ASC, m.id ASC ");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            Instant measuredAt = rs.getTimestamp("measured_at").toInstant();
            LocalDate measuredLocalDate = measuredAt.atZone(timezone).toLocalDate();
            Boolean boolVal = rs.getObject("boolean_value") != null ? rs.getBoolean("boolean_value") : null;
            Short unitId = rs.getObject("unit_id") != null ? rs.getShort("unit_id") : null;
            Short methodId = rs.getObject("method_id") != null ? rs.getShort("method_id") : null;

            return new MeasurementObservationFact(
                    rs.getObject("id", UUID.class),
                    rs.getObject("student_id", UUID.class),
                    rs.getInt("metric_definition_id"),
                    rs.getString("metric_code"),
                    rs.getString("metric_display_name"),
                    measuredAt,
                    measuredLocalDate,
                    rs.getBigDecimal("numeric_value"),
                    rs.getString("text_value"),
                    boolVal,
                    unitId,
                    rs.getString("unit_code"),
                    rs.getString("unit_symbol"),
                    rs.getObject("measurement_batch_id", UUID.class),
                    rs.getShort("source_id"),
                    rs.getString("source_code"),
                    rs.getString("source_name"),
                    rs.getString("source_kind"),
                    methodId,
                    rs.getString("method_code"),
                    rs.getString("method_name"),
                    rs.getObject("entered_by", UUID.class),
                    rs.getObject("integration_connection_id", UUID.class),
                    rs.getString("external_record_id"),
                    rs.getBigDecimal("confidence_score"),
                    rs.getBigDecimal("quality_score"),
                    rs.getString("validation_status"),
                    rs.getObject("superseded_by_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant(),
                    "HISTORICAL_SNAPSHOT_EXACT"
            );
        });
    }

    private boolean checkHistoricalReconstructionLimits(
            UUID studentId,
            Instant effectiveStart,
            Instant windowEndInstant,
            Instant historyUntil,
            Instant asOf,
            Set<String> metricCodes
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM fitness.measurements m
                JOIN fitness.metric_definitions md ON md.id = m.metric_definition_id
                LEFT JOIN fitness.measurements sup ON sup.id = m.superseded_by_id
                WHERE m.student_id = :studentId
                  AND m.measured_at >= :effectiveStart
                  AND m.measured_at < :windowEndInstant
                  AND m.measured_at <= :asOf
                  AND (
                      m.created_at > :asOf
                      OR (m.created_at <= :asOf AND m.erased_at > :asOf)
                      OR (m.created_at <= :asOf AND m.superseded_by_id IS NOT NULL AND (sup.created_at IS NULL OR sup.created_at > :asOf))
                      OR EXISTS (
                          SELECT 1 FROM fitness.measurement_validation_events ve
                          WHERE ve.measurement_id = m.id AND ve.occurred_at > :asOf
                      )
                  )
                """);

        var params = new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("effectiveStart", Timestamp.from(effectiveStart))
                .addValue("windowEndInstant", Timestamp.from(windowEndInstant))
                .addValue("asOf", Timestamp.from(asOf));

        if (historyUntil != null) {
            sql.append(" AND m.measured_at <= :historyUntil ");
            params.addValue("historyUntil", Timestamp.from(historyUntil));
        }

        if (metricCodes != null && !metricCodes.isEmpty()) {
            sql.append(" AND md.code IN (:metricCodes) ");
            params.addValue("metricCodes", metricCodes);
        }

        Integer count = jdbc.queryForObject(sql.toString(), params, Integer.class);
        return count != null && count > 0;
    }

    private List<UnitCatalogRow> fetchUnitCatalog(Set<Short> unitIds) {
        if (unitIds == null || unitIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT id, code, symbol, dimension, multiplier_to_base
                FROM fitness.measurement_units
                WHERE id IN (:unitIds)
                ORDER BY id ASC
                """;
        var params = new MapSqlParameterSource("unitIds", unitIds);
        return jdbc.query(sql, params, (rs, rowNum) -> new UnitCatalogRow(
                rs.getShort("id"),
                rs.getString("code"),
                rs.getString("symbol"),
                rs.getString("dimension"),
                rs.getBigDecimal("multiplier_to_base")
        ));
    }

    private List<MetricCatalogRow> fetchMetricCatalog(Set<String> metricCodes, Set<Integer> observationMetricIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, code, display_name, default_unit_id
                FROM fitness.metric_definitions
                """);
        var params = new MapSqlParameterSource();
        boolean hasFilter = false;

        if (metricCodes != null && !metricCodes.isEmpty()) {
            sql.append(" WHERE code IN (:metricCodes) ");
            params.addValue("metricCodes", metricCodes);
            hasFilter = true;
        } else if (observationMetricIds != null && !observationMetricIds.isEmpty()) {
            sql.append(" WHERE id IN (:obsMetricIds) ");
            params.addValue("obsMetricIds", observationMetricIds);
            hasFilter = true;
        }

        if (!hasFilter) {
            return List.of();
        }

        sql.append(" ORDER BY code ASC ");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new MetricCatalogRow(
                rs.getInt("id"),
                rs.getString("code"),
                rs.getString("display_name"),
                rs.getObject("default_unit_id") != null ? rs.getShort("default_unit_id") : null
        ));
    }

    private String calculateSourceWatermark(
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            boolean isSelf,
            Instant effectiveHistoryFrom,
            Instant effectiveHistoryUntil,
            boolean hasHistoricalLimits,
            String availabilityReason,
            List<UnitCatalogRow> units,
            List<MetricCatalogRow> metrics,
            List<MeasurementObservationFact> observations
    ) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();

            // 1. WINDOW
            sb.append("WINDOW:").append(fromDate).append(":").append(toDate).append(":").append(timezone.getId()).append("|");

            // 2. AUTH
            sb.append("AUTH:").append(isSelf).append(":")
                    .append(effectiveHistoryFrom != null ? effectiveHistoryFrom.toEpochMilli() : "unbounded").append(":")
                    .append(effectiveHistoryUntil != null ? effectiveHistoryUntil.toEpochMilli() : "unbounded").append("|");

            // 3. SUMMARY METADATA
            sb.append("SUMMARY:").append(hasHistoricalLimits).append(":")
                    .append(availabilityReason != null ? availabilityReason : "none").append("|");

            // 4. UNITS catalog dependency
            var sortedUnits = new ArrayList<>(units);
            sortedUnits.sort(Comparator.comparingInt(u -> (int) u.id()));
            sb.append("UNITS:").append(sortedUnits.size()).append(";");
            for (var u : sortedUnits) {
                sb.append(u.id()).append(",")
                        .append(u.code()).append(",")
                        .append(u.symbol() != null ? u.symbol() : "null").append(",")
                        .append(u.dimension() != null ? u.dimension() : "null").append(",")
                        .append(u.multiplierToBase() != null ? u.multiplierToBase().stripTrailingZeros().toPlainString() : "null").append(";");
            }
            sb.append("|");

            // 5. METRICS catalog dependency
            var sortedMetrics = new ArrayList<>(metrics);
            sortedMetrics.sort(Comparator.comparing(MetricCatalogRow::code));
            sb.append("METRICS:").append(sortedMetrics.size()).append(";");
            for (var m : sortedMetrics) {
                sb.append(m.id()).append(",")
                        .append(m.code()).append(",")
                        .append(m.displayName()).append(",")
                        .append(m.defaultUnitId() != null ? m.defaultUnitId() : "null").append(";");
            }
            sb.append("|");

            // 6. MEASUREMENTS in authorized window (sorted by observation ID for determinism)
            var sortedMeas = new ArrayList<>(observations);
            sortedMeas.sort(Comparator.comparing(MeasurementObservationFact::id));
            sb.append("MEAS:").append(sortedMeas.size()).append(";");
            for (var m : sortedMeas) {
                sb.append(m.id()).append(",")
                        .append(m.metricCode()).append(",")
                        .append(m.measuredAt().toEpochMilli()).append(",")
                        .append(m.numericValue() != null ? m.numericValue().stripTrailingZeros().toPlainString() : "null").append(",")
                        .append(m.textValue() != null ? m.textValue() : "null").append(",")
                        .append(m.booleanValue() != null ? m.booleanValue().toString() : "null").append(",")
                        .append(m.unitId() != null ? m.unitId().toString() : "null").append(",")
                        .append(m.unitCode() != null ? m.unitCode() : "null").append(",")
                        .append(m.measurementBatchId() != null ? m.measurementBatchId().toString() : "null").append(",")
                        .append(m.sourceCode() != null ? m.sourceCode() : "null").append(",")
                        .append(m.sourceKind() != null ? m.sourceKind() : "null").append(",")
                        .append(m.methodCode() != null ? m.methodCode() : "null").append(",")
                        .append(m.enteredBy() != null ? m.enteredBy().toString() : "null").append(",")
                        .append(m.externalRecordId() != null ? m.externalRecordId() : "null").append(",")
                        .append(m.confidenceScore() != null ? m.confidenceScore().stripTrailingZeros().toPlainString() : "null").append(",")
                        .append(m.qualityScore() != null ? m.qualityScore().stripTrailingZeros().toPlainString() : "null").append(",")
                        .append(m.validationStatus()).append(",")
                        .append(m.supersededById() != null ? m.supersededById() : "active").append(",")
                        .append(m.createdAt().toEpochMilli()).append(",")
                        .append(m.historicalReconstructionStatus()).append(";");
            }

            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record UnitCatalogRow(
            short id, String code, String symbol, String dimension, BigDecimal multiplierToBase
    ) {}

    private record MetricCatalogRow(
            int id, String code, String display_name, Short defaultUnitId
    ) {
        public String displayName() {
            return display_name;
        }
    }
}
