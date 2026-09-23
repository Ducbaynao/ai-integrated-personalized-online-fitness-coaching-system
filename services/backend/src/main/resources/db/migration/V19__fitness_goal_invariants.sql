SET search_path TO fitness, public;

-- Enforce at most one active fitness goal per student concurrently.
CREATE UNIQUE INDEX uq_student_active_fitness_goal
    ON fitness_goals(student_id)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;

-- Enforce metric uniqueness within a goal version's targets.
CREATE UNIQUE INDEX uq_goal_target_metric
    ON goal_targets(goal_version_id, metric_definition_id);

-- Fix false-positive in validate_active_parent_version_lock where NOT FOUND was evaluated
-- on dynamic EXECUTE INTO (which does not set FOUND in PL/pgSQL).
CREATE OR REPLACE FUNCTION validate_active_parent_version_lock() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE
    current_lock timestamptz;
BEGIN
    IF NEW.status::text NOT IN ('ACTIVE','PAUSED') THEN
        RETURN NULL;
    END IF;
    EXECUTE format('SELECT locked_at FROM fitness.%I WHERE %I=$1 AND effective_until IS NULL', TG_ARGV[0], TG_ARGV[1])
        INTO current_lock USING NEW.id;
    IF current_lock IS NULL THEN
        RAISE EXCEPTION 'Active/paused parent requires one locked current version';
    END IF;
    RETURN NULL;
END; $$;

