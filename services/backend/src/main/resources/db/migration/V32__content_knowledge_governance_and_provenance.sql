SET search_path TO fitness, public;

-- Forward migration V32: Content knowledge governance, provenance, and publish guards.
-- Extends V9 and V15 knowledge schema for F01/F09/P2-M2 general knowledge corpus management.

ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS document_code varchar(100) UNIQUE,
    ADD COLUMN IF NOT EXISTS source_id varchar(100) NOT NULL DEFAULT 'SYSTEM',
    ADD COLUMN IF NOT EXISTS language varchar(10) NOT NULL DEFAULT 'vi';

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_source_id ON knowledge_documents(source_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_doc_code ON knowledge_documents(document_code);

-- Safe addition of version_label: preserve legacy version_number records without key collisions
ALTER TABLE knowledge_versions
    ADD COLUMN IF NOT EXISTS version_label varchar(50),
    ADD COLUMN IF NOT EXISTS technical_readiness varchar(50) NOT NULL DEFAULT 'DRAFT_STAGED'
        CHECK (technical_readiness IN ('DRAFT_STAGED', 'TECHNICAL_VERIFICATION_PASSED', 'HELD_AS_DRAFT')),
    ADD COLUMN IF NOT EXISTS hold_reason text,
    ADD COLUMN IF NOT EXISTS content_path text,
    ADD COLUMN IF NOT EXISTS source_bytes integer CHECK (source_bytes IS NULL OR source_bytes >= 0),
    ADD COLUMN IF NOT EXISTS review_evidence_id varchar(100),
    ADD COLUMN IF NOT EXISTS lineage_base_document_id varchar(100),
    ADD COLUMN IF NOT EXISTS lineage_base_version varchar(50),
    ADD COLUMN IF NOT EXISTS lineage_base_hash varchar(64),
    ADD COLUMN IF NOT EXISTS content text;

-- Populate version_label from existing version_number for any legacy records
-- Temporarily disable immutability trigger for migration backfill
ALTER TABLE knowledge_versions DISABLE TRIGGER protect_knowledge_version_content;

UPDATE knowledge_versions
SET version_label = version_number::text
WHERE version_label IS NULL;

ALTER TABLE knowledge_versions ENABLE TRIGGER protect_knowledge_version_content;

ALTER TABLE knowledge_versions
    ALTER COLUMN version_label SET NOT NULL,
    ALTER COLUMN version_label SET DEFAULT '1.0.0';

CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_versions_doc_version_label
    ON knowledge_versions(knowledge_document_id, version_label);

CREATE INDEX IF NOT EXISTS idx_knowledge_versions_status_readiness ON knowledge_versions(status, technical_readiness);
CREATE INDEX IF NOT EXISTS idx_knowledge_versions_review_evidence ON knowledge_versions(review_evidence_id);

-- Add monotonic review sequence, review provenance, and content hash verification columns to reviews
ALTER TABLE knowledge_version_reviews
    ADD COLUMN IF NOT EXISTS review_sequence bigint GENERATED ALWAYS AS IDENTITY,
    ADD COLUMN IF NOT EXISTS content_hash varchar(64),
    ADD COLUMN IF NOT EXISTS reviewer_identity text,
    ADD COLUMN IF NOT EXISTS reviewer_role varchar(50);

CREATE INDEX IF NOT EXISTS idx_knowledge_version_reviews_ver_seq
    ON knowledge_version_reviews(knowledge_version_id, reviewed_at DESC, review_sequence DESC);

-- Add bounded lease, worker locking, and retry columns for outbox processing
ALTER TABLE knowledge_processing_runs
    ADD COLUMN IF NOT EXISTS operation_key varchar(200),
    ADD COLUMN IF NOT EXISTS locked_at timestamptz,
    ADD COLUMN IF NOT EXISTS locked_by varchar(100),
    ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_attempts integer NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS next_attempt_at timestamptz,
    ADD COLUMN IF NOT EXISTS lease_expires_at timestamptz;

-- Ensure an uncompleted operation cannot be enqueued or processed twice simultaneously
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_processing_runs_active_op
    ON knowledge_processing_runs(operation_key)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX IF NOT EXISTS idx_knowledge_processing_runs_status_time
    ON knowledge_processing_runs(status, created_at);

CREATE INDEX IF NOT EXISTS idx_knowledge_processing_runs_claim
    ON knowledge_processing_runs(status, next_attempt_at, lease_expires_at, created_at);

-- Enforce strict publish guardrail:
-- 1. technical_readiness must strictly be TECHNICAL_VERIFICATION_PASSED (fail-closed for DRAFT_STAGED, HELD_AS_DRAFT, etc.)
-- 2. Must have publisher
-- 3. Latest effective review MUST be APPROVED and have non-empty content_hash matching version content hash
CREATE OR REPLACE FUNCTION validate_knowledge_publish()
RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE
    v_latest_decision varchar(20);
    v_latest_hash varchar(64);
BEGIN
    IF NEW.status = 'ACTIVE' AND OLD.status <> 'ACTIVE' THEN
        IF NEW.technical_readiness IS DISTINCT FROM 'TECHNICAL_VERIFICATION_PASSED' THEN
            RAISE EXCEPTION 'Knowledge version technical readiness must be TECHNICAL_VERIFICATION_PASSED to publish';
        END IF;
        IF NEW.published_by IS NULL OR NEW.published_at IS NULL THEN
            RAISE EXCEPTION 'Knowledge version requires publisher before activation';
        END IF;

        -- Order by reviewed_at DESC, review_sequence DESC to guarantee stable deterministic ordering
        SELECT r.decision, r.content_hash INTO v_latest_decision, v_latest_hash
        FROM knowledge_version_reviews r
        WHERE r.knowledge_version_id = NEW.id
        ORDER BY r.reviewed_at DESC, r.review_sequence DESC
        LIMIT 1;

        IF v_latest_decision IS NULL OR v_latest_decision <> 'APPROVED' THEN
            RAISE EXCEPTION 'Knowledge version requires effective APPROVED review before activation';
        END IF;

        IF v_latest_hash IS NULL OR btrim(v_latest_hash) = '' OR v_latest_hash <> NEW.content_hash THEN
            RAISE EXCEPTION 'Knowledge version content hash does not match approved review hash';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_validate_knowledge_publish ON knowledge_versions;
CREATE TRIGGER trg_validate_knowledge_publish
    BEFORE UPDATE OF status ON knowledge_versions
    FOR EACH ROW EXECUTE FUNCTION validate_knowledge_publish();

-- Note: Content immutability for ACTIVE and ARCHIVED versions is already enforced
-- at the database level by V16 function protect_governed_version_content()
-- which rejects modifications to published content with 'Published content is immutable'.

-- Seed Content management and governance permissions
INSERT INTO permissions(code, description) VALUES
('KNOWLEDGE_IMPORT', 'Import knowledge corpus bundles and packages'),
('KNOWLEDGE_REVIEW', 'Perform expert/specialist review on knowledge versions'),
('KNOWLEDGE_ARCHIVE', 'Archive active knowledge versions')
ON CONFLICT (code) DO NOTHING;

-- Grant permissions to ADMIN role
INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN ('KNOWLEDGE_IMPORT', 'KNOWLEDGE_REVIEW', 'KNOWLEDGE_PUBLISH', 'KNOWLEDGE_ARCHIVE')
ON CONFLICT DO NOTHING;
