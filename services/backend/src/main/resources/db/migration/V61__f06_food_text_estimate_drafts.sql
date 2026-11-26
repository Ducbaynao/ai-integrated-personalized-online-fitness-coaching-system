SET search_path TO fitness, public;

-- V7 analysis rows remain legacy-readable. Only new command-keyed TEXT drafts use
-- the F06 context/revision columns; estimates never enter food_log_items here.
ALTER TABLE food_analysis_requests
    ADD COLUMN command_key varchar(120),
    ADD COLUMN request_fingerprint char(64),
    ADD COLUMN log_date date,
    ADD COLUMN timezone varchar(64),
    ADD COLUMN consumed_at timestamptz,
    ADD COLUMN meal_type meal_type,
    ADD COLUMN draft_revision bigint NOT NULL DEFAULT 0 CHECK (draft_revision >= 0),
    ADD COLUMN expires_at timestamptz,
    ADD COLUMN meal_id uuid REFERENCES meals(id) ON DELETE RESTRICT,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN failure_reason varchar(80),
    ADD CONSTRAINT f06_analysis_context_ck CHECK (
        command_key IS NULL OR
        (input_type = 'TEXT' AND request_fingerprint IS NOT NULL
         AND log_date IS NOT NULL AND timezone IS NOT NULL
         AND length(btrim(timezone)) > 0 AND consumed_at IS NOT NULL
         AND meal_type IS NOT NULL)
    );
CREATE UNIQUE INDEX uq_f06_analysis_actor_command
    ON food_analysis_requests(student_id, command_key) WHERE command_key IS NOT NULL;
CREATE INDEX idx_f06_analysis_owner_created
    ON food_analysis_requests(student_id, created_at DESC, id DESC)
    WHERE command_key IS NOT NULL;

-- One row is one detected item, not one alternative catalog candidate. V7
-- food_analysis_candidates remains untouched; it references the old foods table.
CREATE TABLE food_analysis_draft_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id uuid NOT NULL REFERENCES food_analysis_requests(id) ON DELETE CASCADE,
    item_order integer NOT NULL CHECK (item_order > 0),
    item_revision bigint NOT NULL DEFAULT 0 CHECK (item_revision >= 0),
    detected_name varchar(200),
    candidate_names jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(candidate_names) = 'array'),
    estimated_quantity numeric(18,6) CHECK (estimated_quantity > 0),
    estimated_unit_text varchar(80),
    confidence numeric(5,4) CHECK (confidence BETWEEN 0 AND 1),
    missing_data jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(missing_data) = 'array'),
    limitations jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(limitations) = 'array'),
    selected_food_id uuid REFERENCES nutrition_catalog_foods(id) ON DELETE RESTRICT,
    selected_revision_id uuid REFERENCES nutrition_catalog_revisions(id) ON DELETE RESTRICT,
    quantity numeric(18,6) CHECK (quantity > 0),
    quantity_basis varchar(12)
        CHECK (quantity_basis IN ('GRAM', 'SERVING', 'MILLILITRE')),
    portion_id uuid REFERENCES nutrition_catalog_portions(id) ON DELETE RESTRICT,
    confirmed_food_log_item_id uuid UNIQUE REFERENCES food_log_items(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (request_id, item_order),
    UNIQUE (request_id, id),
    FOREIGN KEY (selected_food_id, selected_revision_id)
        REFERENCES nutrition_catalog_revisions(food_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (selected_revision_id, portion_id)
        REFERENCES nutrition_catalog_portions(revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT f06_selected_food_revision_pair_ck CHECK (
        (selected_food_id IS NULL) = (selected_revision_id IS NULL)
    ),
    CONSTRAINT f06_portion_requires_revision_ck CHECK (
        portion_id IS NULL OR selected_revision_id IS NOT NULL
    )
);
CREATE INDEX idx_f06_draft_items_request
    ON food_analysis_draft_items(request_id, item_order);

-- Preview is an immutable selection snapshot. A later draft/catalog revision
-- makes it ineligible for a new confirmation; committed receipts remain stable.
CREATE TABLE food_analysis_previews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id uuid NOT NULL REFERENCES food_analysis_requests(id) ON DELETE CASCADE,
    preview_revision bigint NOT NULL CHECK (preview_revision > 0),
    draft_revision bigint NOT NULL CHECK (draft_revision >= 0),
    command_key varchar(120) NOT NULL,
    payload_hash char(64) NOT NULL,
    selection_json jsonb NOT NULL CHECK (jsonb_typeof(selection_json) = 'array'),
    nutrients_json jsonb NOT NULL CHECK (jsonb_typeof(nutrients_json) = 'object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (request_id, preview_revision),
    UNIQUE (request_id, command_key),
    UNIQUE (request_id, id)
);
CREATE INDEX idx_f06_preview_latest
    ON food_analysis_previews(request_id, preview_revision DESC);

-- Existing actor-scoped Nutrition receipt PK remains the dedupe authority.
-- The UUID gives F06's public receipt a stable identity on exact replay.
ALTER TABLE nutrition_command_receipts
    ADD COLUMN receipt_id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE;
