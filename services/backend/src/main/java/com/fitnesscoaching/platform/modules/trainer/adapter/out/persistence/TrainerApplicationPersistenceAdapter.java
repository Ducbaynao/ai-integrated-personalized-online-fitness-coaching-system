package com.fitnesscoaching.platform.modules.trainer.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerApplicationPort;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class TrainerApplicationPersistenceAdapter implements TrainerApplicationPort {

    private final JdbcTemplate jdbcTemplate;

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
                applicantNote,
                Collections.emptyList(),
                Collections.emptyList(),
                createdAt,
                updatedAt
        );
    };

    public TrainerApplicationPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
                       rejection_reason, applicant_note, created_at, updated_at
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

        TrainerApplication app = list.get(0);
        List<UUID> certIds = loadCertificateIds(app.id());
        List<UUID> mediaIds = loadDocumentMediaIds(app.id());

        return Optional.of(new TrainerApplication(
                app.id(),
                app.trainerId(),
                app.status(),
                app.submittedAt(),
                app.reviewedAt(),
                app.reviewedBy(),
                app.rejectionReason(),
                app.applicantNote(),
                certIds,
                mediaIds,
                app.createdAt(),
                app.updatedAt()
        ));
    }

    @Override
    public TrainerApplication save(TrainerApplication application) {
        String insertAppSql = """
                INSERT INTO fitness.trainer_applications (
                    id, trainer_id, status, submitted_at, reviewed_at, reviewed_by,
                    rejection_reason, applicant_note, created_at, updated_at
                ) VALUES (
                    ?, ?, ?::fitness.trainer_verification_state, ?, ?, ?,
                    ?, ?, ?, ?
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

    private List<UUID> loadCertificateIds(UUID applicationId) {
        String sql = "SELECT certificate_id FROM fitness.trainer_application_certificates WHERE trainer_application_id = ? ORDER BY certificate_id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> (UUID) rs.getObject("certificate_id"), applicationId);
    }

    private List<UUID> loadDocumentMediaIds(UUID applicationId) {
        String sql = "SELECT media_id FROM fitness.trainer_verification_documents WHERE trainer_application_id = ? ORDER BY media_id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> (UUID) rs.getObject("media_id"), applicationId);
    }
}
