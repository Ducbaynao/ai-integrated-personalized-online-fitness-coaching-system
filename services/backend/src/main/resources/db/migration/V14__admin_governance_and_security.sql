SET search_path TO fitness, public;

CREATE TYPE trainer_verification_state AS ENUM ('NOT_SUBMITTED','PENDING','VERIFIED','REJECTED','SUSPENDED');
CREATE TYPE trainer_activity_status AS ENUM ('ACTIVE','INACTIVE','SUSPENDED');
CREATE TYPE privileged_access_status AS ENUM ('REQUESTED','APPROVED','ACTIVE','REJECTED','EXPIRED','REVOKED');
CREATE TYPE step_up_status AS ENUM ('PENDING','VERIFIED','FAILED','EXPIRED','CONSUMED');

ALTER TABLE trainer_profiles ALTER COLUMN verification_status DROP DEFAULT;
ALTER TABLE trainer_profiles
    ALTER COLUMN verification_status TYPE trainer_verification_state
    USING (CASE verification_status::text
        WHEN 'APPROVED' THEN 'VERIFIED'
        WHEN 'REVOKED' THEN 'SUSPENDED'
        WHEN 'EXPIRED' THEN 'SUSPENDED'
        ELSE verification_status::text
    END)::trainer_verification_state;
ALTER TABLE trainer_profiles ALTER COLUMN verification_status SET DEFAULT 'NOT_SUBMITTED';
ALTER TABLE trainer_profiles
    ADD COLUMN activity_status trainer_activity_status NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE trainer_verification_requests RENAME TO trainer_applications;
ALTER TABLE trainer_verification_documents RENAME COLUMN verification_request_id TO trainer_application_id;
ALTER TABLE trainer_applications
    ADD COLUMN application_number varchar(40),
    ADD COLUMN requested_information text;
CREATE UNIQUE INDEX uq_trainer_application_number ON trainer_applications(application_number) WHERE application_number IS NOT NULL;

CREATE TABLE user_account_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    from_status account_status,
    to_status account_status NOT NULL,
    changed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    reason text NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE trainer_application_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_application_id uuid NOT NULL REFERENCES trainer_applications(id) ON DELETE CASCADE,
    from_status verification_status,
    to_status verification_status NOT NULL,
    changed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    reason text,
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE trainer_activity_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE CASCADE,
    from_status trainer_activity_status,
    to_status trainer_activity_status NOT NULL,
    changed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    reason text NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE moderation_cases (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id uuid REFERENCES user_reports(id) ON DELETE SET NULL,
    subject_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    priority severity_level NOT NULL DEFAULT 'MEDIUM',
    status varchar(25) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_REVIEW','RESOLVED','CLOSED')),
    assigned_to uuid REFERENCES users(id) ON DELETE SET NULL,
    opened_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    resolution text,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE moderation_actions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    moderation_case_id uuid NOT NULL REFERENCES moderation_cases(id) ON DELETE CASCADE,
    action_type varchar(40) NOT NULL CHECK (action_type IN ('WARNING','CONTENT_RESTRICTION','ACCOUNT_RESTRICTION','SUSPENSION','ESCALATION','NO_ACTION')),
    target_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    performed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    reason text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE support_cases (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_number varchar(40) NOT NULL UNIQUE,
    requester_id uuid REFERENCES users(id) ON DELETE SET NULL,
    subject varchar(200) NOT NULL,
    description text NOT NULL,
    priority severity_level NOT NULL DEFAULT 'MEDIUM',
    status varchar(25) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','WAITING_USER','RESOLVED','CLOSED')),
    assigned_to uuid REFERENCES users(id) ON DELETE SET NULL,
    opened_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE support_case_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    support_case_id uuid NOT NULL REFERENCES support_cases(id) ON DELETE CASCADE,
    event_type varchar(40) NOT NULL,
    actor_id uuid REFERENCES users(id) ON DELETE SET NULL,
    note text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE privileged_data_access_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    requested_by_admin_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    approved_by_admin_id uuid REFERENCES users(id) ON DELETE SET NULL,
    target_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    target_resource_type varchar(80),
    target_resource_id uuid,
    purpose varchar(40) NOT NULL CHECK (purpose IN ('SUPPORT','MODERATION','SECURITY','LEGAL_COMPLIANCE','DATA_CORRECTION')),
    access_scope jsonb NOT NULL,
    reason text NOT NULL,
    related_support_case_id uuid REFERENCES support_cases(id) ON DELETE SET NULL,
    related_moderation_case_id uuid REFERENCES moderation_cases(id) ON DELETE SET NULL,
    status privileged_access_status NOT NULL DEFAULT 'REQUESTED',
    requested_at timestamptz NOT NULL DEFAULT now(),
    approved_at timestamptz,
    opened_at timestamptz,
    expires_at timestamptz,
    revoked_at timestamptz,
    CHECK (target_user_id IS NOT NULL OR (target_resource_type IS NOT NULL AND target_resource_id IS NOT NULL)),
    CHECK (expires_at IS NULL OR opened_at IS NULL OR expires_at > opened_at)
);
CREATE TABLE privileged_data_access_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    privileged_access_request_id uuid NOT NULL REFERENCES privileged_data_access_requests(id) ON DELETE CASCADE,
    admin_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    event_type varchar(40) NOT NULL CHECK (event_type IN ('REQUESTED','APPROVED','REJECTED','OPENED','RESOURCE_ACCESSED','EXPIRED','REVOKED')),
    resource_type varchar(80),
    resource_id uuid,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE data_correction_actions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    support_case_id uuid NOT NULL REFERENCES support_cases(id) ON DELETE RESTRICT,
    target_type varchar(80) NOT NULL,
    target_id uuid NOT NULL,
    before_data jsonb NOT NULL,
    proposed_data jsonb NOT NULL,
    applied_data jsonb,
    validation_result jsonb NOT NULL DEFAULT '{}'::jsonb,
    requested_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    applied_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    applied_at timestamptz
);
CREATE TABLE feature_flags (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(100) NOT NULL UNIQUE,
    description text,
    enabled boolean NOT NULL DEFAULT false,
    criticality severity_level NOT NULL DEFAULT 'LOW',
    updated_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE feature_flag_rollouts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_flag_id uuid NOT NULL REFERENCES feature_flags(id) ON DELETE CASCADE,
    rollout_type varchar(30) NOT NULL CHECK (rollout_type IN ('PERCENTAGE','USER_ALLOWLIST','ROLE','ORGANIZATION')),
    configuration jsonb NOT NULL,
    starts_at timestamptz,
    ends_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);
CREATE TABLE background_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type varchar(100) NOT NULL,
    requested_by uuid REFERENCES users(id) ON DELETE SET NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE TABLE job_executions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    background_job_id uuid NOT NULL REFERENCES background_jobs(id) ON DELETE CASCADE,
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    status varchar(20) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED')),
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    error_code varchar(100),
    error_message text,
    UNIQUE (background_job_id, attempt_number)
);
CREATE TABLE integration_status_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    integration_provider_id smallint REFERENCES integration_providers(id) ON DELETE SET NULL,
    integration_connection_id uuid REFERENCES integration_connections(id) ON DELETE SET NULL,
    status varchar(20) NOT NULL CHECK (status IN ('HEALTHY','DEGRADED','OUTAGE','RECOVERED')),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    observed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE mfa_factors (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    factor_type varchar(20) NOT NULL CHECK (factor_type IN ('TOTP','PASSKEY','SMS','EMAIL')),
    label varchar(100),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING','ACTIVE','REVOKED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz
);
CREATE TABLE step_up_authentications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mfa_factor_id uuid REFERENCES mfa_factors(id) ON DELETE SET NULL,
    action_code varchar(100) NOT NULL,
    request_id uuid,
    status step_up_status NOT NULL DEFAULT 'PENDING',
    initiated_at timestamptz NOT NULL DEFAULT now(),
    verified_at timestamptz,
    expires_at timestamptz NOT NULL,
    ip_address inet,
    user_agent text,
    CHECK (expires_at > initiated_at)
);
CREATE TABLE security_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    event_type varchar(100) NOT NULL,
    severity severity_level NOT NULL DEFAULT 'INFO',
    ip_address inet,
    user_agent text,
    device_id uuid REFERENCES user_devices(id) ON DELETE SET NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER user_account_status_history_append_only BEFORE UPDATE OR DELETE ON user_account_status_history FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER trainer_application_history_append_only BEFORE UPDATE OR DELETE ON trainer_application_status_history FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER trainer_activity_history_append_only BEFORE UPDATE OR DELETE ON trainer_activity_status_history FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER moderation_actions_append_only BEFORE UPDATE OR DELETE ON moderation_actions FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER support_case_events_append_only BEFORE UPDATE OR DELETE ON support_case_events FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER privileged_access_events_append_only BEFORE UPDATE OR DELETE ON privileged_data_access_events FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER security_events_append_only BEFORE UPDATE OR DELETE ON security_events FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER trg_moderation_cases_updated_at BEFORE UPDATE ON moderation_cases FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_support_cases_updated_at BEFORE UPDATE ON support_cases FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_feature_flags_updated_at BEFORE UPDATE ON feature_flags FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_account_status_history_user_time ON user_account_status_history(user_id, changed_at DESC);
CREATE INDEX idx_trainer_applications_status_time ON trainer_applications(status, submitted_at DESC);
CREATE INDEX idx_moderation_cases_status_priority ON moderation_cases(status, priority, opened_at DESC);
CREATE INDEX idx_support_cases_status_priority ON support_cases(status, priority, opened_at DESC);
CREATE INDEX idx_privileged_access_active ON privileged_data_access_requests(target_user_id, expires_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_job_executions_status_time ON job_executions(status, started_at DESC);
CREATE INDEX idx_step_up_user_status_expiry ON step_up_authentications(user_id, status, expires_at DESC);
