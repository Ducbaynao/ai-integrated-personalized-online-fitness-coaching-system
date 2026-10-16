SET search_path TO fitness, public;

INSERT INTO measurement_units(code,symbol,dimension,base_unit_code,multiplier_to_base,offset_to_base)
VALUES ('B07_ML','ml','VOLUME','B07_ML',1,0) ON CONFLICT(code) DO NOTHING;

-- Governed USDA Foundation subset is distinct from V39 Student USER_REPORTED foods and
-- the older generic V3 foods. No legacy row is promoted or assigned invented provenance.
CREATE TABLE nutrition_catalog_foods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fdc_id bigint NOT NULL UNIQUE CHECK (fdc_id > 0),
    data_type varchar(30) NOT NULL CHECK (data_type = 'Foundation'),
    source_name varchar(80) NOT NULL DEFAULT 'USDA FoodData Central',
    source_release varchar(40) NOT NULL,
    source_date date NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
    aggregate_version bigint NOT NULL DEFAULT 0 CHECK (aggregate_version >= 0),
    current_revision_id uuid,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE nutrition_catalog_revisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    food_id uuid NOT NULL REFERENCES nutrition_catalog_foods(id) ON DELETE RESTRICT,
    revision_number integer NOT NULL CHECK (revision_number > 0),
    name varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    -- Source values are per 100 g. Missing nutrient remains NULL, never zero.
    energy_kcal_per_100g numeric(18,6) CHECK (energy_kcal_per_100g >= 0),
    protein_g_per_100g numeric(18,6) CHECK (protein_g_per_100g >= 0),
    carbohydrate_g_per_100g numeric(18,6) CHECK (carbohydrate_g_per_100g >= 0),
    fat_g_per_100g numeric(18,6) CHECK (fat_g_per_100g >= 0),
    source_record_version varchar(80) NOT NULL,
    source_url text NOT NULL,
    reviewed_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reviewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(food_id,revision_number),
    UNIQUE(food_id,id)
);
ALTER TABLE nutrition_catalog_foods ADD CONSTRAINT catalog_current_revision_fk
    FOREIGN KEY (id,current_revision_id)
    REFERENCES nutrition_catalog_revisions(food_id,id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE nutrition_catalog_portions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    revision_id uuid NOT NULL REFERENCES nutrition_catalog_revisions(id) ON DELETE RESTRICT,
    label varchar(100) NOT NULL CHECK (length(btrim(label)) > 0),
    gram_factor numeric(18,9) CHECK (gram_factor > 0),
    grams_per_ml numeric(18,9) CHECK (grams_per_ml > 0),
    factor_source text,
    ml_factor_source text,
    CONSTRAINT catalog_portion_evidence_ck CHECK (
        ((gram_factor IS NULL) OR
         (factor_source IS NOT NULL AND length(btrim(factor_source)) > 0)) AND
        ((grams_per_ml IS NULL) OR
         (ml_factor_source IS NOT NULL AND length(btrim(ml_factor_source)) > 0))),
    UNIQUE(revision_id,label),
    UNIQUE(revision_id,id)
);
CREATE INDEX idx_nutrition_catalog_published_name
    ON nutrition_catalog_foods(status,id) WHERE status='PUBLISHED';

-- B07 catalog items pin their exact immutable revision and quantity evidence. V39/V7
-- rows keep their existing nullable columns and historical nutrient snapshots.
ALTER TABLE food_log_items
    ADD COLUMN nutrition_catalog_revision_id uuid REFERENCES nutrition_catalog_revisions(id) ON DELETE RESTRICT,
    ADD COLUMN nutrition_catalog_portion_id uuid REFERENCES nutrition_catalog_portions(id) ON DELETE RESTRICT,
    ADD COLUMN quantity_basis varchar(12) CHECK (quantity_basis IN ('SERVING','GRAM','MILLILITRE'));
ALTER TABLE food_log_items ADD CONSTRAINT catalog_item_portion_revision_fk
    FOREIGN KEY (nutrition_catalog_revision_id,nutrition_catalog_portion_id)
    REFERENCES nutrition_catalog_portions(revision_id,id) ON DELETE RESTRICT;
ALTER TABLE food_log_items DROP CONSTRAINT b07_food_item_shape_ck;
ALTER TABLE food_log_items ADD CONSTRAINT b07_food_item_shape_ck CHECK (
    entry_kind = 'LEGACY'
    OR (entry_kind = 'DESCRIPTION_ONLY' AND private_food_id IS NULL AND quantity IS NULL AND unit_id IS NULL)
    OR (entry_kind = 'PRIVATE_SERVING' AND private_food_id IS NOT NULL AND quantity > 0
        AND private_food_version IS NOT NULL AND serving_label_snapshot IS NOT NULL)
    OR (entry_kind = 'CATALOG_QUANTIFIED' AND nutrition_catalog_revision_id IS NOT NULL
        AND private_food_id IS NULL AND quantity > 0 AND unit_id IS NOT NULL
        AND gram_weight IS NOT NULL AND gram_weight > 0 AND quantity_basis IS NOT NULL
        AND ((quantity_basis = 'GRAM' AND nutrition_catalog_portion_id IS NULL)
          OR (quantity_basis IN ('SERVING','MILLILITRE') AND nutrition_catalog_portion_id IS NOT NULL)))
);
ALTER TABLE food_log_items ADD CONSTRAINT catalog_item_kind_ck CHECK (
    entry_kind = 'CATALOG_QUANTIFIED' OR
    (nutrition_catalog_revision_id IS NULL AND nutrition_catalog_portion_id IS NULL
     AND quantity_basis IS NULL)
);
ALTER TABLE food_log_items DROP CONSTRAINT food_log_items_entry_kind_check;
ALTER TABLE food_log_items ADD CONSTRAINT food_log_items_entry_kind_check
    CHECK (entry_kind IN ('LEGACY','PRIVATE_SERVING','DESCRIPTION_ONLY','CATALOG_QUANTIFIED'));

-- Original V40 snapshot/target rows remain append-only. Corrections form a per-nutrient
-- version chain; a missing legacy snapshot cannot acquire fabricated provenance.
CREATE TABLE nutrition_frozen_target_corrections (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL,
    target_date date NOT NULL,
    nutrient_id smallint NOT NULL REFERENCES nutrients(id) ON DELETE RESTRICT,
    version bigint NOT NULL CHECK (version > 0),
    original_target_id uuid NOT NULL REFERENCES resolved_daily_nutrition_targets(id) ON DELETE RESTRICT,
    unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    before_amount nonnegative_numeric NOT NULL,
    after_amount nonnegative_numeric NOT NULL,
    reason text NOT NULL CHECK (length(btrim(reason)) > 0),
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    corrected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (student_id,target_date)
        REFERENCES nutrition_target_day_snapshots(student_id,target_date) ON DELETE RESTRICT,
    UNIQUE(student_id,target_date,nutrient_id,version)
);
CREATE INDEX idx_nutrition_frozen_correction_history
    ON nutrition_frozen_target_corrections(student_id,target_date,nutrient_id,version DESC);
CREATE TRIGGER nutrition_frozen_target_correction_append_only
    BEFORE UPDATE OR DELETE ON nutrition_frozen_target_corrections
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
