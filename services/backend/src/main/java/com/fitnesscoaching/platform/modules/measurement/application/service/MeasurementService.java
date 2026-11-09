package com.fitnesscoaching.platform.modules.measurement.application.service;

import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.CorrectMeasurementRequest;
import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.MeasurementPageResponse;
import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.MeasurementResponse;
import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.RecordMeasurementRequest;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementError;
import com.fitnesscoaching.platform.modules.measurement.domain.MeasurementFailure;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class MeasurementService {

    private final NamedParameterJdbcTemplate jdbc;
    private final MeasurementAccessPolicy accessPolicy;
    private final AuditService auditService;

    public MeasurementService(
            NamedParameterJdbcTemplate jdbc,
            MeasurementAccessPolicy accessPolicy,
            AuditService auditService
    ) {
        this.jdbc = jdbc;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
    }

    public MeasurementResponse recordMeasurement(UUID actorId, RecordMeasurementRequest request) {
        if (actorId == null || request == null) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Actor and request must not be null");
        }

        UUID studentId = request.studentId() != null ? request.studentId() : actorId;
        accessPolicy.evaluateWriteAccess(actorId, studentId);

        Instant measuredAt = request.measuredAt() != null ? request.measuredAt() : Instant.now();
        if (measuredAt.isAfter(Instant.now().plusSeconds(300))) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Measurement time cannot be in the future");
        }

        // Look up metric definition
        var metricRows = jdbc.query(
                "SELECT id, code, display_name, value_type, default_unit_id, is_active FROM fitness.metric_definitions WHERE UPPER(code) = UPPER(:code)",
                Map.of("code", request.metricCode()),
                (rs, rowNum) -> Map.of(
                        "id", rs.getInt("id"),
                        "code", rs.getString("code"),
                        "displayName", rs.getString("display_name"),
                        "valueType", rs.getString("value_type"),
                        "defaultUnitId", rs.getShort("default_unit_id"),
                        "isActive", rs.getBoolean("is_active")
                )
        );

        if (metricRows.isEmpty() || !((Boolean) metricRows.get(0).get("isActive"))) {
            throw new MeasurementFailure(MeasurementError.METRIC_NOT_FOUND, "Metric not found or inactive: " + request.metricCode());
        }

        var metric = metricRows.get(0);
        int metricDefId = (Integer) metric.get("id");
        String valueType = (String) metric.get("valueType");

        // Value type enforcement
        if ("NUMERIC".equalsIgnoreCase(valueType) && request.numericValue() == null) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Numeric metric requires numericValue");
        }
        if ("TEXT".equalsIgnoreCase(valueType) && (request.textValue() == null || request.textValue().isBlank())) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Text metric requires textValue");
        }
        if ("BOOLEAN".equalsIgnoreCase(valueType) && request.booleanValue() == null) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Boolean metric requires booleanValue");
        }

        // Resolve unit_id
        Short unitId = metric.get("defaultUnitId") instanceof Number n ? n.shortValue() : null;
        if (request.unitCode() != null && !request.unitCode().isBlank()) {
            var unitRows = jdbc.query(
                    "SELECT id, dimension FROM fitness.measurement_units WHERE UPPER(code) = UPPER(:code)",
                    Map.of("code", request.unitCode()),
                    (rs, rowNum) -> Map.of("id", rs.getShort("id"), "dimension", rs.getString("dimension") != null ? rs.getString("dimension") : "")
            );
            if (unitRows.isEmpty()) {
                throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Unknown unit code: " + request.unitCode());
            }
            unitId = (Short) unitRows.get(0).get("id");
            String selectedDim = (String) unitRows.get(0).get("dimension");

            if (metric.get("defaultUnitId") != null) {
                var defaultDimRows = jdbc.query(
                        "SELECT dimension FROM fitness.measurement_units WHERE id = :defId",
                        Map.of("defId", metric.get("defaultUnitId")),
                        (rs, rowNum) -> rs.getString("dimension") != null ? rs.getString("dimension") : ""
                );
                if (!defaultDimRows.isEmpty()) {
                    String defaultDim = defaultDimRows.get(0);
                    if (!defaultDim.equalsIgnoreCase(selectedDim)) {
                        throw new MeasurementFailure(MeasurementError.UNIT_INCOMPATIBLE,
                                "Measurement unit '" + request.unitCode() + "' with dimension '" + selectedDim +
                                "' is incompatible with metric default unit dimension: " + defaultDim);
                    }
                }
            }
        }

        // Resolve source_id
        boolean isSelf = actorId.equals(studentId);
        String defaultSourceCode = isSelf ? "MANUAL_STUDENT" : "MANUAL_TRAINER";
        var sourceRows = jdbc.query(
                "SELECT id FROM fitness.measurement_sources WHERE UPPER(code) = UPPER(:code)",
                Map.of("code", defaultSourceCode),
                (rs, rowNum) -> rs.getShort("id")
        );
        Short sourceId = sourceRows.isEmpty() ? null : sourceRows.get(0);
        if (sourceId == null) {
            var anySource = jdbc.query("SELECT id FROM fitness.measurement_sources LIMIT 1", Map.of(), (rs, r) -> rs.getShort("id"));
            sourceId = anySource.isEmpty() ? 1 : anySource.get(0);
        }

        // Resolve method_id (optional)
        Short methodId = null;
        if (request.methodCode() != null && !request.methodCode().isBlank()) {
            var methodRows = jdbc.query(
                    "SELECT id FROM fitness.measurement_methods WHERE UPPER(code) = UPPER(:code)",
                    Map.of("code", request.methodCode()),
                    (rs, rowNum) -> rs.getShort("id")
            );
            if (!methodRows.isEmpty()) {
                methodId = methodRows.get(0);
            }
        }

        UUID newId = UUID.randomUUID();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", newId)
                .addValue("studentId", studentId)
                .addValue("metricDefId", metricDefId)
                .addValue("numericValue", request.numericValue())
                .addValue("textValue", request.textValue())
                .addValue("booleanValue", request.booleanValue())
                .addValue("unitId", unitId)
                .addValue("measuredAt", Timestamp.from(measuredAt))
                .addValue("sourceId", sourceId)
                .addValue("methodId", methodId)
                .addValue("enteredBy", actorId)
                .addValue("notes", request.notes());

        jdbc.update("""
                INSERT INTO fitness.measurements(
                    id, student_id, metric_definition_id, numeric_value, text_value, boolean_value,
                    unit_id, measured_at, source_id, method_id, entered_by, notes, validation_status
                ) VALUES (
                    :id, :studentId, :metricDefId, :numericValue, :textValue, :booleanValue,
                    :unitId, :measuredAt, :sourceId, :methodId, :enteredBy, :notes, 'VALID'
                )
                """, params);

        return fetchMeasurementById(newId);
    }

    public MeasurementResponse correctMeasurement(UUID actorId, UUID oldMeasurementId, CorrectMeasurementRequest request) {
        if (actorId == null || oldMeasurementId == null || request == null) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Actor and request parameters must not be null");
        }

        record OldMeasurementData(
                UUID studentId,
                int metricDefId,
                BigDecimal numericValue,
                String textValue,
                Boolean booleanValue,
                Short unitId,
                Instant measuredAt,
                Short sourceId,
                Short methodId,
                String notes,
                UUID supersededById,
                String validationStatus
        ) {}

        var oldRows = jdbc.query("""
                SELECT m.id, m.student_id, m.metric_definition_id, m.numeric_value, m.text_value, m.boolean_value,
                       m.unit_id, m.measured_at, m.source_id, m.method_id, m.notes, m.superseded_by_id, m.validation_status
                FROM fitness.measurements m
                WHERE m.id = :id AND m.erased_at IS NULL
                FOR UPDATE
                """, Map.of("id", oldMeasurementId), (rs, rowNum) -> new OldMeasurementData(
                (UUID) rs.getObject("student_id"),
                rs.getInt("metric_definition_id"),
                rs.getBigDecimal("numeric_value"),
                rs.getString("text_value"),
                (Boolean) rs.getObject("boolean_value"),
                parseNullableShort(rs.getObject("unit_id")),
                rs.getTimestamp("measured_at").toInstant(),
                parseNullableShort(rs.getObject("source_id")),
                parseNullableShort(rs.getObject("method_id")),
                rs.getString("notes"),
                (UUID) rs.getObject("superseded_by_id"),
                rs.getString("validation_status")
        ));

        if (oldRows.isEmpty()) {
            throw new MeasurementFailure(MeasurementError.MEASUREMENT_NOT_FOUND, "Measurement not found: " + oldMeasurementId);
        }

        var old = oldRows.get(0);
        try {
            UUID studentId = old.studentId();
            accessPolicy.evaluateWriteAccess(actorId, studentId);

            UUID existingSuperseded = old.supersededById();
            if (existingSuperseded != null) {
                throw new MeasurementFailure(MeasurementError.MEASUREMENT_CORRECTION_CONFLICT,
                        "Measurement has already been corrected and superseded by: " + existingSuperseded);
            }

            int metricDefId = old.metricDefId();
            Short unitId = old.unitId();
            if (request.unitCode() != null && !request.unitCode().isBlank()) {
                var unitRows = jdbc.query(
                        "SELECT id, dimension FROM fitness.measurement_units WHERE UPPER(code) = UPPER(:code)",
                        Map.of("code", request.unitCode()),
                        (rs, rowNum) -> Map.of("id", rs.getShort("id"), "dimension", rs.getString("dimension") != null ? rs.getString("dimension") : "")
                );
                if (unitRows.isEmpty()) {
                    throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Unknown unit code: " + request.unitCode());
                }
                unitId = (Short) unitRows.get(0).get("id");
                String selectedDim = (String) unitRows.get(0).get("dimension");

                var defaultDimRows = jdbc.query(
                        "SELECT u.dimension FROM fitness.metric_definitions m JOIN fitness.measurement_units u ON u.id = m.default_unit_id WHERE m.id = :defId",
                        Map.of("defId", metricDefId),
                        (rs, rowNum) -> rs.getString("dimension") != null ? rs.getString("dimension") : ""
                );
                if (!defaultDimRows.isEmpty()) {
                    String defaultDim = defaultDimRows.get(0);
                    if (!defaultDim.equalsIgnoreCase(selectedDim)) {
                        throw new MeasurementFailure(MeasurementError.UNIT_INCOMPATIBLE,
                                "Measurement unit '" + request.unitCode() + "' with dimension '" + selectedDim +
                                "' is incompatible with metric default unit dimension: " + defaultDim);
                    }
                }
            }

            Instant measuredAt = request.measuredAt() != null
                    ? request.measuredAt()
                    : old.measuredAt();

            String combinedNotes = request.notes() != null
                    ? request.notes()
                    : (old.notes() != null ? old.notes() + " (Corrected: " + request.reason() + ")" : "Corrected: " + request.reason());

            UUID newId = UUID.randomUUID();
            MapSqlParameterSource insertParams = new MapSqlParameterSource()
                    .addValue("id", newId)
                    .addValue("studentId", studentId)
                    .addValue("metricDefId", metricDefId)
                    .addValue("numericValue", request.numericValue() != null ? request.numericValue() : old.numericValue())
                    .addValue("textValue", request.textValue() != null ? request.textValue() : old.textValue())
                    .addValue("booleanValue", request.booleanValue() != null ? request.booleanValue() : old.booleanValue())
                    .addValue("unitId", unitId)
                    .addValue("measuredAt", Timestamp.from(measuredAt))
                    .addValue("sourceId", old.sourceId())
                    .addValue("methodId", old.methodId())
                    .addValue("enteredBy", actorId)
                    .addValue("notes", combinedNotes);

            jdbc.update("""
                    INSERT INTO fitness.measurements(
                        id, student_id, metric_definition_id, numeric_value, text_value, boolean_value,
                        unit_id, measured_at, source_id, method_id, entered_by, notes, validation_status
                    ) VALUES (
                        :id, :studentId, :metricDefId, :numericValue, :textValue, :booleanValue,
                        :unitId, :measuredAt, :sourceId, :methodId, :enteredBy, :notes, 'VALID'
                    )
                    """, insertParams);

            // Mark old measurement as superseded and update status
            int updated = jdbc.update(
                    "UPDATE fitness.measurements SET superseded_by_id = :newId, validation_status = 'SUPERSEDED'::fitness.measurement_validation_status WHERE id = :oldId AND superseded_by_id IS NULL",
                    Map.of("newId", newId, "oldId", oldMeasurementId)
            );
            if (updated != 1) {
                throw new MeasurementFailure(MeasurementError.MEASUREMENT_CORRECTION_CONFLICT,
                        "Measurement has already been corrected by another transaction: " + oldMeasurementId);
            }

            // Record validation events for both old (SUPERSEDED) and new (VALID)
            boolean isSelf = actorId.equals(studentId);
            jdbc.update("""
                    INSERT INTO fitness.measurement_validation_events(
                        measurement_id, previous_status, new_status, validator_type, validator_user_id, rule_code, reason
                    ) VALUES (
                        :measurementId, :prevStatus::fitness.measurement_validation_status, 'SUPERSEDED'::fitness.measurement_validation_status,
                        :validatorType, :validatorUserId, 'USER_CORRECTION', :reason
                    )
                    """, Map.of(
                    "measurementId", oldMeasurementId,
                    "prevStatus", old.validationStatus() != null ? old.validationStatus() : "VALID",
                    "validatorType", isSelf ? "USER" : "TRAINER",
                    "validatorUserId", actorId,
                    "reason", request.reason()
            ));

            jdbc.update("""
                    INSERT INTO fitness.measurement_validation_events(
                        measurement_id, previous_status, new_status, validator_type, validator_user_id, rule_code, reason
                    ) VALUES (
                        :measurementId, 'PENDING'::fitness.measurement_validation_status, 'VALID'::fitness.measurement_validation_status,
                        :validatorType, :validatorUserId, 'USER_CORRECTION', :reason
                    )
                    """, Map.of(
                    "measurementId", newId,
                    "validatorType", isSelf ? "USER" : "TRAINER",
                    "validatorUserId", actorId,
                    "reason", request.reason()
            ));

            // Record mandatory audit record
            String beforeDataJson = String.format("{\"value\":%s,\"unitId\":%s,\"notes\":%s}",
                    old.numericValue() != null ? old.numericValue().toString() : (old.textValue() != null ? "\"" + old.textValue() + "\"" : "null"),
                    old.unitId() != null ? old.unitId().toString() : "null",
                    old.notes() != null ? "\"" + old.notes().replace("\"", "\\\"") + "\"" : "null");
            String afterDataJson = String.format("{\"value\":%s,\"unitId\":%s,\"notes\":%s}",
                    request.numericValue() != null ? request.numericValue().toString() : (request.textValue() != null ? "\"" + request.textValue() + "\"" : (old.numericValue() != null ? old.numericValue().toString() : "null")),
                    unitId != null ? unitId.toString() : "null",
                    combinedNotes != null ? "\"" + combinedNotes.replace("\"", "\\\"") + "\"" : "null");
            String metadataJson = String.format("{\"reason\":\"%s\",\"supersededMeasurementId\":\"%s\"}",
                    request.reason().replace("\"", "\\\""),
                    oldMeasurementId.toString());

            auditService.recordAudit(AuditRecord.builder()
                    .actorUserId(actorId)
                    .actorRole(isSelf ? "STUDENT" : "TRAINER")
                    .action("MEASUREMENT_CORRECTED")
                    .targetType("MEASUREMENT")
                    .targetId(oldMeasurementId)
                    .beforeDataJson(beforeDataJson)
                    .afterDataJson(afterDataJson)
                    .metadataJson(metadataJson)
                    .occurredAt(Instant.now())
                    .build());

            return fetchMeasurementById(newId);
        } catch (MeasurementFailure mf) {
            throw mf;
        } catch (Exception ex) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Failed to correct measurement: " + ex.getMessage());
        }
    }

    @Transactional
    public MeasurementPageResponse getHistory(
            UUID actorId,
            UUID targetStudentId,
            String metricCode,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            int page,
            int size,
            String sort
    ) {
        if (actorId == null) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Actor ID must not be null");
        }
        UUID studentId = targetStudentId != null ? targetStudentId : actorId;
        var access = accessPolicy.evaluateAccess(actorId, studentId);

        ZoneId zone = timezone != null ? timezone : ZoneId.of("Asia/Ho_Chi_Minh");
        LocalDate start = fromDate != null ? fromDate : LocalDate.now(zone).minusYears(1);
        LocalDate end = toDate != null ? toDate : LocalDate.now(zone);

        Instant windowStart = start.atStartOfDay(zone).toInstant();
        Instant windowEnd = end.plusDays(1).atStartOfDay(zone).toInstant();

        Instant historyFrom = access.historyFrom();
        Instant historyUntil = access.historyUntil();

        Instant effectiveStart = (historyFrom != null && historyFrom.isAfter(windowStart)) ? historyFrom : windowStart;
        Instant effectiveEnd = (historyUntil != null && historyUntil.isBefore(windowEnd)) ? historyUntil : windowEnd;

        int safePage = Math.max(0, page);
        int safeSize = (size <= 0 || size > 100) ? 20 : size;
        int offset = safePage * safeSize;

        String sortOrder = "ASC".equalsIgnoreCase(sort) ? "ASC" : "DESC";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("startTime", Timestamp.from(effectiveStart))
                .addValue("endTime", Timestamp.from(effectiveEnd))
                .addValue("offset", offset)
                .addValue("limit", safeSize);

        String metricFilter = "";
        if (metricCode != null && !metricCode.isBlank()) {
            metricFilter = " AND UPPER(md.code) = UPPER(:metricCode)";
            params.addValue("metricCode", metricCode.trim());
        }

        String countSql = "SELECT COUNT(*) FROM fitness.measurements m "
                + "JOIN fitness.metric_definitions md ON md.id = m.metric_definition_id "
                + "WHERE m.student_id = :studentId AND m.erased_at IS NULL "
                + "AND m.measured_at >= :startTime AND m.measured_at < :endTime"
                + metricFilter;

        Long total = jdbc.queryForObject(countSql, params, Long.class);
        long totalElements = total != null ? total : 0;
        int totalPages = (int) Math.ceil((double) totalElements / safeSize);

        if (totalElements == 0 || effectiveStart.isAfter(effectiveEnd)) {
            return new MeasurementPageResponse(List.of(), safePage, safeSize, totalElements, totalPages, false);
        }

        String dataSql = """
                SELECT m.id, m.student_id, m.metric_definition_id, md.code AS metric_code, md.display_name AS metric_display_name,
                       m.numeric_value, m.text_value, m.boolean_value, m.unit_id, mu.code AS unit_code, mu.symbol AS unit_symbol,
                       m.measured_at, ms.code AS source_code, ms.name AS source_name, ms.source_kind,
                       mm.code AS method_code, mm.name AS method_name, m.entered_by, m.confidence_score, m.quality_score,
                       m.validation_status, m.superseded_by_id, m.notes, m.created_at
                FROM fitness.measurements m
                JOIN fitness.metric_definitions md ON md.id = m.metric_definition_id
                LEFT JOIN fitness.measurement_units mu ON mu.id = m.unit_id
                LEFT JOIN fitness.measurement_sources ms ON ms.id = m.source_id
                LEFT JOIN fitness.measurement_methods mm ON mm.id = m.method_id
                WHERE m.student_id = :studentId AND m.erased_at IS NULL
                  AND m.measured_at >= :startTime AND m.measured_at < :endTime
                """ + metricFilter
                + "\nORDER BY m.measured_at " + sortOrder + ", m.created_at " + sortOrder
                + "\nOFFSET :offset LIMIT :limit\n";

        List<MeasurementResponse> content = jdbc.query(dataSql, params, (rs, rowNum) -> new MeasurementResponse(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("student_id"),
                rs.getInt("metric_definition_id"),
                rs.getString("metric_code"),
                rs.getString("metric_display_name"),
                rs.getBigDecimal("numeric_value"),
                rs.getString("text_value"),
                (Boolean) rs.getObject("boolean_value"),
                parseNullableShort(rs.getObject("unit_id")),
                rs.getString("unit_code"),
                rs.getString("unit_symbol"),
                rs.getTimestamp("measured_at").toInstant(),
                rs.getString("source_code"),
                rs.getString("source_name"),
                rs.getString("source_kind"),
                rs.getString("method_code"),
                rs.getString("method_name"),
                (UUID) rs.getObject("entered_by"),
                rs.getBigDecimal("confidence_score"),
                rs.getBigDecimal("quality_score"),
                rs.getString("validation_status"),
                (UUID) rs.getObject("superseded_by_id"),
                rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant()
        ));

        boolean hasMore = (offset + content.size()) < totalElements;
        return new MeasurementPageResponse(content, safePage, safeSize, totalElements, totalPages, hasMore);
    }

    public MeasurementResponse getMeasurementById(UUID actorId, UUID id) {
        if (actorId == null || id == null) {
            throw new MeasurementFailure(MeasurementError.VALIDATION_FAILED, "Actor ID and Measurement ID must not be null");
        }
        MeasurementResponse response = fetchMeasurementById(id);
        var decision = accessPolicy.evaluateAccess(actorId, response.studentId(), com.fitnesscoaching.platform.modules.coaching.domain.DataScope.BODY_METRICS);
        if (decision.historyFrom() != null && response.measuredAt().isBefore(decision.historyFrom())) {
            throw new MeasurementFailure(MeasurementError.MEASUREMENT_ACCESS_DENIED, "Measurement is before authorized history window");
        }
        if (decision.historyUntil() != null && response.measuredAt().isAfter(decision.historyUntil())) {
            throw new MeasurementFailure(MeasurementError.MEASUREMENT_ACCESS_DENIED, "Measurement is after authorized history window");
        }
        return response;
    }

    public MeasurementResponse fetchMeasurementById(UUID id) {
        String sql = """
                SELECT m.id, m.student_id, m.metric_definition_id, md.code AS metric_code, md.display_name AS metric_display_name,
                       m.numeric_value, m.text_value, m.boolean_value, m.unit_id, mu.code AS unit_code, mu.symbol AS unit_symbol,
                       m.measured_at, ms.code AS source_code, ms.name AS source_name, ms.source_kind,
                       mm.code AS method_code, mm.name AS method_name, m.entered_by, m.confidence_score, m.quality_score,
                       m.validation_status, m.superseded_by_id, m.notes, m.created_at
                FROM fitness.measurements m
                JOIN fitness.metric_definitions md ON md.id = m.metric_definition_id
                LEFT JOIN fitness.measurement_units mu ON mu.id = m.unit_id
                LEFT JOIN fitness.measurement_sources ms ON ms.id = m.source_id
                LEFT JOIN fitness.measurement_methods mm ON mm.id = m.method_id
                WHERE m.id = :id AND m.erased_at IS NULL
                """;

        var list = jdbc.query(sql, Map.of("id", id), (rs, rowNum) -> new MeasurementResponse(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("student_id"),
                rs.getInt("metric_definition_id"),
                rs.getString("metric_code"),
                rs.getString("metric_display_name"),
                rs.getBigDecimal("numeric_value"),
                rs.getString("text_value"),
                (Boolean) rs.getObject("boolean_value"),
                parseNullableShort(rs.getObject("unit_id")),
                rs.getString("unit_code"),
                rs.getString("unit_symbol"),
                rs.getTimestamp("measured_at").toInstant(),
                rs.getString("source_code"),
                rs.getString("source_name"),
                rs.getString("source_kind"),
                rs.getString("method_code"),
                rs.getString("method_name"),
                (UUID) rs.getObject("entered_by"),
                rs.getBigDecimal("confidence_score"),
                rs.getBigDecimal("quality_score"),
                rs.getString("validation_status"),
                (UUID) rs.getObject("superseded_by_id"),
                rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant()
        ));

        if (list.isEmpty()) {
            throw new MeasurementFailure(MeasurementError.MEASUREMENT_NOT_FOUND, "Measurement not found: " + id);
        }
        return list.get(0);
    }

    private Short parseNullableShort(Object obj) {
        if (obj instanceof Number num) {
            return num.shortValue();
        }
        return null;
    }
}
