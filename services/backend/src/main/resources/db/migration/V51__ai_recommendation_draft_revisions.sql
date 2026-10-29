-- V51: Add draft revision, original payload provenance, and customization tracking to ai_recommendations

ALTER TABLE fitness.ai_recommendations
    ADD COLUMN IF NOT EXISTS revision integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS original_payload jsonb,
    ADD COLUMN IF NOT EXISTS is_customized boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS last_modified_by uuid REFERENCES fitness.users(id) ON DELETE SET NULL;

-- Backfill original_payload with structured_payload for any existing rows
UPDATE fitness.ai_recommendations
SET original_payload = structured_payload
WHERE original_payload IS NULL;
