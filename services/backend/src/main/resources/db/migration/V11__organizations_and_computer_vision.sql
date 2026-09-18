SET search_path TO fitness, public;

CREATE TABLE organizations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(200) NOT NULL,
    organization_type varchar(40) NOT NULL CHECK (organization_type IN ('GYM', 'FITNESS_STUDIO', 'COACHING_TEAM', 'ENTERPRISE')),
    status varchar(30) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    owner_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE organization_locations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name varchar(180) NOT NULL,
    address_line text,
    latitude numeric(10,7),
    longitude numeric(10,7),
    timezone varchar(64) NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_location_lat_ck CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT organization_location_lng_ck CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

CREATE TABLE organization_memberships (
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    membership_role varchar(40) NOT NULL CHECK (membership_role IN ('OWNER', 'ADMIN', 'TRAINER', 'STAFF', 'STUDENT')),
    status varchar(30) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'ENDED')),
    joined_at timestamptz,
    ended_at timestamptz,
    PRIMARY KEY (organization_id, user_id, membership_role),
    CONSTRAINT organization_membership_dates_ck CHECK (ended_at IS NULL OR joined_at IS NULL OR ended_at >= joined_at)
);

CREATE TABLE pose_analysis_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    workout_session_log_id uuid REFERENCES workout_session_logs(id) ON DELETE SET NULL,
    exercise_variation_id uuid NOT NULL REFERENCES exercise_variations(id) ON DELETE RESTRICT,
    source_video_media_id uuid REFERENCES media_files(id) ON DELETE SET NULL,
    processing_mode varchar(30) NOT NULL CHECK (processing_mode IN ('REAL_TIME', 'RECORDED_VIDEO')),
    model_version_id uuid REFERENCES ai_model_versions(id) ON DELETE SET NULL,
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    status varchar(30) NOT NULL DEFAULT 'PROCESSING' CHECK (status IN ('PROCESSING', 'COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')),
    overall_form_score percentage_0_100,
    estimated_calories numeric(18,6) CHECK (estimated_calories IS NULL OR estimated_calories >= 0),
    summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pose_session_dates_ck CHECK (ended_at IS NULL OR ended_at >= started_at)
);

CREATE TABLE pose_rep_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    pose_analysis_session_id uuid NOT NULL REFERENCES pose_analysis_sessions(id) ON DELETE CASCADE,
    rep_number integer NOT NULL CHECK (rep_number > 0),
    started_offset_ms integer NOT NULL CHECK (started_offset_ms >= 0),
    ended_offset_ms integer CHECK (ended_offset_ms IS NULL OR ended_offset_ms >= started_offset_ms),
    form_score percentage_0_100,
    range_of_motion_score percentage_0_100,
    tempo_score percentage_0_100,
    confidence_score percentage_0_100,
    metrics jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (pose_analysis_session_id, rep_number)
);

CREATE TABLE movement_feedback (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    pose_analysis_session_id uuid NOT NULL REFERENCES pose_analysis_sessions(id) ON DELETE CASCADE,
    pose_rep_event_id uuid REFERENCES pose_rep_events(id) ON DELETE CASCADE,
    feedback_code varchar(100) NOT NULL,
    severity severity_level NOT NULL,
    joint_or_region varchar(80),
    message text NOT NULL,
    correction_cue text,
    first_detected_offset_ms integer CHECK (first_detected_offset_ms IS NULL OR first_detected_offset_ms >= 0),
    confidence_score percentage_0_100,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE pose_keypoint_artifacts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    pose_analysis_session_id uuid NOT NULL REFERENCES pose_analysis_sessions(id) ON DELETE CASCADE,
    media_id uuid NOT NULL REFERENCES media_files(id) ON DELETE RESTRICT,
    format varchar(30) NOT NULL CHECK (format IN ('JSON', 'JSONL', 'PARQUET', 'BINARY')),
    frame_rate numeric(8,3) CHECK (frame_rate IS NULL OR frame_rate > 0),
    schema_version varchar(40) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

