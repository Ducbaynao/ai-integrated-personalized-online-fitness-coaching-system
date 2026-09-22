package com.fitnesscoaching.platform.modules.trainer.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerApplicationPort;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class TrainerApplicationPersistenceAdapter implements TrainerApplicationPort {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    private final RowMapper<TrainerApplication> applicationRowMapper = (rs, rowNum) -> {
        UUID id = (UUID) rs.getObject("id");
        UUID trainerId = (UUID) rs.getObject("trainer_id");
        String statusStr = rs.getString("status");
        TrainerVerificationStatus status = statusStr != null
                ? TrainerVerificationStatus.valueOf(statusStr)
                : TrainerVerificationStatus.PENDING;

        Timestamp submittedTs = rs.getTimestamp("submitted_at");
        Instant submittedAt = submittedTs != null ? submittedTs.toInstant() : null;

        Timestamp reviewedTs = rs.getTimestamp("reviewed_at");
        Instant reviewedAt = reviewedTs != null ? reviewedTs.toInstant() : null;

        UUID reviewedBy = (UUID) rs.getObject("reviewed_by");
        String rejectionReason = rs.getString("rejection_reason");
        String reviewNotes = rs.getString("review_notes");
        String applicantNote = rs.getString("applicant_note");

        Timestamp createdTs = rs.getTimestamp("created_at");
        Instant createdAt = createdTs != null ? createdTs.toInstant() : null;

        Timestamp updatedTs = rs.getTimestamp("updated_at");
        Instant updatedAt = updatedTs != null ? updatedTs.toInstant() : null;

        return new TrainerApplication(
                id,
                trainerId,
                status,
                submittedAt,
                reviewedAt,
                reviewedBy,
                rejectionReason,
                reviewNotes,
                applicantNote,
                Collections.emptyList(),
                Collections.emptyList(),
                createdAt,
                updatedAt
        );
    };

    public TrainerApplicationPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    @Override
    public boolean hasActiveApplication(UUID trainerId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM fitness.trainer_applications WHERE trainer_id = ? AND status = 'PENDING'::fitness.trainer_verification_state)",
                Boolean.class,
                trainerId
        );
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<TrainerApplication> findCurrentByTrainerId(UUID trainerId) {
        String sql = """
                SELECT id, trainer_id, status, submitted_at, reviewed_at, reviewed_by,
                       rejection_reason, review_notes, applicant_note, created_at, updated_at
                FROM fitness.trainer_applications
                WHERE trainer_id = ?
                ORDER BY CASE WHEN status = 'PENDING'::fitness.trainer_verification_state THEN 0 ELSE 1 END,
                         submitted_at DESC,
                         id DESC
                LIMIT 1
                """;

        List<TrainerApplication> list = jdbcTemplate.query(sql, applicationRowMapper, trainerId);
        if (list.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(populateAttachments(list.get(0)));
    }

    @Override
    public Optional<TrainerApplication> findById(UUID id) {
        String sql = """
                SELECT id, trainer_id, status, submitted_at, reviewed_at, reviewed_by,
                       rejection_reason, review_notes, applicant_note, created_at, updated_at
                FROM fitness.trainer_applications
                WHERE id = ?
                """;
        List<TrainerApplication> list = jdbcTemplate.query(sql, applicationRowMapper, id);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(populateAttachments(list.get(0)));
    }

    @Override
    public Optional<TrainerApplication> findByIdForUpdate(UUID id) {
        String sql = """
                SELECT id, trainer_id, status, submitted_at, reviewed_at, reviewed_by,
                       rejection_reason, review_notes, applicant_note, created_at, updated_at
                FROM fitness.trainer_applications
                WHERE id = ?
                FOR UPDATE
                """;
        List<TrainerApplication> list = jdbcTemplate.query(sql, applicationRowMapper, id);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(populateAttachments(list.get(0)));
    }

    @Override
    public List<TrainerApplication> findApplications(TrainerVerificationStatus status, long offset, int limit) {
        String sql;
        List<TrainerApplication> apps;
        if (status != null) {
            sql = """
                    SELECT id, trainer_id, status, submitted_at, reviewed_at, reviewed_by,
                           rejection_reason, review_notes, applicant_note, created_at, updated_at
                    FROM fitness.trainer_applications
                    WHERE status = ?::fitness.trainer_verification_state
                    ORDER BY submitted_at ASC, id ASC
                    LIMIT ? OFFSET ?
                    """;
            apps = jdbcTemplate.query(sql, applicationRowMapper, status.name(), limit, offset);
        } else {
            sql = """
                    SELECT id, trainer_id, status, submitted_at, reviewed_at, reviewed_by,
                           rejection_reason, review_notes, applicant_note, created_at, updated_at
                    FROM fitness.trainer_applications
                    ORDER BY submitted_at ASC, id ASC
                    LIMIT ? OFFSET ?
                    """;
            apps = jdbcTemplate.query(sql, applicationRowMapper, limit, offset);
        }

        if (apps.isEmpty()) {
            return List.of();
        }

        return populateAttachmentsBatch(apps);
    }

    @Override
    public long countApplications(TrainerVerificationStatus status) {
        if (status != null) {
            String sql = "SELECT count(*) FROM fitness.trainer_applications WHERE status = ?::fitness.trainer_verification_state";
            Long count = jdbcTemplate.queryForObject(sql, Long.class, status.name());
            return count != null ? count : 0L;
        } else {
            String sql = "SELECT count(*) FROM fitness.trainer_applications";
            Long count = jdbcTemplate.queryForObject(sql, Long.class);
            return count != null ? count : 0L;
        }
    }

    @Override
    public int updateDecision(
            UUID applicationId,
            TrainerVerificationStatus newStatus,
            Instant reviewedAt,
            UUID reviewedBy,
            String rejectionReason,
            String reviewNotes,
            Instant updatedAt
    ) {
        String sql = """
                UPDATE fitness.trainer_applications SET
                    status = ?::fitness.trainer_verification_state,
                    reviewed_at = ?,
                    reviewed_by = ?,
                    rejection_reason = ?,
                    review_notes = ?,
                    updated_at = ?
                WHERE id = ? AND status = 'PENDING'::fitness.trainer_verification_state
                """;
        return jdbcTemplate.update(
                sql,
                newStatus.name(),
                Timestamp.from(reviewedAt),
                reviewedBy,
                rejectionReason,
                reviewNotes,
                Timestamp.from(updatedAt),
                applicationId
        );
    }

    @Override
    public void recordStatusHistory(
            UUID applicationId,
            TrainerVerificationStatus fromStatus,
            TrainerVerificationStatus toStatus,
            UUID changedBy,
            String reason,
            Instant changedAt
    ) {
        String sql = """
                INSERT INTO fitness.trainer_application_status_history (
                    id, trainer_application_id, from_status, to_status,
                    changed_by, reason, changed_at
                ) VALUES (
                    gen_random_uuid(), ?, ?::fitness.trainer_verification_state, ?::fitness.trainer_verification_state,
                    ?, ?, ?
                )
                """;
        jdbcTemplate.update(
                sql,
                applicationId,
                fromStatus.name(),
                toStatus.name(),
                changedBy,
                reason,
                Timestamp.from(changedAt)
        );
    }

    @Override
    public TrainerApplication save(TrainerApplication application) {
        String insertAppSql = """
                INSERT INTO fitness.trainer_applications (
                    id, trainer_id, status, submitted_at, reviewed_at, reviewed_by,
                    rejection_reason, review_notes, applicant_note, created_at, updated_at
                ) VALUES (
                    ?, ?, ?::fitness.trainer_verification_state, ?, ?, ?,
                    ?, ?, ?, ?, ?
                )
                """;

        jdbcTemplate.update(
                insertAppSql,
                application.id(),
                application.trainerId(),
                application.status().name(),
                Timestamp.from(application.submittedAt()),
                application.reviewedAt() != null ? Timestamp.from(application.reviewedAt()) : null,
                application.reviewedBy(),
                application.rejectionReason(),
                application.reviewNotes(),
                application.applicantNote(),
                Timestamp.from(application.createdAt()),
                Timestamp.from(application.updatedAt())
        );

        if (!application.certificateIds().isEmpty()) {
            String insertCertSql = """
                    INSERT INTO fitness.trainer_application_certificates (
                        trainer_application_id, certificate_id, created_at
                    ) VALUES (?, ?, ?)
                    """;
            List<Object[]> certBatch = application.certificateIds().stream()
                    .map(certId -> new Object[]{application.id(), certId, Timestamp.from(application.createdAt())})
                    .toList();
            jdbcTemplate.batchUpdate(insertCertSql, certBatch);
        }

        if (!application.documentMediaIds().isEmpty()) {
            String insertDocSql = """
                    INSERT INTO fitness.trainer_verification_documents (
                        id, trainer_application_id, media_id, document_type, created_at
                    ) VALUES (gen_random_uuid(), ?, ?, 'VERIFICATION_DOCUMENT', ?)
                    """;
            List<Object[]> docBatch = application.documentMediaIds().stream()
                    .map(mediaId -> new Object[]{application.id(), mediaId, Timestamp.from(application.createdAt())})
                    .toList();
            jdbcTemplate.batchUpdate(insertDocSql, docBatch);
        }

        String insertHistorySql = """
                INSERT INTO fitness.trainer_application_status_history (
                    id, trainer_application_id, from_status, to_status,
                    changed_by, reason, changed_at
                ) VALUES (
                    gen_random_uuid(), ?, null, ?::fitness.trainer_verification_state,
                    ?, ?, ?
                )
                """;
        jdbcTemplate.update(
                insertHistorySql,
                application.id(),
                application.status().name(),
                application.trainerId(),
                "Application submitted by trainer",
                Timestamp.from(application.createdAt())
        );

        return application;
    }

    private List<TrainerApplication> populateAttachmentsBatch(List<TrainerApplication> apps) {
        if (apps.isEmpty()) {
            return List.of();
        }

        List<UUID> applicationIds = apps.stream().map(TrainerApplication::id).toList();

        String certSql = """
                SELECT trainer_application_id, certificate_id
                FROM fitness.trainer_application_certificates
                WHERE trainer_application_id IN (:applicationIds)
                ORDER BY certificate_id
                """;
        MapSqlParameterSource certParams = new MapSqlParameterSource("applicationIds", applicationIds);
        Map<UUID, List<UUID>> certsByAppId = new HashMap<>();
        namedParameterJdbcTemplate.query(certSql, certParams, rs -> {
            UUID appId = (UUID) rs.getObject("trainer_application_id");
            UUID certId = (UUID) rs.getObject("certificate_id");
            certsByAppId.computeIfAbsent(appId, k -> new ArrayList<>()).add(certId);
        });

        String docSql = """
                SELECT trainer_application_id, media_id
                FROM fitness.trainer_verification_documents
                WHERE trainer_application_id IN (:applicationIds)
                ORDER BY media_id
                """;
        MapSqlParameterSource docParams = new MapSqlParameterSource("applicationIds", applicationIds);
        Map<UUID, List<UUID>> docsByAppId = new HashMap<>();
        namedParameterJdbcTemplate.query(docSql, docParams, rs -> {
            UUID appId = (UUID) rs.getObject("trainer_application_id");
            UUID mediaId = (UUID) rs.getObject("media_id");
            docsByAppId.computeIfAbsent(appId, k -> new ArrayList<>()).add(mediaId);
        });

        return apps.stream()
                .map(app -> new TrainerApplication(
                        app.id(),
                        app.trainerId(),
                        app.status(),
                        app.submittedAt(),
                        app.reviewedAt(),
                        app.reviewedBy(),
                        app.rejectionReason(),
                        app.reviewNotes(),
                        app.applicantNote(),
                        certsByAppId.getOrDefault(app.id(), List.of()),
                        docsByAppId.getOrDefault(app.id(), List.of()),
                        app.createdAt(),
                        app.updatedAt()
                ))
                .toList();
    }

    private TrainerApplication populateAttachments(TrainerApplication app) {
        List<UUID> certIds = loadCertificateIds(app.id());
        List<UUID> mediaIds = loadDocumentMediaIds(app.id());
        return new TrainerApplication(
                app.id(),
                app.trainerId(),
                app.status(),
                app.submittedAt(),
                app.reviewedAt(),
                app.reviewedBy(),
                app.rejectionReason(),
                app.reviewNotes(),
                app.applicantNote(),
                certIds,
                mediaIds,
                app.createdAt(),
                app.updatedAt()
        );
    }

    private List<UUID> loadCertificateIds(UUID applicationId) {
        String sql = "SELECT certificate_id FROM fitness.trainer_application_certificates WHERE trainer_application_id = ? ORDER BY certificate_id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> (UUID) rs.getObject("certificate_id"), applicationId);
    }

    private List<UUID> loadDocumentMediaIds(UUID applicationId) {
        String sql = "SELECT media_id FROM fitness.trainer_verification_documents WHERE trainer_application_id = ? ORDER BY media_id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> (UUID) rs.getObject("media_id"), applicationId);
    }
}
