SET search_path TO fitness, public;

-- Separate from V10's generic admin request. No legacy request is reinterpreted.
CREATE TABLE account_erasure_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL UNIQUE REFERENCES users(id) ON DELETE RESTRICT,
    command_key uuid NOT NULL,
    status_key_hash char(64) NOT NULL UNIQUE,
    status varchar(32) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN
        ('REQUESTED','PROCESSING','RETRYABLE','HELD','DOMAIN_REVIEW_PENDING','ACTIVE_DATA_CLEANED')),
    requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    target_at timestamptz NOT NULL,
    active_cleaned_at timestamptz,
    backup_state varchar(24) NOT NULL DEFAULT 'BACKUP_UNVERIFIED'
        CHECK (backup_state = 'BACKUP_UNVERIFIED'),
    provider_state varchar(24) NOT NULL DEFAULT 'PROVIDER_UNVERIFIED'
        CHECK (provider_state = 'PROVIDER_UNVERIFIED'),
    lease_token uuid,
    lease_expires_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error_code varchar(80),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT account_erasure_claim_ck CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT account_erasure_cleaned_ck CHECK
        ((status = 'ACTIVE_DATA_CLEANED') = (active_cleaned_at IS NOT NULL))
);

-- No hold writer or permission is granted by this migration. Authority is unresolved.
-- Existing valid holds, if any, block their covered work; the worker denies by default.
CREATE TABLE account_erasure_holds (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id uuid NOT NULL REFERENCES account_erasure_requests(id) ON DELETE RESTRICT,
    case_id varchar(120) NOT NULL,
    legal_basis text NOT NULL,
    scope varchar(40) NOT NULL CHECK (scope IN ('ALL','ACCOUNT','CHAT','OBJECT','NOTIFICATION','AUDIT')),
    approved_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    placed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    next_review_at timestamptz NOT NULL,
    released_at timestamptz,
    released_by uuid REFERENCES users(id) ON DELETE RESTRICT,
    UNIQUE (request_id,case_id,scope),
    CHECK (next_review_at <= placed_at + interval '30 days'),
    CHECK ((released_at IS NULL) = (released_by IS NULL))
);

CREATE TABLE account_erasure_objects (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id uuid NOT NULL REFERENCES account_erasure_requests(id) ON DELETE RESTRICT,
    media_id uuid NOT NULL REFERENCES media_files(id) ON DELETE RESTRICT,
    bucket_name varchar(255),
    object_key text NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN
        ('PENDING','CLAIMED','RETRYABLE','OWNER_REVIEW_REQUIRED','STORAGE_REVIEW_REQUIRED','VERIFIED')),
    claim_token uuid,
    lease_expires_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error_code varchar(80),
    verified_at timestamptz,
    tombstone_until timestamptz,
    UNIQUE (request_id,media_id),
    CONSTRAINT account_erasure_object_claim_ck CHECK
        ((status='CLAIMED') = (claim_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CONSTRAINT account_erasure_object_verified_ck CHECK
        ((status='VERIFIED') = (verified_at IS NOT NULL AND tombstone_until IS NOT NULL)),
    CONSTRAINT account_erasure_object_owner_ck CHECK
        (status='OWNER_REVIEW_REQUIRED' OR bucket_name IS NOT NULL)
);

CREATE TABLE account_erasure_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES account_erasure_requests(id) ON DELETE RESTRICT,
    event_type varchar(80) NOT NULL,
    object_id uuid,
    error_code varchar(80),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER account_erasure_events_append_only BEFORE UPDATE OR DELETE ON account_erasure_events
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

-- The only exception to the pre-existing append-only audit triggers is an
-- evidenced, field-restricted redaction for a real erasure request. Event type,
-- severity, chronology and stable pseudonymous history are preserved.
CREATE TABLE account_erasure_audit_redactions (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES account_erasure_requests(id) ON DELETE RESTRICT,
    source_table varchar(40) NOT NULL CHECK (source_table IN
        ('audit_logs','security_events','user_account_status_history')),
    source_id varchar(80) NOT NULL,
    prior_sha256 char(64) NOT NULL,
    redacted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (request_id,source_table,source_id)
);
CREATE TRIGGER account_erasure_audit_redactions_append_only BEFORE UPDATE OR DELETE
    ON account_erasure_audit_redactions FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

-- Detached from notification/device FKs before the recipient inbox is removed.
CREATE TABLE account_erasure_push_diagnostics (
    source_attempt_id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES account_erasure_requests(id) ON DELETE RESTRICT,
    category varchar(40) NOT NULL,
    final_status varchar(30) NOT NULL,
    event_at timestamptz NOT NULL,
    retain_until timestamptz NOT NULL,
    CHECK (retain_until = event_at + interval '30 days')
);

CREATE FUNCTION account_erasure_audit_redaction_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'audit history is append-only'; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM fitness.account_erasure_audit_redactions e
        JOIN fitness.account_erasure_requests r ON r.id=e.request_id
        JOIN fitness.users u ON u.id=r.user_id
        WHERE e.source_table='audit_logs' AND e.source_id=OLD.id::text
          AND e.prior_sha256=encode(fitness.digest(to_jsonb(OLD)::text,'sha256'),'hex')
          AND (OLD.actor_user_id=r.user_id OR OLD.target_id=r.user_id
               OR to_jsonb(OLD)::text LIKE '%'||r.user_id::text||'%'
               OR to_jsonb(OLD)::text ILIKE '%'||u.email::text||'%')
    ) OR NEW.id IS DISTINCT FROM OLD.id OR NEW.action IS DISTINCT FROM OLD.action
      OR NEW.actor_role IS DISTINCT FROM OLD.actor_role
      OR NEW.target_type IS DISTINCT FROM OLD.target_type
      OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
      OR NEW.actor_user_id IS NOT NULL OR NEW.target_id IS NOT NULL
      OR NEW.request_id IS NOT NULL OR NEW.ip_address IS NOT NULL
      OR NEW.user_agent IS NOT NULL OR NEW.before_data IS NOT NULL
      OR NEW.after_data IS NOT NULL OR NEW.metadata <> '{}'::jsonb THEN
        RAISE EXCEPTION 'audit redaction requires matching immutable evidence and minimal fields';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION account_erasure_security_redaction_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'security history is append-only'; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM fitness.account_erasure_audit_redactions e
        JOIN fitness.account_erasure_requests r ON r.id=e.request_id
        JOIN fitness.users u ON u.id=r.user_id
        WHERE e.source_table='security_events' AND e.source_id=OLD.id::text
          AND e.prior_sha256=encode(fitness.digest(to_jsonb(OLD)::text,'sha256'),'hex')
          AND (OLD.user_id=r.user_id OR to_jsonb(OLD)::text LIKE '%'||r.user_id::text||'%'
               OR to_jsonb(OLD)::text ILIKE '%'||u.email::text||'%')
    ) OR NEW.id IS DISTINCT FROM OLD.id OR NEW.event_type IS DISTINCT FROM OLD.event_type
      OR NEW.severity IS DISTINCT FROM OLD.severity
      OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
      OR NEW.user_id IS NOT NULL OR NEW.device_id IS NOT NULL
      OR NEW.ip_address IS NOT NULL OR NEW.user_agent IS NOT NULL
      OR NEW.details <> '{}'::jsonb THEN
        RAISE EXCEPTION 'security redaction requires matching immutable evidence and minimal fields';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION account_erasure_status_redaction_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'status history is append-only'; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM fitness.account_erasure_audit_redactions e
        JOIN fitness.account_erasure_requests r ON r.id=e.request_id
        WHERE e.source_table='user_account_status_history' AND e.source_id=OLD.id::text
          AND e.prior_sha256=encode(fitness.digest(to_jsonb(OLD)::text,'sha256'),'hex')
          AND OLD.user_id=r.user_id
    ) OR NEW.id IS DISTINCT FROM OLD.id OR NEW.user_id IS DISTINCT FROM OLD.user_id
      OR NEW.from_status IS DISTINCT FROM OLD.from_status
      OR NEW.to_status IS DISTINCT FROM OLD.to_status
      OR NEW.changed_at IS DISTINCT FROM OLD.changed_at
      OR NEW.changed_by IS NOT NULL OR NEW.reason <> 'ACCOUNT_ERASURE_REDACTED' THEN
        RAISE EXCEPTION 'status redaction requires matching immutable evidence and minimal fields';
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER audit_logs_append_only_update ON audit_logs;
CREATE TRIGGER audit_logs_append_only_update BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION account_erasure_audit_redaction_guard();
DROP TRIGGER security_events_append_only ON security_events;
CREATE TRIGGER security_events_append_only BEFORE UPDATE OR DELETE ON security_events
    FOR EACH ROW EXECUTE FUNCTION account_erasure_security_redaction_guard();
DROP TRIGGER user_account_status_history_append_only ON user_account_status_history;
CREATE TRIGGER user_account_status_history_append_only BEFORE UPDATE OR DELETE ON user_account_status_history
    FOR EACH ROW EXECUTE FUNCTION account_erasure_status_redaction_guard();

CREATE INDEX idx_account_erasure_due ON account_erasure_requests(next_attempt_at,id)
    WHERE status IN ('REQUESTED','PROCESSING','RETRYABLE','HELD');
CREATE INDEX idx_account_erasure_object_due ON account_erasure_objects(next_attempt_at,id)
    WHERE status IN ('PENDING','CLAIMED','RETRYABLE');
CREATE INDEX idx_account_erasure_holds_active ON account_erasure_holds(request_id,scope)
    WHERE released_at IS NULL;
