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
        // user_id is an explicit owner here. Orphaned ingestion batches/raw payloads
        // are deliberately excluded until their provenance is reconciled.
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
}
