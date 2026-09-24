SET search_path TO fitness, public;

-- Enforce at most one outgoing transition per goal (a goal can be replaced at most once).
CREATE UNIQUE INDEX IF NOT EXISTS uq_goal_transitions_previous_goal
    ON goal_transitions(previous_goal_id);

-- Enforce at most one incoming transition per goal (a goal can replace at most one goal).
CREATE UNIQUE INDEX IF NOT EXISTS uq_goal_transitions_new_goal
    ON goal_transitions(new_goal_id);

-- Enforce at most one transition per goal proposal.
CREATE UNIQUE INDEX IF NOT EXISTS uq_goal_transitions_proposal
    ON goal_transitions(proposal_id)
    WHERE proposal_id IS NOT NULL;

-- Append-only business history for goal transitions.
CREATE TRIGGER goal_transitions_append_only
    BEFORE UPDATE OR DELETE ON goal_transitions
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
