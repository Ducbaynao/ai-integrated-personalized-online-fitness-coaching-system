SET search_path TO fitness, public;

CREATE TABLE media_files (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    storage_provider varchar(40) NOT NULL,
    bucket_name varchar(120),
    object_key text NOT NULL,
    original_filename varchar(255),
    content_type varchar(120) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    checksum_sha256 varchar(64),
    media_purpose varchar(60) NOT NULL,
    visibility varchar(30) NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE', 'RELATIONSHIP', 'PUBLIC')),
    scan_status varchar(30) NOT NULL DEFAULT 'PENDING' CHECK (scan_status IN ('PENDING', 'CLEAN', 'REJECTED', 'FAILED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    UNIQUE (storage_provider, bucket_name, object_key)
);

ALTER TABLE users ADD COLUMN avatar_media_id uuid REFERENCES media_files(id) ON DELETE SET NULL;
ALTER TABLE trainer_certificates ADD CONSTRAINT fk_trainer_certificate_media FOREIGN KEY (document_media_id) REFERENCES media_files(id) ON DELETE SET NULL;
ALTER TABLE exercise_media ADD CONSTRAINT fk_exercise_media_file FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE CASCADE;

CREATE TABLE progress_photos (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    media_id uuid NOT NULL REFERENCES media_files(id) ON DELETE RESTRICT,
    taken_at timestamptz NOT NULL,
    view_type varchar(30) CHECK (view_type IN ('FRONT', 'SIDE_LEFT', 'SIDE_RIGHT', 'BACK', 'OTHER')),
    weight_at_time numeric(18,6),
    weight_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE integration_providers (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(80) NOT NULL UNIQUE,
    name varchar(160) NOT NULL,
    provider_type varchar(40) NOT NULL CHECK (provider_type IN ('SMART_SCALE', 'BODY_COMPOSITION', 'WEARABLE', 'HEALTH_PLATFORM', 'GYM_DEVICE', 'FILE_IMPORT')),
    is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE integration_connections (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_id smallint NOT NULL REFERENCES integration_providers(id) ON DELETE RESTRICT,
    external_account_id varchar(255),
    status varchar(30) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED', 'ERROR')),
    granted_scopes text[],
    encrypted_credentials_ref text,
    last_sync_at timestamptz,
    next_sync_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    UNIQUE (user_id, provider_id, external_account_id)
);

CREATE TABLE ingestion_batches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id uuid REFERENCES integration_connections(id) ON DELETE SET NULL,
    source_id smallint NOT NULL REFERENCES measurement_sources(id) ON DELETE RESTRICT,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    status varchar(30) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED')),
    received_count integer NOT NULL DEFAULT 0 CHECK (received_count >= 0),
    accepted_count integer NOT NULL DEFAULT 0 CHECK (accepted_count >= 0),
    rejected_count integer NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),
    error_summary text
);

CREATE TABLE raw_ingestion_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id uuid NOT NULL REFERENCES ingestion_batches(id) ON DELETE CASCADE,
    external_record_id varchar(255),
    payload jsonb NOT NULL,
    payload_hash varchar(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    processing_status varchar(30) NOT NULL DEFAULT 'PENDING' CHECK (processing_status IN ('PENDING', 'NORMALIZED', 'DUPLICATE', 'REJECTED', 'FAILED')),
    error_message text,
    UNIQUE (batch_id, payload_hash)
);

CREATE TABLE measurement_batches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    measured_at timestamptz NOT NULL,
    source_id smallint NOT NULL REFERENCES measurement_sources(id) ON DELETE RESTRICT,
    method_id smallint REFERENCES measurement_methods(id) ON DELETE RESTRICT,
    entered_by uuid REFERENCES users(id) ON DELETE SET NULL,
    integration_connection_id uuid REFERENCES integration_connections(id) ON DELETE SET NULL,
    device_identifier varchar(255),
    notes text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE measurements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    measurement_batch_id uuid REFERENCES measurement_batches(id) ON DELETE SET NULL,
    metric_definition_id integer NOT NULL REFERENCES metric_definitions(id) ON DELETE RESTRICT,
    numeric_value numeric(24,8),
    text_value text,
    boolean_value boolean,
    unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    measured_at timestamptz NOT NULL,
    source_id smallint NOT NULL REFERENCES measurement_sources(id) ON DELETE RESTRICT,
    method_id smallint REFERENCES measurement_methods(id) ON DELETE RESTRICT,
    entered_by uuid REFERENCES users(id) ON DELETE SET NULL,
    integration_connection_id uuid REFERENCES integration_connections(id) ON DELETE SET NULL,
    raw_ingestion_record_id uuid REFERENCES raw_ingestion_records(id) ON DELETE SET NULL,
    external_record_id varchar(255),
    confidence_score percentage_0_100,
    quality_score percentage_0_100,
    validation_status measurement_validation_status NOT NULL DEFAULT 'PENDING',
    superseded_by_id uuid REFERENCES measurements(id) ON DELETE SET NULL,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT measurement_one_value_ck CHECK (num_nonnulls(numeric_value, text_value, boolean_value) = 1),
    CONSTRAINT measurement_numeric_unit_ck CHECK (numeric_value IS NULL OR unit_id IS NOT NULL),
    CONSTRAINT measurement_not_self_superseded_ck CHECK (superseded_by_id IS NULL OR superseded_by_id <> id)
);

CREATE UNIQUE INDEX uq_measurement_external_record
    ON measurements(integration_connection_id, external_record_id, metric_definition_id)
    WHERE integration_connection_id IS NOT NULL AND external_record_id IS NOT NULL;

CREATE TABLE measurement_validation_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    measurement_id uuid NOT NULL REFERENCES measurements(id) ON DELETE CASCADE,
    previous_status measurement_validation_status,
    new_status measurement_validation_status NOT NULL,
    validator_type varchar(30) NOT NULL CHECK (validator_type IN ('SYSTEM_RULE', 'USER', 'TRAINER', 'ADMIN')),
    validator_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    rule_code varchar(100),
    reason text,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE training_activity_periods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    state training_activity_state NOT NULL,
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    detected_by varchar(30) NOT NULL CHECK (detected_by IN ('SYSTEM', 'STUDENT', 'TRAINER', 'ADMIN')),
    reason varchar(100),
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT training_activity_dates_ck CHECK (ended_at IS NULL OR ended_at > started_at)
);

ALTER TABLE training_activity_periods
    ADD CONSTRAINT training_activity_no_overlap
    EXCLUDE USING gist (
        student_id WITH =,
        tstzrange(started_at, COALESCE(ended_at, 'infinity'::timestamptz), '[)') WITH &&
    );

CREATE TABLE return_to_training_assessments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    inactivity_period_id uuid REFERENCES training_activity_periods(id) ON DELETE SET NULL,
    assessed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    assessed_at timestamptz NOT NULL DEFAULT now(),
    readiness_score percentage_0_100,
    previous_performance_summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    current_condition_summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    recommendation text,
    status varchar(30) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'COMPLETED', 'SUPERSEDED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE progress_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    fitness_goal_id uuid REFERENCES fitness_goals(id) ON DELETE CASCADE,
    goal_version_id uuid REFERENCES fitness_goal_versions(id) ON DELETE SET NULL,
    scope varchar(30) NOT NULL CHECK (scope IN ('CURRENT_GOAL', 'LIFETIME')),
    period_start date NOT NULL,
    period_end date NOT NULL,
    calculated_at timestamptz NOT NULL DEFAULT now(),
    algorithm_version varchar(60) NOT NULL,
    data_quality_score percentage_0_100,
    summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT progress_snapshot_dates_ck CHECK (period_end >= period_start)
);

CREATE TABLE progress_metric_values (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    progress_snapshot_id uuid NOT NULL REFERENCES progress_snapshots(id) ON DELETE CASCADE,
    metric_definition_id integer NOT NULL REFERENCES metric_definitions(id) ON DELETE RESTRICT,
    unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    current_value numeric(24,8),
    moving_average numeric(24,8),
    rate_of_change numeric(24,8),
    variance numeric(24,8),
    sample_count integer NOT NULL DEFAULT 0 CHECK (sample_count >= 0),
    data_availability varchar(20) NOT NULL CHECK (data_availability IN ('AVAILABLE', 'MISSING', 'STALE', 'SUSPECT')),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (progress_snapshot_id, metric_definition_id)
);

CREATE TABLE goal_progress_checkpoints (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fitness_goal_id uuid NOT NULL REFERENCES fitness_goals(id) ON DELETE CASCADE,
    goal_version_id uuid NOT NULL REFERENCES fitness_goal_versions(id) ON DELETE CASCADE,
    checkpoint_date date NOT NULL,
    goal_day integer CHECK (goal_day IS NULL OR goal_day > 0),
    calendar_elapsed_days integer CHECK (calendar_elapsed_days IS NULL OR calendar_elapsed_days >= 0),
    active_training_days integer CHECK (active_training_days IS NULL OR active_training_days >= 0),
    inactive_days integer CHECK (inactive_days IS NULL OR inactive_days >= 0),
    completion_percentage percentage_0_100,
    status varchar(30) NOT NULL DEFAULT 'CALCULATED' CHECK (status IN ('PLANNED', 'CALCULATED', 'REVIEWED')),
    summary text,
    calculated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (fitness_goal_id, checkpoint_date)
);

CREATE TABLE goal_checkpoint_values (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    checkpoint_id uuid NOT NULL REFERENCES goal_progress_checkpoints(id) ON DELETE CASCADE,
    goal_target_id uuid NOT NULL REFERENCES goal_targets(id) ON DELETE CASCADE,
    current_value numeric(24,8),
    expected_value numeric(24,8),
    progress_percentage percentage_0_100,
    data_quality_score percentage_0_100,
    notes text,
    UNIQUE (checkpoint_id, goal_target_id)
);

CREATE TABLE daily_training_aggregates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    activity_date date NOT NULL,
    workout_count integer NOT NULL DEFAULT 0 CHECK (workout_count >= 0),
    completed_set_count integer NOT NULL DEFAULT 0 CHECK (completed_set_count >= 0),
    total_repetitions integer NOT NULL DEFAULT 0 CHECK (total_repetitions >= 0),
    total_volume numeric(24,8) NOT NULL DEFAULT 0 CHECK (total_volume >= 0),
    volume_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    total_duration_seconds bigint NOT NULL DEFAULT 0 CHECK (total_duration_seconds >= 0),
    average_rpe numeric(4,2) CHECK (average_rpe IS NULL OR average_rpe BETWEEN 1 AND 10),
    algorithm_version varchar(60) NOT NULL,
    calculated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (student_id, activity_date)
);

CREATE TABLE adherence_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    period_start date NOT NULL,
    period_end date NOT NULL,
    planned_count integer NOT NULL DEFAULT 0 CHECK (planned_count >= 0),
    completed_count integer NOT NULL DEFAULT 0 CHECK (completed_count >= 0),
    completion_rate percentage_0_100,
    on_schedule_count integer NOT NULL DEFAULT 0 CHECK (on_schedule_count >= 0),
    schedule_adherence_rate percentage_0_100,
    continuity_streak_days integer NOT NULL DEFAULT 0 CHECK (continuity_streak_days >= 0),
    days_since_last_workout integer CHECK (days_since_last_workout IS NULL OR days_since_last_workout >= 0),
    calculated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT adherence_snapshot_dates_ck CHECK (period_end >= period_start),
    UNIQUE (student_id, period_start, period_end)
);

