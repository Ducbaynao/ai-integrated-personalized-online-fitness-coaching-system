SET search_path TO fitness, public;

-- 1. Alter trainer_applications.status from verification_status to trainer_verification_state
ALTER TABLE trainer_applications ALTER COLUMN status DROP DEFAULT;
ALTER TABLE trainer_applications
    ALTER COLUMN status TYPE trainer_verification_state
    USING (CASE status::text
        WHEN 'NOT_SUBMITTED' THEN 'NOT_SUBMITTED'
        WHEN 'PENDING' THEN 'PENDING'
        WHEN 'APPROVED' THEN 'VERIFIED'
        WHEN 'REJECTED' THEN 'REJECTED'
        WHEN 'EXPIRED' THEN 'SUSPENDED'
        WHEN 'REVOKED' THEN 'SUSPENDED'
        ELSE status::text
    END)::trainer_verification_state;
ALTER TABLE trainer_applications ALTER COLUMN status SET DEFAULT 'PENDING'::trainer_verification_state;

-- 2. Alter trainer_application_status_history.from_status and to_status
ALTER TABLE trainer_application_status_history
    ALTER COLUMN from_status TYPE trainer_verification_state
    USING (CASE from_status::text
        WHEN 'NOT_SUBMITTED' THEN 'NOT_SUBMITTED'
        WHEN 'PENDING' THEN 'PENDING'
        WHEN 'APPROVED' THEN 'VERIFIED'
        WHEN 'REJECTED' THEN 'REJECTED'
        WHEN 'EXPIRED' THEN 'SUSPENDED'
        WHEN 'REVOKED' THEN 'SUSPENDED'
        ELSE from_status::text
    END)::trainer_verification_state;

ALTER TABLE trainer_application_status_history
    ALTER COLUMN to_status TYPE trainer_verification_state
    USING (CASE to_status::text
        WHEN 'NOT_SUBMITTED' THEN 'NOT_SUBMITTED'
        WHEN 'PENDING' THEN 'PENDING'
        WHEN 'APPROVED' THEN 'VERIFIED'
        WHEN 'REJECTED' THEN 'REJECTED'
        WHEN 'EXPIRED' THEN 'SUSPENDED'
        WHEN 'REVOKED' THEN 'SUSPENDED'
        ELSE to_status::text
    END)::trainer_verification_state;

-- 3. Add applicant_note column to trainer_applications
ALTER TABLE trainer_applications
    ADD COLUMN applicant_note varchar(2000);

-- 4. Create trainer_application_certificates junction table
CREATE TABLE trainer_application_certificates (
    trainer_application_id uuid NOT NULL REFERENCES trainer_applications(id) ON DELETE CASCADE,
    certificate_id uuid NOT NULL REFERENCES trainer_certificates(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (trainer_application_id, certificate_id)
);

-- 5. Default for trainer_verification_documents.document_type
ALTER TABLE trainer_verification_documents
    ALTER COLUMN document_type SET DEFAULT 'VERIFICATION_DOCUMENT';

-- 6. Partial unique index to enforce at most one PENDING application per trainer
CREATE UNIQUE INDEX uq_trainer_pending_application ON trainer_applications(trainer_id) WHERE status = 'PENDING';

-- 7. Query index for fast, deterministic retrieval of trainer applications
CREATE INDEX idx_trainer_applications_trainer_time ON trainer_applications(trainer_id, submitted_at DESC, id DESC);
