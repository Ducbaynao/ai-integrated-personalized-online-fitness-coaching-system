SET search_path TO fitness, public;

CREATE TABLE workout_schedule_change_proposals (
    id uuid PRIMARY KEY,
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    trainer_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    coaching_period_id uuid NOT NULL REFERENCES coaching_periods(id) ON DELETE RESTRICT,
    occurrence_id uuid NOT NULL REFERENCES planned_workouts(id) ON DELETE RESTRICT,
    observed_occurrence_version bigint NOT NULL CHECK (observed_occurrence_version >= 0),
    change_type varchar(12) NOT NULL CHECK (change_type IN ('RESCHEDULE','CANCEL')),
    old_planned_start_at timestamptz NOT NULL,
    old_planned_end_at timestamptz,
    original_planned_start_at timestamptz NOT NULL,
    old_timezone varchar(64),
    old_local_start varchar(35),
    old_local_end varchar(35),
    old_start_utc_offset varchar(6),
    old_end_utc_offset varchar(6),
    source_plan_id uuid REFERENCES workout_plans(id) ON DELETE RESTRICT,
    source_plan_version_id uuid REFERENCES workout_plan_versions(id) ON DELETE RESTRICT,
    source_plan_session_id uuid REFERENCES workout_plan_sessions(id) ON DELETE RESTRICT,
    destination_timezone varchar(64),
    destination_local_start varchar(35),
    destination_local_end varchar(35),
    destination_start_utc_offset varchar(6),
    destination_end_utc_offset varchar(6),
    reason varchar(1000),
    status varchar(12) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACCEPTED','REJECTED','WITHDRAWN')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    proposed_at timestamptz NOT NULL,
    decided_at timestamptz,
    decided_by uuid REFERENCES users(id) ON DELETE RESTRICT,
    withdrawn_at timestamptz,
    withdrawn_by uuid REFERENCES users(id) ON DELETE RESTRICT,
    applied_occurrence_id uuid REFERENCES planned_workouts(id) ON DELETE RESTRICT,
    CONSTRAINT workout_schedule_change_proposal_destination_ck CHECK (
        (change_type='RESCHEDULE' AND destination_timezone IS NOT NULL
            AND destination_local_start IS NOT NULL AND destination_local_end IS NOT NULL
            AND destination_start_utc_offset IS NOT NULL AND destination_end_utc_offset IS NOT NULL)
        OR (change_type='CANCEL' AND destination_timezone IS NULL
            AND destination_local_start IS NULL AND destination_local_end IS NULL
            AND destination_start_utc_offset IS NULL AND destination_end_utc_offset IS NULL)
    ),
    CONSTRAINT workout_schedule_change_proposal_terminal_ck CHECK (
        (status='PENDING' AND decided_at IS NULL AND decided_by IS NULL
            AND withdrawn_at IS NULL AND withdrawn_by IS NULL AND applied_occurrence_id IS NULL)
        OR (status='ACCEPTED' AND decided_at IS NOT NULL AND decided_by=student_id
            AND withdrawn_at IS NULL AND withdrawn_by IS NULL AND applied_occurrence_id=occurrence_id)
        OR (status='REJECTED' AND decided_at IS NOT NULL AND decided_by=student_id
            AND withdrawn_at IS NULL AND withdrawn_by IS NULL AND applied_occurrence_id IS NULL)
        OR (status='WITHDRAWN' AND decided_at IS NULL AND decided_by IS NULL
            AND withdrawn_at IS NOT NULL AND withdrawn_by=trainer_id AND applied_occurrence_id IS NULL)
    )
);

CREATE TABLE workout_schedule_change_proposal_history (
    proposal_id uuid NOT NULL REFERENCES workout_schedule_change_proposals(id) ON DELETE RESTRICT,
    proposal_version bigint NOT NULL CHECK (proposal_version >= 0),
    from_status varchar(12),
    to_status varchar(12) NOT NULL,
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key varchar(120) NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY(proposal_id, proposal_version)
);

CREATE TABLE workout_schedule_change_proposal_receipts (
    actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    command_key varchar(120) NOT NULL,
    command_name varchar(12) NOT NULL CHECK (command_name IN ('PROPOSE','DECIDE','WITHDRAW')),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    proposal_id uuid NOT NULL REFERENCES workout_schedule_change_proposals(id) ON DELETE RESTRICT,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(actor_id, command_key)
);

CREATE TRIGGER workout_schedule_change_proposal_history_append_only
    BEFORE UPDATE OR DELETE ON workout_schedule_change_proposal_history
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER workout_schedule_change_proposal_receipts_append_only
    BEFORE UPDATE OR DELETE ON workout_schedule_change_proposal_receipts
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE INDEX ix_workout_schedule_change_proposals_student_time
    ON workout_schedule_change_proposals(student_id, proposed_at DESC, id DESC);
CREATE INDEX ix_workout_schedule_change_proposals_trainer_student_time
    ON workout_schedule_change_proposals(trainer_id, student_id, proposed_at DESC, id DESC);
