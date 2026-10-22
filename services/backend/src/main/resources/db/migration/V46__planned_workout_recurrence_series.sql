SET search_path TO fitness, public;

-- Composite references below bind each selected session to the immutable
-- source version of its series, including writes that bypass the application.
ALTER TABLE workout_plan_sessions
    ADD CONSTRAINT uq_workout_plan_session_id_version_v46
    UNIQUE (id, workout_plan_version_id);

-- V5 recurring_schedules belongs to Appointments. Planned Workout automation uses
-- a separate, source-version-bound aggregate; historical V5 rows are untouched.
CREATE TABLE workout_recurrence_series (
    id uuid PRIMARY KEY,
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    plan_id uuid NOT NULL REFERENCES workout_plans(id) ON DELETE RESTRICT,
    source_plan_version_id uuid NOT NULL REFERENCES workout_plan_versions(id) ON DELETE RESTRICT,
    source_plan_version_number integer NOT NULL CHECK (source_plan_version_number > 0),
    observed_plan_aggregate_version bigint NOT NULL CHECK (observed_plan_aggregate_version >= 0),
    plan_weeks integer NOT NULL CHECK (plan_weeks > 0),
    monday_anchor date NOT NULL CHECK (EXTRACT(ISODOW FROM monday_anchor) = 1),
    timezone varchar(64) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN
        ('PENDING','ACTIVE','PAUSED_SOURCE','STOPPED','REJECTED','WITHDRAWN')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    initiated_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    proposing_trainer_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE RESTRICT,
    confirmed_by uuid REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    confirmed_at timestamptz,
    stopped_at timestamptz,
    CONSTRAINT workout_recurrence_series_authority_ck CHECK (
        (proposing_trainer_id IS NULL AND coaching_period_id IS NULL
            AND initiated_by = student_id AND status <> 'PENDING')
        OR (proposing_trainer_id IS NOT NULL AND coaching_period_id IS NOT NULL
            AND initiated_by = proposing_trainer_id)
    ),
    CONSTRAINT uq_workout_recurrence_series_id_source_version_v46
        UNIQUE (id, source_plan_version_id),
    CONSTRAINT workout_recurrence_series_confirmed_ck CHECK (
        (status IN ('ACTIVE','PAUSED_SOURCE','STOPPED') AND confirmed_by = student_id
            AND confirmed_at IS NOT NULL)
        OR (status IN ('PENDING','REJECTED','WITHDRAWN')
            AND confirmed_by IS NULL AND confirmed_at IS NULL)
    )
);

CREATE TABLE workout_recurrence_series_items (
    series_id uuid NOT NULL REFERENCES workout_recurrence_series(id) ON DELETE RESTRICT,
    source_plan_version_id uuid NOT NULL,
    plan_session_id uuid NOT NULL,
    week_number integer NOT NULL CHECK (week_number > 0),
    day_number integer NOT NULL CHECK (day_number BETWEEN 1 AND 7),
    sequence_number integer NOT NULL CHECK (sequence_number > 0),
    first_local_start varchar(35) NOT NULL,
    first_local_end varchar(35) NOT NULL,
    PRIMARY KEY(series_id, plan_session_id),
    CONSTRAINT fk_workout_recurrence_item_series_version_v46
        FOREIGN KEY (series_id, source_plan_version_id)
        REFERENCES workout_recurrence_series(id, source_plan_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_workout_recurrence_item_session_version_v46
        FOREIGN KEY (plan_session_id, source_plan_version_id)
        REFERENCES workout_plan_sessions(id, workout_plan_version_id) ON DELETE RESTRICT
);

CREATE TABLE workout_recurrence_periods (
    id uuid PRIMARY KEY,
    series_id uuid NOT NULL REFERENCES workout_recurrence_series(id) ON DELETE RESTRICT,
    block_iteration bigint NOT NULL CHECK (block_iteration >= 0),
    plan_session_id uuid NOT NULL REFERENCES workout_plan_sessions(id) ON DELETE RESTRICT,
    local_start varchar(35) NOT NULL,
    local_end varchar(35) NOT NULL,
    timezone varchar(64) NOT NULL,
    start_utc_offset varchar(6),
    end_utc_offset varchar(6),
    planned_start_at timestamptz,
    planned_end_at timestamptz,
    outcome varchar(30) NOT NULL CHECK (outcome IN
        ('CREATED','DST_GAP','DST_AMBIGUOUS','INVALID_INTERVAL',
         'SKIPPED_OVERLAP','SKIPPED_UNKNOWN_END','SKIPPED_DUPLICATE')),
    occurrence_id uuid UNIQUE,
    processed_at timestamptz NOT NULL,
    UNIQUE(series_id, block_iteration, plan_session_id),
    FOREIGN KEY(series_id, plan_session_id)
        REFERENCES workout_recurrence_series_items(series_id, plan_session_id) ON DELETE RESTRICT,
    CONSTRAINT workout_recurrence_period_result_ck CHECK (
        (outcome = 'CREATED' AND occurrence_id IS NOT NULL
            AND start_utc_offset IS NOT NULL AND end_utc_offset IS NOT NULL
            AND planned_start_at IS NOT NULL AND planned_end_at IS NOT NULL)
        OR (outcome <> 'CREATED' AND occurrence_id IS NULL)
    )
);

ALTER TABLE planned_workouts
    ADD COLUMN series_period_id uuid UNIQUE REFERENCES workout_recurrence_periods(id) ON DELETE RESTRICT,
    ADD CONSTRAINT planned_workout_series_intent_ck CHECK (
        series_period_id IS NULL OR (
            schedule_batch_id IS NULL AND schedule_change_id IS NULL
            AND planned_end_at IS NOT NULL AND schedule_local_start IS NOT NULL
            AND schedule_local_end IS NOT NULL AND schedule_timezone IS NOT NULL
            AND schedule_start_utc_offset IS NOT NULL AND schedule_end_utc_offset IS NOT NULL
        )
    );

ALTER TABLE workout_recurrence_periods
    ADD CONSTRAINT workout_recurrence_period_occurrence_fk
    FOREIGN KEY(occurrence_id) REFERENCES planned_workouts(id)
    DEFERRABLE INITIALLY DEFERRED;

-- Extend V33's protections to recurrence rows. Legacy rows remain outside both
-- partial constraints and must still be checked transactionally by every writer.
CREATE UNIQUE INDEX uq_workout_schedule_materialized_source_instant_v46
    ON planned_workouts(student_id, workout_plan_session_id, planned_start_at)
    WHERE schedule_batch_id IS NOT NULL OR schedule_change_id IS NOT NULL
       OR series_period_id IS NOT NULL;
ALTER TABLE planned_workouts ADD CONSTRAINT ex_workout_schedule_materialized_occupied_v46
    EXCLUDE USING gist (student_id WITH =,
        tstzrange(planned_start_at, planned_end_at, '[)') WITH &&)
    WHERE ((schedule_batch_id IS NOT NULL OR schedule_change_id IS NOT NULL
        OR series_period_id IS NOT NULL) AND status = 'SCHEDULED');

CREATE TABLE workout_recurrence_series_history (
    series_id uuid NOT NULL REFERENCES workout_recurrence_series(id) ON DELETE RESTRICT,
    series_version bigint NOT NULL CHECK (series_version >= 0),
    from_status varchar(20),
    to_status varchar(20) NOT NULL,
    actor_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    reason varchar(1000),
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY(series_id, series_version)
);

CREATE TABLE workout_recurrence_command_receipts (
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key varchar(120) NOT NULL,
    command_name varchar(30) NOT NULL,
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    series_id uuid NOT NULL REFERENCES workout_recurrence_series(id) ON DELETE RESTRICT,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(actor_id, command_key)
);

CREATE TRIGGER workout_recurrence_series_items_append_only
    BEFORE UPDATE OR DELETE ON workout_recurrence_series_items
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER workout_recurrence_periods_append_only
    BEFORE UPDATE OR DELETE ON workout_recurrence_periods
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER workout_recurrence_history_append_only
    BEFORE UPDATE OR DELETE ON workout_recurrence_series_history
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER workout_recurrence_receipts_append_only
    BEFORE UPDATE OR DELETE ON workout_recurrence_command_receipts
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE INDEX ix_workout_recurrence_series_student_time
    ON workout_recurrence_series(student_id, created_at DESC, id DESC);
CREATE INDEX ix_workout_recurrence_series_trainer_time
    ON workout_recurrence_series(proposing_trainer_id, student_id, created_at DESC, id DESC)
    WHERE proposing_trainer_id IS NOT NULL;
CREATE INDEX ix_workout_recurrence_periods_series_iteration
    ON workout_recurrence_periods(series_id, block_iteration, plan_session_id);
