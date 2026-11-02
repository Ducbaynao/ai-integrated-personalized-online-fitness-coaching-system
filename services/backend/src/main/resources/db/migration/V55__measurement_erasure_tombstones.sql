SET search_path TO fitness, public;

-- V6 required exactly one value. An erased measurement retains only its
-- stable identity/provenance skeleton; it must never masquerade as a reading.
ALTER TABLE measurements
    ADD COLUMN erased_at timestamptz,
    ADD COLUMN erasure_request_id uuid REFERENCES account_erasure_requests(id) ON DELETE RESTRICT;

ALTER TABLE measurements DROP CONSTRAINT measurement_one_value_ck;
ALTER TABLE measurements ADD CONSTRAINT measurement_one_value_ck CHECK (
    (erased_at IS NULL AND erasure_request_id IS NULL
        AND num_nonnulls(numeric_value, text_value, boolean_value) = 1)
    OR
    (erased_at IS NOT NULL AND erasure_request_id IS NOT NULL
        AND num_nonnulls(numeric_value, text_value, boolean_value) = 0
        AND unit_id IS NULL AND external_record_id IS NULL
        AND raw_ingestion_record_id IS NULL AND notes IS NULL
        AND confidence_score IS NULL AND quality_score IS NULL)
);

-- Preserve V12 validation for ordinary observations. Only an existing row
-- belonging to the request's Student may become an irreversible tombstone.
CREATE OR REPLACE FUNCTION validate_measurement_value()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fitness, public
AS $$
DECLARE
    metric metric_definitions%ROWTYPE;
    expected_dimension varchar(40);
    selected_dimension varchar(40);
BEGIN
    IF TG_OP = 'INSERT' AND NEW.erased_at IS NOT NULL THEN
        RAISE EXCEPTION 'Measurement tombstone requires an existing observation';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.erased_at IS NOT NULL THEN
        RAISE EXCEPTION 'Erased measurement is immutable';
    END IF;
    IF NEW.erased_at IS NOT NULL THEN
        IF TG_OP <> 'UPDATE' OR OLD.student_id IS DISTINCT FROM NEW.student_id
            OR NOT EXISTS (SELECT 1 FROM account_erasure_requests r
                JOIN users u ON u.id=r.user_id
                WHERE r.id=NEW.erasure_request_id AND r.user_id=NEW.student_id
                  AND u.status='DISABLED') THEN
            RAISE EXCEPTION 'Measurement erasure request does not match Student';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO metric FROM metric_definitions WHERE id = NEW.metric_definition_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Unknown metric definition';
    END IF;
    IF metric.value_type = 'NUMERIC' AND NEW.numeric_value IS NULL THEN
        RAISE EXCEPTION 'Numeric metric requires numeric_value';
    ELSIF metric.value_type = 'TEXT' AND NEW.text_value IS NULL THEN
        RAISE EXCEPTION 'Text metric requires text_value';
    ELSIF metric.value_type = 'BOOLEAN' AND NEW.boolean_value IS NULL THEN
        RAISE EXCEPTION 'Boolean metric requires boolean_value';
    END IF;
    IF NEW.numeric_value IS NOT NULL AND metric.valid_min IS NOT NULL AND NEW.numeric_value < metric.valid_min THEN
        NEW.validation_status := 'SUSPECT';
    END IF;
    IF NEW.numeric_value IS NOT NULL AND metric.valid_max IS NOT NULL AND NEW.numeric_value > metric.valid_max THEN
        NEW.validation_status := 'SUSPECT';
    END IF;
    IF NEW.numeric_value IS NOT NULL AND metric.default_unit_id IS NOT NULL THEN
        SELECT dimension INTO expected_dimension FROM measurement_units WHERE id = metric.default_unit_id;
        SELECT dimension INTO selected_dimension FROM measurement_units WHERE id = NEW.unit_id;
        IF selected_dimension IS DISTINCT FROM expected_dimension THEN
            RAISE EXCEPTION 'Measurement unit is incompatible with its metric';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- V12's current-value projection must not promote a tombstone even if the
-- historical validation_status was VALID. Preserve prior history/identity.
CREATE OR REPLACE VIEW current_valid_measurements AS
SELECT DISTINCT ON (m.student_id, m.metric_definition_id)
       m.*
FROM measurements m
WHERE m.validation_status = 'VALID'
  AND m.superseded_by_id IS NULL
  AND m.erased_at IS NULL
ORDER BY m.student_id, m.metric_definition_id, m.measured_at DESC, m.created_at DESC;

-- V16 makes observation/provenance immutable. Permit only the explicitly
-- evidenced one-way erasure transition; all other V16 guards remain.
CREATE OR REPLACE FUNCTION protect_measurement_observation()
RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE superseding_student uuid; superseding_metric integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Measurements are lifetime observations and cannot be hard-deleted';
    END IF;
    IF OLD.erased_at IS NOT NULL THEN
        RAISE EXCEPTION 'Erased measurement is immutable';
    END IF;
    IF NEW.erased_at IS NOT NULL THEN
        IF (to_jsonb(NEW) - ARRAY['numeric_value','text_value','boolean_value',
                'unit_id','external_record_id','raw_ingestion_record_id','notes',
                'confidence_score','quality_score','erased_at','erasure_request_id'])
           IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['numeric_value','text_value','boolean_value',
                'unit_id','external_record_id','raw_ingestion_record_id','notes',
                'confidence_score','quality_score','erased_at','erasure_request_id']) THEN
            RAISE EXCEPTION 'Measurement erasure cannot alter historical identity';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - ARRAY['confidence_score','quality_score',
            'validation_status','superseded_by_id','notes']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['confidence_score','quality_score',
            'validation_status','superseded_by_id','notes']) THEN
        RAISE EXCEPTION 'Measurement value and provenance are immutable';
    END IF;
    IF NEW.superseded_by_id IS NOT NULL THEN
        SELECT student_id,metric_definition_id INTO superseding_student,superseding_metric
        FROM measurements WHERE id=NEW.superseded_by_id;
        IF superseding_student IS DISTINCT FROM NEW.student_id
            OR superseding_metric IS DISTINCT FROM NEW.metric_definition_id THEN
            RAISE EXCEPTION 'Superseding measurement must have same student and metric';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
