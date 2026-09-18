SET search_path TO fitness, public;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email citext NOT NULL UNIQUE,
    password_hash text,
    display_name varchar(160) NOT NULL,
    phone_number varchar(32),
    status account_status NOT NULL DEFAULT 'PENDING_VERIFICATION',
    email_verified_at timestamptz,
    phone_verified_at timestamptz,
    preferred_locale varchar(16) NOT NULL DEFAULT 'vi-VN',
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT users_password_or_external_identity_ck CHECK (password_hash IS NOT NULL OR status = 'PENDING_VERIFICATION')
);

CREATE TABLE roles (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(40) NOT NULL UNIQUE,
    name varchar(100) NOT NULL,
    description text
);

CREATE TABLE permissions (
    id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(100) NOT NULL UNIQUE,
    description text
);

CREATE TABLE user_roles (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id smallint NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    assigned_by uuid REFERENCES users(id) ON DELETE SET NULL,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT user_roles_revocation_ck CHECK (revoked_at IS NULL OR revoked_at >= assigned_at)
);

CREATE TABLE role_permissions (
    role_id smallint NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id integer NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE auth_identities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider varchar(40) NOT NULL,
    provider_subject varchar(255) NOT NULL,
    provider_email citext,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    UNIQUE (provider, provider_subject)
);

CREATE TABLE refresh_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash varchar(128) NOT NULL UNIQUE,
    device_name varchar(200),
    issued_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    rotated_from_id uuid REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    revoked_at timestamptz,
    revoke_reason varchar(200),
    CONSTRAINT refresh_tokens_expiry_ck CHECK (expires_at > issued_at)
);

CREATE TABLE one_time_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose varchar(40) NOT NULL,
    token_hash varchar(128) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform varchar(30) NOT NULL,
    device_identifier varchar(255),
    push_token text,
    app_version varchar(40),
    last_seen_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, device_identifier)
);

CREATE TABLE user_settings (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    week_starts_on smallint NOT NULL DEFAULT 1 CHECK (week_starts_on BETWEEN 0 AND 6),
    measurement_system varchar(16) NOT NULL DEFAULT 'METRIC' CHECK (measurement_system IN ('METRIC', 'IMPERIAL')),
    accessibility_preferences jsonb NOT NULL DEFAULT '{}'::jsonb,
    privacy_preferences jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE student_profiles (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    date_of_birth date,
    gender gender_code,
    training_experience_level varchar(30) CHECK (training_experience_level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    training_experience_months nonnegative_numeric,
    available_days_per_week smallint CHECK (available_days_per_week BETWEEN 1 AND 7),
    preferred_session_minutes integer CHECK (preferred_session_minutes BETWEEN 5 AND 480),
    onboarding_completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE student_availability_windows (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    weekday smallint NOT NULL CHECK (weekday BETWEEN 0 AND 6),
    start_local_time time NOT NULL,
    end_local_time time NOT NULL,
    timezone varchar(64) NOT NULL,
    valid_from date,
    valid_until date,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT student_availability_time_ck CHECK (end_local_time > start_local_time),
    CONSTRAINT student_availability_dates_ck CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until >= valid_from)
);

CREATE TABLE student_movement_limitations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    body_region varchar(80),
    description text NOT NULL,
    severity severity_level,
    reported_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    is_medically_confirmed boolean NOT NULL DEFAULT false,
    notes text,
    CONSTRAINT movement_limitation_dates_ck CHECK (resolved_at IS NULL OR resolved_at >= reported_at)
);

CREATE TABLE trainer_profiles (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    public_slug varchar(100) UNIQUE,
    bio text,
    years_experience numeric(5,2) CHECK (years_experience >= 0),
    verification_status verification_status NOT NULL DEFAULT 'NOT_SUBMITTED',
    verified_at timestamptz,
    verified_by uuid REFERENCES users(id) ON DELETE SET NULL,
    is_accepting_students boolean NOT NULL DEFAULT false,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE trainer_certificates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE CASCADE,
    name varchar(200) NOT NULL,
    issuer varchar(200),
    credential_number varchar(120),
    issued_on date,
    expires_on date,
    verification_status verification_status NOT NULL DEFAULT 'PENDING',
    verified_by uuid REFERENCES users(id) ON DELETE SET NULL,
    verified_at timestamptz,
    document_media_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT trainer_certificate_dates_ck CHECK (expires_on IS NULL OR issued_on IS NULL OR expires_on >= issued_on)
);

CREATE TABLE trainer_specialties (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    description text
);

CREATE TABLE trainer_specialty_assignments (
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE CASCADE,
    specialty_id smallint NOT NULL REFERENCES trainer_specialties(id) ON DELETE RESTRICT,
    is_primary boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (trainer_id, specialty_id)
);

