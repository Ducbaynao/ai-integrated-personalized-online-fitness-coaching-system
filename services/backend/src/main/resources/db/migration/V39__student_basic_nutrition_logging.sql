SET search_path TO fitness, public;

-- Private, explicitly user-reported foods are not governed catalog entries.
CREATE TABLE student_private_foods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    name varchar(200) NOT NULL,
    serving_label varchar(80) NOT NULL,
    energy_kcal_per_serving numeric(18,6) CHECK (energy_kcal_per_serving >= 0),
    protein_g_per_serving numeric(18,6) CHECK (protein_g_per_serving >= 0),
    carbohydrate_g_per_serving numeric(18,6) CHECK (carbohydrate_g_per_serving >= 0),
    fat_g_per_serving numeric(18,6) CHECK (fat_g_per_serving >= 0),
    quantification_status varchar(12) GENERATED ALWAYS AS (
        CASE WHEN energy_kcal_per_serving IS NULL AND protein_g_per_serving IS NULL
            AND carbohydrate_g_per_serving IS NULL AND fat_g_per_serving IS NULL
            THEN 'DRAFT' ELSE 'LOGGABLE' END
    ) STORED,
    source varchar(30) NOT NULL DEFAULT 'USER_REPORTED' CHECK (source = 'USER_REPORTED'),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz,
    CONSTRAINT private_food_name_ck CHECK (length(btrim(name)) > 0),
    CONSTRAINT private_food_serving_ck CHECK (length(btrim(serving_label)) > 0)
);
CREATE INDEX idx_student_private_foods_owner ON student_private_foods(student_id, created_at DESC, id DESC);

-- Existing V7 rows remain readable. Only the B07 description-only entry kind may omit quantity/unit.
ALTER TABLE food_log_items
    ALTER COLUMN quantity DROP NOT NULL,
    ALTER COLUMN unit_id DROP NOT NULL,
    ADD COLUMN private_food_id uuid REFERENCES student_private_foods(id) ON DELETE RESTRICT,
    ADD COLUMN entry_kind varchar(24) NOT NULL DEFAULT 'LEGACY'
        CHECK (entry_kind IN ('LEGACY', 'PRIVATE_SERVING', 'DESCRIPTION_ONLY')),
    ADD COLUMN item_version bigint NOT NULL DEFAULT 0 CHECK (item_version >= 0),
    ADD COLUMN private_food_version bigint,
    ADD COLUMN serving_label_snapshot varchar(80),
    ADD COLUMN deleted_at timestamptz,
    ADD CONSTRAINT b07_food_item_shape_ck CHECK (
        entry_kind = 'LEGACY'
        OR (entry_kind = 'DESCRIPTION_ONLY' AND private_food_id IS NULL AND quantity IS NULL AND unit_id IS NULL)
        OR (entry_kind = 'PRIVATE_SERVING' AND private_food_id IS NOT NULL AND quantity > 0
            AND private_food_version IS NOT NULL AND serving_label_snapshot IS NOT NULL)
    );
CREATE INDEX idx_b07_food_items_private ON food_log_items(private_food_id) WHERE private_food_id IS NOT NULL;

ALTER TABLE meals
    ADD COLUMN log_date date,
    ADD COLUMN timezone varchar(64),
    ADD COLUMN meal_version bigint NOT NULL DEFAULT 0 CHECK (meal_version >= 0);
CREATE INDEX idx_b07_meals_student_day ON meals(student_id, log_date, consumed_at, id)
    WHERE deleted_at IS NULL AND log_date IS NOT NULL;

ALTER TABLE daily_nutrition_log_status
    ADD COLUMN timezone varchar(64),
    ADD COLUMN day_version bigint NOT NULL DEFAULT 0 CHECK (day_version >= 0),
    ADD COLUMN no_intake_confirmed boolean NOT NULL DEFAULT false,
    ADD COLUMN confirmed_at timestamptz;

-- A nullable total means unknown; known_subtotal is separate and never masquerades as a total.
ALTER TABLE daily_nutrition_summaries
    ALTER COLUMN consumed_amount DROP NOT NULL,
    ADD COLUMN known_subtotal nonnegative_numeric,
    ADD COLUMN unknown_item_count integer NOT NULL DEFAULT 0 CHECK (unknown_item_count >= 0);

CREATE TABLE nutrition_item_corrections (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    food_log_item_id uuid NOT NULL REFERENCES food_log_items(id) ON DELETE RESTRICT,
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    from_version bigint NOT NULL,
    to_version bigint NOT NULL,
    before_json jsonb NOT NULL,
    after_json jsonb NOT NULL,
    corrected_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT nutrition_item_correction_version_ck CHECK (to_version = from_version + 1),
    UNIQUE (food_log_item_id, to_version)
);
CREATE TRIGGER nutrition_item_corrections_append_only BEFORE UPDATE OR DELETE ON nutrition_item_corrections
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE TABLE nutrition_command_receipts (
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key varchar(120) NOT NULL,
    command_name varchar(60) NOT NULL,
    payload_hash char(64) NOT NULL,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, command_key)
);

-- Semantic IDs are resolved by code; never rely on generated smallint identities.
INSERT INTO measurement_units(code, symbol, dimension, base_unit_code, multiplier_to_base, offset_to_base)
VALUES ('B07_SERVING', 'serving', 'COUNT', 'B07_SERVING', 1, 0),
       ('B07_KCAL', 'kcal', 'ENERGY', 'B07_KCAL', 1, 0),
       ('B07_GRAM', 'g', 'MASS', 'B07_GRAM', 1, 0)
ON CONFLICT (code) DO NOTHING;
INSERT INTO nutrients(code, name, default_unit_id, is_macro)
SELECT v.code, v.name, u.id, v.is_macro
FROM (VALUES ('B07_ENERGY_KCAL', 'Energy', 'B07_KCAL', false),
             ('B07_PROTEIN_G', 'Protein', 'B07_GRAM', true),
             ('B07_CARBOHYDRATE_G', 'Carbohydrate', 'B07_GRAM', true),
             ('B07_FAT_G', 'Fat', 'B07_GRAM', true)) AS v(code, name, unit_code, is_macro)
JOIN measurement_units u ON u.code = v.unit_code
ON CONFLICT (code) DO NOTHING;
