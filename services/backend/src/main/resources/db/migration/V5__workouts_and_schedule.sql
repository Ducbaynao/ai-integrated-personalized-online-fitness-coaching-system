SET search_path TO fitness, public;

CREATE TABLE workout_templates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(200) NOT NULL,
    description text,
    difficulty varchar(30) CHECK (difficulty IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    days_per_week smallint CHECK (days_per_week BETWEEN 1 AND 7),
    duration_weeks integer CHECK (duration_weeks IS NULL OR duration_weeks > 0),
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    status varchar(30) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    is_public boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE workout_template_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id uuid NOT NULL REFERENCES workout_templates(id) ON DELETE CASCADE,
    version_number integer NOT NULL CHECK (version_number > 0),
    change_summary text,
    published_at timestamptz,
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (template_id, version_number)
);

CREATE TABLE workout_template_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_version_id uuid NOT NULL REFERENCES workout_template_versions(id) ON DELETE CASCADE,
    week_number integer NOT NULL CHECK (week_number > 0),
    day_number integer NOT NULL CHECK (day_number > 0),
    sequence_number integer NOT NULL CHECK (sequence_number > 0),
    name varchar(180) NOT NULL,
    session_focus varchar(160),
    estimated_duration_minutes integer CHECK (estimated_duration_minutes IS NULL OR estimated_duration_minutes > 0),
    notes text,
    UNIQUE (template_version_id, week_number, day_number, sequence_number)
);

CREATE TABLE workout_template_session_exercises (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_session_id uuid NOT NULL REFERENCES workout_template_sessions(id) ON DELETE CASCADE,
    exercise_variation_id uuid NOT NULL REFERENCES exercise_variations(id) ON DELETE RESTRICT,
    sequence_number integer NOT NULL CHECK (sequence_number > 0),
    superset_group varchar(30),
    target_sets integer CHECK (target_sets IS NULL OR target_sets > 0),
    target_reps_min integer CHECK (target_reps_min IS NULL OR target_reps_min >= 0),
    target_reps_max integer CHECK (target_reps_max IS NULL OR target_reps_max >= 0),
    target_load numeric(18,6) CHECK (target_load IS NULL OR target_load >= 0),
    load_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    target_rpe numeric(4,2) CHECK (target_rpe IS NULL OR target_rpe BETWEEN 1 AND 10),
    target_rir numeric(4,2) CHECK (target_rir IS NULL OR target_rir BETWEEN 0 AND 10),
    rest_seconds integer CHECK (rest_seconds IS NULL OR rest_seconds >= 0),
    tempo varchar(30),
    duration_seconds integer CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    distance_value numeric(18,6) CHECK (distance_value IS NULL OR distance_value >= 0),
    distance_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    instructions text,
    UNIQUE (template_session_id, sequence_number),
    CONSTRAINT template_reps_range_ck CHECK (target_reps_max IS NULL OR target_reps_min IS NULL OR target_reps_max >= target_reps_min)
);

CREATE TABLE workout_plans (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    fitness_goal_id uuid REFERENCES fitness_goals(id) ON DELETE SET NULL,
    coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE SET NULL,
    source_template_id uuid REFERENCES workout_templates(id) ON DELETE SET NULL,
    name varchar(200) NOT NULL,
    description text,
    source plan_source NOT NULL,
    status plan_status NOT NULL DEFAULT 'DRAFT',
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    assigned_by uuid REFERENCES users(id) ON DELETE SET NULL,
    assigned_at timestamptz,
    ownership_transferred_to_student_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz,
    deleted_at timestamptz
);

CREATE TABLE workout_plan_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workout_plan_id uuid NOT NULL REFERENCES workout_plans(id) ON DELETE CASCADE,
    version_number integer NOT NULL CHECK (version_number > 0),
    effective_from timestamptz NOT NULL DEFAULT now(),
    effective_until timestamptz,
    change_level varchar(20) NOT NULL DEFAULT 'MAJOR' CHECK (change_level IN ('INITIAL', 'MINOR', 'MAJOR')),
    change_reason varchar(100),
    change_summary text,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    source_ai_recommendation_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workout_plan_id, version_number),
    CONSTRAINT workout_plan_version_dates_ck CHECK (effective_until IS NULL OR effective_until > effective_from)
);

CREATE UNIQUE INDEX uq_workout_plan_current_version
    ON workout_plan_versions(workout_plan_id)
    WHERE effective_until IS NULL;

CREATE TABLE workout_plan_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workout_plan_version_id uuid NOT NULL REFERENCES workout_plan_versions(id) ON DELETE CASCADE,
    week_number integer NOT NULL CHECK (week_number > 0),
    day_number integer NOT NULL CHECK (day_number > 0),
    sequence_number integer NOT NULL CHECK (sequence_number > 0),
    name varchar(180) NOT NULL,
    session_focus varchar(160),
    estimated_duration_minutes integer CHECK (estimated_duration_minutes IS NULL OR estimated_duration_minutes > 0),
    notes text,
    UNIQUE (workout_plan_version_id, week_number, day_number, sequence_number)
);

CREATE TABLE workout_plan_session_exercises (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workout_plan_session_id uuid NOT NULL REFERENCES workout_plan_sessions(id) ON DELETE CASCADE,
    exercise_variation_id uuid NOT NULL REFERENCES exercise_variations(id) ON DELETE RESTRICT,
    sequence_number integer NOT NULL CHECK (sequence_number > 0),
    superset_group varchar(30),
    target_sets integer CHECK (target_sets IS NULL OR target_sets > 0),
    target_reps_min integer CHECK (target_reps_min IS NULL OR target_reps_min >= 0),
    target_reps_max integer CHECK (target_reps_max IS NULL OR target_reps_max >= 0),
    target_load numeric(18,6) CHECK (target_load IS NULL OR target_load >= 0),
    load_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    target_rpe numeric(4,2) CHECK (target_rpe IS NULL OR target_rpe BETWEEN 1 AND 10),
    target_rir numeric(4,2) CHECK (target_rir IS NULL OR target_rir BETWEEN 0 AND 10),
    rest_seconds integer CHECK (rest_seconds IS NULL OR rest_seconds >= 0),
    tempo varchar(30),
    duration_seconds integer CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    distance_value numeric(18,6) CHECK (distance_value IS NULL OR distance_value >= 0),
    distance_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    instructions text,
    UNIQUE (workout_plan_session_id, sequence_number),
    CONSTRAINT plan_reps_range_ck CHECK (target_reps_max IS NULL OR target_reps_min IS NULL OR target_reps_max >= target_reps_min)
);

CREATE TABLE planned_workouts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    workout_plan_session_id uuid REFERENCES workout_plan_sessions(id) ON DELETE SET NULL,
    coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE SET NULL,
    planned_start_at timestamptz NOT NULL,
    planned_end_at timestamptz,
    original_planned_start_at timestamptz NOT NULL,
    status planned_workout_status NOT NULL DEFAULT 'SCHEDULED',
    rescheduled_from_id uuid REFERENCES planned_workouts(id) ON DELETE SET NULL,
    status_reason text,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT planned_workout_time_ck CHECK (planned_end_at IS NULL OR planned_end_at > planned_start_at),
    CONSTRAINT planned_workout_reschedule_ck CHECK (rescheduled_from_id IS NULL OR rescheduled_from_id <> id)
);

CREATE TABLE workout_session_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    planned_workout_id uuid REFERENCES planned_workouts(id) ON DELETE SET NULL,
    workout_plan_version_id uuid REFERENCES workout_plan_versions(id) ON DELETE SET NULL,
    coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE SET NULL,
    performed_start_at timestamptz NOT NULL,
    performed_end_at timestamptz,
    status actual_workout_status NOT NULL DEFAULT 'IN_PROGRESS',
    overall_rpe numeric(4,2) CHECK (overall_rpe IS NULL OR overall_rpe BETWEEN 1 AND 10),
    perceived_recovery numeric(4,2) CHECK (perceived_recovery IS NULL OR perceived_recovery BETWEEN 1 AND 10),
    calories_burned_estimate numeric(18,6) CHECK (calories_burned_estimate IS NULL OR calories_burned_estimate >= 0),
    notes text,
    logged_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workout_log_time_ck CHECK (performed_end_at IS NULL OR performed_end_at > performed_start_at)
);

CREATE UNIQUE INDEX uq_workout_log_planned_workout
    ON workout_session_logs(planned_workout_id)
    WHERE planned_workout_id IS NOT NULL AND status <> 'ABORTED';

CREATE TABLE exercise_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workout_session_log_id uuid NOT NULL REFERENCES workout_session_logs(id) ON DELETE CASCADE,
    exercise_variation_id uuid NOT NULL REFERENCES exercise_variations(id) ON DELETE RESTRICT,
    planned_session_exercise_id uuid REFERENCES workout_plan_session_exercises(id) ON DELETE SET NULL,
    sequence_number integer NOT NULL CHECK (sequence_number > 0),
    started_at timestamptz,
    ended_at timestamptz,
    notes text,
    UNIQUE (workout_session_log_id, sequence_number),
    CONSTRAINT exercise_log_time_ck CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at)
);

CREATE TABLE set_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    exercise_log_id uuid NOT NULL REFERENCES exercise_logs(id) ON DELETE CASCADE,
    set_number integer NOT NULL CHECK (set_number > 0),
    set_type set_type NOT NULL DEFAULT 'WORKING',
    completion_status set_completion_status NOT NULL DEFAULT 'COMPLETED',
    repetitions integer CHECK (repetitions IS NULL OR repetitions >= 0),
    load_value numeric(18,6) CHECK (load_value IS NULL OR load_value >= 0),
    load_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    duration_seconds integer CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    distance_value numeric(18,6) CHECK (distance_value IS NULL OR distance_value >= 0),
    distance_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    rpe numeric(4,2) CHECK (rpe IS NULL OR rpe BETWEEN 1 AND 10),
    rir numeric(4,2) CHECK (rir IS NULL OR rir BETWEEN 0 AND 10),
    tempo varchar(30),
    rest_after_seconds integer CHECK (rest_after_seconds IS NULL OR rest_after_seconds >= 0),
    notes text,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (exercise_log_id, set_number)
);

-- Scheduling and conflict history.
CREATE TABLE trainer_availability_rules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE CASCADE,
    weekday smallint NOT NULL CHECK (weekday BETWEEN 0 AND 6),
    start_local_time time NOT NULL,
    end_local_time time NOT NULL,
    timezone varchar(64) NOT NULL,
    valid_from date,
    valid_until date,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT trainer_availability_time_ck CHECK (end_local_time > start_local_time),
    CONSTRAINT trainer_availability_dates_ck CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until >= valid_from)
);

CREATE TABLE trainer_availability_exceptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE CASCADE,
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    is_available boolean NOT NULL DEFAULT false,
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT trainer_availability_exception_time_ck CHECK (end_at > start_at)
);

CREATE TABLE recurring_schedules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id uuid REFERENCES trainer_profiles(user_id) ON DELETE RESTRICT,
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    relationship_id uuid REFERENCES coaching_relationships(id) ON DELETE SET NULL,
    recurrence_rule text NOT NULL,
    timezone varchar(64) NOT NULL,
    start_date date NOT NULL,
    end_date date,
    default_duration_minutes integer NOT NULL CHECK (default_duration_minutes > 0),
    location varchar(255),
    session_type varchar(60),
    is_active boolean NOT NULL DEFAULT true,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT recurring_schedule_dates_ck CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE TABLE appointments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id uuid REFERENCES trainer_profiles(user_id) ON DELETE RESTRICT,
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    relationship_id uuid REFERENCES coaching_relationships(id) ON DELETE SET NULL,
    recurring_schedule_id uuid REFERENCES recurring_schedules(id) ON DELETE SET NULL,
    planned_workout_id uuid REFERENCES planned_workouts(id) ON DELETE SET NULL,
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    display_timezone varchar(64) NOT NULL,
    location varchar(255),
    session_type varchar(60),
    status appointment_status NOT NULL DEFAULT 'SCHEDULED',
    notes text,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT appointment_time_ck CHECK (end_at > start_at)
);

ALTER TABLE appointments
    ADD CONSTRAINT trainer_appointment_no_overlap
    EXCLUDE USING gist (
        trainer_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    ) WHERE (trainer_id IS NOT NULL AND status IN ('SCHEDULED', 'CONFIRMED'));

CREATE TABLE reschedule_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    requested_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    requested_start_at timestamptz NOT NULL,
    requested_end_at timestamptz NOT NULL,
    reason text,
    status request_status NOT NULL DEFAULT 'PENDING',
    decided_by uuid REFERENCES users(id) ON DELETE SET NULL,
    decided_at timestamptz,
    decision_note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reschedule_request_time_ck CHECK (requested_end_at > requested_start_at)
);

CREATE TABLE appointment_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    event_type varchar(50) NOT NULL,
    old_start_at timestamptz,
    old_end_at timestamptz,
    new_start_at timestamptz,
    new_end_at timestamptz,
    old_status appointment_status,
    new_status appointment_status,
    actor_id uuid REFERENCES users(id) ON DELETE SET NULL,
    reason text,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

