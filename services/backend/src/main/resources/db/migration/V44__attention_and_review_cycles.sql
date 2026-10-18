SET search_path TO fitness, public;

-- Explicit Student confirmation; the default users.timezone is not consent for review scheduling.
CREATE TABLE student_review_timezones (
    student_id uuid PRIMARY KEY REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    timezone varchar(64) NOT NULL,
    confirmed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE TABLE student_review_timezone_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key uuid NOT NULL,
    previous_timezone varchar(64),
    confirmed_timezone varchar(64) NOT NULL,
    resulting_version bigint NOT NULL CHECK (resulting_version >= 0),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_review_timezone_events ON student_review_timezone_events(student_id,occurred_at,id);
CREATE TRIGGER review_timezone_events_append_only BEFORE UPDATE OR DELETE ON student_review_timezone_events
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE TABLE coaching_review_tasks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE RESTRICT,
    coaching_period_id uuid NOT NULL REFERENCES coaching_periods(id) ON DELETE RESTRICT,
    cycle_start_date date NOT NULL,
    due_date date NOT NULL,
    timezone varchar(64) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','COMPLETED','CANCELLED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    summary text,
    evidence jsonb NOT NULL DEFAULT '[]'::jsonb,
    missing_data jsonb NOT NULL DEFAULT '[]'::jsonb,
    completed_at timestamptz,
    student_acknowledged_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_review_task_period_cycle UNIQUE (coaching_period_id, cycle_start_date),
    CONSTRAINT review_task_dates_ck CHECK (due_date = cycle_start_date + 7),
    CONSTRAINT review_task_completion_ck CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);
CREATE INDEX ix_review_tasks_student ON coaching_review_tasks(student_id,cycle_start_date DESC,id DESC);
CREATE INDEX ix_review_tasks_trainer ON coaching_review_tasks(trainer_id,cycle_start_date DESC,id DESC);

CREATE TABLE coaching_review_task_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id uuid NOT NULL REFERENCES coaching_review_tasks(id) ON DELETE RESTRICT,
    actor_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    event_type varchar(32) NOT NULL,
    previous_version bigint,
    resulting_version bigint NOT NULL,
    detail jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER review_task_events_append_only BEFORE UPDATE OR DELETE ON coaching_review_task_events
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

ALTER TABLE attention_signals
    ADD COLUMN coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE RESTRICT,
    ADD COLUMN source_key varchar(200),
    ADD COLUMN source_version bigint,
    ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0);
CREATE UNIQUE INDEX uq_m7_signal_source ON attention_signals(coaching_period_id,signal_type,source_key)
    WHERE coaching_period_id IS NOT NULL AND source_key IS NOT NULL AND source='SYSTEM_RULE';
CREATE UNIQUE INDEX uq_m7_signal_self_source ON attention_signals(student_id,signal_type,source_key)
    WHERE coaching_period_id IS NULL AND source_key IS NOT NULL AND source='SYSTEM_RULE';
CREATE INDEX ix_m7_signal_student ON attention_signals(student_id,created_at DESC,id DESC);
CREATE INDEX ix_m7_signal_trainer ON attention_signals(trainer_id,coaching_period_id,created_at DESC,id DESC);

CREATE TABLE attention_signal_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    signal_id uuid NOT NULL REFERENCES attention_signals(id) ON DELETE RESTRICT,
    actor_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    event_type varchar(32) NOT NULL,
    previous_severity severity_level,
    resulting_severity severity_level NOT NULL,
    previous_status attention_status,
    resulting_status attention_status NOT NULL,
    previous_version bigint,
    resulting_version bigint NOT NULL,
    reason text,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_m7_signal_events ON attention_signal_events(signal_id,occurred_at ASC,id ASC);
CREATE TRIGGER attention_signal_events_append_only BEFORE UPDATE OR DELETE ON attention_signal_events
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE TABLE attention_review_command_receipts (
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key uuid NOT NULL,
    payload_hash char(64) NOT NULL,
    resource_kind varchar(16) NOT NULL,
    resource_id uuid NOT NULL,
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(actor_id,command_key)
);
CREATE TRIGGER attention_review_receipts_append_only BEFORE UPDATE OR DELETE ON attention_review_command_receipts
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
