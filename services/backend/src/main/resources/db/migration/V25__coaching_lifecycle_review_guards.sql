-- Keep profile deactivation distinct from revocation: existing relationships may be
-- ended after eligibility is lost, but a new ACTIVE transition needs an active profile.
CREATE OR REPLACE FUNCTION validate_trainer_coaching_capability() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE verification trainer_verification_state;
        activity trainer_activity_status;
        profile_active boolean;
BEGIN
    IF NEW.status = 'ACTIVE' THEN
        SELECT verification_status, activity_status, is_active
        INTO verification, activity, profile_active
        FROM trainer_profiles WHERE user_id = NEW.trainer_id;
        IF verification IS DISTINCT FROM 'VERIFIED'
           OR activity IS DISTINCT FROM 'ACTIVE'
           OR profile_active IS DISTINCT FROM true THEN
            RAISE EXCEPTION 'Trainer profile must be eligible for active coaching';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- A close is an immediate transition, not a way to reschedule an authority
-- interval. statement_timestamp() is later than a transaction's start even
-- after earlier lock waits; clock_timestamp() rejects future boundaries.
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
       OR NEW.ended_at < statement_timestamp() OR NEW.ended_at > clock_timestamp()
       OR (OLD.ended_at IS NOT NULL AND NEW.ended_at >= OLD.ended_at) THEN
        RAISE EXCEPTION 'Coaching period history is immutable except for immediate closure';
    END IF;
    RETURN NEW;
END;
$$;
