SET search_path TO fitness, public;

-- No existing preference implies disabled. This migration does not opt in any account.
CREATE TABLE push_devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_key uuid NOT NULL,
    expo_token varchar(255) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    registered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (user_id, device_key),
    UNIQUE (expo_token)
);

CREATE TABLE push_delivery_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    device_id uuid NOT NULL REFERENCES push_devices(id) ON DELETE CASCADE,
    category varchar(40) NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'QUEUED' CHECK (status IN
        ('QUEUED','SENDING','TICKET_ACCEPTED','CHECKING_RECEIPT',
         'PROVIDER_ACCEPTED','DEVICE_ACKNOWLEDGED','FAILED','SKIPPED')),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    claim_token uuid,
    lease_expires_at timestamptz,
    send_attempt_count integer NOT NULL DEFAULT 0 CHECK (send_attempt_count BETWEEN 0 AND 1),
    receipt_attempt_count integer NOT NULL DEFAULT 0 CHECK (receipt_attempt_count BETWEEN 0 AND 20),
    expo_ticket_id varchar(255),
    provider_error varchar(100),
    attempted_at timestamptz,
    provider_accepted_at timestamptz,
    device_acknowledged_at timestamptz,
    UNIQUE (notification_id, device_id),
    CONSTRAINT push_claim_lease_consistent CHECK
      ((status IN ('SENDING','CHECKING_RECEIPT')) = (claim_token IS NOT NULL AND lease_expires_at IS NOT NULL))
);

CREATE INDEX idx_push_attempt_queue ON push_delivery_attempts(status, next_attempt_at, id)
    WHERE status IN ('QUEUED','TICKET_ACCEPTED','SENDING','CHECKING_RECEIPT');

CREATE INDEX idx_push_attempt_lease ON push_delivery_attempts(status, lease_expires_at)
    WHERE status IN ('SENDING','CHECKING_RECEIPT');
