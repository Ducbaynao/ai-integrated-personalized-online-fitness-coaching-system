SET search_path TO fitness, public;

-- V8 relationships are not period identities. Existing rows stay nullable legacy data;
-- only first-flow B08 conversations carry a verified period and parties.
ALTER TABLE conversations ADD COLUMN coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE RESTRICT;
ALTER TABLE conversations ADD COLUMN student_id uuid REFERENCES student_profiles(user_id) ON DELETE RESTRICT;
ALTER TABLE conversations ADD COLUMN trainer_id uuid REFERENCES trainer_profiles(user_id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uq_b08_period_parties_reference ON coaching_periods(id,student_id,trainer_id);
ALTER TABLE conversations ADD CONSTRAINT b08_conversation_period_parties_fk
    FOREIGN KEY (coaching_period_id,student_id,trainer_id)
    REFERENCES coaching_periods(id,student_id,trainer_id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uq_b08_conversation_period ON conversations(coaching_period_id)
    WHERE coaching_period_id IS NOT NULL;
ALTER TABLE conversations ADD CONSTRAINT b08_period_parties_ck CHECK
    (coaching_period_id IS NULL OR (student_id IS NOT NULL AND trainer_id IS NOT NULL
       AND conversation_type = 'DIRECT_COACHING'));

CREATE TABLE chat_command_receipts (
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key uuid NOT NULL,
    payload_hash char(64) NOT NULL,
    message_id uuid NOT NULL REFERENCES messages(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (actor_id, command_key)
);

ALTER TABLE notifications ADD COLUMN coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE RESTRICT;
ALTER TABLE notifications ADD COLUMN chat_student_id uuid REFERENCES student_profiles(user_id) ON DELETE RESTRICT;
ALTER TABLE notifications ADD COLUMN chat_trainer_id uuid REFERENCES trainer_profiles(user_id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uq_b08_message_notification ON notifications(recipient_user_id, related_entity_id)
    WHERE event_type = 'CHAT_MESSAGE' AND related_entity_type = 'MESSAGE'
      AND coaching_period_id IS NOT NULL;
CREATE INDEX ix_b08_conversations_student ON conversations(student_id, created_at DESC, id DESC)
    WHERE coaching_period_id IS NOT NULL;
CREATE INDEX ix_b08_conversations_trainer ON conversations(trainer_id, created_at DESC, id DESC)
    WHERE coaching_period_id IS NOT NULL;
CREATE INDEX ix_b08_messages_page ON messages(conversation_id, sent_at DESC, id DESC);
CREATE INDEX ix_b08_notifications_page ON notifications(recipient_user_id, created_at DESC, id DESC);
