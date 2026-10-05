SET search_path TO fitness, public;

-- Refuse ambiguous legacy state before installing stricter indexes. No row is rewritten.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM coaching_relationships
        WHERE status IN ('ACTIVE', 'PAUSED')
        GROUP BY student_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V24 cannot enforce one current coaching relationship per student';
    END IF;
    IF EXISTS (SELECT 1 FROM coaching_relationships WHERE requested_by NOT IN (student_id, trainer_id)) THEN
        RAISE EXCEPTION 'V24 found a coaching request initiator outside its relationship';
    END IF;
    IF EXISTS (
        SELECT 1 FROM coaching_relationships WHERE NOT (
            (status = 'PENDING' AND accepted_at IS NULL AND started_at IS NULL AND ended_at IS NULL)
            OR (status IN ('ACTIVE', 'PAUSED') AND accepted_at IS NOT NULL AND started_at IS NOT NULL AND ended_at IS NULL)
            OR (status = 'ENDED' AND accepted_at IS NOT NULL AND started_at IS NOT NULL AND ended_at IS NOT NULL)
            OR (status IN ('REJECTED', 'CANCELLED') AND accepted_at IS NULL AND started_at IS NULL AND ended_at IS NULL)
        )
    ) THEN
        RAISE EXCEPTION 'V24 found coaching relationship timestamps inconsistent with lifecycle status';
    END IF;
END;
$$;

ALTER TABLE coaching_relationships
    ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD CONSTRAINT coaching_relationship_initiator_ck CHECK (requested_by IN (student_id, trainer_id)),
    ADD CONSTRAINT coaching_relationship_lifecycle_ck CHECK (
        (status = 'PENDING' AND accepted_at IS NULL AND started_at IS NULL AND ended_at IS NULL)
        OR (status IN ('ACTIVE', 'PAUSED') AND accepted_at IS NOT NULL AND started_at IS NOT NULL AND ended_at IS NULL)
        OR (status = 'ENDED' AND accepted_at IS NOT NULL AND started_at IS NOT NULL AND ended_at IS NOT NULL)
        OR (status IN ('REJECTED', 'CANCELLED') AND accepted_at IS NULL AND started_at IS NULL AND ended_at IS NULL)
    );

CREATE UNIQUE INDEX uq_coaching_current_student
    ON coaching_relationships(student_id) WHERE status IN ('ACTIVE', 'PAUSED');
CREATE INDEX idx_coaching_pending_student
    ON coaching_relationships(student_id, requested_at DESC, id DESC) WHERE status = 'PENDING';
CREATE INDEX idx_coaching_pending_trainer
    ON coaching_relationships(trainer_id, requested_at DESC, id DESC) WHERE status = 'PENDING';

CREATE TABLE coaching_resume_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id uuid NOT NULL REFERENCES coaching_relationships(id) ON DELETE RESTRICT,
    requested_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    requested_at timestamptz NOT NULL DEFAULT now(),
    decided_by uuid REFERENCES users(id) ON DELETE RESTRICT,
    decided_at timestamptz,
    reason varchar(500),
    CONSTRAINT coaching_resume_decision_ck CHECK (
        (status = 'PENDING' AND decided_at IS NULL AND decided_by IS NULL)
        OR (status <> 'PENDING' AND decided_at IS NOT NULL AND decided_by IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_coaching_pending_resume
    ON coaching_resume_requests(relationship_id) WHERE status = 'PENDING';
CREATE INDEX idx_coaching_resume_history
    ON coaching_resume_requests(relationship_id, requested_at DESC, id DESC);

CREATE OR REPLACE FUNCTION validate_coaching_resume_request() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE rel coaching_relationships%ROWTYPE;
BEGIN
    SELECT * INTO rel FROM coaching_relationships WHERE id = NEW.relationship_id;
    IF NOT FOUND OR NEW.requested_by NOT IN (rel.student_id, rel.trainer_id) THEN
        RAISE EXCEPTION 'Resume request initiator must be a relationship participant';
    END IF;
    IF NEW.status = 'PENDING' AND rel.status <> 'PAUSED' THEN
        RAISE EXCEPTION 'Pending resume request requires a paused relationship';
    END IF;
    IF NEW.decided_by IS NOT NULL AND NEW.decided_by NOT IN (rel.student_id, rel.trainer_id) THEN
        RAISE EXCEPTION 'Resume decision actor must be a relationship participant';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status <> 'PENDING' OR NEW.status = 'PENDING' OR NEW.id IS DISTINCT FROM OLD.id
            OR NEW.relationship_id IS DISTINCT FROM OLD.relationship_id
            OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
            OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
            OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.version <> OLD.version + 1 THEN
            RAISE EXCEPTION 'Resume request identity and terminal decisions are immutable';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER coaching_resume_request_guard
    BEFORE INSERT OR UPDATE ON coaching_resume_requests
    FOR EACH ROW EXECUTE FUNCTION validate_coaching_resume_request();

CREATE TABLE coaching_command_receipts (
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key uuid NOT NULL,
    command_name varchar(60) NOT NULL,
    payload_hash char(64) NOT NULL,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, command_key)
);

-- V16 must not block revocation when a trainer loses eligibility.
CREATE OR REPLACE FUNCTION validate_trainer_coaching_capability() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE verification trainer_verification_state; activity trainer_activity_status;
BEGIN
    IF NEW.status = 'ACTIVE' THEN
        SELECT verification_status, activity_status INTO verification, activity
        FROM trainer_profiles WHERE user_id = NEW.trainer_id;
        IF verification IS DISTINCT FROM 'VERIFIED' OR activity IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION 'Trainer must be VERIFIED and ACTIVE';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER coaching_relationship_no_delete
    BEFORE DELETE ON coaching_relationships FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE OR REPLACE FUNCTION protect_coaching_relationship_identity() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF OLD.status IN ('ENDED', 'REJECTED', 'CANCELLED') THEN
        RAISE EXCEPTION 'Terminal coaching relationship is immutable';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.student_id IS DISTINCT FROM OLD.student_id
       OR NEW.trainer_id IS DISTINCT FROM OLD.trainer_id OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
       OR NEW.requested_at IS DISTINCT FROM OLD.requested_at OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR (OLD.accepted_at IS NOT NULL AND NEW.accepted_at IS DISTINCT FROM OLD.accepted_at)
       OR (OLD.started_at IS NOT NULL AND NEW.started_at IS DISTINCT FROM OLD.started_at)
       OR (OLD.ended_at IS NOT NULL AND NEW.ended_at IS DISTINCT FROM OLD.ended_at) THEN
        RAISE EXCEPTION 'Coaching relationship identity and historical timestamps are immutable';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NEW.version <> OLD.version + 1 OR NOT (
            (OLD.status = 'PENDING' AND NEW.status IN ('ACTIVE', 'REJECTED', 'CANCELLED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status IN ('PAUSED', 'ENDED'))
            OR (OLD.status = 'PAUSED' AND NEW.status IN ('ACTIVE', 'ENDED'))
        ) THEN
            RAISE EXCEPTION 'Invalid coaching relationship state transition or version';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER coaching_relationship_identity_guard
    BEFORE UPDATE ON coaching_relationships FOR EACH ROW EXECUTE FUNCTION protect_coaching_relationship_identity();
CREATE TRIGGER coaching_period_no_delete
    BEFORE DELETE ON coaching_periods FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER coaching_resume_no_delete
    BEFORE DELETE ON coaching_resume_requests FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE OR REPLACE FUNCTION protect_coaching_period_identity() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF OLD.ended_at IS NOT NULL AND OLD.ended_at <= clock_timestamp() THEN
        RAISE EXCEPTION USING ERRCODE = 'PZ001', MESSAGE = 'COACHING_PERIOD_EXPIRED_BEFORE_CLOSURE';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.student_id IS DISTINCT FROM OLD.student_id
       OR NEW.mode IS DISTINCT FROM OLD.mode OR NEW.coaching_relationship_id IS DISTINCT FROM OLD.coaching_relationship_id
       OR NEW.trainer_id IS DISTINCT FROM OLD.trainer_id OR NEW.started_at IS DISTINCT FROM OLD.started_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.reason IS DISTINCT FROM OLD.reason
       OR NEW.ended_at IS NULL OR NEW.ended_at <= OLD.started_at
       OR (OLD.ended_at IS NOT NULL AND NEW.ended_at > OLD.ended_at) THEN
        RAISE EXCEPTION 'Coaching period history is immutable except for closing an effective interval';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER coaching_period_history_guard
    BEFORE UPDATE ON coaching_periods FOR EACH ROW EXECUTE FUNCTION protect_coaching_period_identity();
