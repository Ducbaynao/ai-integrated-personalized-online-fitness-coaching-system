-- V52: Add source_snapshot tracking to ai_recommendations for proposal integrity and revalidation

ALTER TABLE fitness.ai_recommendations
    ADD COLUMN IF NOT EXISTS source_snapshot jsonb;
