SET search_path TO fitness, public;

-- Normalize the legacy fourth lifecycle state before tightening the contract.
UPDATE exercises
SET admin_status = 'ARCHIVED',
    updated_at = now()
WHERE admin_status = 'INACTIVE';

ALTER TABLE exercises
    DROP CONSTRAINT exercises_admin_status_check;

ALTER TABLE exercises
    ADD CONSTRAINT exercises_admin_status_check
        CHECK (admin_status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    ADD COLUMN version bigint NOT NULL DEFAULT 0
        CHECK (version >= 0);

CREATE INDEX idx_exercises_admin_status_name_id
    ON exercises(admin_status, lower(name), id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_exercises_admin_updated_id
    ON exercises(updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- CATALOG_MANAGE is a platform-content permission, not coaching authority.
INSERT INTO permissions(code, description)
VALUES ('CATALOG_MANAGE', 'Manage the governed Exercise catalog')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'CATALOG_MANAGE'
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

-- Existing mappings imply that their source is retired content. Normalize the source
-- before enabling the stricter mapping guard. Targets must already be usable ACTIVE content.
UPDATE exercises e
SET admin_status = 'ARCHIVED',
    updated_at = now(),
    version = version + 1
FROM exercise_canonical_mappings m
WHERE e.id = m.duplicate_exercise_id
  AND e.admin_status <> 'ARCHIVED';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM exercise_canonical_mappings m
        JOIN exercises target ON target.id = m.canonical_exercise_id
        WHERE target.admin_status <> 'ACTIVE'
           OR target.deleted_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Existing canonical mapping targets must be active and non-deleted before V22';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM exercise_canonical_mappings source_mapping
        JOIN exercise_canonical_mappings target_mapping
          ON target_mapping.duplicate_exercise_id = source_mapping.canonical_exercise_id
    ) THEN
        RAISE EXCEPTION 'Existing canonical mappings must not contain chains before V22';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION validate_exercise_canonical_mapping()
RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE
    source_status varchar(30);
    source_deleted_at timestamptz;
    target_status varchar(30);
    target_deleted_at timestamptz;
BEGIN
    IF NEW.duplicate_exercise_id = NEW.canonical_exercise_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'Canonical Exercise cannot reference itself';
    END IF;

    SELECT admin_status, deleted_at
      INTO source_status, source_deleted_at
      FROM exercises
     WHERE id = NEW.duplicate_exercise_id;

    SELECT admin_status, deleted_at
      INTO target_status, target_deleted_at
      FROM exercises
     WHERE id = NEW.canonical_exercise_id;

    IF source_status IS DISTINCT FROM 'ARCHIVED' OR source_deleted_at IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'Canonical source Exercise must be archived and non-deleted';
    END IF;

    IF target_status IS DISTINCT FROM 'ACTIVE' OR target_deleted_at IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'Canonical target Exercise must be active and non-deleted';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM exercise_canonical_mappings m
        WHERE m.duplicate_exercise_id = NEW.canonical_exercise_id
          AND m.duplicate_exercise_id <> NEW.duplicate_exercise_id
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'Canonical mapping chains are not permitted';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM exercise_canonical_mappings m
        WHERE m.canonical_exercise_id = NEW.duplicate_exercise_id
          AND m.duplicate_exercise_id <> NEW.duplicate_exercise_id
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'Canonical mapping cycles are not permitted';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_exercise_canonical_mapping
    BEFORE INSERT OR UPDATE ON exercise_canonical_mappings
    FOR EACH ROW EXECUTE FUNCTION validate_exercise_canonical_mapping();

CREATE OR REPLACE FUNCTION protect_exercise_canonical_target()
RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF OLD.admin_status = 'ACTIVE'
       AND NEW.admin_status = 'ARCHIVED'
       AND EXISTS (
           SELECT 1
           FROM exercise_canonical_mappings m
           WHERE m.canonical_exercise_id = OLD.id
       ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'Exercise is an active canonical replacement target';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_exercise_canonical_target
    BEFORE UPDATE OF admin_status ON exercises
    FOR EACH ROW EXECUTE FUNCTION protect_exercise_canonical_target();
