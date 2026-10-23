-- Existing V32 proposals remain valid: legacy items have no Appointment observation.
ALTER TABLE fitness.workout_schedule_proposal_items
    ADD COLUMN appointment_id uuid REFERENCES fitness.appointments(id) ON DELETE RESTRICT,
    ADD COLUMN observed_appointment_version bigint,
    ADD CONSTRAINT schedule_proposal_appointment_pair_ck CHECK (
        (appointment_id IS NULL AND observed_appointment_version IS NULL)
        OR (appointment_id IS NOT NULL AND observed_appointment_version >= 0)
    );

CREATE INDEX ix_schedule_proposal_item_appointment
    ON fitness.workout_schedule_proposal_items(appointment_id)
    WHERE appointment_id IS NOT NULL;

-- Preserve potentially ambiguous V5 rows (guarded_v2=false), while preventing
-- two new confirmed V45+ Appointments from actively claiming one occurrence.
-- The application must still inspect legacy links under its Student/occurrence
-- lock before creating a guarded link; this index cannot validate old rows.
CREATE UNIQUE INDEX uq_appointment_guarded_confirmed_workout_v47
    ON fitness.appointments(planned_workout_id)
    WHERE guarded_v2 AND status='CONFIRMED' AND planned_workout_id IS NOT NULL;

ALTER TABLE fitness.workout_schedule_change_proposals
    ADD COLUMN appointment_id uuid REFERENCES fitness.appointments(id) ON DELETE RESTRICT,
    ADD COLUMN observed_appointment_version bigint,
    ADD CONSTRAINT change_proposal_appointment_pair_ck CHECK (
        (appointment_id IS NULL AND observed_appointment_version IS NULL)
        OR (appointment_id IS NOT NULL AND observed_appointment_version >= 0)
    );
