SET search_path TO fitness, public;

-- 1. Add FITNESS_GOAL data scope for coaching permissions
ALTER TYPE data_scope_code ADD VALUE IF NOT EXISTS 'FITNESS_GOAL';

-- 2. Versioned title on fitness_goal_versions to preserve title history across versions
ALTER TABLE fitness_goal_versions ADD COLUMN title varchar(200);
UPDATE fitness_goal_versions fgv
SET title = fg.title
FROM fitness_goals fg
WHERE fgv.fitness_goal_id = fg.id;
ALTER TABLE fitness_goal_versions ALTER COLUMN title SET NOT NULL;

-- 3. Proposal status history table for tracking proposal lifecycle transitions
CREATE TABLE goal_proposal_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_proposal_id uuid NOT NULL REFERENCES goal_proposals(id) ON DELETE CASCADE,
    from_status proposal_status,
    to_status proposal_status NOT NULL,
    changed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    reason text,
    changed_at timestamptz NOT NULL DEFAULT now()
);

-- Append-only trigger for proposal status history
CREATE TRIGGER goal_proposal_history_append_only
    BEFORE UPDATE OR DELETE ON goal_proposal_status_history
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

-- 4. Enforce metric uniqueness within a goal proposal's targets
CREATE UNIQUE INDEX IF NOT EXISTS uq_goal_proposal_target_metric
    ON goal_proposal_targets(goal_proposal_id, metric_definition_id);

-- 5. Query indexes for student proposal listing and lookups
CREATE INDEX IF NOT EXISTS idx_goal_proposals_student_created
    ON goal_proposals(student_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_goal_proposals_fitness_goal
    ON goal_proposals(fitness_goal_id);

CREATE INDEX IF NOT EXISTS idx_goal_proposals_status
    ON goal_proposals(status);

-- 6. Add notes to goal_proposal_objectives to preserve objective notes in proposals
ALTER TABLE goal_proposal_objectives ADD COLUMN IF NOT EXISTS notes text;
