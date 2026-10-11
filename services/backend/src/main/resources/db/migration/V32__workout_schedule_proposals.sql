SET search_path TO fitness, public;

CREATE TABLE workout_schedule_proposals (
    id uuid PRIMARY KEY,
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    trainer_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    coaching_period_id uuid NOT NULL REFERENCES coaching_periods(id) ON DELETE RESTRICT,
    plan_id uuid NOT NULL REFERENCES workout_plans(id) ON DELETE RESTRICT,
    source_plan_version_id uuid NOT NULL REFERENCES workout_plan_versions(id) ON DELETE RESTRICT,
    source_plan_version_number integer NOT NULL CHECK (source_plan_version_number > 0),
    observed_plan_aggregate_version bigint NOT NULL CHECK (observed_plan_aggregate_version >= 0),
    week_anchor_date date NOT NULL,
    timezone varchar(64) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    proposed_at timestamptz NOT NULL,
    decided_at timestamptz,
    decided_by uuid REFERENCES users(id) ON DELETE RESTRICT,
    decision_note varchar(1000),
    withdrawn_at timestamptz,
    withdrawn_by uuid REFERENCES users(id) ON DELETE RESTRICT,
    confirmed_batch_id uuid UNIQUE REFERENCES workout_schedule_batches(id) ON DELETE RESTRICT,
    CONSTRAINT workout_schedule_proposal_terminal_ck CHECK (
        (status = 'PENDING' AND decided_at IS NULL AND decided_by IS NULL
            AND withdrawn_at IS NULL AND withdrawn_by IS NULL AND confirmed_batch_id IS NULL)
        OR (status = 'ACCEPTED' AND decided_at IS NOT NULL AND decided_by = student_id
            AND withdrawn_at IS NULL AND withdrawn_by IS NULL AND confirmed_batch_id IS NOT NULL)
        OR (status = 'REJECTED' AND decided_at IS NOT NULL AND decided_by = student_id
            AND withdrawn_at IS NULL AND withdrawn_by IS NULL AND confirmed_batch_id IS NULL)
        OR (status = 'WITHDRAWN' AND decided_at IS NULL AND decided_by IS NULL
            AND withdrawn_at IS NOT NULL AND withdrawn_by = trainer_id AND confirmed_batch_id IS NULL)
    )
);

CREATE TABLE workout_schedule_proposal_items (
    proposal_id uuid NOT NULL REFERENCES workout_schedule_proposals(id) ON DELETE RESTRICT,
    client_item_id uuid NOT NULL,
    plan_session_id uuid NOT NULL REFERENCES workout_plan_sessions(id) ON DELETE RESTRICT,
    week_number integer NOT NULL CHECK (week_number > 0),
    day_number integer NOT NULL CHECK (day_number BETWEEN 1 AND 7),
    sequence_number integer NOT NULL CHECK (sequence_number > 0),
    local_start varchar(35) NOT NULL,
    local_end varchar(35) NOT NULL,
    start_utc_offset varchar(6) NOT NULL,
    end_utc_offset varchar(6) NOT NULL,
    PRIMARY KEY (proposal_id, client_item_id),
    UNIQUE (proposal_id, plan_session_id)
);

CREATE TABLE workout_schedule_proposal_history (
    proposal_id uuid NOT NULL REFERENCES workout_schedule_proposals(id) ON DELETE RESTRICT,
    proposal_version bigint NOT NULL CHECK (proposal_version >= 0),
    from_status varchar(20),
    to_status varchar(20) NOT NULL,
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key varchar(120) NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (proposal_id, proposal_version)
);

-- V31 receipts are shared across direct confirmation and proposal commands. A proposal
-- command may have no confirmed batch; ACCEPT retains both references atomically.
ALTER TABLE workout_schedule_command_receipts
    ALTER COLUMN batch_id DROP NOT NULL,
    ADD COLUMN proposal_id uuid REFERENCES workout_schedule_proposals(id) ON DELETE RESTRICT,
    ADD CONSTRAINT workout_schedule_receipt_target_ck
        CHECK (batch_id IS NOT NULL OR proposal_id IS NOT NULL);

ALTER TABLE workout_schedule_batches
    ADD CONSTRAINT workout_schedule_batch_proposal_fk
        FOREIGN KEY (source_proposal_id) REFERENCES workout_schedule_proposals(id) ON DELETE RESTRICT,
    ADD CONSTRAINT workout_schedule_batch_proposal_source_ck CHECK (
        (confirmation_source = 'STUDENT_DIRECT' AND source_proposal_id IS NULL)
        OR (confirmation_source = 'TRAINER_PROPOSAL' AND source_proposal_id IS NOT NULL)
    );

CREATE INDEX ix_workout_schedule_proposals_student_time
    ON workout_schedule_proposals(student_id, proposed_at DESC, id DESC);
CREATE INDEX ix_workout_schedule_proposals_trainer_time
    ON workout_schedule_proposals(trainer_id, proposed_at DESC, id DESC);
