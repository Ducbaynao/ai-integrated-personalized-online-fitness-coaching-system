-- V60: Add draft sharing fields to fitness.ai_recommendations for Trainer-Student collaboration
ALTER TABLE fitness.ai_recommendations
    ADD COLUMN IF NOT EXISTS shared_with_student boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS shared_revision integer,
    ADD COLUMN IF NOT EXISTS shared_at timestamptz,
    ADD COLUMN IF NOT EXISTS shared_by uuid REFERENCES fitness.users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS shared_title text,
    ADD COLUMN IF NOT EXISTS shared_explanation text,
    ADD COLUMN IF NOT EXISTS shared_payload jsonb;

CREATE INDEX IF NOT EXISTS idx_ai_recommendations_sharing
    ON fitness.ai_recommendations(student_id, shared_with_student)
    WHERE shared_with_student IS TRUE;
