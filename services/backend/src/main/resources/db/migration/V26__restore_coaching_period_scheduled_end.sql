-- V25 made immediate lifecycle closure safe, but also rejected a scheduled
-- future end. Future-ended periods remain effective until that boundary.
-- Preserve V25's no-backdating and expired-history protection while allowing
-- an open period to be scheduled and an unexpired future end to be shortened.
CREATE OR REPLACE FUNCTION protect_coaching_period_identity() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE
    guard_time timestamptz := clock_timestamp();
BEGIN
    IF OLD.ended_at IS NOT NULL AND OLD.ended_at <= guard_time THEN
        RAISE EXCEPTION USING ERRCODE = 'PZ001', MESSAGE = 'COACHING_PERIOD_EXPIRED_BEFORE_CLOSURE';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.student_id IS DISTINCT FROM OLD.student_id
       OR NEW.mode IS DISTINCT FROM OLD.mode OR NEW.coaching_relationship_id IS DISTINCT FROM OLD.coaching_relationship_id
       OR NEW.trainer_id IS DISTINCT FROM OLD.trainer_id OR NEW.started_at IS DISTINCT FROM OLD.started_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.reason IS DISTINCT FROM OLD.reason
       OR NEW.ended_at IS NULL OR NEW.ended_at <= OLD.started_at
       OR NEW.ended_at < statement_timestamp()
       OR (OLD.ended_at IS NOT NULL AND NEW.ended_at >= OLD.ended_at) THEN
        RAISE EXCEPTION 'Coaching period history is immutable except for shortening an effective interval';
    END IF;
    RETURN NEW;
END;
$$;
