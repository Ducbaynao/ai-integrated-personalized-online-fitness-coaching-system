SET search_path TO fitness, public;

-- A plan version is identified by both its plan and version. Existing rows stay
-- untouched. The NULL guard marks legacy rows; new writes cannot opt out.
ALTER TABLE messages ADD COLUMN shared_parent_id uuid;
ALTER TABLE messages ADD COLUMN shared_guarded_v1 boolean;
ALTER TABLE messages ALTER COLUMN shared_guarded_v1 SET DEFAULT true;

CREATE FUNCTION guard_chat_shared_resource() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.shared_guarded_v1 := true;
    ELSIF OLD.shared_guarded_v1 IS TRUE OR
          ROW(OLD.message_type,OLD.shared_entity_type,OLD.shared_entity_id,OLD.shared_parent_id)
          IS DISTINCT FROM
          ROW(NEW.message_type,NEW.shared_entity_type,NEW.shared_entity_id,NEW.shared_parent_id) THEN
        NEW.shared_guarded_v1 := true;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_guard_chat_shared_resource
    BEFORE INSERT OR UPDATE ON messages FOR EACH ROW
    EXECUTE FUNCTION guard_chat_shared_resource();

ALTER TABLE messages ADD CONSTRAINT chat_shared_resource_shape_ck CHECK (
    shared_guarded_v1 IS DISTINCT FROM TRUE OR CASE
        WHEN message_type = 'WORKOUT_SHARE' THEN COALESCE(
            shared_entity_type = 'WORKOUT_PLAN_VERSION' AND shared_entity_id IS NOT NULL
              AND shared_parent_id IS NOT NULL, false)
        WHEN message_type = 'SCHEDULE_SHARE' THEN COALESCE(
            shared_entity_type IN ('PLANNED_WORKOUT','APPOINTMENT') AND shared_entity_id IS NOT NULL
              AND shared_parent_id IS NULL, false)
        ELSE shared_entity_type IS NULL AND shared_entity_id IS NULL AND shared_parent_id IS NULL
    END
) NOT VALID;
