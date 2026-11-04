package com.fitnesscoaching.platform.modules.progress;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProgressAccountErasureService implements ProgressAccountErasure {
    private final JdbcTemplate jdbc;

    public ProgressAccountErasureService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void minimizeOwnedConnectionIdentifiers(UUID userId) {
        // The non-null connection FK and connection.user_id prove ownership of
        // these raw records. Orphaned batches remain unresolved; do not infer
        // an owner from their source or payload.
        jdbc.update("""
                UPDATE fitness.raw_ingestion_records r
                SET payload='{}'::jsonb,external_record_id=NULL,error_message=NULL
                FROM fitness.ingestion_batches b
                JOIN fitness.integration_connections c ON c.id=b.connection_id
                WHERE r.batch_id=b.id AND c.user_id=?
                  AND (r.payload<>'{}'::jsonb OR r.external_record_id IS NOT NULL
                       OR r.error_message IS NOT NULL)
                """, userId);
        // The stable batch/hash chronology remains for owner review; the hash
        // is not an anonymity claim or a completed Progress attestation.
        jdbc.update("""
                UPDATE fitness.integration_connections
                SET external_account_id=NULL,encrypted_credentials_ref=NULL,
                    granted_scopes=NULL,status='REVOKED',
                    revoked_at=COALESCE(revoked_at,clock_timestamp()),updated_at=clock_timestamp()
                WHERE user_id=? AND (external_account_id IS NOT NULL
                    OR encrypted_credentials_ref IS NOT NULL OR granted_scopes IS NOT NULL
                    OR status<>'REVOKED' OR revoked_at IS NULL)
                """, userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void tombstoneStudentMeasurements(UUID userId, UUID requestId) {
        // student_id proves the health-data subject even if a Trainer entered
        // the observation. Keep the row/FK skeleton, never a fabricated zero.
        jdbc.update("""
                UPDATE fitness.measurements SET
                    numeric_value=NULL,text_value=NULL,boolean_value=NULL,unit_id=NULL,
                    notes=NULL,external_record_id=NULL,raw_ingestion_record_id=NULL,
                    confidence_score=NULL,quality_score=NULL,
                    erased_at=clock_timestamp(),erasure_request_id=?
                WHERE student_id=? AND erased_at IS NULL
                """, requestId, userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void minimizeSubjectScopedPhotoAndBatchValues(UUID userId) {
        // The Student FK proves whose health value/device reference this is.
        // Notes may have been written by someone else; media can have multiple
        // consumers. Neither is erased or attested by this narrow port.
        jdbc.update("""
                UPDATE fitness.progress_photos SET weight_at_time=NULL,weight_unit_id=NULL
                WHERE student_id=? AND (weight_at_time IS NOT NULL OR weight_unit_id IS NOT NULL)
                """, userId);
        jdbc.update("""
                UPDATE fitness.measurement_batches SET device_identifier=NULL
                WHERE student_id=? AND device_identifier IS NOT NULL
                """, userId);
    }
}
