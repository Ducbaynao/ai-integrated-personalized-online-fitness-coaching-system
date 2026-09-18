SET search_path TO fitness, public;

CREATE TYPE supervision_requirement AS ENUM ('SELF_ALLOWED', 'COACH_PREFERRED', 'COACH_REQUIRED');
CREATE TYPE recurring_change_scope AS ENUM ('THIS_SESSION_ONLY', 'THIS_AND_FOLLOWING', 'ENTIRE_SERIES');

ALTER TABLE workout_plan_sessions
    ADD COLUMN supervision_requirement supervision_requirement NOT NULL DEFAULT 'SELF_ALLOWED';
ALTER TABLE planned_workouts
    ADD COLUMN supervision_requirement supervision_requirement NOT NULL DEFAULT 'SELF_ALLOWED',
    ADD COLUMN supervision_override_by uuid REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN supervision_override_reason text;
ALTER TABLE reschedule_requests
    ADD COLUMN change_scope recurring_change_scope NOT NULL DEFAULT 'THIS_SESSION_ONLY',
    ADD COLUMN conflict_checked_at timestamptz,
    ADD COLUMN accepted_revalidated_at timestamptz;

CREATE TABLE workout_session_adjustments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    planned_workout_id uuid NOT NULL REFERENCES planned_workouts(id) ON DELETE CASCADE,
    adjustment_type varchar(40) NOT NULL CHECK (adjustment_type IN ('EXERCISE_SWAP','LOAD','REPS','SETS','DURATION','ORDER','NOTE','OTHER')),
    planned_session_exercise_id uuid REFERENCES workout_plan_session_exercises(id) ON DELETE SET NULL,
    before_value jsonb,
    after_value jsonb NOT NULL,
    reason text NOT NULL,
    adjusted_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE nutrition_goal_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    nutrition_goal_id uuid NOT NULL REFERENCES nutrition_goals(id) ON DELETE CASCADE,
    from_status lifecycle_status,
    to_status lifecycle_status NOT NULL,
    changed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    reason text,
    changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE nutrition_proposals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    nutrition_goal_id uuid REFERENCES nutrition_goals(id) ON DELETE CASCADE,
    base_nutrition_goal_version_id uuid REFERENCES nutrition_goal_versions(id) ON DELETE SET NULL,
    source proposal_source NOT NULL,
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    proposed_title varchar(200),
    proposed_goal_type varchar(60),
    proposed_strategy varchar(60),
    proposed_effective_from date,
    reason text NOT NULL,
    status proposal_status NOT NULL DEFAULT 'PENDING',
    decided_by uuid REFERENCES users(id) ON DELETE SET NULL,
    decided_at timestamptz,
    decision_note text,
    expires_at timestamptz,
    source_ai_recommendation_id uuid REFERENCES ai_recommendations(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT nutrition_proposal_decision_ck CHECK ((status = 'PENDING' AND decided_at IS NULL) OR (status <> 'PENDING' AND decided_at IS NOT NULL))
);

CREATE TABLE nutrition_proposal_targets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    nutrition_proposal_id uuid NOT NULL REFERENCES nutrition_proposals(id) ON DELETE CASCADE,
    nutrient_id smallint NOT NULL REFERENCES nutrients(id) ON DELETE RESTRICT,
    target_amount nonnegative_numeric NOT NULL,
    minimum_amount nonnegative_numeric,
    maximum_amount nonnegative_numeric,
    unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    day_type varchar(30) NOT NULL DEFAULT 'ALL' CHECK (day_type IN ('ALL','TRAINING_DAY','REST_DAY')),
    UNIQUE (nutrition_proposal_id, nutrient_id, day_type),
    CHECK (maximum_amount IS NULL OR minimum_amount IS NULL OR maximum_amount >= minimum_amount)
);

ALTER TABLE nutrition_goal_versions
    ADD COLUMN source_proposal_id uuid REFERENCES nutrition_proposals(id) ON DELETE SET NULL,
    ADD COLUMN coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE SET NULL;

CREATE TABLE daily_nutrition_target_overrides (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    nutrition_goal_version_id uuid NOT NULL REFERENCES nutrition_goal_versions(id) ON DELETE CASCADE,
    target_date date NOT NULL,
    nutrient_id smallint NOT NULL REFERENCES nutrients(id) ON DELETE RESTRICT,
    target_amount nonnegative_numeric NOT NULL,
    unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    reason text NOT NULL,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (student_id, target_date, nutrient_id)
);

CREATE TABLE resolved_daily_nutrition_targets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    target_date date NOT NULL,
    nutrition_goal_version_id uuid NOT NULL REFERENCES nutrition_goal_versions(id) ON DELETE RESTRICT,
    nutrient_id smallint NOT NULL REFERENCES nutrients(id) ON DELETE RESTRICT,
    target_amount nonnegative_numeric NOT NULL,
    unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    day_type varchar(30) NOT NULL CHECK (day_type IN ('ALL','TRAINING_DAY','REST_DAY')),
    source_override_id uuid REFERENCES daily_nutrition_target_overrides(id) ON DELETE SET NULL,
    resolved_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (student_id, target_date, nutrient_id)
);

CREATE TABLE daily_nutrition_log_status (
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    log_date date NOT NULL,
    completeness varchar(20) NOT NULL CHECK (completeness IN ('NOT_LOGGED','PARTIAL','COMPLETE')),
    manual_item_count integer NOT NULL DEFAULT 0 CHECK (manual_item_count >= 0),
    estimated_item_count integer NOT NULL DEFAULT 0 CHECK (estimated_item_count >= 0),
    confirmed_item_count integer NOT NULL DEFAULT 0 CHECK (confirmed_item_count >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, log_date)
);

ALTER TABLE food_log_items
    ADD COLUMN estimation_state varchar(20) NOT NULL DEFAULT 'USER_CONFIRMED'
        CHECK (estimation_state IN ('ESTIMATED','USER_CONFIRMED','USER_CORRECTED'));

CREATE TABLE nutrition_review_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    nutrition_goal_id uuid REFERENCES nutrition_goals(id) ON DELETE SET NULL,
    trigger_type varchar(40) NOT NULL CHECK (trigger_type IN ('INACTIVITY','RETURN_TO_TRAINING','GOAL_CHANGE','MEASUREMENT_CHANGE','MANUAL')),
    status varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_REVIEW','RESOLVED','DISMISSED')),
    requested_by uuid REFERENCES users(id) ON DELETE SET NULL,
    assigned_to uuid REFERENCES users(id) ON DELETE SET NULL,
    reason text NOT NULL,
    resolution text,
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE coaching_review_schedules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id uuid NOT NULL REFERENCES coaching_relationships(id) ON DELETE CASCADE,
    cadence_days integer NOT NULL CHECK (cadence_days > 0),
    next_review_at timestamptz NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE coaching_reviews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    review_schedule_id uuid REFERENCES coaching_review_schedules(id) ON DELETE SET NULL,
    relationship_id uuid NOT NULL REFERENCES coaching_relationships(id) ON DELETE CASCADE,
    coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE SET NULL,
    reviewer_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    review_at timestamptz NOT NULL DEFAULT now(),
    outcome varchar(30) NOT NULL CHECK (outcome IN ('NO_CHANGE','FOLLOW_UP','PROPOSAL_CREATED','PLAN_ADJUSTMENT','ESCALATED')),
    summary text NOT NULL,
    missing_data jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER nutrition_goal_history_append_only BEFORE UPDATE OR DELETE ON nutrition_goal_status_history FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER workout_adjustments_append_only BEFORE UPDATE OR DELETE ON workout_session_adjustments FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER trg_nutrition_proposals_updated_at BEFORE UPDATE ON nutrition_proposals FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_nutrition_review_requests_updated_at BEFORE UPDATE ON nutrition_review_requests FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_coaching_review_schedules_updated_at BEFORE UPDATE ON coaching_review_schedules FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_coaching_reviews_updated_at BEFORE UPDATE ON coaching_reviews FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_nutrition_proposals_student_status ON nutrition_proposals(student_id, status, created_at DESC);
CREATE INDEX idx_nutrition_reviews_student_status ON nutrition_review_requests(student_id, status, created_at DESC);
CREATE INDEX idx_coaching_reviews_relationship_time ON coaching_reviews(relationship_id, review_at DESC);

CREATE VIEW current_resolved_nutrition_targets AS
SELECT r.* FROM resolved_daily_nutrition_targets r;
