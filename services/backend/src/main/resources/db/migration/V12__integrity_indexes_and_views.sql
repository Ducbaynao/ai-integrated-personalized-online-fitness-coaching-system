SET search_path TO fitness, public;

ALTER TABLE users DROP CONSTRAINT users_password_or_external_identity_ck;

ALTER TABLE workout_plan_versions
    DROP CONSTRAINT workout_plan_versions_change_level_check;

ALTER TABLE workout_plan_versions
    ADD CONSTRAINT workout_plan_versions_change_level_check
    CHECK (change_level IN ('INITIAL', 'MAJOR', 'AI_ACCEPTED'));

CREATE OR REPLACE FUNCTION validate_coaching_period()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fitness, public
AS $$
DECLARE
    rel coaching_relationships%ROWTYPE;
BEGIN
    IF NEW.mode = 'HUMAN_COACH' THEN
        SELECT * INTO rel FROM coaching_relationships WHERE id = NEW.coaching_relationship_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'HUMAN_COACH period requires an existing coaching relationship';
        END IF;
        IF rel.student_id <> NEW.student_id OR rel.trainer_id <> NEW.trainer_id THEN
            RAISE EXCEPTION 'Coaching period participants do not match relationship participants';
        END IF;
        IF rel.status NOT IN ('ACTIVE', 'PAUSED', 'ENDED') THEN
            RAISE EXCEPTION 'Relationship status % cannot back a coaching period', rel.status;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_coaching_period
    BEFORE INSERT OR UPDATE ON coaching_periods
    FOR EACH ROW EXECUTE FUNCTION validate_coaching_period();

CREATE OR REPLACE FUNCTION validate_data_sharing_permission()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fitness, public
AS $$
DECLARE
    rel coaching_relationships%ROWTYPE;
BEGIN
    SELECT * INTO rel FROM coaching_relationships WHERE id = NEW.relationship_id;
    IF NOT FOUND OR rel.student_id <> NEW.student_id OR rel.trainer_id <> NEW.trainer_id THEN
        RAISE EXCEPTION 'Data-sharing participants must match the coaching relationship';
    END IF;
    IF NEW.granted_by <> NEW.student_id THEN
        RAISE EXCEPTION 'Only the student can grant or deny data-sharing permission';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_data_sharing_permission
    BEFORE INSERT OR UPDATE ON data_sharing_permissions
    FOR EACH ROW EXECUTE FUNCTION validate_data_sharing_permission();

CREATE OR REPLACE FUNCTION validate_goal_transition()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fitness, public
AS $$
DECLARE
    previous_student uuid;
    new_student uuid;
BEGIN
    SELECT student_id INTO previous_student FROM fitness_goals WHERE id = NEW.previous_goal_id;
    SELECT student_id INTO new_student FROM fitness_goals WHERE id = NEW.new_goal_id;
    IF previous_student IS DISTINCT FROM new_student THEN
        RAISE EXCEPTION 'Goal transition must remain within one student';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_goal_transition
    BEFORE INSERT OR UPDATE ON goal_transitions
    FOR EACH ROW EXECUTE FUNCTION validate_goal_transition();

CREATE OR REPLACE FUNCTION validate_measurement_value()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fitness, public
AS $$
DECLARE
    metric metric_definitions%ROWTYPE;
    expected_dimension varchar(40);
    selected_dimension varchar(40);
BEGIN
    SELECT * INTO metric FROM metric_definitions WHERE id = NEW.metric_definition_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Unknown metric definition';
    END IF;
    IF metric.value_type = 'NUMERIC' AND NEW.numeric_value IS NULL THEN
        RAISE EXCEPTION 'Numeric metric requires numeric_value';
    ELSIF metric.value_type = 'TEXT' AND NEW.text_value IS NULL THEN
        RAISE EXCEPTION 'Text metric requires text_value';
    ELSIF metric.value_type = 'BOOLEAN' AND NEW.boolean_value IS NULL THEN
        RAISE EXCEPTION 'Boolean metric requires boolean_value';
    END IF;
    IF NEW.numeric_value IS NOT NULL AND metric.valid_min IS NOT NULL AND NEW.numeric_value < metric.valid_min THEN
        NEW.validation_status := 'SUSPECT';
    END IF;
    IF NEW.numeric_value IS NOT NULL AND metric.valid_max IS NOT NULL AND NEW.numeric_value > metric.valid_max THEN
        NEW.validation_status := 'SUSPECT';
    END IF;
    IF NEW.numeric_value IS NOT NULL AND metric.default_unit_id IS NOT NULL THEN
        SELECT dimension INTO expected_dimension FROM measurement_units WHERE id = metric.default_unit_id;
        SELECT dimension INTO selected_dimension FROM measurement_units WHERE id = NEW.unit_id;
        IF selected_dimension IS DISTINCT FROM expected_dimension THEN
            RAISE EXCEPTION 'Measurement unit is incompatible with its metric';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_measurement_value
    BEFORE INSERT OR UPDATE ON measurements
    FOR EACH ROW EXECUTE FUNCTION validate_measurement_value();

CREATE OR REPLACE FUNCTION validate_goal_target_unit()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fitness, public
AS $$
DECLARE
    metric_unit smallint;
BEGIN
    SELECT default_unit_id INTO metric_unit FROM metric_definitions WHERE id = NEW.metric_definition_id;
    IF metric_unit IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM measurement_units selected
        JOIN measurement_units expected ON expected.id = metric_unit
        WHERE selected.id = NEW.unit_id AND selected.dimension = expected.dimension
    ) THEN
        RAISE EXCEPTION 'Goal target unit is incompatible with its metric';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_goal_target_unit
    BEFORE INSERT OR UPDATE ON goal_targets
    FOR EACH ROW EXECUTE FUNCTION validate_goal_target_unit();

CREATE TRIGGER trg_validate_goal_proposal_target_unit
    BEFORE INSERT OR UPDATE ON goal_proposal_targets
    FOR EACH ROW EXECUTE FUNCTION validate_goal_target_unit();

-- Standard updated_at maintenance.
DO $$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'users', 'user_devices', 'user_settings', 'student_profiles', 'trainer_profiles',
        'metric_definitions', 'exercises', 'exercise_variations', 'student_exercise_preferences', 'foods',
        'coaching_relationships', 'data_sharing_permissions', 'fitness_goals', 'goal_proposals',
        'workout_templates', 'workout_plans', 'planned_workouts', 'workout_session_logs', 'set_logs',
        'recurring_schedules', 'appointments', 'reschedule_requests', 'integration_connections',
        'return_to_training_assessments', 'nutrition_goals', 'meals', 'food_log_items',
        'conversations', 'notification_preferences', 'attention_signals', 'knowledge_documents',
        'ai_recommendations', 'ai_threads', 'trainer_verification_requests', 'user_reports',
        'trainer_packages', 'subscriptions', 'payments', 'trainer_reviews', 'organizations'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE ON fitness.%I FOR EACH ROW EXECUTE FUNCTION fitness.set_updated_at()',
            'trg_' || table_name || '_updated_at', table_name
        );
    END LOOP;
END;
$$;

-- Append-only business history.
CREATE TRIGGER coaching_relationship_history_append_only
    BEFORE UPDATE OR DELETE ON coaching_relationship_status_history
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE TRIGGER fitness_goal_history_append_only
    BEFORE UPDATE OR DELETE ON fitness_goal_status_history
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE TRIGGER appointment_history_append_only
    BEFORE UPDATE OR DELETE ON appointment_history
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE TRIGGER measurement_validation_history_append_only
    BEFORE UPDATE OR DELETE ON measurement_validation_events
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE TRIGGER payment_transaction_append_only
    BEFORE UPDATE OR DELETE ON payment_transactions
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE TRIGGER admin_actions_append_only
    BEFORE UPDATE OR DELETE ON admin_actions
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

-- Identity and authorization indexes.
CREATE INDEX idx_user_roles_active ON user_roles(user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_tokens_user_expiry ON refresh_tokens(user_id, expires_at) WHERE revoked_at IS NULL;
CREATE INDEX idx_users_status ON users(status) WHERE deleted_at IS NULL;
CREATE INDEX idx_trainer_profiles_discovery ON trainer_profiles(verification_status, is_accepting_students) WHERE is_active;

-- Coaching and goal indexes.
CREATE INDEX idx_coaching_relationships_student_status ON coaching_relationships(student_id, status);
CREATE INDEX idx_coaching_relationships_trainer_status ON coaching_relationships(trainer_id, status);
CREATE INDEX idx_coaching_periods_student_started ON coaching_periods(student_id, started_at DESC);
CREATE INDEX idx_data_sharing_lookup ON data_sharing_permissions(relationship_id, data_scope, valid_from DESC);
CREATE INDEX idx_fitness_goals_student_status ON fitness_goals(student_id, status) WHERE deleted_at IS NULL;
CREATE INDEX idx_goal_versions_goal_effective ON fitness_goal_versions(fitness_goal_id, effective_from DESC);
CREATE INDEX idx_goal_proposals_student_status ON goal_proposals(student_id, status, created_at DESC);

-- Workout and schedule indexes.
CREATE INDEX idx_workout_plans_student_status ON workout_plans(student_id, status) WHERE deleted_at IS NULL;
CREATE INDEX idx_planned_workouts_student_time ON planned_workouts(student_id, planned_start_at DESC);
CREATE INDEX idx_planned_workouts_pending ON planned_workouts(planned_start_at) WHERE status = 'SCHEDULED';
CREATE INDEX idx_workout_logs_student_performed ON workout_session_logs(student_id, performed_start_at DESC);
CREATE INDEX idx_exercise_logs_session ON exercise_logs(workout_session_log_id, sequence_number);
CREATE INDEX idx_set_logs_exercise ON set_logs(exercise_log_id, set_number);
CREATE INDEX idx_appointments_student_time ON appointments(student_id, start_at DESC);
CREATE INDEX idx_appointments_trainer_time ON appointments(trainer_id, start_at DESC) WHERE trainer_id IS NOT NULL;

-- Measurement and progress indexes.
CREATE INDEX idx_measurements_student_metric_time ON measurements(student_id, metric_definition_id, measured_at DESC);
CREATE INDEX idx_measurements_valid_current ON measurements(student_id, metric_definition_id, measured_at DESC)
    WHERE validation_status = 'VALID' AND superseded_by_id IS NULL;
CREATE INDEX idx_measurement_batches_student_time ON measurement_batches(student_id, measured_at DESC);
CREATE INDEX idx_training_periods_student_time ON training_activity_periods(student_id, started_at DESC);
CREATE INDEX idx_progress_snapshots_student_period ON progress_snapshots(student_id, period_end DESC);
CREATE INDEX idx_daily_training_student_date ON daily_training_aggregates(student_id, activity_date DESC);

-- Nutrition, communication, AI, and audit indexes.
CREATE INDEX idx_meals_student_consumed ON meals(student_id, consumed_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_food_log_items_meal ON food_log_items(meal_id);
CREATE INDEX idx_messages_conversation_time ON messages(conversation_id, sent_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_notifications_recipient_unread ON notifications(recipient_user_id, created_at DESC) WHERE read_at IS NULL;
CREATE INDEX idx_attention_signals_trainer_open ON attention_signals(trainer_id, severity, created_at DESC) WHERE status = 'OPEN';
CREATE INDEX idx_outbox_events_pending ON outbox_events(occurred_at) WHERE published_at IS NULL;
CREATE INDEX idx_ai_runs_student_time ON ai_runs(student_id, created_at DESC);
CREATE INDEX idx_ai_recommendations_student_status ON ai_recommendations(student_id, status, created_at DESC);
CREATE INDEX idx_knowledge_versions_active ON knowledge_versions(status, activated_at DESC);
CREATE INDEX idx_audit_logs_target ON audit_logs(target_type, target_id, occurred_at DESC);
CREATE INDEX idx_audit_logs_actor_time ON audit_logs(actor_user_id, occurred_at DESC);

CREATE VIEW current_fitness_goal_versions AS
SELECT DISTINCT ON (v.fitness_goal_id)
       v.*
FROM fitness_goal_versions v
JOIN fitness_goals g ON g.id = v.fitness_goal_id
WHERE v.effective_until IS NULL
  AND g.deleted_at IS NULL
ORDER BY v.fitness_goal_id, v.version_number DESC;

CREATE VIEW current_workout_plan_versions AS
SELECT DISTINCT ON (v.workout_plan_id)
       v.*
FROM workout_plan_versions v
JOIN workout_plans p ON p.id = v.workout_plan_id
WHERE v.effective_until IS NULL
  AND p.deleted_at IS NULL
ORDER BY v.workout_plan_id, v.version_number DESC;

CREATE VIEW active_coaching_periods AS
SELECT *
FROM coaching_periods
WHERE ended_at IS NULL;

CREATE VIEW current_valid_measurements AS
SELECT DISTINCT ON (m.student_id, m.metric_definition_id)
       m.*
FROM measurements m
WHERE m.validation_status = 'VALID'
  AND m.superseded_by_id IS NULL
ORDER BY m.student_id, m.metric_definition_id, m.measured_at DESC, m.created_at DESC;

CREATE VIEW workout_session_volume AS
SELECT wsl.id AS workout_session_log_id,
       wsl.student_id,
       sl.load_unit_id,
       SUM(COALESCE(sl.load_value, 0) * COALESCE(sl.repetitions, 0)) AS total_volume,
       COUNT(*) FILTER (WHERE sl.completion_status = 'COMPLETED') AS completed_sets
FROM workout_session_logs wsl
JOIN exercise_logs el ON el.workout_session_log_id = wsl.id
JOIN set_logs sl ON sl.exercise_log_id = el.id
WHERE sl.completion_status = 'COMPLETED'
GROUP BY wsl.id, wsl.student_id, sl.load_unit_id;

CREATE VIEW daily_nutrition_intake AS
SELECT m.student_id,
       (m.consumed_at AT TIME ZONE u.timezone)::date AS local_date,
       n.code AS nutrient_code,
       fn.unit_id,
       SUM(fn.amount) AS consumed_amount
FROM meals m
JOIN users u ON u.id = m.student_id
JOIN food_log_items fi ON fi.meal_id = m.id
JOIN food_log_item_nutrients fn ON fn.food_log_item_id = fi.id
JOIN nutrients n ON n.id = fn.nutrient_id
WHERE m.deleted_at IS NULL
GROUP BY m.student_id, (m.consumed_at AT TIME ZONE u.timezone)::date, n.code, fn.unit_id;
