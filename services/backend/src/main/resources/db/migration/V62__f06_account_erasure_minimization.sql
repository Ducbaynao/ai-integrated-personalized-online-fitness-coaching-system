SET search_path TO fitness, public;

-- V61 context and V7 meal NOT NULLs cannot express erased/unknown clinical data.
-- Keep stable IDs/FKs as processing tombstones; never invent zero nutrition or a fake meal date.
ALTER TABLE food_analysis_requests
    ADD COLUMN erased_at timestamptz,
    ADD COLUMN erasure_request_id uuid REFERENCES account_erasure_requests(id) ON DELETE RESTRICT;
ALTER TABLE food_analysis_requests DROP CONSTRAINT f06_analysis_context_ck;
ALTER TABLE food_analysis_requests ADD CONSTRAINT f06_analysis_context_ck CHECK (
    erased_at IS NOT NULL OR command_key IS NULL OR
    (input_type='TEXT' AND request_fingerprint IS NOT NULL AND log_date IS NOT NULL
     AND timezone IS NOT NULL AND length(btrim(timezone))>0 AND consumed_at IS NOT NULL AND meal_type IS NOT NULL)
);
ALTER TABLE food_analysis_requests ADD CONSTRAINT f06_erasure_tombstone_ck CHECK (
    (erased_at IS NULL AND erasure_request_id IS NULL) OR
    (erased_at IS NOT NULL AND erasure_request_id IS NOT NULL AND input_type='TEXT' AND input_text='ERASED'
     AND log_date IS NULL AND timezone IS NULL AND consumed_at IS NULL AND meal_type IS NULL AND expires_at IS NULL)
);

ALTER TABLE meals
    ADD COLUMN erased_at timestamptz,
    ADD COLUMN erasure_request_id uuid REFERENCES account_erasure_requests(id) ON DELETE RESTRICT,
    ALTER COLUMN consumed_at DROP NOT NULL,
    ALTER COLUMN meal_type DROP NOT NULL;
ALTER TABLE meals ADD CONSTRAINT f06_meal_erasure_tombstone_ck CHECK (
    (erased_at IS NULL AND erasure_request_id IS NULL AND consumed_at IS NOT NULL AND meal_type IS NOT NULL) OR
    (erased_at IS NOT NULL AND erasure_request_id IS NOT NULL AND consumed_at IS NULL AND meal_type IS NULL
     AND log_date IS NULL AND timezone IS NULL AND title IS NULL AND notes IS NULL)
);

CREATE FUNCTION nutrition_f06_tombstone_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.erased_at IS NOT NULL AND (to_jsonb(NEW)-'updated_at') IS DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN
        RAISE EXCEPTION 'F06 erasure tombstones cannot be restored or rewritten';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER f06_analysis_tombstone_guard BEFORE UPDATE ON food_analysis_requests
    FOR EACH ROW EXECUTE FUNCTION nutrition_f06_tombstone_guard();
CREATE TRIGGER f06_meal_tombstone_guard BEFORE UPDATE ON meals
    FOR EACH ROW EXECUTE FUNCTION nutrition_f06_tombstone_guard();

-- Follow V50's evidenced, field-restricted redaction model, not an unrestricted
-- exception to append-only correction history. No new retention interval is introduced.
CREATE TABLE nutrition_f06_erasure_redactions (
    request_id uuid NOT NULL REFERENCES account_erasure_requests(id) ON DELETE RESTRICT,
    correction_id uuid NOT NULL REFERENCES nutrition_item_corrections(id) ON DELETE RESTRICT,
    prior_sha256 char(64) NOT NULL,
    redacted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(request_id,correction_id)
);
CREATE TRIGGER nutrition_f06_erasure_redactions_append_only BEFORE UPDATE OR DELETE
    ON nutrition_f06_erasure_redactions FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE FUNCTION nutrition_f06_correction_redaction_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'nutrition correction history is append-only'; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM fitness.nutrition_f06_erasure_redactions e
        JOIN fitness.account_erasure_requests r ON r.id=e.request_id
        JOIN fitness.users u ON u.id=r.user_id AND u.status='DISABLED'
        JOIN fitness.food_analysis_requests a ON a.student_id=r.user_id AND a.input_type='TEXT' AND a.command_key IS NOT NULL
        JOIN fitness.food_analysis_draft_items i ON i.request_id=a.id AND i.confirmed_food_log_item_id=OLD.food_log_item_id
        WHERE e.correction_id=OLD.id
          AND e.prior_sha256=encode(fitness.digest(to_jsonb(OLD)::text,'sha256'),'hex')
    ) OR (to_jsonb(NEW)-'before_json'-'after_json') IS DISTINCT FROM (to_jsonb(OLD)-'before_json'-'after_json')
      OR NEW.before_json <> '{}'::jsonb OR NEW.after_json <> '{}'::jsonb THEN
        RAISE EXCEPTION 'nutrition correction redaction requires matching erasure evidence and minimal fields';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER nutrition_item_corrections_append_only ON nutrition_item_corrections;
CREATE TRIGGER nutrition_item_corrections_append_only BEFORE UPDATE OR DELETE ON nutrition_item_corrections
    FOR EACH ROW EXECUTE FUNCTION nutrition_f06_correction_redaction_guard();
