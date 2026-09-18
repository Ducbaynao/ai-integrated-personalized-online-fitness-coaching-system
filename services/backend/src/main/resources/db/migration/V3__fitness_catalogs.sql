SET search_path TO fitness, public;

-- Extensible units and measurement taxonomy.
CREATE TABLE measurement_units (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(30) NOT NULL UNIQUE,
    symbol varchar(30) NOT NULL,
    dimension varchar(40) NOT NULL,
    base_unit_code varchar(30),
    multiplier_to_base numeric(24,12),
    offset_to_base numeric(24,12),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE metric_definitions (
    id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(80) NOT NULL UNIQUE,
    display_name varchar(160) NOT NULL,
    description text,
    value_type measurement_value_type NOT NULL DEFAULT 'NUMERIC',
    collection_type metric_collection_type NOT NULL,
    default_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    valid_min numeric(24,8),
    valid_max numeric(24,8),
    stale_after_days integer CHECK (stale_after_days IS NULL OR stale_after_days > 0),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT metric_range_ck CHECK (valid_max IS NULL OR valid_min IS NULL OR valid_max >= valid_min)
);

CREATE TABLE measurement_methods (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    description text,
    typical_confidence percentage_0_100,
    is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE measurement_sources (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    source_kind varchar(40) NOT NULL CHECK (source_kind IN ('MANUAL', 'TRAINER', 'DEVICE', 'HEALTH_PLATFORM', 'SYSTEM_DERIVED', 'IMPORT')),
    is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE metric_supported_methods (
    metric_definition_id integer NOT NULL REFERENCES metric_definitions(id) ON DELETE CASCADE,
    measurement_method_id smallint NOT NULL REFERENCES measurement_methods(id) ON DELETE RESTRICT,
    PRIMARY KEY (metric_definition_id, measurement_method_id)
);

-- Exercise catalog.
CREATE TABLE exercise_categories (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    description text
);

CREATE TABLE muscle_groups (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    parent_id smallint REFERENCES muscle_groups(id) ON DELETE SET NULL
);

CREATE TABLE equipment (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    description text,
    is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE exercises (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(100) NOT NULL UNIQUE,
    name varchar(180) NOT NULL,
    category_id smallint REFERENCES exercise_categories(id) ON DELETE SET NULL,
    description text,
    instructions text,
    difficulty varchar(30) CHECK (difficulty IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    movement_pattern varchar(60),
    unilateral boolean NOT NULL DEFAULT false,
    admin_status varchar(30) NOT NULL DEFAULT 'ACTIVE' CHECK (admin_status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE exercise_variations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    exercise_id uuid NOT NULL REFERENCES exercises(id) ON DELETE CASCADE,
    code varchar(120) NOT NULL UNIQUE,
    name varchar(180) NOT NULL,
    description text,
    instructions text,
    difficulty varchar(30) CHECK (difficulty IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    is_default boolean NOT NULL DEFAULT false,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE exercise_muscles (
    exercise_variation_id uuid NOT NULL REFERENCES exercise_variations(id) ON DELETE CASCADE,
    muscle_group_id smallint NOT NULL REFERENCES muscle_groups(id) ON DELETE RESTRICT,
    involvement varchar(20) NOT NULL CHECK (involvement IN ('PRIMARY', 'SECONDARY', 'STABILIZER')),
    PRIMARY KEY (exercise_variation_id, muscle_group_id, involvement)
);

CREATE TABLE exercise_equipment (
    exercise_variation_id uuid NOT NULL REFERENCES exercise_variations(id) ON DELETE CASCADE,
    equipment_id smallint NOT NULL REFERENCES equipment(id) ON DELETE RESTRICT,
    requirement varchar(20) NOT NULL DEFAULT 'REQUIRED' CHECK (requirement IN ('REQUIRED', 'OPTIONAL', 'ALTERNATIVE')),
    PRIMARY KEY (exercise_variation_id, equipment_id)
);

CREATE TABLE exercise_media (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    exercise_variation_id uuid NOT NULL REFERENCES exercise_variations(id) ON DELETE CASCADE,
    media_id uuid NOT NULL,
    purpose varchar(30) NOT NULL CHECK (purpose IN ('THUMBNAIL', 'DEMO_IMAGE', 'DEMO_VIDEO', 'TECHNIQUE_GUIDE')),
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE student_equipment (
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    equipment_id smallint NOT NULL REFERENCES equipment(id) ON DELETE RESTRICT,
    available_from date,
    available_until date,
    notes text,
    PRIMARY KEY (student_id, equipment_id),
    CONSTRAINT student_equipment_dates_ck CHECK (available_until IS NULL OR available_from IS NULL OR available_until >= available_from)
);

CREATE TABLE student_exercise_preferences (
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    exercise_variation_id uuid NOT NULL REFERENCES exercise_variations(id) ON DELETE CASCADE,
    preference varchar(20) NOT NULL CHECK (preference IN ('PREFERRED', 'NEUTRAL', 'AVOID')),
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, exercise_variation_id)
);

-- Nutrition catalog. Nutrient values are normalized per serving quantity.
CREATE TABLE food_categories (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    parent_id smallint REFERENCES food_categories(id) ON DELETE SET NULL
);

CREATE TABLE nutrients (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    default_unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    is_macro boolean NOT NULL DEFAULT false
);

CREATE TABLE foods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(120) UNIQUE,
    name varchar(200) NOT NULL,
    normalized_name varchar(200) NOT NULL,
    brand varchar(160),
    category_id smallint REFERENCES food_categories(id) ON DELETE SET NULL,
    serving_quantity numeric(18,6) NOT NULL CHECK (serving_quantity > 0),
    serving_unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    data_source varchar(120),
    is_verified boolean NOT NULL DEFAULT false,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE food_nutrients (
    food_id uuid NOT NULL REFERENCES foods(id) ON DELETE CASCADE,
    nutrient_id smallint NOT NULL REFERENCES nutrients(id) ON DELETE RESTRICT,
    amount nonnegative_numeric NOT NULL,
    unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    PRIMARY KEY (food_id, nutrient_id)
);

