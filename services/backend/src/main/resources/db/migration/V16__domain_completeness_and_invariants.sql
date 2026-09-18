SET search_path TO fitness, public;

CREATE TYPE metric_requirement_level AS ENUM ('REQUIRED','RECOMMENDED','OPTIONAL');
CREATE TYPE measurement_checkin_status AS ENUM ('DRAFT','REQUESTED','IN_PROGRESS','COMPLETED','OVERDUE','CANCELLED');
CREATE TYPE measurement_requirement_status AS ENUM ('PENDING','CAPTURED','SKIPPED','UNAVAILABLE');
CREATE TYPE measurement_deduplication_status AS ENUM ('OPEN','AUTO_RESOLVED','REVIEW_REQUIRED','RESOLVED','DISMISSED');
CREATE TYPE measurement_member_disposition AS ENUM ('CANDIDATE','CANONICAL','DUPLICATE','CONFLICT','REJECTED');
CREATE TYPE measurement_resolution_decision AS ENUM ('KEEP_ONE','KEEP_ALL','MERGE','DISMISS');
CREATE TYPE exercise_guidance_type AS ENUM ('COMMON_MISTAKE','COACHING_CUE','SAFETY_NOTE','REGRESSION','PROGRESSION');
CREATE TYPE version_lock_reason AS ENUM ('APPROVED','ACTIVATED','SUPERSEDED','MIGRATED');

CREATE TABLE nutrition_goal_types (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    description text,
    is_active boolean NOT NULL DEFAULT true
);
ALTER TABLE nutrition_goal_versions ADD COLUMN nutrition_goal_type_id smallint REFERENCES nutrition_goal_types(id) ON DELETE RESTRICT;
ALTER TABLE nutrition_proposals ADD COLUMN proposed_goal_type_id smallint REFERENCES nutrition_goal_types(id) ON DELETE RESTRICT;

ALTER TABLE daily_nutrition_targets DROP CONSTRAINT daily_nutrition_targets_day_type_check;
ALTER TABLE nutrition_proposal_targets DROP CONSTRAINT nutrition_proposal_targets_day_type_check;
ALTER TABLE resolved_daily_nutrition_targets DROP CONSTRAINT resolved_daily_nutrition_targets_day_type_check;
UPDATE daily_nutrition_targets SET day_type = 'DEFAULT' WHERE day_type = 'ALL';
UPDATE nutrition_proposal_targets SET day_type = 'DEFAULT' WHERE day_type = 'ALL';
UPDATE resolved_daily_nutrition_targets SET day_type = 'DEFAULT' WHERE day_type = 'ALL';
ALTER TABLE daily_nutrition_targets ADD CONSTRAINT daily_nutrition_targets_day_type_check CHECK (day_type IN ('DEFAULT','TRAINING_DAY','REST_DAY'));
ALTER TABLE nutrition_proposal_targets ADD CONSTRAINT nutrition_proposal_targets_day_type_check CHECK (day_type IN ('DEFAULT','TRAINING_DAY','REST_DAY'));
ALTER TABLE resolved_daily_nutrition_targets ADD CONSTRAINT resolved_daily_nutrition_targets_day_type_check CHECK (day_type IN ('DEFAULT','TRAINING_DAY','REST_DAY'));
ALTER TABLE daily_nutrition_targets ALTER COLUMN day_type SET DEFAULT 'DEFAULT';
ALTER TABLE nutrition_proposal_targets ALTER COLUMN day_type SET DEFAULT 'DEFAULT';

CREATE TABLE goal_type_metric_requirements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_type_id smallint NOT NULL REFERENCES goal_types(id) ON DELETE CASCADE,
    metric_definition_id integer NOT NULL REFERENCES metric_definitions(id) ON DELETE RESTRICT,
    requirement_level metric_requirement_level NOT NULL,
    baseline_required boolean NOT NULL DEFAULT false,
    suggested_interval_days integer CHECK (suggested_interval_days IS NULL OR suggested_interval_days > 0),
    rationale text,
    valid_from date NOT NULL DEFAULT CURRENT_DATE,
    valid_until date,
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_until IS NULL OR valid_until >= valid_from)
);
CREATE UNIQUE INDEX uq_current_goal_type_metric_requirement ON goal_type_metric_requirements(goal_type_id, metric_definition_id) WHERE valid_until IS NULL;

CREATE TABLE exercise_tags (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(80) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    description text,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE exercise_tag_assignments (
    exercise_id uuid NOT NULL REFERENCES exercises(id) ON DELETE CASCADE,
    exercise_tag_id smallint NOT NULL REFERENCES exercise_tags(id) ON DELETE RESTRICT,
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (exercise_id, exercise_tag_id)
);
CREATE TABLE exercise_guidance (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    exercise_id uuid NOT NULL REFERENCES exercises(id) ON DELETE CASCADE,
    exercise_variation_id uuid REFERENCES exercise_variations(id) ON DELETE CASCADE,
    guidance_type exercise_guidance_type NOT NULL,
    title varchar(180) NOT NULL,
    description text NOT NULL,
    correction text,
    severity severity_level,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE OR REPLACE FUNCTION validate_exercise_guidance_scope() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF NEW.exercise_variation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM exercise_variations v WHERE v.id = NEW.exercise_variation_id AND v.exercise_id = NEW.exercise_id) THEN
        RAISE EXCEPTION 'Exercise guidance variation must belong to the selected exercise';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_exercise_guidance_scope BEFORE INSERT OR UPDATE ON exercise_guidance FOR EACH ROW EXECUTE FUNCTION validate_exercise_guidance_scope();

CREATE TABLE measurement_checkins (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    checkin_type varchar(40) NOT NULL CHECK (checkin_type IN ('BASELINE','PERIODIC','TRAINER_REQUESTED','GOAL_CHECKPOINT','RETURN_TO_TRAINING','MANUAL')),
    fitness_goal_id uuid REFERENCES fitness_goals(id) ON DELETE SET NULL,
    goal_version_id uuid REFERENCES fitness_goal_versions(id) ON DELETE SET NULL,
    coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE SET NULL,
    requested_by uuid REFERENCES users(id) ON DELETE SET NULL,
    assigned_to uuid REFERENCES users(id) ON DELETE SET NULL,
    status measurement_checkin_status NOT NULL DEFAULT 'DRAFT',
    requested_at timestamptz,
    due_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    cancellation_reason text,
    instructions text,
    context jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (goal_version_id IS NULL OR fitness_goal_id IS NOT NULL),
    CHECK (due_at IS NULL OR requested_at IS NULL OR due_at >= requested_at)
);
CREATE TABLE measurement_checkin_requirements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    measurement_checkin_id uuid NOT NULL REFERENCES measurement_checkins(id) ON DELETE CASCADE,
    metric_definition_id integer NOT NULL REFERENCES metric_definitions(id) ON DELETE RESTRICT,
    requirement_level metric_requirement_level NOT NULL,
    status measurement_requirement_status NOT NULL DEFAULT 'PENDING',
    captured_measurement_id uuid REFERENCES measurements(id) ON DELETE RESTRICT,
    unavailable_reason text,
    display_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (measurement_checkin_id, metric_definition_id),
    CHECK ((status = 'CAPTURED' AND captured_measurement_id IS NOT NULL) OR (status <> 'CAPTURED' AND captured_measurement_id IS NULL)),
    CHECK (status NOT IN ('SKIPPED','UNAVAILABLE') OR unavailable_reason IS NOT NULL)
);
CREATE TABLE measurement_checkin_batches (
    measurement_checkin_id uuid NOT NULL REFERENCES measurement_checkins(id) ON DELETE CASCADE,
    measurement_batch_id uuid NOT NULL REFERENCES measurement_batches(id) ON DELETE RESTRICT,
    linked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (measurement_checkin_id, measurement_batch_id)
);
CREATE TABLE measurement_source_priorities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id smallint NOT NULL REFERENCES measurement_sources(id) ON DELETE CASCADE,
    metric_definition_id integer REFERENCES metric_definitions(id) ON DELETE CASCADE,
    priority_rank integer NOT NULL CHECK (priority_rank > 0),
    valid_from timestamptz NOT NULL DEFAULT now(),
    valid_until timestamptz,
    reason text,
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE UNIQUE INDEX uq_current_measurement_source_priority_default ON measurement_source_priorities(source_id) WHERE metric_definition_id IS NULL AND valid_until IS NULL;
CREATE UNIQUE INDEX uq_current_measurement_source_priority_metric ON measurement_source_priorities(source_id, metric_definition_id) WHERE metric_definition_id IS NOT NULL AND valid_until IS NULL;
CREATE TABLE measurement_deduplication_groups (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    metric_definition_id integer NOT NULL REFERENCES metric_definitions(id) ON DELETE RESTRICT,
    event_time_start timestamptz NOT NULL,
    event_time_end timestamptz NOT NULL,
    status measurement_deduplication_status NOT NULL DEFAULT 'OPEN',
    detection_method varchar(60) NOT NULL,
    detection_key varchar(160),
    canonical_measurement_id uuid REFERENCES measurements(id) ON DELETE RESTRICT,
    resolution_summary text,
    detected_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    CHECK (event_time_end >= event_time_start),
    CHECK (status NOT IN ('AUTO_RESOLVED','RESOLVED') OR resolved_at IS NOT NULL)
);
CREATE TABLE measurement_deduplication_members (
    measurement_deduplication_group_id uuid NOT NULL REFERENCES measurement_deduplication_groups(id) ON DELETE CASCADE,
    measurement_id uuid NOT NULL REFERENCES measurements(id) ON DELETE RESTRICT,
    disposition measurement_member_disposition NOT NULL DEFAULT 'CANDIDATE',
    source_priority_snapshot integer,
    similarity_score percentage_0_100,
    match_evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    added_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (measurement_deduplication_group_id, measurement_id)
);
CREATE UNIQUE INDEX uq_measurement_deduplication_canonical_member ON measurement_deduplication_members(measurement_deduplication_group_id) WHERE disposition = 'CANONICAL';
CREATE TABLE measurement_conflict_resolutions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    measurement_deduplication_group_id uuid NOT NULL REFERENCES measurement_deduplication_groups(id) ON DELETE CASCADE,
    decision measurement_resolution_decision NOT NULL,
    canonical_measurement_id uuid REFERENCES measurements(id) ON DELETE RESTRICT,
    resolved_by_type varchar(30) NOT NULL CHECK (resolved_by_type IN ('SYSTEM_RULE','STUDENT','TRAINER','ADMIN')),
    resolved_by uuid REFERENCES users(id) ON DELETE SET NULL,
    rule_code varchar(100),
    rationale text NOT NULL,
    before_state jsonb NOT NULL DEFAULT '{}'::jsonb,
    after_state jsonb NOT NULL DEFAULT '{}'::jsonb,
    resolved_at timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION validate_measurement_checkin_context() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE goal_student uuid; version_goal uuid;
BEGIN
    IF NEW.fitness_goal_id IS NOT NULL THEN SELECT student_id INTO goal_student FROM fitness_goals WHERE id = NEW.fitness_goal_id; IF goal_student IS DISTINCT FROM NEW.student_id THEN RAISE EXCEPTION 'Check-in goal must belong to student'; END IF; END IF;
    IF NEW.goal_version_id IS NOT NULL THEN SELECT fitness_goal_id INTO version_goal FROM fitness_goal_versions WHERE id = NEW.goal_version_id; IF version_goal IS DISTINCT FROM NEW.fitness_goal_id THEN RAISE EXCEPTION 'Check-in version must belong to goal'; END IF; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_measurement_checkin_context BEFORE INSERT OR UPDATE ON measurement_checkins FOR EACH ROW EXECUTE FUNCTION validate_measurement_checkin_context();
CREATE OR REPLACE FUNCTION validate_measurement_checkin_requirement() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE expected_student uuid; actual_student uuid; actual_metric integer;
BEGIN
    IF NEW.captured_measurement_id IS NULL THEN RETURN NEW; END IF;
    SELECT student_id INTO expected_student FROM measurement_checkins WHERE id = NEW.measurement_checkin_id;
    SELECT student_id, metric_definition_id INTO actual_student, actual_metric FROM measurements WHERE id = NEW.captured_measurement_id;
    IF actual_student IS DISTINCT FROM expected_student OR actual_metric IS DISTINCT FROM NEW.metric_definition_id THEN RAISE EXCEPTION 'Captured measurement must match check-in student and metric'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_measurement_checkin_requirement BEFORE INSERT OR UPDATE ON measurement_checkin_requirements FOR EACH ROW EXECUTE FUNCTION validate_measurement_checkin_requirement();
CREATE OR REPLACE FUNCTION validate_measurement_checkin_batch() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE checkin_student uuid; batch_student uuid;
BEGIN
    SELECT student_id INTO checkin_student FROM measurement_checkins WHERE id=NEW.measurement_checkin_id;
    SELECT student_id INTO batch_student FROM measurement_batches WHERE id=NEW.measurement_batch_id;
    IF checkin_student IS DISTINCT FROM batch_student THEN RAISE EXCEPTION 'Measurement batch must belong to check-in student'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_measurement_checkin_batch BEFORE INSERT OR UPDATE ON measurement_checkin_batches FOR EACH ROW EXECUTE FUNCTION validate_measurement_checkin_batch();

CREATE OR REPLACE FUNCTION validate_measurement_deduplication_reference() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE group_student uuid; group_metric integer; measurement_student uuid; measurement_metric integer; candidate uuid;
BEGIN
    IF TG_TABLE_NAME='measurement_deduplication_groups' THEN candidate:=NEW.canonical_measurement_id; group_student:=NEW.student_id; group_metric:=NEW.metric_definition_id;
    ELSIF TG_TABLE_NAME='measurement_deduplication_members' THEN candidate:=NEW.measurement_id; SELECT student_id,metric_definition_id INTO group_student,group_metric FROM measurement_deduplication_groups WHERE id=NEW.measurement_deduplication_group_id;
    ELSE candidate:=NEW.canonical_measurement_id; SELECT student_id,metric_definition_id INTO group_student,group_metric FROM measurement_deduplication_groups WHERE id=NEW.measurement_deduplication_group_id; END IF;
    IF candidate IS NULL THEN RETURN NEW; END IF;
    SELECT student_id,metric_definition_id INTO measurement_student,measurement_metric FROM measurements WHERE id=candidate;
    IF measurement_student IS DISTINCT FROM group_student OR measurement_metric IS DISTINCT FROM group_metric THEN RAISE EXCEPTION 'Dedup measurement must match group student and metric'; END IF;
    IF TG_TABLE_NAME='measurement_conflict_resolutions' AND NOT EXISTS (SELECT 1 FROM measurement_deduplication_members m WHERE m.measurement_deduplication_group_id=NEW.measurement_deduplication_group_id AND m.measurement_id=NEW.canonical_measurement_id) THEN RAISE EXCEPTION 'Canonical measurement must be a group member'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_measurement_deduplication_group BEFORE INSERT OR UPDATE ON measurement_deduplication_groups FOR EACH ROW EXECUTE FUNCTION validate_measurement_deduplication_reference();
CREATE TRIGGER trg_validate_measurement_deduplication_member BEFORE INSERT OR UPDATE ON measurement_deduplication_members FOR EACH ROW EXECUTE FUNCTION validate_measurement_deduplication_reference();
CREATE TRIGGER trg_validate_measurement_conflict_resolution BEFORE INSERT OR UPDATE ON measurement_conflict_resolutions FOR EACH ROW EXECUTE FUNCTION validate_measurement_deduplication_reference();

CREATE OR REPLACE FUNCTION protect_measurement_observation() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE superseding_student uuid; superseding_metric integer;
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Measurements are lifetime observations and cannot be hard-deleted'; END IF;
    IF (to_jsonb(NEW) - ARRAY['confidence_score','quality_score','validation_status','superseded_by_id','notes']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['confidence_score','quality_score','validation_status','superseded_by_id','notes']) THEN
        RAISE EXCEPTION 'Measurement value and provenance are immutable';
    END IF;
    IF NEW.superseded_by_id IS NOT NULL THEN
        SELECT student_id, metric_definition_id INTO superseding_student, superseding_metric FROM measurements WHERE id = NEW.superseded_by_id;
        IF superseding_student IS DISTINCT FROM NEW.student_id OR superseding_metric IS DISTINCT FROM NEW.metric_definition_id THEN RAISE EXCEPTION 'Superseding measurement must have same student and metric'; END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER protect_measurement_observation BEFORE UPDATE OR DELETE ON measurements FOR EACH ROW EXECUTE FUNCTION protect_measurement_observation();

ALTER TABLE fitness_goal_versions ADD COLUMN locked_at timestamptz, ADD COLUMN locked_by uuid REFERENCES users(id) ON DELETE SET NULL, ADD COLUMN lock_reason version_lock_reason;
ALTER TABLE workout_plan_versions ADD COLUMN locked_at timestamptz, ADD COLUMN locked_by uuid REFERENCES users(id) ON DELETE SET NULL, ADD COLUMN lock_reason version_lock_reason;
ALTER TABLE nutrition_goal_versions ADD COLUMN locked_at timestamptz, ADD COLUMN locked_by uuid REFERENCES users(id) ON DELETE SET NULL, ADD COLUMN lock_reason version_lock_reason;
UPDATE fitness_goal_versions v SET locked_at = COALESCE(g.activated_at, v.created_at), locked_by = g.created_by, lock_reason = 'MIGRATED' FROM fitness_goals g WHERE g.id = v.fitness_goal_id AND g.status <> 'DRAFT';
UPDATE workout_plan_versions v SET locked_at = COALESCE(p.assigned_at, v.created_at), locked_by = COALESCE(p.assigned_by,p.created_by), lock_reason = 'MIGRATED' FROM workout_plans p WHERE p.id = v.workout_plan_id AND p.status <> 'DRAFT';
UPDATE nutrition_goal_versions v SET locked_at = COALESCE(g.activated_at, v.created_at), locked_by = g.created_by, lock_reason = 'MIGRATED' FROM nutrition_goals g WHERE g.id = v.nutrition_goal_id AND g.status <> 'DRAFT';
ALTER TABLE fitness_goal_versions ADD CHECK ((locked_at IS NULL AND locked_by IS NULL AND lock_reason IS NULL) OR (locked_at IS NOT NULL AND lock_reason IS NOT NULL));
ALTER TABLE workout_plan_versions ADD CHECK ((locked_at IS NULL AND locked_by IS NULL AND lock_reason IS NULL) OR (locked_at IS NOT NULL AND lock_reason IS NOT NULL));
ALTER TABLE nutrition_goal_versions ADD CHECK ((locked_at IS NULL AND locked_by IS NULL AND lock_reason IS NULL) OR (locked_at IS NOT NULL AND lock_reason IS NOT NULL));

CREATE OR REPLACE FUNCTION protect_locked_version_row() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN IF OLD.locked_at IS NOT NULL THEN RAISE EXCEPTION 'Locked version cannot be deleted'; END IF; RETURN OLD; END IF;
    IF OLD.locked_at IS NOT NULL THEN
        IF (to_jsonb(NEW) - 'effective_until') IS DISTINCT FROM (to_jsonb(OLD) - 'effective_until') THEN RAISE EXCEPTION 'Locked version content is immutable'; END IF;
        IF OLD.effective_until IS NOT NULL AND NEW.effective_until IS DISTINCT FROM OLD.effective_until THEN RAISE EXCEPTION 'Closed version cannot be reopened'; END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER protect_fitness_goal_version BEFORE UPDATE OR DELETE ON fitness_goal_versions FOR EACH ROW EXECUTE FUNCTION protect_locked_version_row();
CREATE TRIGGER protect_workout_plan_version BEFORE UPDATE OR DELETE ON workout_plan_versions FOR EACH ROW EXECUTE FUNCTION protect_locked_version_row();
CREATE TRIGGER protect_nutrition_goal_version BEFORE UPDATE OR DELETE ON nutrition_goal_versions FOR EACH ROW EXECUTE FUNCTION protect_locked_version_row();

CREATE OR REPLACE FUNCTION validate_current_version_lock() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE parent_id uuid; is_current boolean; current_lock timestamptz; parent_status text;
BEGIN
    EXECUTE format('SELECT %I, effective_until IS NULL, locked_at FROM fitness.%I WHERE id=$1',TG_ARGV[1],TG_TABLE_NAME)
        INTO parent_id,is_current,current_lock USING NEW.id;
    EXECUTE format('SELECT status::text FROM fitness.%I WHERE id=$1',TG_ARGV[0]) INTO parent_status USING parent_id;
    IF parent_status IN ('ACTIVE','PAUSED') AND is_current AND current_lock IS NULL THEN RAISE EXCEPTION 'Current version must be locked while parent is active/paused'; END IF;
    RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER validate_fitness_goal_version_lock AFTER INSERT OR UPDATE ON fitness_goal_versions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_current_version_lock('fitness_goals','fitness_goal_id');
CREATE CONSTRAINT TRIGGER validate_workout_plan_version_lock AFTER INSERT OR UPDATE ON workout_plan_versions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_current_version_lock('workout_plans','workout_plan_id');
CREATE CONSTRAINT TRIGGER validate_nutrition_goal_version_lock AFTER INSERT OR UPDATE ON nutrition_goal_versions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_current_version_lock('nutrition_goals','nutrition_goal_id');

CREATE OR REPLACE FUNCTION validate_active_parent_version_lock() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE current_lock timestamptz;
BEGIN
    IF NEW.status::text NOT IN ('ACTIVE','PAUSED') THEN RETURN NULL; END IF;
    EXECUTE format('SELECT locked_at FROM fitness.%I WHERE %I=$1 AND effective_until IS NULL',TG_ARGV[0],TG_ARGV[1]) INTO current_lock USING NEW.id;
    IF NOT FOUND OR current_lock IS NULL THEN RAISE EXCEPTION 'Active/paused parent requires one locked current version'; END IF;
    RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER validate_active_fitness_goal_version AFTER INSERT OR UPDATE OF status ON fitness_goals DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_active_parent_version_lock('fitness_goal_versions','fitness_goal_id');
CREATE CONSTRAINT TRIGGER validate_active_workout_plan_version AFTER INSERT OR UPDATE OF status ON workout_plans DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_active_parent_version_lock('workout_plan_versions','workout_plan_id');
CREATE CONSTRAINT TRIGGER validate_active_nutrition_goal_version AFTER INSERT OR UPDATE OF status ON nutrition_goals DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_active_parent_version_lock('nutrition_goal_versions','nutrition_goal_id');

CREATE OR REPLACE FUNCTION protect_locked_version_child() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE row_data jsonb; parent_id uuid; parent_locked timestamptz;
BEGIN
    row_data := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    parent_id := (row_data ->> TG_ARGV[1])::uuid;
    EXECUTE format('SELECT locked_at FROM fitness.%I WHERE id=$1', TG_ARGV[0]) INTO parent_locked USING parent_id;
    IF parent_locked IS NOT NULL THEN RAISE EXCEPTION 'Content belongs to a locked version'; END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END; $$;
CREATE TRIGGER protect_goal_objective_content BEFORE INSERT OR UPDATE OR DELETE ON goal_objectives FOR EACH ROW EXECUTE FUNCTION protect_locked_version_child('fitness_goal_versions','goal_version_id');
CREATE TRIGGER protect_goal_target_content BEFORE INSERT OR UPDATE OR DELETE ON goal_targets FOR EACH ROW EXECUTE FUNCTION protect_locked_version_child('fitness_goal_versions','goal_version_id');
CREATE TRIGGER protect_workout_plan_session_content BEFORE INSERT OR UPDATE OR DELETE ON workout_plan_sessions FOR EACH ROW EXECUTE FUNCTION protect_locked_version_child('workout_plan_versions','workout_plan_version_id');
CREATE TRIGGER protect_nutrition_target_content BEFORE INSERT OR UPDATE OR DELETE ON daily_nutrition_targets FOR EACH ROW EXECUTE FUNCTION protect_locked_version_child('nutrition_goal_versions','nutrition_goal_version_id');

CREATE OR REPLACE FUNCTION protect_locked_workout_exercise() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE session_id uuid; parent_locked timestamptz;
BEGIN
    session_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.workout_plan_session_id ELSE NEW.workout_plan_session_id END;
    SELECT v.locked_at INTO parent_locked FROM workout_plan_sessions s JOIN workout_plan_versions v ON v.id=s.workout_plan_version_id WHERE s.id=session_id;
    IF parent_locked IS NOT NULL THEN RAISE EXCEPTION 'Exercise belongs to locked workout version'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END; $$;
CREATE TRIGGER protect_workout_plan_exercise_content BEFORE INSERT OR UPDATE OR DELETE ON workout_plan_session_exercises FOR EACH ROW EXECUTE FUNCTION protect_locked_workout_exercise();

CREATE OR REPLACE FUNCTION protect_governed_version_content() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF TG_OP='DELETE' THEN IF OLD.status::text IN ('ACTIVE','ARCHIVED') THEN RAISE EXCEPTION 'Published version cannot be deleted'; END IF; RETURN OLD; END IF;
    IF OLD.status::text IN ('ACTIVE','ARCHIVED') AND (to_jsonb(NEW)-ARRAY['status','archived_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','archived_at']) THEN RAISE EXCEPTION 'Published content is immutable'; END IF;
    IF OLD.status::text='ARCHIVED' AND NEW.status::text<>'ARCHIVED' THEN RAISE EXCEPTION 'Archived version cannot be reactivated'; END IF;
    IF OLD.status::text='ACTIVE' AND NEW.status::text NOT IN ('ACTIVE','ARCHIVED') THEN RAISE EXCEPTION 'Active version may only be archived'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER protect_knowledge_version_content BEFORE UPDATE OR DELETE ON knowledge_versions FOR EACH ROW EXECUTE FUNCTION protect_governed_version_content();
CREATE TRIGGER protect_prompt_version_content BEFORE UPDATE OR DELETE ON prompt_versions FOR EACH ROW EXECUTE FUNCTION protect_governed_version_content();

CREATE OR REPLACE FUNCTION validate_trainer_coaching_capability() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE verification trainer_verification_state; activity trainer_activity_status;
BEGIN
    IF NEW.status IN ('ACTIVE','PAUSED') THEN
        SELECT verification_status, activity_status INTO verification, activity FROM trainer_profiles WHERE user_id=NEW.trainer_id;
        IF verification IS DISTINCT FROM 'VERIFIED' OR activity IS DISTINCT FROM 'ACTIVE' THEN RAISE EXCEPTION 'Trainer must be VERIFIED and ACTIVE'; END IF;
    END IF; RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_trainer_coaching_capability BEFORE INSERT OR UPDATE OF trainer_id,status ON coaching_relationships FOR EACH ROW EXECUTE FUNCTION validate_trainer_coaching_capability();

CREATE OR REPLACE FUNCTION validate_student_proposal_decision() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF NEW.status IN ('ACCEPTED','REJECTED') AND (NEW.decided_by IS DISTINCT FROM NEW.student_id OR NEW.decided_at IS NULL) THEN RAISE EXCEPTION 'Only owning student may decide personal goal proposal'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_goal_proposal_decision BEFORE INSERT OR UPDATE OF status,decided_by,decided_at ON goal_proposals FOR EACH ROW EXECUTE FUNCTION validate_student_proposal_decision();
CREATE TRIGGER trg_validate_nutrition_proposal_decision BEFORE INSERT OR UPDATE OF status,decided_by,decided_at ON nutrition_proposals FOR EACH ROW EXECUTE FUNCTION validate_student_proposal_decision();

CREATE OR REPLACE FUNCTION protect_decided_proposal() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN IF OLD.status <> 'PENDING' THEN RAISE EXCEPTION 'Decided proposal is immutable'; END IF; IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW; END; $$;
CREATE TRIGGER protect_decided_goal_proposal BEFORE UPDATE OR DELETE ON goal_proposals FOR EACH ROW EXECUTE FUNCTION protect_decided_proposal();
CREATE TRIGGER protect_decided_nutrition_proposal BEFORE UPDATE OR DELETE ON nutrition_proposals FOR EACH ROW EXECUTE FUNCTION protect_decided_proposal();

CREATE OR REPLACE FUNCTION protect_proposal_child() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE row_data jsonb; proposal_id uuid; proposal_state proposal_status;
BEGIN
    row_data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    proposal_id:=(row_data->>TG_ARGV[1])::uuid;
    EXECUTE format('SELECT status FROM fitness.%I WHERE id=$1',TG_ARGV[0]) INTO proposal_state USING proposal_id;
    IF proposal_state<>'PENDING' THEN RAISE EXCEPTION 'Decided proposal content is immutable'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END; $$;
CREATE TRIGGER protect_goal_proposal_objectives BEFORE INSERT OR UPDATE OR DELETE ON goal_proposal_objectives FOR EACH ROW EXECUTE FUNCTION protect_proposal_child('goal_proposals','goal_proposal_id');
CREATE TRIGGER protect_goal_proposal_targets BEFORE INSERT OR UPDATE OR DELETE ON goal_proposal_targets FOR EACH ROW EXECUTE FUNCTION protect_proposal_child('goal_proposals','goal_proposal_id');
CREATE TRIGGER protect_nutrition_proposal_targets BEFORE INSERT OR UPDATE OR DELETE ON nutrition_proposal_targets FOR EACH ROW EXECUTE FUNCTION protect_proposal_child('nutrition_proposals','nutrition_proposal_id');

ALTER TABLE ai_runs ADD CONSTRAINT ai_run_non_business_purpose_ck CHECK (purpose NOT IN ('EVALUATION','REPLAY','REGRESSION_TEST') OR evaluation_only);
CREATE OR REPLACE FUNCTION validate_ai_recommendation_authority() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE eval_only boolean; run_purpose varchar(30); period_trainer uuid;
BEGIN
    SELECT evaluation_only,purpose INTO eval_only,run_purpose FROM ai_runs WHERE id=NEW.ai_run_id;
    IF eval_only OR run_purpose IN ('EVALUATION','REPLAY','REGRESSION_TEST') THEN RAISE EXCEPTION 'Evaluation run cannot create business recommendation'; END IF;
    IF NEW.status IN ('ACCEPTED','REJECTED','APPLIED') THEN
        IF NEW.decided_by IS NULL OR NEW.decided_at IS NULL THEN RAISE EXCEPTION 'Human decision is required'; END IF;
        IF NEW.decision_authority='STUDENT' AND NEW.decided_by<>NEW.student_id THEN RAISE EXCEPTION 'Student authority mismatch'; END IF;
        IF NEW.decision_authority='TRAINER' THEN SELECT trainer_id INTO period_trainer FROM coaching_periods WHERE id=NEW.coaching_period_id AND student_id=NEW.student_id AND mode='HUMAN_COACH'; IF period_trainer IS DISTINCT FROM NEW.decided_by THEN RAISE EXCEPTION 'Trainer authority mismatch'; END IF; END IF;
    END IF; RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_ai_recommendation_authority BEFORE INSERT OR UPDATE ON ai_recommendations FOR EACH ROW EXECUTE FUNCTION validate_ai_recommendation_authority();
CREATE OR REPLACE FUNCTION validate_ai_recommendation_application() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE recommendation_state recommendation_status; decision_actor uuid;
BEGIN SELECT status,decided_by INTO recommendation_state,decision_actor FROM ai_recommendations WHERE id=NEW.ai_recommendation_id; IF recommendation_state NOT IN ('ACCEPTED','APPLIED') OR decision_actor IS DISTINCT FROM NEW.applied_by THEN RAISE EXCEPTION 'Application requires the same human approver'; END IF; RETURN NEW; END; $$;
CREATE TRIGGER trg_validate_ai_recommendation_application BEFORE INSERT OR UPDATE ON ai_recommendation_applications FOR EACH ROW EXECUTE FUNCTION validate_ai_recommendation_application();

CREATE OR REPLACE FUNCTION validate_ai_replay_result() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE result_purpose varchar(30); result_eval boolean;
BEGIN
    IF NEW.resulting_ai_run_id IS NULL THEN RETURN NEW; END IF;
    SELECT purpose,evaluation_only INTO result_purpose,result_eval FROM ai_runs WHERE id=NEW.resulting_ai_run_id;
    IF result_purpose IS DISTINCT FROM 'REPLAY' OR result_eval IS DISTINCT FROM true THEN RAISE EXCEPTION 'Replay result must be an evaluation-only REPLAY run'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_ai_replay_result BEFORE INSERT OR UPDATE OF resulting_ai_run_id ON ai_replay_requests FOR EACH ROW EXECUTE FUNCTION validate_ai_replay_result();

CREATE OR REPLACE FUNCTION validate_planned_workout_supervision() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE required supervision_requirement;
BEGIN
    IF NEW.workout_plan_session_id IS NULL THEN RETURN NEW; END IF;
    SELECT supervision_requirement INTO required FROM workout_plan_sessions WHERE id=NEW.workout_plan_session_id;
    IF required='COACH_REQUIRED' AND NEW.supervision_requirement<>'COACH_REQUIRED' AND (NEW.supervision_override_by IS NULL OR NEW.supervision_override_reason IS NULL) THEN RAISE EXCEPTION 'Lowering COACH_REQUIRED needs accountable override'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_planned_workout_supervision BEFORE INSERT OR UPDATE ON planned_workouts FOR EACH ROW EXECUTE FUNCTION validate_planned_workout_supervision();
CREATE OR REPLACE FUNCTION validate_coach_required_execution() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE planned planned_workouts%ROWTYPE;
BEGIN
    IF NEW.planned_workout_id IS NULL THEN RETURN NEW; END IF; SELECT * INTO planned FROM planned_workouts WHERE id=NEW.planned_workout_id;
    IF planned.supervision_requirement='COACH_REQUIRED' AND NOT (planned.supervision_override_by IS NOT NULL AND planned.supervision_override_reason IS NOT NULL) AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.planned_workout_id=planned.id AND a.trainer_id IS NOT NULL AND a.status IN ('CONFIRMED','COMPLETED')) THEN RAISE EXCEPTION 'COACH_REQUIRED workout needs coached appointment or override'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_coach_required_execution BEFORE INSERT OR UPDATE OF planned_workout_id ON workout_session_logs FOR EACH ROW EXECUTE FUNCTION validate_coach_required_execution();

UPDATE reschedule_requests
SET conflict_checked_at=COALESCE(conflict_checked_at,created_at),
    accepted_revalidated_at=COALESCE(accepted_revalidated_at,decided_at,updated_at),
    decided_at=COALESCE(decided_at,updated_at)
WHERE status='ACCEPTED';
ALTER TABLE reschedule_requests
    ADD CONSTRAINT reschedule_acceptance_validation_ck CHECK (status<>'ACCEPTED' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL AND conflict_checked_at IS NOT NULL AND accepted_revalidated_at IS NOT NULL)),
    ADD CONSTRAINT reschedule_revalidation_order_ck CHECK (accepted_revalidated_at IS NULL OR conflict_checked_at IS NULL OR accepted_revalidated_at>=conflict_checked_at);

INSERT INTO permissions(code,description) VALUES
('USER_SUSPEND','Suspend users'),('USER_DISABLE','Disable users'),('USER_ROLE_MANAGE','Manage roles'),('TRAINER_ACTIVITY_MANAGE','Manage trainer activity'),('KNOWLEDGE_PUBLISH','Publish knowledge'),('DATA_CORRECTION_EXECUTE','Execute governed correction'),('SYSTEM_CONFIG_MANAGE','Manage system configuration'),('SESSION_REVOKE','Revoke sessions')
ON CONFLICT (code) DO NOTHING;

ALTER TABLE refresh_tokens ADD COLUMN user_device_id uuid REFERENCES user_devices(id) ON DELETE SET NULL;
ALTER TABLE admin_actions ADD COLUMN permission_id integer REFERENCES permissions(id) ON DELETE SET NULL, ADD COLUMN step_up_authentication_id uuid REFERENCES step_up_authentications(id) ON DELETE SET NULL, ADD COLUMN privileged_access_request_id uuid REFERENCES privileged_data_access_requests(id) ON DELETE SET NULL, ADD COLUMN related_support_case_id uuid REFERENCES support_cases(id) ON DELETE SET NULL, ADD COLUMN completed_at timestamptz NOT NULL DEFAULT now();
CREATE TABLE admin_action_policies (
    action_type varchar(100) PRIMARY KEY,
    required_permission_id integer REFERENCES permissions(id) ON DELETE RESTRICT,
    requires_step_up boolean NOT NULL DEFAULT false,
    requires_privileged_access boolean NOT NULL DEFAULT false,
    max_step_up_age_minutes integer NOT NULL DEFAULT 15 CHECK (max_step_up_age_minutes > 0),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO admin_action_policies(action_type,required_permission_id,requires_step_up,requires_privileged_access)
SELECT p.action_type,perm.id,p.step_up,p.privileged FROM (VALUES
('SUSPEND_ACCOUNT','USER_SUSPEND',true,false),('DISABLE_ACCOUNT','USER_DISABLE',true,false),('FORCE_LOGOUT','SESSION_REVOKE',true,false),('CHANGE_USER_ROLE','USER_ROLE_MANAGE',true,false),('PUBLISH_KNOWLEDGE','KNOWLEDGE_PUBLISH',true,false),('DELETE_USER','USER_DISABLE',true,true),('DATA_CORRECTION','DATA_CORRECTION_EXECUTE',true,true)
) p(action_type,permission_code,step_up,privileged) JOIN permissions perm ON perm.code=p.permission_code;
CREATE TABLE session_revocation_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    target_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requested_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    revocation_scope varchar(20) NOT NULL CHECK (revocation_scope IN ('ALL_SESSIONS','DEVICE','REFRESH_TOKEN')),
    user_device_id uuid REFERENCES user_devices(id) ON DELETE SET NULL,
    refresh_token_id uuid REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    reason text NOT NULL,
    admin_action_id uuid REFERENCES admin_actions(id) ON DELETE SET NULL,
    revoked_token_count integer NOT NULL DEFAULT 0 CHECK (revoked_token_count >= 0),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((revocation_scope='ALL_SESSIONS' AND user_device_id IS NULL AND refresh_token_id IS NULL) OR (revocation_scope='DEVICE' AND user_device_id IS NOT NULL AND refresh_token_id IS NULL) OR (revocation_scope='REFRESH_TOKEN' AND refresh_token_id IS NOT NULL))
);

CREATE OR REPLACE FUNCTION validate_admin_action_authorization() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE policy admin_action_policies%ROWTYPE; step_up step_up_authentications%ROWTYPE; privileged privileged_data_access_requests%ROWTYPE;
BEGIN
    SELECT * INTO policy FROM admin_action_policies WHERE action_type=NEW.action_type AND is_active;
    IF NOT FOUND THEN RETURN NEW; END IF;
    IF policy.required_permission_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id
        WHERE ur.user_id=NEW.admin_user_id AND ur.revoked_at IS NULL AND rp.permission_id=policy.required_permission_id
    ) THEN RAISE EXCEPTION 'Admin lacks required permission'; END IF;
    IF policy.requires_step_up THEN
        SELECT * INTO step_up FROM step_up_authentications WHERE id=NEW.step_up_authentication_id;
        IF NOT FOUND OR step_up.user_id IS DISTINCT FROM NEW.admin_user_id OR step_up.status<>'VERIFIED' OR step_up.expires_at<=NEW.completed_at OR step_up.action_code<>NEW.action_type OR step_up.verified_at<NEW.completed_at-make_interval(mins=>policy.max_step_up_age_minutes) THEN RAISE EXCEPTION 'Current verified step-up is required'; END IF;
    END IF;
    IF policy.requires_privileged_access THEN
        SELECT * INTO privileged FROM privileged_data_access_requests WHERE id=NEW.privileged_access_request_id;
        IF NOT FOUND OR privileged.status<>'ACTIVE' OR privileged.requested_by_admin_id IS DISTINCT FROM NEW.admin_user_id OR privileged.expires_at<=NEW.completed_at THEN RAISE EXCEPTION 'Active scoped privileged access is required'; END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_admin_action_authorization BEFORE INSERT ON admin_actions FOR EACH ROW EXECUTE FUNCTION validate_admin_action_authorization();

CREATE OR REPLACE FUNCTION validate_session_revocation_target() RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE owner_id uuid;
BEGIN
    IF NEW.revocation_scope='DEVICE' THEN SELECT user_id INTO owner_id FROM user_devices WHERE id=NEW.user_device_id;
    ELSIF NEW.revocation_scope='REFRESH_TOKEN' THEN SELECT user_id INTO owner_id FROM refresh_tokens WHERE id=NEW.refresh_token_id;
    ELSE owner_id:=NEW.target_user_id; END IF;
    IF owner_id IS DISTINCT FROM NEW.target_user_id THEN RAISE EXCEPTION 'Revoked session resource must belong to target user'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_validate_session_revocation_target BEFORE INSERT ON session_revocation_events FOR EACH ROW EXECUTE FUNCTION validate_session_revocation_target();

CREATE TRIGGER measurement_conflict_resolutions_append_only BEFORE UPDATE OR DELETE ON measurement_conflict_resolutions FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER session_revocation_events_append_only BEFORE UPDATE OR DELETE ON session_revocation_events FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER trg_goal_type_metric_requirements_updated_at BEFORE UPDATE ON goal_type_metric_requirements FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_exercise_guidance_updated_at BEFORE UPDATE ON exercise_guidance FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_measurement_checkins_updated_at BEFORE UPDATE ON measurement_checkins FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_measurement_checkin_requirements_updated_at BEFORE UPDATE ON measurement_checkin_requirements FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_admin_action_policies_updated_at BEFORE UPDATE ON admin_action_policies FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_nutrition_goal_versions_type ON nutrition_goal_versions(nutrition_goal_type_id) WHERE nutrition_goal_type_id IS NOT NULL;
CREATE INDEX idx_nutrition_proposals_type ON nutrition_proposals(proposed_goal_type_id) WHERE proposed_goal_type_id IS NOT NULL;
CREATE INDEX idx_goal_metric_requirements_metric ON goal_type_metric_requirements(metric_definition_id,requirement_level);
CREATE INDEX idx_exercise_guidance_exercise_status ON exercise_guidance(exercise_id,status,guidance_type);
CREATE INDEX idx_measurement_checkins_student_due ON measurement_checkins(student_id,status,due_at DESC);
CREATE INDEX idx_measurement_deduplication_open ON measurement_deduplication_groups(student_id,metric_definition_id,detected_at DESC) WHERE status IN ('OPEN','REVIEW_REQUIRED');
CREATE INDEX idx_measurement_deduplication_members_measurement ON measurement_deduplication_members(measurement_id);
CREATE INDEX idx_session_revocation_target_time ON session_revocation_events(target_user_id,occurred_at DESC);

CREATE VIEW current_goal_type_metric_requirements AS SELECT r.* FROM goal_type_metric_requirements r WHERE r.valid_until IS NULL;
CREATE VIEW open_measurement_checkins AS SELECT c.* FROM measurement_checkins c WHERE c.status IN ('REQUESTED','IN_PROGRESS','OVERDUE');
CREATE VIEW unresolved_measurement_deduplication_groups AS SELECT g.* FROM measurement_deduplication_groups g WHERE g.status IN ('OPEN','REVIEW_REQUIRED');
