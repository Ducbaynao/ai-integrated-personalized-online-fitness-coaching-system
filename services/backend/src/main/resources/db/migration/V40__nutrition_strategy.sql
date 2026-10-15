SET search_path TO fitness, public;

-- V7 stored dates without an executable inclusive/exclusive convention. Preserve those
-- rows as LEGACY; only newly confirmed strategy versions use half-open dates.
ALTER TYPE data_scope_code ADD VALUE IF NOT EXISTS 'NUTRITION_GOAL';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM nutrition_goals WHERE status = 'ACTIVE' AND deleted_at IS NULL
               GROUP BY student_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'V40 found multiple legacy ACTIVE nutrition goals for a Student; reconcile with audit first';
    END IF;
END $$;
CREATE UNIQUE INDEX uq_nutrition_active_goal_student ON nutrition_goals(student_id)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;

ALTER TABLE nutrition_goals
    ADD COLUMN aggregate_version bigint NOT NULL DEFAULT 0 CHECK (aggregate_version >= 0),
    ADD COLUMN calendar_timezone varchar(64),
    ADD COLUMN strategy_managed boolean NOT NULL DEFAULT false;
ALTER TABLE nutrition_goal_versions
    ADD COLUMN date_semantics varchar(12) NOT NULL DEFAULT 'LEGACY'
        CHECK (date_semantics IN ('LEGACY','HALF_OPEN')),
    ADD COLUMN calendar_timezone varchar(64),
    ADD CONSTRAINT nutrition_half_open_dates_ck CHECK
        (date_semantics <> 'HALF_OPEN' OR effective_until IS NULL OR effective_until > effective_from);

ALTER TABLE nutrition_proposals
    ADD COLUMN proposal_kind varchar(20) CHECK (proposal_kind IN ('NEW_GOAL','CHANGE_TARGET')),
    ADD COLUMN proposal_version bigint NOT NULL DEFAULT 0 CHECK (proposal_version >= 0),
    ADD COLUMN base_goal_aggregate_version bigint CHECK (base_goal_aggregate_version IS NULL OR base_goal_aggregate_version >= 0),
    ADD COLUMN coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE SET NULL,
    ADD COLUMN calendar_timezone varchar(64),
    ADD COLUMN decided_goal_id uuid REFERENCES nutrition_goals(id) ON DELETE SET NULL,
    ADD COLUMN decided_goal_version_id uuid REFERENCES nutrition_goal_versions(id) ON DELETE SET NULL;

ALTER TABLE daily_nutrition_target_overrides
    ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE TABLE nutrition_override_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    override_id uuid NOT NULL REFERENCES daily_nutrition_target_overrides(id) ON DELETE RESTRICT,
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    from_version bigint,
    to_version bigint NOT NULL,
    before_json jsonb,
    after_json jsonb NOT NULL,
    changed_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (override_id,to_version)
);
CREATE TRIGGER nutrition_override_history_append_only BEFORE UPDATE OR DELETE ON nutrition_override_history
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

-- Explicit REST_DAY selection; no-workout is DEFAULT, not inferred rest.
CREATE TABLE nutrition_day_type_choices (
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    target_date date NOT NULL,
    calendar_timezone varchar(64) NOT NULL,
    day_type varchar(12) NOT NULL CHECK (day_type = 'REST_DAY'),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    chosen_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    chosen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (student_id,target_date)
);
CREATE TABLE nutrition_day_type_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    target_date date NOT NULL,
    calendar_timezone varchar(64) NOT NULL,
    before_type varchar(12),
    after_type varchar(12),
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER nutrition_day_type_history_append_only BEFORE UPDATE OR DELETE ON nutrition_day_type_history
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

-- Header also freezes NO_TARGET, so a later Goal cannot retroactively target a logged day.
CREATE TABLE nutrition_target_day_snapshots (
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    target_date date NOT NULL,
    nutrition_goal_version_id uuid REFERENCES nutrition_goal_versions(id) ON DELETE RESTRICT,
    calendar_timezone varchar(64) NOT NULL,
    resolved_day_type varchar(12) NOT NULL CHECK (resolved_day_type IN ('DEFAULT','TRAINING_DAY','REST_DAY')),
    trigger_kind varchar(18) NOT NULL CHECK (trigger_kind IN ('FIRST_LOG','DAY_CONFIRM')),
    frozen_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    frozen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (student_id,target_date)
);
ALTER TABLE resolved_daily_nutrition_targets
    ADD COLUMN source_target_id uuid REFERENCES daily_nutrition_targets(id) ON DELETE SET NULL;
CREATE TRIGGER nutrition_resolved_targets_append_only BEFORE UPDATE OR DELETE ON resolved_daily_nutrition_targets
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER nutrition_target_day_snapshots_append_only BEFORE UPDATE OR DELETE ON nutrition_target_day_snapshots
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE INDEX idx_nutrition_strategy_proposals_student ON nutrition_proposals(student_id,created_at DESC,id DESC)
    WHERE proposal_kind IS NOT NULL;
CREATE INDEX idx_nutrition_strategy_proposals_trainer ON nutrition_proposals(created_by,student_id,created_at DESC,id DESC)
    WHERE proposal_kind IS NOT NULL;
