SET search_path TO fitness, public;

-- V5 rows retain unknown period and local-time intent. No inferred backfill.
-- Report uncertain legacy data before adding only new-row constraints. An
-- operator must review these counts before enabling the new command writers.
DO $$
DECLARE legacy_appointments bigint;
DECLARE legacy_student_overlaps bigint;
DECLARE legacy_coach_links bigint;
DECLARE non_chat_notices bigint;
BEGIN
    SELECT count(*) INTO legacy_appointments FROM appointments;
    SELECT count(*) INTO legacy_student_overlaps
    FROM appointments a JOIN appointments b ON a.student_id = b.student_id
        AND a.id < b.id AND a.status IN ('SCHEDULED', 'CONFIRMED')
        AND b.status IN ('SCHEDULED', 'CONFIRMED')
        AND tstzrange(a.start_at, a.end_at, '[)') && tstzrange(b.start_at, b.end_at, '[)');
    SELECT count(*) INTO legacy_coach_links
    FROM appointments a JOIN planned_workouts p ON p.id = a.planned_workout_id
    WHERE p.supervision_requirement = 'COACH_REQUIRED';
    SELECT count(*) INTO non_chat_notices FROM notifications WHERE event_type <> 'CHAT_MESSAGE';
    RAISE NOTICE 'V45 legacy appointments requiring period/local-intent review: %; Student overlaps: %; linked COACH_REQUIRED rows requiring pair/interval review: %; non-Chat notifications: %',
        legacy_appointments, legacy_student_overlaps, legacy_coach_links, non_chat_notices;
END; $$;

ALTER TABLE appointments
    ADD COLUMN coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE RESTRICT,
    ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN local_start timestamp without time zone,
    ADD COLUMN local_end timestamp without time zone,
    ADD COLUMN start_utc_offset varchar(6),
    ADD COLUMN end_utc_offset varchar(6),
    ADD COLUMN guarded_v2 boolean NOT NULL DEFAULT false;

ALTER TABLE appointments
    ADD CONSTRAINT appointment_period_parties_v2_fk
        FOREIGN KEY (coaching_period_id, student_id, trainer_id)
        REFERENCES coaching_periods(id, student_id, trainer_id) ON DELETE RESTRICT,
    ADD CONSTRAINT appointment_guarded_v2_complete_ck CHECK (
        NOT guarded_v2 OR (
            trainer_id IS NOT NULL AND coaching_period_id IS NOT NULL
            AND local_start IS NOT NULL AND local_end IS NOT NULL
            AND start_utc_offset IS NOT NULL AND end_utc_offset IS NOT NULL
            AND start_utc_offset ~ '^[+-](0[0-9]|1[0-4]):[0-5][0-9]$'
            AND end_utc_offset ~ '^[+-](0[0-9]|1[0-4]):[0-5][0-9]$'
        )
    );

-- This excludes only verified V45 rows. Application transactions must also
-- inspect unguarded V5 rows while holding the Student/Trainer lock boundary.
ALTER TABLE appointments ADD CONSTRAINT appointment_student_no_overlap_v2
    EXCLUDE USING gist (
        student_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    ) WHERE (guarded_v2 AND status = 'CONFIRMED');
CREATE INDEX ix_appointment_student_history_v2
    ON appointments(student_id, start_at DESC, id DESC);
CREATE INDEX ix_appointment_period_history_v2
    ON appointments(coaching_period_id, start_at DESC, id DESC)
    WHERE coaching_period_id IS NOT NULL;

-- The default FALSE is intentional for rows that predate V45. A new writer
-- must opt into the complete V45 identity/intent CHECK and partial exclusion;
-- otherwise an omitted or explicitly FALSE flag would bypass both.
CREATE FUNCTION require_guarded_v2_appointment_write() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.guarded_v2 IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'new Appointment must use the V45 guard'
            USING ERRCODE = '23514', CONSTRAINT = 'appointment_guarded_v2_insert_ck';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.guarded_v2 AND NEW.guarded_v2 IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'guarded Appointment cannot drop the V45 guard'
            USING ERRCODE = '23514', CONSTRAINT = 'appointment_guarded_v2_downgrade_ck';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER appointment_guarded_v2_insert_ck
    BEFORE INSERT ON appointments
    FOR EACH ROW EXECUTE FUNCTION require_guarded_v2_appointment_write();
CREATE TRIGGER appointment_guarded_v2_downgrade_ck
    BEFORE UPDATE OF guarded_v2 ON appointments
    FOR EACH ROW EXECUTE FUNCTION require_guarded_v2_appointment_write();

-- Replace the V16 weak status-only execution predicate. This check is a
-- database backstop for direct SQL writers as well as the B05 command guard.
CREATE OR REPLACE FUNCTION validate_coach_required_execution() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
DECLARE planned planned_workouts%ROWTYPE;
DECLARE qualifying_id uuid;
BEGIN
    IF NEW.planned_workout_id IS NULL THEN RETURN NEW; END IF;
    SELECT * INTO planned FROM planned_workouts WHERE id=NEW.planned_workout_id;
    IF planned.supervision_requirement <> 'COACH_REQUIRED' THEN RETURN NEW; END IF;
    SELECT a.id INTO qualifying_id
    FROM appointments a JOIN coaching_periods cp ON cp.id=a.coaching_period_id
    WHERE a.planned_workout_id=planned.id AND a.status='CONFIRMED'
      AND a.student_id=planned.student_id AND a.student_id=cp.student_id
      AND a.trainer_id=cp.trainer_id AND planned.coaching_period_id=cp.id
      AND cp.mode='HUMAN_COACH' AND cp.started_at<=clock_timestamp()
      AND (cp.ended_at IS NULL OR cp.ended_at>clock_timestamp())
      AND planned.planned_end_at IS NOT NULL
      AND a.start_at<=planned.planned_start_at AND a.end_at>=planned.planned_end_at
      AND clock_timestamp()>=a.start_at AND clock_timestamp()<a.end_at
    ORDER BY a.id LIMIT 1 FOR SHARE OF a;
    IF qualifying_id IS NULL THEN
        RAISE EXCEPTION 'COACH_REQUIRED workout needs current qualifying confirmed Appointment';
    END IF;
    RETURN NEW;
END; $$;

CREATE TABLE appointment_requests_v2 (
    id uuid PRIMARY KEY,
    kind varchar(16) NOT NULL CHECK (kind IN ('CREATE', 'RESCHEDULE')),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE RESTRICT,
    coaching_period_id uuid NOT NULL,
    planned_workout_id uuid REFERENCES planned_workouts(id) ON DELETE RESTRICT,
    appointment_id uuid REFERENCES appointments(id) ON DELETE RESTRICT,
    observed_appointment_version bigint CHECK (observed_appointment_version >= 0),
    requested_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    local_start timestamp without time zone NOT NULL,
    local_end timestamp without time zone NOT NULL,
    timezone varchar(64) NOT NULL,
    start_utc_offset varchar(6) NOT NULL,
    end_utc_offset varchar(6) NOT NULL,
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    decided_at timestamptz,
    decided_by uuid REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT appointment_request_period_parties_v2_fk
        FOREIGN KEY (coaching_period_id, student_id, trainer_id)
        REFERENCES coaching_periods(id, student_id, trainer_id) ON DELETE RESTRICT,
    CONSTRAINT appointment_request_actor_v2_ck
        CHECK (requested_by IN (student_id, trainer_id)),
    CONSTRAINT appointment_request_interval_v2_ck CHECK (end_at > start_at),
    CONSTRAINT appointment_request_offset_v2_ck CHECK (
        start_utc_offset ~ '^[+-](0[0-9]|1[0-4]):[0-5][0-9]$'
        AND end_utc_offset ~ '^[+-](0[0-9]|1[0-4]):[0-5][0-9]$'),
    CONSTRAINT appointment_request_kind_v2_ck CHECK (
        (kind = 'CREATE' AND observed_appointment_version IS NULL)
        OR (kind = 'RESCHEDULE' AND appointment_id IS NOT NULL
            AND observed_appointment_version IS NOT NULL)),
    CONSTRAINT appointment_request_decision_v2_ck CHECK (
        (status = 'PENDING' AND decided_at IS NULL AND decided_by IS NULL)
        OR (status <> 'PENDING' AND decided_at IS NOT NULL AND decided_by IS NOT NULL))
);
CREATE INDEX ix_appointment_request_student_v2
    ON appointment_requests_v2(student_id, created_at DESC, id DESC);
CREATE INDEX ix_appointment_request_trainer_v2
    ON appointment_requests_v2(trainer_id, created_at DESC, id DESC);
CREATE INDEX ix_appointment_request_appointment_v2
    ON appointment_requests_v2(appointment_id, created_at DESC, id DESC)
    WHERE appointment_id IS NOT NULL;
CREATE UNIQUE INDEX uq_appointment_pending_reschedule_v2
    ON appointment_requests_v2(appointment_id)
    WHERE kind = 'RESCHEDULE' AND status = 'PENDING';

CREATE TABLE appointment_command_receipts_v2 (
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key uuid NOT NULL,
    command_type varchar(40) NOT NULL,
    payload_hash char(64) NOT NULL,
    request_id uuid REFERENCES appointment_requests_v2(id) ON DELETE RESTRICT,
    appointment_id uuid REFERENCES appointments(id) ON DELETE RESTRICT,
    response_json jsonb NOT NULL,
    committed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (actor_id, command_key),
    CONSTRAINT appointment_receipt_subject_v2_ck
        CHECK (request_id IS NOT NULL OR appointment_id IS NOT NULL)
);

CREATE TABLE appointment_events_v2 (
    id uuid PRIMARY KEY,
    appointment_id uuid REFERENCES appointments(id) ON DELETE RESTRICT,
    request_id uuid REFERENCES appointment_requests_v2(id) ON DELETE RESTRICT,
    event_type varchar(50) NOT NULL,
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    old_state jsonb,
    new_state jsonb,
    reason text,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT appointment_event_subject_v2_ck
        CHECK (appointment_id IS NOT NULL OR request_id IS NOT NULL)
);

CREATE FUNCTION reject_appointment_v2_history_change() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    RAISE EXCEPTION 'Appointment command receipt and event history are append-only';
END; $$;
CREATE TRIGGER trg_appointment_events_v2_immutable
    BEFORE UPDATE OR DELETE ON appointment_events_v2 FOR EACH ROW
    EXECUTE FUNCTION reject_appointment_v2_history_change();
CREATE TRIGGER trg_appointment_receipts_v2_immutable
    BEFORE UPDATE OR DELETE ON appointment_command_receipts_v2 FOR EACH ROW
    EXECUTE FUNCTION reject_appointment_v2_history_change();

-- A source event ID is an immutable transition ID, never an aggregate ID.
-- Null preserves V8/V43 legacy rows and their Chat-specific dedupe index.
ALTER TABLE notifications
    ADD COLUMN source_event_id uuid,
    ADD COLUMN source_student_id uuid REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    ADD COLUMN source_trainer_id uuid REFERENCES trainer_profiles(user_id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uq_lifecycle_event_notification_v2
    ON notifications(source_event_id, recipient_user_id, event_type)
    WHERE source_event_id IS NOT NULL;
ALTER TABLE notifications ADD CONSTRAINT lifecycle_source_subject_v2_ck CHECK (
    source_event_id IS NULL OR (related_entity_type IS NOT NULL
        AND related_entity_id IS NOT NULL AND source_student_id IS NOT NULL));
