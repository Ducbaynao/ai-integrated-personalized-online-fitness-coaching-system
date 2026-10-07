SET search_path TO fitness, public;

CREATE TYPE workout_plan_decision_owner AS ENUM ('STUDENT', 'TRAINER');

ALTER TABLE workout_plans
    ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN decision_owner_type workout_plan_decision_owner,
    ADD COLUMN decision_owner_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    ADD COLUMN based_on_plan_id uuid,
    ADD COLUMN based_on_plan_version_id uuid;

UPDATE workout_plans p
SET decision_owner_type = CASE
        WHEN p.created_by = p.student_id THEN 'STUDENT'::workout_plan_decision_owner
        WHEN EXISTS (
            SELECT 1 FROM coaching_periods cp
            WHERE cp.id = p.coaching_period_id
              AND cp.mode = 'HUMAN_COACH'
              AND cp.trainer_id = p.created_by
        )
            THEN 'TRAINER'::workout_plan_decision_owner
    END,
    decision_owner_id = CASE
        WHEN p.created_by = p.student_id THEN p.student_id
        WHEN EXISTS (
            SELECT 1 FROM coaching_periods cp
            WHERE cp.id = p.coaching_period_id
              AND cp.mode = 'HUMAN_COACH'
              AND cp.trainer_id = p.created_by
        ) THEN p.created_by
    END;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM workout_plans
        WHERE decision_owner_type IS NULL OR decision_owner_id IS NULL
    ) THEN
        RAISE EXCEPTION 'V28 cannot infer workout-plan decision ownership unambiguously';
    END IF;
    IF EXISTS (
        SELECT student_id FROM workout_plans
        WHERE status = 'ACTIVE'
        GROUP BY student_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V28 found more than one ACTIVE workout plan for a student';
    END IF;
END;
$$;

ALTER TABLE workout_plans
    ALTER COLUMN decision_owner_type SET NOT NULL,
    ALTER COLUMN decision_owner_id SET NOT NULL,
    ADD CONSTRAINT workout_plan_owner_matches_student_ck CHECK (
        (decision_owner_type = 'STUDENT' AND decision_owner_id = student_id)
        OR decision_owner_type = 'TRAINER'
    ),
    ADD CONSTRAINT workout_plan_lineage_pair_ck CHECK (
        (based_on_plan_id IS NULL) = (based_on_plan_version_id IS NULL)
    ),
    ADD CONSTRAINT workout_plan_not_self_derived_ck CHECK (based_on_plan_id IS NULL OR based_on_plan_id <> id),
    ADD CONSTRAINT uq_workout_plan_id_student UNIQUE (id, student_id);

ALTER TABLE workout_plan_versions
    ADD CONSTRAINT uq_workout_plan_version_id_plan UNIQUE (id, workout_plan_id);

ALTER TABLE workout_plans
    ADD CONSTRAINT workout_plan_based_on_plan_fk
        FOREIGN KEY (based_on_plan_id, student_id)
        REFERENCES workout_plans(id, student_id) ON DELETE RESTRICT,
    ADD CONSTRAINT workout_plan_based_on_version_fk
        FOREIGN KEY (based_on_plan_version_id, based_on_plan_id)
        REFERENCES workout_plan_versions(id, workout_plan_id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_workout_plan_one_active_per_student
    ON workout_plans(student_id) WHERE status = 'ACTIVE';

CREATE OR REPLACE FUNCTION validate_workout_plan_ownership_and_lineage() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE
    period_mode coaching_mode;
    period_trainer uuid;
    source_locked_at timestamptz;
BEGIN
    IF NEW.decision_owner_type = 'TRAINER' THEN
        SELECT mode, trainer_id INTO period_mode, period_trainer
        FROM coaching_periods WHERE id = NEW.coaching_period_id;
        IF period_mode IS DISTINCT FROM 'HUMAN_COACH' OR period_trainer IS DISTINCT FROM NEW.decision_owner_id THEN
            RAISE EXCEPTION 'Trainer-owned workout plan requires its matching HUMAN_COACH period';
        END IF;
    END IF;
    IF NEW.based_on_plan_version_id IS NOT NULL THEN
        SELECT locked_at INTO source_locked_at
        FROM workout_plan_versions
        WHERE id = NEW.based_on_plan_version_id AND workout_plan_id = NEW.based_on_plan_id;
        IF source_locked_at IS NULL THEN
            RAISE EXCEPTION 'Workout plan successor must reference a locked source version';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validate_workout_plan_ownership_and_lineage
    BEFORE INSERT OR UPDATE OF decision_owner_type, decision_owner_id, coaching_period_id,
        based_on_plan_id, based_on_plan_version_id ON workout_plans
    FOR EACH ROW EXECUTE FUNCTION validate_workout_plan_ownership_and_lineage();

CREATE TABLE workout_plan_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workout_plan_id uuid NOT NULL REFERENCES workout_plans(id) ON DELETE RESTRICT,
    from_status plan_status,
    to_status plan_status NOT NULL,
    changed_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reason text NOT NULL,
    plan_version bigint NOT NULL CHECK (plan_version >= 0),
    changed_at timestamptz NOT NULL
);

INSERT INTO workout_plan_status_history (
    workout_plan_id, from_status, to_status, changed_by, reason, plan_version, changed_at
)
SELECT id, NULL, status, created_by, 'MIGRATED_BASELINE', version, created_at
FROM workout_plans;

CREATE OR REPLACE FUNCTION protect_workout_plan_status_history() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    RAISE EXCEPTION 'Workout plan status history is append-only';
END;
$$;
CREATE TRIGGER workout_plan_status_history_append_only
    BEFORE UPDATE OR DELETE ON workout_plan_status_history
    FOR EACH ROW EXECUTE FUNCTION protect_workout_plan_status_history();

CREATE OR REPLACE FUNCTION guard_workout_plan_aggregate() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Workout plans cannot be hard-deleted';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Workout plan aggregate version must increment exactly once';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.student_id IS DISTINCT FROM OLD.student_id
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.source IS DISTINCT FROM OLD.source
       OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
       OR NEW.ownership_transferred_to_student_at IS DISTINCT FROM OLD.ownership_transferred_to_student_at
       OR NEW.decision_owner_type IS DISTINCT FROM OLD.decision_owner_type
       OR NEW.decision_owner_id IS DISTINCT FROM OLD.decision_owner_id
       OR NEW.based_on_plan_id IS DISTINCT FROM OLD.based_on_plan_id
       OR NEW.based_on_plan_version_id IS DISTINCT FROM OLD.based_on_plan_version_id THEN
        RAISE EXCEPTION 'Workout plan identity, ownership, and lineage are immutable';
    END IF;
    IF OLD.status <> 'DRAFT' AND (
       NEW.fitness_goal_id IS DISTINCT FROM OLD.fitness_goal_id
       OR NEW.coaching_period_id IS DISTINCT FROM OLD.coaching_period_id
       OR NEW.source_template_id IS DISTINCT FROM OLD.source_template_id
       OR NEW.name IS DISTINCT FROM OLD.name
       OR NEW.description IS DISTINCT FROM OLD.description
       OR NEW.assigned_by IS DISTINCT FROM OLD.assigned_by
       OR NEW.assigned_at IS DISTINCT FROM OLD.assigned_at) THEN
        RAISE EXCEPTION 'Published workout plan authoring metadata is immutable';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT (
            (OLD.status = 'DRAFT' AND NEW.status IN ('ACTIVE', 'ARCHIVED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status IN ('PAUSED', 'COMPLETED', 'ARCHIVED'))
            OR (OLD.status = 'PAUSED' AND NEW.status IN ('ACTIVE', 'COMPLETED', 'ARCHIVED'))
            OR (OLD.status = 'COMPLETED' AND NEW.status = 'ARCHIVED')
        ) THEN
            RAISE EXCEPTION 'Invalid workout plan lifecycle transition';
        END IF;
    END IF;
    IF OLD.status = 'ARCHIVED' THEN
        RAISE EXCEPTION 'Archived workout plan is terminal';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_workout_plan_aggregate
    BEFORE UPDATE OR DELETE ON workout_plans
    FOR EACH ROW EXECUTE FUNCTION guard_workout_plan_aggregate();

ALTER TABLE planned_workouts
    ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0);

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
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_planned_workout_occurrence
    BEFORE UPDATE OR DELETE ON planned_workouts
    FOR EACH ROW EXECUTE FUNCTION guard_planned_workout_occurrence();

ALTER TABLE workout_session_adjustments
    ADD COLUMN replacement_exercise_variation_id uuid
        REFERENCES exercise_variations(id) ON DELETE RESTRICT;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM workout_session_adjustments
        WHERE adjustment_type = 'EXERCISE_SWAP'
          AND replacement_exercise_variation_id IS NULL
    ) THEN
        RAISE EXCEPTION 'V28 found EXERCISE_SWAP adjustment without replacement variation';
    END IF;
END;
$$;

ALTER TABLE workout_session_adjustments
    ADD CONSTRAINT workout_adjustment_replacement_ck CHECK (
        (adjustment_type = 'EXERCISE_SWAP'
            AND planned_session_exercise_id IS NOT NULL
            AND replacement_exercise_variation_id IS NOT NULL)
        OR
        (adjustment_type <> 'EXERCISE_SWAP'
            AND replacement_exercise_variation_id IS NULL)
    );

CREATE OR REPLACE FUNCTION validate_workout_adjustment_occurrence_scope() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE
    occurrence_session_id uuid;
    prescription_session_id uuid;
BEGIN
    IF NEW.planned_session_exercise_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT workout_plan_session_id INTO occurrence_session_id
    FROM planned_workouts WHERE id = NEW.planned_workout_id;
    SELECT workout_plan_session_id INTO prescription_session_id
    FROM workout_plan_session_exercises WHERE id = NEW.planned_session_exercise_id;
    IF occurrence_session_id IS NULL OR prescription_session_id IS DISTINCT FROM occurrence_session_id THEN
        RAISE EXCEPTION 'Workout adjustment prescription must belong to the materialized occurrence session';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validate_workout_adjustment_occurrence_scope
    BEFORE INSERT ON workout_session_adjustments
    FOR EACH ROW EXECUTE FUNCTION validate_workout_adjustment_occurrence_scope();

CREATE TABLE workout_plan_command_receipts (
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key varchar(120) NOT NULL,
    command_name varchar(80) NOT NULL,
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    workout_plan_id uuid REFERENCES workout_plans(id) ON DELETE RESTRICT,
    workout_plan_version_id uuid REFERENCES workout_plan_versions(id) ON DELETE RESTRICT,
    planned_workout_id uuid REFERENCES planned_workouts(id) ON DELETE RESTRICT,
    resulting_status plan_status,
    resulting_version bigint,
    effective_at timestamptz,
    response_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id, command_key)
);

CREATE INDEX ix_workout_plan_status_history_plan_time
    ON workout_plan_status_history(workout_plan_id, changed_at, id);
CREATE INDEX ix_workout_plan_lineage ON workout_plans(based_on_plan_id, based_on_plan_version_id)
    WHERE based_on_plan_id IS NOT NULL;

