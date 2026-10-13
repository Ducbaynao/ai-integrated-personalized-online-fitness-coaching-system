-- Migration V36: Outbox claim fencing token, attempt constraints, and lease recovery guards
SET search_path TO fitness, public;

ALTER TABLE knowledge_processing_runs
    ADD COLUMN IF NOT EXISTS claim_token uuid;

-- Add constraints for attempt boundaries
ALTER TABLE knowledge_processing_runs
    DROP CONSTRAINT IF EXISTS chk_processing_runs_max_attempts,
    ADD CONSTRAINT chk_processing_runs_max_attempts CHECK (max_attempts > 0);

ALTER TABLE knowledge_processing_runs
    DROP CONSTRAINT IF EXISTS chk_processing_runs_attempt_count,
    ADD CONSTRAINT chk_processing_runs_attempt_count CHECK (attempt_count >= 0);

-- Index for claim fencing and lease ownership lookups
CREATE INDEX IF NOT EXISTS idx_knowledge_processing_runs_claim_fence
    ON knowledge_processing_runs(id, claim_token, status, lease_expires_at);
