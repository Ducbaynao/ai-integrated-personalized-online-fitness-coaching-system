-- Forward Migration V37: Full-lifecycle operation identity uniqueness and integrity checks
SET search_path TO fitness, public;

-- Step 1: Pre-check for duplicate legacy operation keys.
-- If legacy data contains duplicate operation keys, fail with clear diagnostic rather than arbitrarily choosing records.
DO $$
DECLARE
    v_dup_count integer;
    v_dup_keys text;
BEGIN
    SELECT count(*), string_agg(operation_key, ', ')
    INTO v_dup_count, v_dup_keys
    FROM (
        SELECT operation_key
        FROM knowledge_processing_runs
        WHERE operation_key IS NOT NULL AND btrim(operation_key) <> ''
        GROUP BY operation_key
        HAVING count(*) > 1
    ) dups;

    IF v_dup_count > 0 THEN
        RAISE EXCEPTION 'Cannot apply V37: Found % duplicate operation_key entries in legacy data: %', v_dup_count, v_dup_keys;
    END IF;
END $$;

-- Step 2: Reconcile legacy records with null/blank operation_key by assigning deterministic legacy identity
UPDATE knowledge_processing_runs
SET operation_key = 'LEGACY:' || id::text
WHERE operation_key IS NULL OR btrim(operation_key) = '';

-- Step 3: Drop legacy partial unique index that only covered QUEUED/RUNNING
DROP INDEX IF EXISTS uq_knowledge_processing_runs_active_op;

-- Step 4: Enforce NOT NULL and non-empty check constraint on operation_key
ALTER TABLE knowledge_processing_runs
    ALTER COLUMN operation_key SET NOT NULL;

ALTER TABLE knowledge_processing_runs
    DROP CONSTRAINT IF EXISTS chk_processing_runs_operation_key_not_empty,
    ADD CONSTRAINT chk_processing_runs_operation_key_not_empty CHECK (btrim(operation_key) <> '');

-- Step 5: Enforce full-lifecycle UNIQUE constraint on operation_key across all statuses
ALTER TABLE knowledge_processing_runs
    DROP CONSTRAINT IF EXISTS uq_knowledge_processing_runs_operation_key,
    ADD CONSTRAINT uq_knowledge_processing_runs_operation_key UNIQUE (operation_key);
