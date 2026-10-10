SET search_path TO fitness, public;

-- Legacy rows are not rewritten or enrolled into the new exclusion constraint.
-- Report uncertainty/conflicts before establishing constraints for new batch rows.
DO $$
DECLARE unknown_ends bigint;
DECLARE legacy_overlaps bigint;
DECLARE legacy_duplicates bigint;
BEGIN
    SELECT count(*) INTO unknown_ends FROM planned_workouts
    WHERE status = 'SCHEDULED' AND planned_end_at IS NULL;
    SELECT count(*) INTO legacy_overlaps
    FROM planned_workouts a JOIN planned_workouts b
      ON a.student_id = b.student_id AND a.id < b.id
     AND a.status = 'SCHEDULED' AND b.status = 'SCHEDULED'
     AND a.planned_end_at IS NOT NULL AND b.planned_end_at IS NOT NULL
     AND tstzrange(a.planned_start_at, a.planned_end_at, '[)') &&
         tstzrange(b.planned_start_at, b.planned_end_at, '[)');
    SELECT count(*) INTO legacy_duplicates
    FROM planned_workouts a JOIN planned_workouts b
      ON a.id < b.id AND a.student_id = b.student_id
     AND a.workout_plan_session_id = b.workout_plan_session_id
     AND a.workout_plan_session_id IS NOT NULL
     AND a.planned_start_at = b.planned_start_at;
    RAISE NOTICE 'V31 legacy SCHEDULED occurrences with unknown end: %; overlapping known ranges: %; duplicate source instants: %',
        unknown_ends, legacy_overlaps, legacy_duplicates;
END;
$$;

CREATE TABLE workout_schedule_batches (
    id uuid PRIMARY KEY,
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    plan_id uuid NOT NULL REFERENCES workout_plans(id) ON DELETE RESTRICT,
    plan_version_id uuid NOT NULL REFERENCES workout_plan_versions(id) ON DELETE RESTRICT,
    plan_version_number integer NOT NULL CHECK (plan_version_number > 0),
    week_anchor_date date NOT NULL,
    timezone varchar(64) NOT NULL,
    confirmation_source varchar(30) NOT NULL CHECK (confirmation_source IN ('STUDENT_DIRECT', 'TRAINER_PROPOSAL')),
    source_proposal_id uuid,
    confirmed_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    confirmed_at timestamptz NOT NULL,
    CONSTRAINT workout_schedule_batch_source_ck CHECK (
        (confirmation_source = 'STUDENT_DIRECT' AND source_proposal_id IS NULL)
        OR confirmation_source = 'TRAINER_PROPOSAL'
    )
);

ALTER TABLE planned_workouts
    ADD COLUMN schedule_batch_id uuid REFERENCES workout_schedule_batches(id) ON DELETE RESTRICT,
    -- ISO local wall times preserve the contract's optional nanosecond fraction;
    -- PostgreSQL timestamp would round them to microseconds.
    ADD COLUMN schedule_local_start varchar(35),
    ADD COLUMN schedule_local_end varchar(35),
    ADD COLUMN schedule_timezone varchar(64),
    ADD COLUMN schedule_start_utc_offset varchar(6),
    ADD COLUMN schedule_end_utc_offset varchar(6),
    ADD CONSTRAINT planned_workout_batch_intent_ck CHECK (
        schedule_batch_id IS NULL OR (
            planned_end_at IS NOT NULL AND schedule_local_start IS NOT NULL AND schedule_local_end IS NOT NULL
            AND schedule_timezone IS NOT NULL AND schedule_start_utc_offset IS NOT NULL
            AND schedule_end_utc_offset IS NOT NULL
        )
    );

CREATE TABLE workout_schedule_batch_items (
    batch_id uuid NOT NULL REFERENCES workout_schedule_batches(id) ON DELETE RESTRICT,
    client_item_id uuid NOT NULL,
    occurrence_id uuid NOT NULL UNIQUE REFERENCES planned_workouts(id) ON DELETE RESTRICT,
    plan_session_id uuid NOT NULL REFERENCES workout_plan_sessions(id) ON DELETE RESTRICT,
    week_number integer NOT NULL CHECK (week_number > 0),
    day_number integer NOT NULL CHECK (day_number BETWEEN 1 AND 7),
    sequence_number integer NOT NULL CHECK (sequence_number > 0),
    PRIMARY KEY (batch_id, client_item_id),
    UNIQUE (batch_id, plan_session_id)
);

CREATE TABLE workout_schedule_command_receipts (
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key varchar(120) NOT NULL,
    command_name varchar(80) NOT NULL,
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    batch_id uuid NOT NULL REFERENCES workout_schedule_batches(id) ON DELETE RESTRICT,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id, command_key)
);

-- Constrain new B06 rows without retroactively rejecting valid or anomalous legacy history.
CREATE UNIQUE INDEX uq_workout_schedule_new_source_instant
    ON planned_workouts(student_id, workout_plan_session_id, planned_start_at)
    WHERE schedule_batch_id IS NOT NULL;
ALTER TABLE planned_workouts ADD CONSTRAINT ex_workout_schedule_new_occupied_range
    EXCLUDE USING gist (student_id WITH =,
        tstzrange(planned_start_at, planned_end_at, '[)') WITH &&)
    WHERE (schedule_batch_id IS NOT NULL AND status = 'SCHEDULED');

CREATE INDEX ix_workout_schedule_batch_student_time
    ON workout_schedule_batches(student_id, confirmed_at, id);
