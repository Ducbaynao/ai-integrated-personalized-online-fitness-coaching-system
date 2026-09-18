SET search_path TO fitness, public;

CREATE TABLE conversations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_type varchar(30) NOT NULL CHECK (conversation_type IN ('DIRECT_COACHING', 'SUPPORT', 'GROUP')),
    coaching_relationship_id uuid REFERENCES coaching_relationships(id) ON DELETE SET NULL,
    title varchar(200),
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz
);

CREATE TABLE conversation_members (
    conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at timestamptz NOT NULL DEFAULT now(),
    left_at timestamptz,
    last_read_at timestamptz,
    is_muted boolean NOT NULL DEFAULT false,
    PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT conversation_member_dates_ck CHECK (left_at IS NULL OR left_at >= joined_at)
);

CREATE TABLE messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id uuid REFERENCES users(id) ON DELETE SET NULL,
    message_type message_type NOT NULL DEFAULT 'TEXT',
    body text,
    reply_to_message_id uuid REFERENCES messages(id) ON DELETE SET NULL,
    shared_entity_type varchar(60),
    shared_entity_id uuid,
    status message_status NOT NULL DEFAULT 'SENT',
    sent_at timestamptz NOT NULL DEFAULT now(),
    edited_at timestamptz,
    deleted_at timestamptz,
    CONSTRAINT message_content_ck CHECK (body IS NOT NULL OR message_type IN ('IMAGE', 'FILE', 'WORKOUT_SHARE', 'SCHEDULE_SHARE', 'SYSTEM'))
);

CREATE TABLE message_attachments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    media_id uuid NOT NULL REFERENCES media_files(id) ON DELETE RESTRICT,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE message_read_receipts (
    message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    delivered_at timestamptz,
    read_at timestamptz,
    PRIMARY KEY (message_id, user_id),
    CONSTRAINT message_receipt_dates_ck CHECK (read_at IS NULL OR delivered_at IS NULL OR read_at >= delivered_at)
);

CREATE TABLE notification_preferences (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type varchar(80) NOT NULL,
    channel notification_channel NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    quiet_hours_start time,
    quiet_hours_end time,
    timezone varchar(64),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_type, channel)
);

CREATE TABLE notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type varchar(80) NOT NULL,
    title varchar(200) NOT NULL,
    body text NOT NULL,
    related_entity_type varchar(60),
    related_entity_id uuid,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz,
    expires_at timestamptz
);

CREATE TABLE notification_deliveries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    channel notification_channel NOT NULL,
    status delivery_status NOT NULL DEFAULT 'PENDING',
    provider_message_id varchar(255),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_attempt_at timestamptz,
    delivered_at timestamptz,
    error_code varchar(100),
    error_message text,
    UNIQUE (notification_id, channel)
);

CREATE TABLE attention_signals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    trainer_id uuid REFERENCES trainer_profiles(user_id) ON DELETE SET NULL,
    coaching_relationship_id uuid REFERENCES coaching_relationships(id) ON DELETE SET NULL,
    source attention_source NOT NULL,
    signal_type varchar(100) NOT NULL,
    severity severity_level NOT NULL,
    status attention_status NOT NULL DEFAULT 'OPEN',
    title varchar(200) NOT NULL,
    explanation text NOT NULL,
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    related_entity_type varchar(60),
    related_entity_id uuid,
    assigned_to uuid REFERENCES users(id) ON DELETE SET NULL,
    due_at timestamptz,
    acknowledged_at timestamptz,
    resolved_at timestamptz,
    resolution_note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(120) NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error text
);

