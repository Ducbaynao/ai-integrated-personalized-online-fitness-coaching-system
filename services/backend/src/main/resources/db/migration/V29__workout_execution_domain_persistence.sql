SET search_path TO fitness, public;

-- PostgreSQL does not allow a newly-added enum value to be consumed safely in the
-- same transaction. Replace the single-column enum instead so V29 stays atomic.
CREATE TYPE planned_workout_status_v29 AS ENUM
    ('SCHEDULED', 'COMPLETED', 'ATTEMPTED', 'MISSED', 'SKIPPED', 'RESCHEDULED', 'CANCELLED');
ALTER TABLE planned_workouts ALTER COLUMN status DROP DEFAULT;
DROP INDEX IF EXISTS idx_planned_workouts_pending;
ALTER TABLE planned_workouts
    ALTER COLUMN status TYPE planned_workout_status_v29 USING status::text::planned_workout_status_v29;
DROP TYPE planned_workout_status;
ALTER TYPE planned_workout_status_v29 RENAME TO planned_workout_status;
ALTER TABLE planned_workouts ALTER COLUMN status SET DEFAULT 'SCHEDULED';
CREATE INDEX idx_planned_workouts_pending ON planned_workouts(planned_start_at) WHERE status = 'SCHEDULED';

ALTER TABLE planned_workouts
    ADD COLUMN execution_started_at timestamptz;

CREATE OR REPLACE FUNCTION guard_planned_workout_occurrence() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Planned workout occurrences cannot be hard-deleted';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Planned workout version must increment exactly once';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.student_id IS DISTINCT FROM OLD.student_id
       OR NEW.workout_plan_session_id IS DISTINCT FROM OLD.workout_plan_session_id
       OR NEW.coaching_period_id IS DISTINCT FROM OLD.coaching_period_id
       OR NEW.original_planned_start_at IS DISTINCT FROM OLD.original_planned_start_at
       OR NEW.rescheduled_from_id IS DISTINCT FROM OLD.rescheduled_from_id
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Planned workout source identity is immutable';
    END IF;
    IF OLD.execution_started_at IS NOT NULL
       AND NEW.execution_started_at IS DISTINCT FROM OLD.execution_started_at THEN
        RAISE EXCEPTION 'Planned workout execution seal is immutable';
    END IF;
    IF OLD.execution_started_at IS NULL AND NEW.execution_started_at IS NOT NULL
       AND (OLD.status <> 'SCHEDULED' OR NEW.status <> 'SCHEDULED') THEN
        RAISE EXCEPTION 'Only a scheduled workout can be sealed for execution';
    END IF;
    IF OLD.execution_started_at IS NOT NULL THEN
        IF NEW.planned_start_at IS DISTINCT FROM OLD.planned_start_at
           OR NEW.planned_end_at IS DISTINCT FROM OLD.planned_end_at THEN
            RAISE EXCEPTION 'Started planned workout schedule is immutable';
        END IF;
        IF NEW.status IS DISTINCT FROM OLD.status
           AND NOT (OLD.status = 'SCHEDULED' AND NEW.status IN ('COMPLETED', 'ATTEMPTED')) THEN
            RAISE EXCEPTION 'Invalid started planned workout transition';
        END IF;
    END IF;
    IF OLD.status IN ('COMPLETED', 'ATTEMPTED', 'MISSED', 'SKIPPED', 'RESCHEDULED', 'CANCELLED') THEN
        RAISE EXCEPTION 'Terminal planned workout is immutable';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE workout_session_adjustments
    ADD COLUMN adjustment_contract_version smallint NOT NULL DEFAULT 0,
    ADD COLUMN resolution_state varchar(24) NOT NULL DEFAULT 'LEGACY_UNRESOLVED',
    ADD COLUMN typed_target_load numeric(18,6),
    ADD COLUMN typed_load_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    ADD COLUMN typed_reps_min integer,
    ADD COLUMN typed_reps_max integer,
    ADD COLUMN typed_target_sets integer,
    ADD COLUMN typed_duration_seconds integer,
    ADD COLUMN typed_sequence integer,
    ADD COLUMN typed_note text,
    ADD CONSTRAINT workout_adjustment_resolution_state_ck
        CHECK (resolution_state IN ('TYPED', 'LEGACY_UNRESOLVED')),
    ADD CONSTRAINT workout_adjustment_contract_version_ck
        CHECK ((resolution_state = 'TYPED' AND adjustment_contract_version = 1)
            OR (resolution_state = 'LEGACY_UNRESOLVED' AND adjustment_contract_version = 0)),
    ADD CONSTRAINT workout_adjustment_typed_values_ck CHECK (
        resolution_state = 'LEGACY_UNRESOLVED' OR CASE adjustment_type
          WHEN 'LOAD' THEN planned_session_exercise_id IS NOT NULL
             AND typed_target_load IS NOT NULL AND typed_target_load >= 0
             AND typed_reps_min IS NULL AND typed_reps_max IS NULL AND typed_target_sets IS NULL
             AND typed_duration_seconds IS NULL AND typed_sequence IS NULL AND typed_note IS NULL
             AND replacement_exercise_variation_id IS NULL
          WHEN 'REPS' THEN planned_session_exercise_id IS NOT NULL
             AND (typed_reps_min IS NOT NULL OR typed_reps_max IS NOT NULL)
             AND (typed_reps_min IS NULL OR typed_reps_min >= 0)
             AND (typed_reps_max IS NULL OR typed_reps_max >= 0)
             AND (typed_reps_min IS NULL OR typed_reps_max IS NULL OR typed_reps_max >= typed_reps_min)
             AND typed_target_load IS NULL AND typed_load_unit_id IS NULL AND typed_target_sets IS NULL
             AND typed_duration_seconds IS NULL AND typed_sequence IS NULL AND typed_note IS NULL
             AND replacement_exercise_variation_id IS NULL
          WHEN 'SETS' THEN planned_session_exercise_id IS NOT NULL
             AND typed_target_sets > 0 AND typed_target_load IS NULL AND typed_load_unit_id IS NULL
             AND typed_reps_min IS NULL AND typed_reps_max IS NULL AND typed_duration_seconds IS NULL
             AND typed_sequence IS NULL AND typed_note IS NULL AND replacement_exercise_variation_id IS NULL
          WHEN 'DURATION' THEN planned_session_exercise_id IS NOT NULL
             AND typed_duration_seconds >= 0 AND typed_target_load IS NULL AND typed_load_unit_id IS NULL
             AND typed_reps_min IS NULL AND typed_reps_max IS NULL AND typed_target_sets IS NULL
             AND typed_sequence IS NULL AND typed_note IS NULL AND replacement_exercise_variation_id IS NULL
          WHEN 'ORDER' THEN planned_session_exercise_id IS NOT NULL
             AND typed_sequence >= 1 AND typed_target_load IS NULL AND typed_load_unit_id IS NULL
             AND typed_reps_min IS NULL AND typed_reps_max IS NULL AND typed_target_sets IS NULL
             AND typed_duration_seconds IS NULL AND typed_note IS NULL AND replacement_exercise_variation_id IS NULL
          WHEN 'NOTE' THEN typed_note IS NOT NULL
             AND typed_target_load IS NULL AND typed_load_unit_id IS NULL AND typed_reps_min IS NULL
             AND typed_reps_max IS NULL AND typed_target_sets IS NULL AND typed_duration_seconds IS NULL
             AND typed_sequence IS NULL AND replacement_exercise_variation_id IS NULL
          WHEN 'EXERCISE_SWAP' THEN planned_session_exercise_id IS NOT NULL
             AND replacement_exercise_variation_id IS NOT NULL
             AND typed_target_load IS NULL AND typed_load_unit_id IS NULL AND typed_reps_min IS NULL
             AND typed_reps_max IS NULL AND typed_target_sets IS NULL AND typed_duration_seconds IS NULL
             AND typed_sequence IS NULL AND typed_note IS NULL
          WHEN 'OTHER' THEN typed_target_load IS NULL AND typed_load_unit_id IS NULL
             AND typed_reps_min IS NULL AND typed_reps_max IS NULL AND typed_target_sets IS NULL
             AND typed_duration_seconds IS NULL AND typed_sequence IS NULL AND typed_note IS NULL
             AND replacement_exercise_variation_id IS NULL
          ELSE false
        END
    );

-- V28 stored caller-supplied JSON without a per-type JSON schema. The only
-- executable legacy shape is NOTE {"note": <string>}; normalize it only when
-- the object contains exactly that key. All other legacy rows remain explicit
-- LEGACY_UNRESOLVED rather than guessing aliases or historical validity.
ALTER TABLE workout_session_adjustments DISABLE TRIGGER workout_adjustments_append_only;
UPDATE workout_session_adjustments
SET adjustment_contract_version = 1,
    resolution_state = 'TYPED',
    typed_note = after_value ->> 'note'
WHERE adjustment_type = 'NOTE'
  AND jsonb_typeof(after_value) = 'object'
  AND after_value ? 'note'
  AND jsonb_typeof(after_value -> 'note') = 'string'
  AND NOT EXISTS (
      SELECT 1 FROM jsonb_object_keys(after_value) AS key
      WHERE key <> 'note'
  );
ALTER TABLE workout_session_adjustments ENABLE TRIGGER workout_adjustments_append_only;

CREATE OR REPLACE FUNCTION guard_workout_adjustment_insert() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE sealed_at timestamptz;
BEGIN
    SELECT execution_started_at INTO sealed_at
    FROM planned_workouts WHERE id = NEW.planned_workout_id FOR UPDATE;
    IF sealed_at IS NOT NULL THEN
        RAISE EXCEPTION 'Planned workout adjustments are sealed';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_workout_adjustment_insert
    BEFORE INSERT ON workout_session_adjustments
    FOR EACH ROW EXECUTE FUNCTION guard_workout_adjustment_insert();

CREATE OR REPLACE FUNCTION protect_workout_adjustment_history() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    RAISE EXCEPTION 'Workout occurrence adjustments are append-only';
END;
$$;
CREATE TRIGGER workout_adjustment_history_append_only
    BEFORE UPDATE OR DELETE ON workout_session_adjustments
    FOR EACH ROW EXECUTE FUNCTION protect_workout_adjustment_history();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM workout_session_logs WHERE status = 'IN_PROGRESS') THEN
        RAISE EXCEPTION 'V29 cannot migrate legacy IN_PROGRESS workout executions without a frozen snapshot';
    END IF;
    IF EXISTS (
        SELECT planned_workout_id FROM workout_session_logs
        WHERE planned_workout_id IS NOT NULL
        GROUP BY planned_workout_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V29 found duplicate legacy workout executions for one planned workout';
    END IF;
END;
$$;

ALTER TABLE workout_session_logs
    ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN workout_plan_session_id uuid REFERENCES workout_plan_sessions(id) ON DELETE RESTRICT,
    ADD COLUMN frozen_occurrence_version bigint CHECK (frozen_occurrence_version IS NULL OR frozen_occurrence_version >= 0),
    ADD COLUMN snapshot_frozen_at timestamptz,
    ADD COLUMN snapshot_mode varchar(24) NOT NULL DEFAULT 'LEGACY_REFERENCE_ONLY',
    ADD CONSTRAINT workout_execution_snapshot_mode_ck
        CHECK (snapshot_mode IN ('FROZEN', 'LEGACY_REFERENCE_ONLY')),
    ADD CONSTRAINT workout_execution_frozen_source_ck CHECK (
        snapshot_mode = 'LEGACY_REFERENCE_ONLY' OR
        (planned_workout_id IS NOT NULL AND workout_plan_version_id IS NOT NULL
         AND workout_plan_session_id IS NOT NULL AND frozen_occurrence_version IS NOT NULL
         AND snapshot_frozen_at IS NOT NULL AND logged_by = student_id)
    ),
    ADD CONSTRAINT workout_execution_lifecycle_time_ck CHECK (
        snapshot_mode = 'LEGACY_REFERENCE_ONLY'
        OR (status = 'IN_PROGRESS' AND performed_end_at IS NULL)
        OR (status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'ABORTED') AND performed_end_at IS NOT NULL)
    );

DROP INDEX uq_workout_log_planned_workout;
CREATE UNIQUE INDEX uq_workout_execution_planned_workout
    ON workout_session_logs(planned_workout_id) WHERE planned_workout_id IS NOT NULL;
CREATE UNIQUE INDEX uq_workout_execution_one_in_progress_student
    ON workout_session_logs(student_id) WHERE status = 'IN_PROGRESS';

ALTER TABLE exercise_logs
    ADD COLUMN actual_exercise_variation_id uuid REFERENCES exercise_variations(id) ON DELETE RESTRICT,
    ADD COLUMN substitution_reason text,
    ADD COLUMN baseline_set_count integer CHECK (baseline_set_count IS NULL OR baseline_set_count > 0),
    ADD COLUMN frozen_sequence_number integer CHECK (frozen_sequence_number IS NULL OR frozen_sequence_number > 0),
    ADD COLUMN target_reps_min integer CHECK (target_reps_min IS NULL OR target_reps_min >= 0),
    ADD COLUMN target_reps_max integer CHECK (target_reps_max IS NULL OR target_reps_max >= 0),
    ADD COLUMN target_load numeric(18,6) CHECK (target_load IS NULL OR target_load >= 0),
    ADD COLUMN target_load_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    ADD COLUMN target_rpe numeric(4,2) CHECK (target_rpe IS NULL OR target_rpe BETWEEN 1 AND 10),
    ADD COLUMN target_rir numeric(4,2) CHECK (target_rir IS NULL OR target_rir BETWEEN 0 AND 10),
    ADD COLUMN target_rest_seconds integer CHECK (target_rest_seconds IS NULL OR target_rest_seconds >= 0),
    ADD COLUMN target_tempo varchar(30),
    ADD COLUMN target_duration_seconds integer CHECK (target_duration_seconds IS NULL OR target_duration_seconds >= 0),
    ADD COLUMN target_distance_value numeric(18,6) CHECK (target_distance_value IS NULL OR target_distance_value >= 0),
    ADD COLUMN target_distance_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    ADD COLUMN frozen_instructions text,
    ADD COLUMN frozen_prescription_note text,
    ADD CONSTRAINT exercise_execution_reps_range_ck
        CHECK (target_reps_max IS NULL OR target_reps_min IS NULL OR target_reps_max >= target_reps_min),
    ADD CONSTRAINT exercise_execution_substitution_ck CHECK (
        substitution_reason IS NULL OR actual_exercise_variation_id IS DISTINCT FROM exercise_variation_id
    );

UPDATE exercise_logs SET actual_exercise_variation_id = exercise_variation_id
WHERE actual_exercise_variation_id IS NULL;
ALTER TABLE exercise_logs ALTER COLUMN actual_exercise_variation_id SET NOT NULL;

ALTER TABLE set_logs
    ADD COLUMN client_set_id uuid,
    ADD COLUMN baseline_set_number integer CHECK (baseline_set_number IS NULL OR baseline_set_number > 0);
UPDATE set_logs SET client_set_id = gen_random_uuid() WHERE client_set_id IS NULL;
ALTER TABLE set_logs ALTER COLUMN client_set_id SET NOT NULL;
CREATE UNIQUE INDEX uq_set_logs_client_set_id ON set_logs(client_set_id);
CREATE UNIQUE INDEX uq_set_logs_baseline_number
    ON set_logs(exercise_log_id, baseline_set_number) WHERE baseline_set_number IS NOT NULL;

CREATE TABLE workout_execution_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workout_execution_id uuid NOT NULL REFERENCES workout_session_logs(id) ON DELETE RESTRICT,
    from_status actual_workout_status,
    to_status actual_workout_status NOT NULL,
    changed_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reason text NOT NULL,
    execution_version bigint NOT NULL CHECK (execution_version >= 0),
    changed_at timestamptz NOT NULL
);

INSERT INTO workout_execution_status_history
    (workout_execution_id,from_status,to_status,changed_by,reason,execution_version,changed_at)
SELECT id,NULL,status,logged_by,'MIGRATED_LEGACY_BASELINE',version,created_at
FROM workout_session_logs;

CREATE TABLE workout_execution_command_receipts (
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key varchar(120) NOT NULL,
    command_name varchar(40) NOT NULL CHECK (command_name IN ('START','SKIP','COMPLETE','ABORT')),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    planned_workout_id uuid NOT NULL REFERENCES planned_workouts(id) ON DELETE RESTRICT,
    workout_execution_id uuid REFERENCES workout_session_logs(id) ON DELETE RESTRICT,
    resulting_execution_status actual_workout_status,
    resulting_planned_status planned_workout_status NOT NULL,
    resulting_version bigint,
    effective_at timestamptz NOT NULL,
    response_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id, command_key)
);

CREATE TABLE workout_execution_applied_adjustments (
    workout_execution_id uuid NOT NULL REFERENCES workout_session_logs(id) ON DELETE RESTRICT,
    adjustment_id uuid NOT NULL REFERENCES workout_session_adjustments(id) ON DELETE RESTRICT,
    resolution_order integer NOT NULL CHECK (resolution_order > 0),
    PRIMARY KEY (workout_execution_id, adjustment_id),
    UNIQUE (workout_execution_id, resolution_order)
);

CREATE OR REPLACE FUNCTION protect_workout_execution_history() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    RAISE EXCEPTION 'Workout execution history is append-only';
END;
$$;
CREATE TRIGGER workout_execution_status_history_append_only
    BEFORE UPDATE OR DELETE ON workout_execution_status_history
    FOR EACH ROW EXECUTE FUNCTION protect_workout_execution_history();
CREATE TRIGGER workout_execution_receipts_append_only
    BEFORE UPDATE OR DELETE ON workout_execution_command_receipts
    FOR EACH ROW EXECUTE FUNCTION protect_workout_execution_history();
CREATE TRIGGER workout_execution_adjustments_append_only
    BEFORE UPDATE OR DELETE ON workout_execution_applied_adjustments
    FOR EACH ROW EXECUTE FUNCTION protect_workout_execution_history();

CREATE OR REPLACE FUNCTION guard_workout_execution() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Workout executions cannot be hard-deleted'; END IF;
    IF OLD.status <> 'IN_PROGRESS' THEN RAISE EXCEPTION 'Terminal workout execution is immutable'; END IF;
    IF NEW.version <> OLD.version + 1 THEN RAISE EXCEPTION 'Workout execution version must increment exactly once'; END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.student_id IS DISTINCT FROM OLD.student_id
       OR NEW.planned_workout_id IS DISTINCT FROM OLD.planned_workout_id
       OR NEW.workout_plan_version_id IS DISTINCT FROM OLD.workout_plan_version_id
       OR NEW.workout_plan_session_id IS DISTINCT FROM OLD.workout_plan_session_id
       OR NEW.coaching_period_id IS DISTINCT FROM OLD.coaching_period_id
       OR NEW.performed_start_at IS DISTINCT FROM OLD.performed_start_at
       OR NEW.frozen_occurrence_version IS DISTINCT FROM OLD.frozen_occurrence_version
       OR NEW.snapshot_frozen_at IS DISTINCT FROM OLD.snapshot_frozen_at
       OR NEW.snapshot_mode IS DISTINCT FROM OLD.snapshot_mode
       OR NEW.logged_by IS DISTINCT FROM OLD.logged_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Workout execution source identity and snapshot are immutable';
    END IF;
    IF NEW.status NOT IN ('IN_PROGRESS','COMPLETED','PARTIALLY_COMPLETED','ABORTED') THEN
        RAISE EXCEPTION 'Invalid workout execution transition';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_workout_execution
    BEFORE UPDATE OR DELETE ON workout_session_logs
    FOR EACH ROW EXECUTE FUNCTION guard_workout_execution();

CREATE OR REPLACE FUNCTION guard_workout_execution_child() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE execution_id uuid; execution_status actual_workout_status;
BEGIN
    IF TG_TABLE_NAME = 'exercise_logs' THEN
        execution_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.workout_session_log_id ELSE NEW.workout_session_log_id END;
    ELSE
        SELECT workout_session_log_id INTO execution_id FROM exercise_logs
        WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.exercise_log_id ELSE NEW.exercise_log_id END;
    END IF;
    SELECT status INTO execution_status FROM workout_session_logs WHERE id=execution_id;
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Workout execution facts cannot be hard-deleted'; END IF;
    IF execution_status <> 'IN_PROGRESS' THEN RAISE EXCEPTION 'Terminal workout execution facts are immutable'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_exercise_execution
    BEFORE UPDATE OR DELETE ON exercise_logs
    FOR EACH ROW EXECUTE FUNCTION guard_workout_execution_child();
CREATE TRIGGER guard_set_execution
    BEFORE UPDATE OR DELETE ON set_logs
    FOR EACH ROW EXECUTE FUNCTION guard_workout_execution_child();

CREATE INDEX ix_workout_execution_student_time
    ON workout_session_logs(student_id, performed_start_at DESC, id);
CREATE INDEX ix_workout_execution_status_history
    ON workout_execution_status_history(workout_execution_id, changed_at, id);
CREATE INDEX ix_workout_execution_exercises
    ON exercise_logs(workout_session_log_id, frozen_sequence_number, id);
