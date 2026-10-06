SET search_path TO fitness, public;

CREATE TYPE data_access_level AS ENUM ('VIEW', 'CONTRIBUTE', 'MANAGE');

ALTER TYPE data_scope_code ADD VALUE IF NOT EXISTS 'WORKOUT_PLAN';

ALTER TABLE data_sharing_permissions
    ADD COLUMN access_level data_access_level,
    ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0);

-- Existing FITNESS_GOAL ALLOW rows already authorize Goal proposals in the
-- executable baseline, so CONTRIBUTE is the least-privilege compatible value.
-- Existing read-oriented scopes and DENY decisions remain VIEW-scoped.
UPDATE data_sharing_permissions
SET access_level = CASE
    WHEN data_scope = 'FITNESS_GOAL' AND decision = 'ALLOW' THEN 'CONTRIBUTE'::data_access_level
    ELSE 'VIEW'::data_access_level
END;

ALTER TABLE data_sharing_permissions
    ALTER COLUMN access_level SET NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM data_sharing_permissions left_permission
        JOIN data_sharing_permissions right_permission
          ON left_permission.id < right_permission.id
         AND left_permission.relationship_id = right_permission.relationship_id
         AND left_permission.data_scope = right_permission.data_scope
         AND tstzrange(
                left_permission.valid_from,
                CASE
                    WHEN left_permission.revoked_at IS NULL THEN left_permission.valid_until
                    WHEN left_permission.valid_until IS NULL THEN
                        GREATEST(left_permission.revoked_at, left_permission.valid_from)
                    ELSE GREATEST(
                        LEAST(left_permission.valid_until, left_permission.revoked_at),
                        left_permission.valid_from)
                END,
                '[)')
             && tstzrange(
                right_permission.valid_from,
                CASE
                    WHEN right_permission.revoked_at IS NULL THEN right_permission.valid_until
                    WHEN right_permission.valid_until IS NULL THEN
                        GREATEST(right_permission.revoked_at, right_permission.valid_from)
                    ELSE GREATEST(
                        LEAST(right_permission.valid_until, right_permission.revoked_at),
                        right_permission.valid_from)
                END,
                '[)')
    ) THEN
        RAISE EXCEPTION 'V27 found overlapping data-sharing decisions for one relationship and scope';
    END IF;
END;
$$;

DROP INDEX uq_current_data_sharing_permission;

ALTER TABLE data_sharing_permissions
    ADD CONSTRAINT data_sharing_permission_no_overlap
    EXCLUDE USING gist (
        relationship_id WITH =,
        data_scope WITH =,
        tstzrange(
            valid_from,
            CASE
                WHEN revoked_at IS NULL THEN valid_until
                WHEN valid_until IS NULL THEN GREATEST(revoked_at, valid_from)
                ELSE GREATEST(LEAST(valid_until, revoked_at), valid_from)
            END,
            '[)') WITH &&
    );

CREATE INDEX idx_data_sharing_effective_authority
    ON data_sharing_permissions(student_id, trainer_id, data_scope, valid_from DESC, id DESC);

ALTER TABLE data_sharing_permissions
    DROP CONSTRAINT data_sharing_permissions_relationship_id_fkey,
    DROP CONSTRAINT data_sharing_permissions_student_id_fkey,
    DROP CONSTRAINT data_sharing_permissions_trainer_id_fkey,
    ADD CONSTRAINT data_sharing_permissions_relationship_id_fkey
        FOREIGN KEY (relationship_id) REFERENCES coaching_relationships(id) ON DELETE RESTRICT,
    ADD CONSTRAINT data_sharing_permissions_student_id_fkey
        FOREIGN KEY (student_id) REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    ADD CONSTRAINT data_sharing_permissions_trainer_id_fkey
        FOREIGN KEY (trainer_id) REFERENCES trainer_profiles(user_id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION protect_data_sharing_permission_history() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL
       OR (OLD.valid_until IS NOT NULL AND OLD.valid_until <= clock_timestamp()) THEN
        RAISE EXCEPTION 'Closed data-sharing decision history is immutable';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.relationship_id IS DISTINCT FROM OLD.relationship_id
       OR NEW.student_id IS DISTINCT FROM OLD.student_id
       OR NEW.trainer_id IS DISTINCT FROM OLD.trainer_id
       OR NEW.data_scope IS DISTINCT FROM OLD.data_scope
       OR NEW.decision IS DISTINCT FROM OLD.decision
       OR NEW.access_level IS DISTINCT FROM OLD.access_level
       OR NEW.history_from IS DISTINCT FROM OLD.history_from
       OR NEW.history_until IS DISTINCT FROM OLD.history_until
       OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
       OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
       OR NEW.granted_by IS DISTINCT FROM OLD.granted_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.revoked_at IS NULL
       OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Data-sharing decisions are immutable except for versioned revocation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER data_sharing_permission_history_guard
    BEFORE UPDATE ON data_sharing_permissions
    FOR EACH ROW EXECUTE FUNCTION protect_data_sharing_permission_history();

CREATE TRIGGER data_sharing_permission_no_delete
    BEFORE DELETE ON data_sharing_permissions
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
