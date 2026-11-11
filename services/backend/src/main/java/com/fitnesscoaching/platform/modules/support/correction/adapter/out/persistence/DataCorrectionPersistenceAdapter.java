package com.fitnesscoaching.platform.modules.support.correction.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.support.correction.application.model.DataCorrectionModel;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DataCorrectionPersistenceAdapter {

    private final NamedParameterJdbcTemplate jdbc;

    public DataCorrectionPersistenceAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DataCorrectionModel> searchCorrections(UUID supportCaseId, String targetType, int limit, long offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT dca.id, dca.support_case_id, sc.case_number,
                       dca.target_type, dca.target_id,
                       dca.before_data::text AS before_data_json,
                       dca.proposed_data::text AS proposed_data_json,
                       dca.applied_data::text AS applied_data_json,
                       dca.validation_result::text AS validation_result_json,
                       dca.requested_by, rb.display_name AS requested_by_name,
                       dca.applied_by, ab.display_name AS applied_by_name,
                       dca.created_at, dca.applied_at
                FROM fitness.data_correction_actions dca
                JOIN fitness.support_cases sc ON sc.id = dca.support_case_id
                LEFT JOIN fitness.users rb ON rb.id = dca.requested_by
                LEFT JOIN fitness.users ab ON ab.id = dca.applied_by
                WHERE 1=1
                """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        if (supportCaseId != null) {
            sql.append(" AND dca.support_case_id = :supportCaseId");
            params.addValue("supportCaseId", supportCaseId);
        }
        if (targetType != null && !targetType.isBlank()) {
            sql.append(" AND dca.target_type = :targetType");
            params.addValue("targetType", targetType.trim().toUpperCase());
        }

        sql.append(" ORDER BY dca.created_at DESC, dca.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit);
        params.addValue("offset", offset);

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapRow(rs));
    }

    public long countCorrections(UUID supportCaseId, String targetType) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(1)
                FROM fitness.data_correction_actions dca
                WHERE 1=1
                """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        if (supportCaseId != null) {
            sql.append(" AND dca.support_case_id = :supportCaseId");
            params.addValue("supportCaseId", supportCaseId);
        }
        if (targetType != null && !targetType.isBlank()) {
            sql.append(" AND dca.target_type = :targetType");
            params.addValue("targetType", targetType.trim().toUpperCase());
        }

        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    public Optional<DataCorrectionModel> findById(UUID id) {
        String sql = """
                SELECT dca.id, dca.support_case_id, sc.case_number,
                       dca.target_type, dca.target_id,
                       dca.before_data::text AS before_data_json,
                       dca.proposed_data::text AS proposed_data_json,
                       dca.applied_data::text AS applied_data_json,
                       dca.validation_result::text AS validation_result_json,
                       dca.requested_by, rb.display_name AS requested_by_name,
                       dca.applied_by, ab.display_name AS applied_by_name,
                       dca.created_at, dca.applied_at
                FROM fitness.data_correction_actions dca
                JOIN fitness.support_cases sc ON sc.id = dca.support_case_id
                LEFT JOIN fitness.users rb ON rb.id = dca.requested_by
                LEFT JOIN fitness.users ab ON ab.id = dca.applied_by
                WHERE dca.id = :id
                """;
        List<DataCorrectionModel> list = jdbc.query(sql, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapRow(rs));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void insertCorrectionAction(UUID id, UUID supportCaseId, String targetType, UUID targetId,
                                       String beforeDataJson, String proposedDataJson, String appliedDataJson,
                                       String validationResultJson, UUID requestedBy, UUID appliedBy,
                                       Instant createdAt, Instant appliedAt) {
        String sql = """
                INSERT INTO fitness.data_correction_actions (
                    id, support_case_id, target_type, target_id, before_data,
                    proposed_data, applied_data, validation_result, requested_by,
                    applied_by, created_at, applied_at
                ) VALUES (
                    :id, :supportCaseId, :targetType, :targetId, coalesce(:beforeData::jsonb, '{}'::jsonb),
                    coalesce(:proposedData::jsonb, '{}'::jsonb), coalesce(:appliedData::jsonb, '{}'::jsonb),
                    coalesce(:validationResult::jsonb, '{}'::jsonb), :requestedBy,
                    :appliedBy, :createdAt, :appliedAt
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("supportCaseId", supportCaseId)
                .addValue("targetType", targetType)
                .addValue("targetId", targetId)
                .addValue("beforeData", beforeDataJson)
                .addValue("proposedData", proposedDataJson)
                .addValue("appliedData", appliedDataJson)
                .addValue("validationResult", validationResultJson)
                .addValue("requestedBy", requestedBy)
                .addValue("appliedBy", appliedBy)
                .addValue("createdAt", Timestamp.from(createdAt))
                .addValue("appliedAt", appliedAt != null ? Timestamp.from(appliedAt) : null)
        );
    }

    public void insertAdminAction(UUID id, UUID adminUserId, String actionType, String targetType, UUID targetId,
                                  String reason, String permissionCode, UUID stepUpAuthId,
                                  UUID privilegedAccessRequestId, UUID supportCaseId, Instant completedAt) {
        String sql = """
                INSERT INTO fitness.admin_actions (
                    id, admin_user_id, action_type, target_type, target_id,
                    reason, metadata, permission_id, step_up_authentication_id,
                    privileged_access_request_id, related_support_case_id, completed_at, created_at
                ) VALUES (
                    :id, :adminUserId, :actionType, :targetType, :targetId,
                    :reason, '{}'::jsonb,
                    (SELECT id FROM fitness.permissions WHERE code = :permissionCode),
                    :stepUpAuthId, :privilegedAccessRequestId, :supportCaseId, :completedAt, :completedAt
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("adminUserId", adminUserId)
                .addValue("actionType", actionType)
                .addValue("targetType", targetType)
                .addValue("targetId", targetId)
                .addValue("reason", reason)
                .addValue("permissionCode", permissionCode)
                .addValue("stepUpAuthId", stepUpAuthId)
                .addValue("privilegedAccessRequestId", privilegedAccessRequestId)
                .addValue("supportCaseId", supportCaseId)
                .addValue("completedAt", Timestamp.from(completedAt))
        );
    }

    public Optional<Map<String, Object>> findMeasurementById(UUID measurementId) {
        String sql = """
                SELECT id, student_id, metric_definition_id, numeric_value, unit_id,
                       measured_at, validation_status, superseded_by_id, source_id, method_id, notes
                FROM fitness.measurements
                WHERE id = :id
                """;
        List<Map<String, Object>> list = jdbc.queryForList(sql, new MapSqlParameterSource("id", measurementId));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<Short> findUnitIdByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String sql = """
                SELECT id FROM fitness.measurement_units
                WHERE UPPER(code) = UPPER(:code) OR UPPER(symbol) = UPPER(:code)
                LIMIT 1
                """;
        List<Short> list = jdbc.query(sql, new MapSqlParameterSource("code", code.trim()), (rs, rowNum) -> rs.getShort("id"));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public int markMeasurementSuperseded(UUID oldId, UUID newId) {
        String sql = """
                UPDATE fitness.measurements
                SET superseded_by_id = :newId,
                    validation_status = 'SUPERSEDED'::fitness.measurement_validation_status
                WHERE id = :oldId AND superseded_by_id IS NULL
                """;
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("oldId", oldId)
                .addValue("newId", newId)
        );
    }

    public void insertCorrectedMeasurement(UUID newId, UUID studentId, int metricDefId, double value,
                                          Short unitId, Short sourceId, Instant measuredAt, String notes, Instant now) {
        String sql = """
                INSERT INTO fitness.measurements (
                    id, student_id, metric_definition_id, numeric_value, unit_id, measured_at,
                    source_id, notes, validation_status, created_at
                ) VALUES (
                    :id, :studentId, :metricDefId, :value, :unitId, :measuredAt,
                    :sourceId, :notes, 'VALID'::fitness.measurement_validation_status, :now
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", newId)
                .addValue("studentId", studentId)
                .addValue("metricDefId", metricDefId)
                .addValue("value", value)
                .addValue("unitId", unitId)
                .addValue("sourceId", sourceId != null ? sourceId : (short) 1)
                .addValue("measuredAt", Timestamp.from(measuredAt))
                .addValue("notes", notes)
                .addValue("now", Timestamp.from(now))
        );
    }

    public Optional<Map<String, Object>> findUserById(UUID userId) {
        String sql = """
                SELECT id, email, display_name, phone_number, timezone
                FROM fitness.users
                WHERE id = :id
                """;
        List<Map<String, Object>> list = jdbc.queryForList(sql, new MapSqlParameterSource("id", userId));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void updateUserProfile(UUID userId, String displayName, String phoneNumber, Instant now) {
        String sql = """
                UPDATE fitness.users
                SET display_name = :displayName,
                    phone_number = :phoneNumber,
                    updated_at = :now
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", userId)
                .addValue("displayName", displayName)
                .addValue("phoneNumber", phoneNumber)
                .addValue("now", Timestamp.from(now))
        );
    }

    private DataCorrectionModel mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DataCorrectionModel(
                rs.getObject("id", UUID.class),
                rs.getObject("support_case_id", UUID.class),
                rs.getString("case_number"),
                rs.getString("target_type"),
                rs.getObject("target_id", UUID.class),
                rs.getString("before_data_json"),
                rs.getString("proposed_data_json"),
                rs.getString("applied_data_json"),
                rs.getString("validation_result_json"),
                rs.getObject("requested_by", UUID.class),
                rs.getString("requested_by_name"),
                rs.getObject("applied_by", UUID.class),
                rs.getString("applied_by_name"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("applied_at") != null ? rs.getTimestamp("applied_at").toInstant() : null
        );
    }
}
