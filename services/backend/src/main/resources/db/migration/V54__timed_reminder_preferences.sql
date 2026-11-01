SET search_path TO fitness, public;

-- Existing notification and push preferences do not imply timed-reminder consent.
ALTER TABLE notifications ADD COLUMN inbox_visible boolean NOT NULL DEFAULT true;

CREATE TABLE timed_reminders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_type varchar(24) NOT NULL CHECK (source_type IN ('APPOINTMENT','PLANNED_WORKOUT')),
    source_id uuid NOT NULL,
    source_version bigint NOT NULL CHECK (source_version >= 0),
    recipient_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    channel notification_channel NOT NULL CHECK (channel IN ('IN_APP','PUSH')),
    remind_at timestamptz NOT NULL,
    source_start_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('SKIPPED','EMITTED')),
    notification_id uuid REFERENCES notifications(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT timed_reminder_notice_ck CHECK
        ((status='EMITTED') = (notification_id IS NOT NULL)),
    CONSTRAINT timed_reminder_before_start_ck CHECK (remind_at < source_start_at),
    UNIQUE (source_type,source_id,source_version,recipient_user_id,channel,remind_at)
);

CREATE INDEX idx_timed_reminders_notification ON timed_reminders(notification_id)
    WHERE notification_id IS NOT NULL;
CREATE INDEX idx_appointment_reminder_due ON appointments(start_at,id)
    WHERE guarded_v2 AND status='CONFIRMED' AND trainer_id IS NOT NULL
      AND coaching_period_id IS NOT NULL;

-- Source writers already hold their owner row. Invalidate only unclaimed push
-- attempts in that same transaction. A SENDING attempt may already have reached
-- Expo; preserve its claim so the ticket/error and later receipt can be recorded.
-- Emitted in-app history is not rewritten.
CREATE FUNCTION invalidate_timed_reminder_push() RETURNS trigger
LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF TG_TABLE_NAME = 'appointments' THEN
        IF NEW.version IS NOT DISTINCT FROM OLD.version
           AND NEW.start_at IS NOT DISTINCT FROM OLD.start_at
           AND NEW.status IS NOT DISTINCT FROM OLD.status THEN RETURN NEW; END IF;
        UPDATE push_delivery_attempts a SET status='SKIPPED',claim_token=NULL,lease_expires_at=NULL
        FROM timed_reminders r
        WHERE a.notification_id=r.notification_id AND r.source_type='APPOINTMENT'
          AND r.source_id=NEW.id AND a.status='QUEUED';
    ELSE
        IF NEW.version IS NOT DISTINCT FROM OLD.version
           AND NEW.planned_start_at IS NOT DISTINCT FROM OLD.planned_start_at
           AND NEW.status IS NOT DISTINCT FROM OLD.status
           AND NEW.supervision_requirement IS NOT DISTINCT FROM OLD.supervision_requirement
           THEN RETURN NEW; END IF;
        UPDATE push_delivery_attempts a SET status='SKIPPED',claim_token=NULL,lease_expires_at=NULL
        FROM timed_reminders r
        WHERE a.notification_id=r.notification_id AND r.source_type='PLANNED_WORKOUT'
          AND r.source_id=NEW.id AND a.status='QUEUED';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER appointment_reminder_invalidate
    AFTER UPDATE ON appointments FOR EACH ROW
    EXECUTE FUNCTION invalidate_timed_reminder_push();
CREATE TRIGGER workout_reminder_invalidate
    AFTER UPDATE ON planned_workouts FOR EACH ROW
    EXECUTE FUNCTION invalidate_timed_reminder_push();
