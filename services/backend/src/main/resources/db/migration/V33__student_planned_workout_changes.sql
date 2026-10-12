SET search_path TO fitness, public;

-- V5 allowed several historical children of one predecessor. Do not select an arbitrary
-- successor in the one-to-one read contract or rewrite such history during upgrade.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM planned_workouts WHERE rescheduled_from_id IS NOT NULL
               GROUP BY rescheduled_from_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Ambiguous legacy planned-workout reschedule lineage requires audited resolution';
    END IF;
END $$;

CREATE TABLE workout_schedule_occurrence_changes (
    id uuid PRIMARY KEY,
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    previous_occurrence_id uuid NOT NULL REFERENCES planned_workouts(id) ON DELETE RESTRICT,
    replacement_occurrence_id uuid UNIQUE REFERENCES planned_workouts(id)
        DEFERRABLE INITIALLY DEFERRED,
    change_type varchar(12) NOT NULL CHECK (change_type IN ('RESCHEDULE', 'CANCEL')),
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    old_planned_start_at timestamptz NOT NULL,
    old_planned_end_at timestamptz,
    new_planned_start_at timestamptz,
    new_planned_end_at timestamptz,
    reason text,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT workout_schedule_change_shape_ck CHECK (
        (change_type='RESCHEDULE' AND replacement_occurrence_id IS NOT NULL
         AND new_planned_start_at IS NOT NULL AND new_planned_end_at IS NOT NULL)
        OR (change_type='CANCEL' AND replacement_occurrence_id IS NULL
            AND new_planned_start_at IS NULL AND new_planned_end_at IS NULL)
    ),
    UNIQUE(previous_occurrence_id)
);

ALTER TABLE planned_workouts
    ADD COLUMN schedule_change_id uuid REFERENCES workout_schedule_occurrence_changes(id) ON DELETE RESTRICT,
    ADD CONSTRAINT planned_workout_change_intent_ck CHECK (
        schedule_change_id IS NULL OR (
            rescheduled_from_id IS NOT NULL AND planned_end_at IS NOT NULL
            AND schedule_local_start IS NOT NULL AND schedule_local_end IS NOT NULL
            AND schedule_timezone IS NOT NULL AND schedule_start_utc_offset IS NOT NULL
            AND schedule_end_utc_offset IS NOT NULL
        )
    );

CREATE UNIQUE INDEX uq_workout_schedule_change_predecessor
    ON planned_workouts(rescheduled_from_id)
    WHERE schedule_change_id IS NOT NULL AND rescheduled_from_id IS NOT NULL;
CREATE INDEX ix_planned_workouts_rescheduled_from
    ON planned_workouts(rescheduled_from_id) WHERE rescheduled_from_id IS NOT NULL;

-- Extend V31 protections to new replacement rows without enrolling or rewriting legacy rows.
CREATE UNIQUE INDEX uq_workout_schedule_materialized_source_instant_v33
    ON planned_workouts(student_id, workout_plan_session_id, planned_start_at)
    WHERE schedule_batch_id IS NOT NULL OR schedule_change_id IS NOT NULL;
ALTER TABLE planned_workouts ADD CONSTRAINT ex_workout_schedule_materialized_occupied_v33
    EXCLUDE USING gist (student_id WITH =,
        tstzrange(planned_start_at, planned_end_at, '[)') WITH &&)
    WHERE ((schedule_batch_id IS NOT NULL OR schedule_change_id IS NOT NULL)
        AND status = 'SCHEDULED');

CREATE TABLE workout_schedule_change_receipts (
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key varchar(120) NOT NULL,
    command_name varchar(20) NOT NULL CHECK (command_name IN ('RESCHEDULE', 'CANCEL')),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    change_id uuid NOT NULL REFERENCES workout_schedule_occurrence_changes(id) ON DELETE RESTRICT,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(actor_id, command_key)
);

CREATE TRIGGER workout_schedule_occurrence_changes_append_only
    BEFORE UPDATE OR DELETE ON workout_schedule_occurrence_changes
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER workout_schedule_change_receipts_append_only
    BEFORE UPDATE OR DELETE ON workout_schedule_change_receipts
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

-- Lock the referenced occurrence while creating a new Appointment link. A concurrent
-- reschedule/cancel holds FOR UPDATE on that row, so a later link cannot slip in after
-- the no-linked-Appointment check and silently attach to its terminal predecessor.
CREATE FUNCTION guard_appointment_link_to_changed_workout() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE planned_status planned_workout_status;
BEGIN
    IF NEW.planned_workout_id IS NULL THEN RETURN NEW; END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.planned_workout_id IS NOT DISTINCT FROM OLD.planned_workout_id THEN RETURN NEW; END IF;
    END IF;
    SELECT status INTO planned_status FROM planned_workouts WHERE id=NEW.planned_workout_id FOR SHARE;
    IF planned_status IN ('RESCHEDULED','CANCELLED') AND EXISTS (
        SELECT 1 FROM workout_schedule_occurrence_changes
        WHERE previous_occurrence_id=NEW.planned_workout_id
    ) THEN
        RAISE EXCEPTION 'Appointment cannot link to a changed planned workout';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_appointment_link_to_changed_workout
    BEFORE INSERT OR UPDATE OF planned_workout_id ON appointments
    FOR EACH ROW EXECUTE FUNCTION guard_appointment_link_to_changed_workout();
