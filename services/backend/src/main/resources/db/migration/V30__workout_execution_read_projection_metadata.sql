SET search_path TO fitness, public;

ALTER TABLE workout_session_logs
    ADD COLUMN source_workout_plan_id uuid REFERENCES workout_plans(id) ON DELETE RESTRICT,
    ADD COLUMN frozen_planned_start_at timestamptz,
    ADD COLUMN frozen_planned_end_at timestamptz,
    ADD COLUMN frozen_original_planned_start_at timestamptz,
    ADD COLUMN frozen_supervision_requirement supervision_requirement,
    ADD COLUMN snapshot_contract_version smallint NOT NULL DEFAULT 0,
    ADD CONSTRAINT workout_execution_new_frozen_metadata_ck CHECK (
        snapshot_contract_version = 0 OR
        (snapshot_contract_version = 1 AND snapshot_mode = 'FROZEN'
         AND source_workout_plan_id IS NOT NULL
         AND frozen_planned_start_at IS NOT NULL
         AND frozen_original_planned_start_at IS NOT NULL
         AND frozen_supervision_requirement IS NOT NULL)
    ),
    ADD CONSTRAINT workout_execution_snapshot_contract_version_ck
        CHECK (snapshot_contract_version IN (0, 1));

-- Existing V29 FROZEN rows predate these columns and cannot be backfilled without
-- fabricating a historical snapshot. Version 0 preserves those rows. All inserts
-- after V30 must use the complete version-1 snapshot contract.
CREATE OR REPLACE FUNCTION require_workout_execution_snapshot_v30() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF NEW.snapshot_mode = 'FROZEN' AND NEW.snapshot_contract_version <> 1 THEN
        RAISE EXCEPTION 'New frozen workout executions require snapshot contract version 1';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER require_workout_execution_snapshot_v30
    BEFORE INSERT ON workout_session_logs
    FOR EACH ROW EXECUTE FUNCTION require_workout_execution_snapshot_v30();

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
       OR NEW.source_workout_plan_id IS DISTINCT FROM OLD.source_workout_plan_id
       OR NEW.frozen_planned_start_at IS DISTINCT FROM OLD.frozen_planned_start_at
       OR NEW.frozen_planned_end_at IS DISTINCT FROM OLD.frozen_planned_end_at
       OR NEW.frozen_original_planned_start_at IS DISTINCT FROM OLD.frozen_original_planned_start_at
       OR NEW.frozen_supervision_requirement IS DISTINCT FROM OLD.frozen_supervision_requirement
       OR NEW.snapshot_contract_version IS DISTINCT FROM OLD.snapshot_contract_version
       OR NEW.logged_by IS DISTINCT FROM OLD.logged_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Workout execution source identity and snapshot are immutable';
    END IF;
    IF NEW.status NOT IN ('IN_PROGRESS','COMPLETED','PARTIALLY_COMPLETED','ABORTED') THEN
        RAISE EXCEPTION 'Invalid workout execution transition';
    END IF;
    RETURN NEW;
END;
$$;
