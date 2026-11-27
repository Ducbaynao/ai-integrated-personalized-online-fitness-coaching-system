-- Scoped AI Agent conversations and turn traces, strictly separated from Student-Trainer human chat.
CREATE TABLE fitness.ai_conversations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id uuid NOT NULL REFERENCES fitness.users(id) ON DELETE CASCADE,
    actor_role varchar(32) NOT NULL CHECK (actor_role IN ('STUDENT', 'TRAINER', 'ADMIN')),
    subject_id uuid REFERENCES fitness.users(id) ON DELETE SET NULL,
    coaching_period_id uuid REFERENCES fitness.coaching_periods(id) ON DELETE SET NULL,
    title varchar(200) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_conversations_owner_updated
    ON fitness.ai_conversations (owner_user_id, updated_at DESC);

CREATE TABLE fitness.ai_conversation_turns (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES fitness.ai_conversations(id) ON DELETE CASCADE,
    turn_index integer NOT NULL CHECK (turn_index > 0),
    operation_key varchar(128),
    ai_run_id uuid REFERENCES fitness.ai_runs(id) ON DELETE SET NULL,
    user_message text NOT NULL,
    assistant_message text,
    clarification_question text,
    result_type varchar(40),
    reason_code varchar(64),
    citations_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    proposal_draft_json jsonb,
    steps_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, turn_index),
    UNIQUE (conversation_id, operation_key)
);

CREATE INDEX idx_ai_conversation_turns_conv_idx
    ON fitness.ai_conversation_turns (conversation_id, turn_index ASC);
