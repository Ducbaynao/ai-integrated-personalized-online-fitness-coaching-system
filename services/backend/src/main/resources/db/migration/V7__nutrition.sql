SET search_path TO fitness, public;

CREATE TABLE nutrition_goals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    fitness_goal_id uuid REFERENCES fitness_goals(id) ON DELETE SET NULL,
    title varchar(200) NOT NULL,
    status lifecycle_status NOT NULL DEFAULT 'DRAFT',
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    activated_at timestamptz,
    ended_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE nutrition_goal_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    nutrition_goal_id uuid NOT NULL REFERENCES nutrition_goals(id) ON DELETE CASCADE,
    version_number integer NOT NULL CHECK (version_number > 0),
    effective_from date NOT NULL,
    effective_until date,
    strategy varchar(60),
    change_reason varchar(100),
    change_summary text,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (nutrition_goal_id, version_number),
    CONSTRAINT nutrition_goal_version_dates_ck CHECK (effective_until IS NULL OR effective_until >= effective_from)
);

CREATE UNIQUE INDEX uq_nutrition_goal_current_version
    ON nutrition_goal_versions(nutrition_goal_id)
    WHERE effective_until IS NULL;

CREATE TABLE daily_nutrition_targets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    nutrition_goal_version_id uuid NOT NULL REFERENCES nutrition_goal_versions(id) ON DELETE CASCADE,
    nutrient_id smallint NOT NULL REFERENCES nutrients(id) ON DELETE RESTRICT,
    target_amount nonnegative_numeric NOT NULL,
    minimum_amount nonnegative_numeric,
    maximum_amount nonnegative_numeric,
    unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    day_type varchar(30) NOT NULL DEFAULT 'ALL' CHECK (day_type IN ('ALL', 'TRAINING_DAY', 'REST_DAY')),
    CONSTRAINT daily_target_range_ck CHECK (maximum_amount IS NULL OR minimum_amount IS NULL OR maximum_amount >= minimum_amount),
    UNIQUE (nutrition_goal_version_id, nutrient_id, day_type)
);

CREATE TABLE meals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    meal_type meal_type NOT NULL,
    consumed_at timestamptz NOT NULL,
    title varchar(180),
    notes text,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE food_log_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    meal_id uuid NOT NULL REFERENCES meals(id) ON DELETE CASCADE,
    food_id uuid REFERENCES foods(id) ON DELETE SET NULL,
    food_name_snapshot varchar(200) NOT NULL,
    quantity numeric(18,6) NOT NULL CHECK (quantity > 0),
    unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    gram_weight numeric(18,6) CHECK (gram_weight IS NULL OR gram_weight > 0),
    entry_source varchar(30) NOT NULL CHECK (entry_source IN ('MANUAL', 'TEXT_AI', 'IMAGE_AI', 'BARCODE', 'IMPORT')),
    confidence_score percentage_0_100,
    confirmed_by_user boolean NOT NULL DEFAULT false,
    confirmed_at timestamptz,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE food_log_item_nutrients (
    food_log_item_id uuid NOT NULL REFERENCES food_log_items(id) ON DELETE CASCADE,
    nutrient_id smallint NOT NULL REFERENCES nutrients(id) ON DELETE RESTRICT,
    amount nonnegative_numeric NOT NULL,
    unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    calculation_source varchar(30) NOT NULL CHECK (calculation_source IN ('CATALOG', 'USER_OVERRIDE', 'ESTIMATED')),
    PRIMARY KEY (food_log_item_id, nutrient_id)
);

CREATE TABLE food_analysis_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    input_type varchar(20) NOT NULL CHECK (input_type IN ('TEXT', 'IMAGE')),
    input_text text,
    image_media_id uuid REFERENCES media_files(id) ON DELETE SET NULL,
    status varchar(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'READY_FOR_CONFIRMATION', 'CONFIRMED', 'FAILED', 'CANCELLED')),
    ai_run_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT food_analysis_input_ck CHECK (
        (input_type = 'TEXT' AND input_text IS NOT NULL)
        OR (input_type = 'IMAGE' AND image_media_id IS NOT NULL)
    )
);

CREATE TABLE food_analysis_candidates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id uuid NOT NULL REFERENCES food_analysis_requests(id) ON DELETE CASCADE,
    candidate_order integer NOT NULL CHECK (candidate_order > 0),
    detected_name varchar(200) NOT NULL,
    matched_food_id uuid REFERENCES foods(id) ON DELETE SET NULL,
    estimated_quantity numeric(18,6) CHECK (estimated_quantity IS NULL OR estimated_quantity > 0),
    estimated_unit_id smallint REFERENCES measurement_units(id) ON DELETE RESTRICT,
    confidence_score percentage_0_100,
    bounding_box jsonb,
    user_action varchar(20) CHECK (user_action IN ('ACCEPTED', 'EDITED', 'REJECTED')),
    resulting_food_log_item_id uuid REFERENCES food_log_items(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (request_id, candidate_order)
);

CREATE TABLE daily_nutrition_summaries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    summary_date date NOT NULL,
    nutrient_id smallint NOT NULL REFERENCES nutrients(id) ON DELETE RESTRICT,
    consumed_amount nonnegative_numeric NOT NULL DEFAULT 0,
    target_amount nonnegative_numeric,
    unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    adherence_percentage percentage_0_100,
    calculated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (student_id, summary_date, nutrient_id)
);

