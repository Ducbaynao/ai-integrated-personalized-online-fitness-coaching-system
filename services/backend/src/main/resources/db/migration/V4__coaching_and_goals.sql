SET search_path TO fitness, public;

CREATE TABLE coaching_relationships (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE RESTRICT,
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    status coaching_relationship_status NOT NULL DEFAULT 'PENDING',
    requested_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    requested_at timestamptz NOT NULL DEFAULT now(),
    accepted_at timestamptz,
    started_at timestamptz,
    paused_at timestamptz,
    ended_at timestamptz,
    termination_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT coaching_relationship_people_ck CHECK (trainer_id <> student_id),
    CONSTRAINT coaching_relationship_dates_ck CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at)
);

CREATE UNIQUE INDEX uq_active_coaching_relationship_pair
    ON coaching_relationships(trainer_id, student_id)
    WHERE status IN ('PENDING', 'ACTIVE', 'PAUSED');

CREATE TABLE coaching_relationship_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id uuid NOT NULL REFERENCES coaching_relationships(id) ON DELETE CASCADE,
    from_status coaching_relationship_status,
    to_status coaching_relationship_status NOT NULL,
    changed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    reason text,
    changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE coaching_periods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    mode coaching_mode NOT NULL,
    coaching_relationship_id uuid REFERENCES coaching_relationships(id) ON DELETE RESTRICT,
    trainer_id uuid REFERENCES trainer_profiles(user_id) ON DELETE RESTRICT,
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    reason varchar(100),
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT coaching_period_dates_ck CHECK (ended_at IS NULL OR ended_at > started_at),
    CONSTRAINT coaching_period_authority_ck CHECK (
        (mode = 'SELF_DIRECTED' AND coaching_relationship_id IS NULL AND trainer_id IS NULL)
        OR
        (mode = 'HUMAN_COACH' AND coaching_relationship_id IS NOT NULL AND trainer_id IS NOT NULL)
    )
);

ALTER TABLE coaching_periods
    ADD CONSTRAINT coaching_period_no_overlap
    EXCLUDE USING gist (
        student_id WITH =,
        tstzrange(started_at, COALESCE(ended_at, 'infinity'::timestamptz), '[)') WITH &&
    );

CREATE TABLE data_sharing_permissions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id uuid NOT NULL REFERENCES coaching_relationships(id) ON DELETE CASCADE,
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE CASCADE,
    data_scope data_scope_code NOT NULL,
    decision permission_decision NOT NULL,
    history_from timestamptz,
    history_until timestamptz,
    valid_from timestamptz NOT NULL DEFAULT now(),
    valid_until timestamptz,
    granted_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    revoked_at timestamptz,
    revoke_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT data_sharing_history_ck CHECK (history_until IS NULL OR history_from IS NULL OR history_until >= history_from),
    CONSTRAINT data_sharing_validity_ck CHECK (valid_until IS NULL OR valid_until >= valid_from)
);

CREATE UNIQUE INDEX uq_current_data_sharing_permission
    ON data_sharing_permissions(relationship_id, data_scope)
    WHERE revoked_at IS NULL AND valid_until IS NULL;

CREATE TABLE goal_types (
    id smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    description text,
    is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE fitness_goals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    title varchar(200) NOT NULL,
    status lifecycle_status NOT NULL DEFAULT 'DRAFT',
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    activated_at timestamptz,
    paused_at timestamptz,
    completed_at timestamptz,
    ended_at timestamptz,
    status_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE fitness_goal_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fitness_goal_id uuid NOT NULL REFERENCES fitness_goals(id) ON DELETE CASCADE,
    from_status lifecycle_status,
    to_status lifecycle_status NOT NULL,
    changed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    reason text,
    changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE fitness_goal_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fitness_goal_id uuid NOT NULL REFERENCES fitness_goals(id) ON DELETE CASCADE,
    version_number integer NOT NULL CHECK (version_number > 0),
    start_date date NOT NULL,
    target_date date,
    duration_days integer CHECK (duration_days IS NULL OR duration_days > 0),
    effective_from timestamptz NOT NULL DEFAULT now(),
    effective_until timestamptz,
    resume_date date,
    change_reason varchar(100),
    change_summary text,
    created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    source_proposal_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (fitness_goal_id, version_number),
    CONSTRAINT goal_version_target_ck CHECK (target_date IS NULL OR target_date >= start_date),
    CONSTRAINT goal_version_effective_ck CHECK (effective_until IS NULL OR effective_until > effective_from)
);

CREATE UNIQUE INDEX uq_goal_current_version
    ON fitness_goal_versions(fitness_goal_id)
    WHERE effective_until IS NULL;

CREATE TABLE goal_objectives (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_version_id uuid NOT NULL REFERENCES fitness_goal_versions(id) ON DELETE CASCADE,
    goal_type_id smallint NOT NULL REFERENCES goal_types(id) ON DELETE RESTRICT,
    priority objective_priority NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    notes text,
    UNIQUE (goal_version_id, goal_type_id)
);

CREATE UNIQUE INDEX uq_goal_version_primary_objective
    ON goal_objectives(goal_version_id)
    WHERE priority = 'PRIMARY';

CREATE TABLE goal_targets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_version_id uuid NOT NULL REFERENCES fitness_goal_versions(id) ON DELETE CASCADE,
    metric_definition_id integer NOT NULL REFERENCES metric_definitions(id) ON DELETE RESTRICT,
    exercise_variation_id uuid REFERENCES exercise_variations(id) ON DELETE RESTRICT,
    start_value numeric(24,8),
    target_value numeric(24,8),
    target_min_value numeric(24,8),
    target_max_value numeric(24,8),
    unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    target_repetitions integer CHECK (target_repetitions IS NULL OR target_repetitions > 0),
    target_date date,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT goal_target_values_ck CHECK (
        target_value IS NOT NULL OR target_min_value IS NOT NULL OR target_max_value IS NOT NULL
    ),
    CONSTRAINT goal_target_range_ck CHECK (
        target_max_value IS NULL OR target_min_value IS NULL OR target_max_value >= target_min_value
    )
);

CREATE TABLE goal_proposals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    fitness_goal_id uuid REFERENCES fitness_goals(id) ON DELETE CASCADE,
    base_goal_version_id uuid REFERENCES fitness_goal_versions(id) ON DELETE SET NULL,
    source proposal_source NOT NULL,
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    proposed_title varchar(200),
    proposed_start_date date,
    proposed_target_date date,
    proposed_duration_days integer CHECK (proposed_duration_days IS NULL OR proposed_duration_days > 0),
    reason text NOT NULL,
    status proposal_status NOT NULL DEFAULT 'PENDING',
    decided_by uuid REFERENCES users(id) ON DELETE SET NULL,
    decided_at timestamptz,
    decision_note text,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT goal_proposal_dates_ck CHECK (proposed_target_date IS NULL OR proposed_start_date IS NULL OR proposed_target_date >= proposed_start_date)
);

ALTER TABLE fitness_goal_versions
    ADD CONSTRAINT fk_goal_version_source_proposal
    FOREIGN KEY (source_proposal_id) REFERENCES goal_proposals(id) ON DELETE SET NULL;

CREATE TABLE goal_proposal_objectives (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_proposal_id uuid NOT NULL REFERENCES goal_proposals(id) ON DELETE CASCADE,
    goal_type_id smallint NOT NULL REFERENCES goal_types(id) ON DELETE RESTRICT,
    priority objective_priority NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    UNIQUE (goal_proposal_id, goal_type_id)
);

CREATE UNIQUE INDEX uq_goal_proposal_primary_objective
    ON goal_proposal_objectives(goal_proposal_id)
    WHERE priority = 'PRIMARY';

CREATE TABLE goal_proposal_targets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_proposal_id uuid NOT NULL REFERENCES goal_proposals(id) ON DELETE CASCADE,
    metric_definition_id integer NOT NULL REFERENCES metric_definitions(id) ON DELETE RESTRICT,
    exercise_variation_id uuid REFERENCES exercise_variations(id) ON DELETE RESTRICT,
    start_value numeric(24,8),
    target_value numeric(24,8),
    target_min_value numeric(24,8),
    target_max_value numeric(24,8),
    unit_id smallint NOT NULL REFERENCES measurement_units(id) ON DELETE RESTRICT,
    target_repetitions integer CHECK (target_repetitions IS NULL OR target_repetitions > 0),
    target_date date,
    notes text,
    CONSTRAINT goal_proposal_target_values_ck CHECK (
        target_value IS NOT NULL OR target_min_value IS NOT NULL OR target_max_value IS NOT NULL
    )
);

CREATE TABLE goal_transitions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    previous_goal_id uuid NOT NULL REFERENCES fitness_goals(id) ON DELETE RESTRICT,
    new_goal_id uuid NOT NULL REFERENCES fitness_goals(id) ON DELETE RESTRICT,
    transition_reason varchar(100) NOT NULL,
    proposal_id uuid REFERENCES goal_proposals(id) ON DELETE SET NULL,
    initiated_by uuid REFERENCES users(id) ON DELETE SET NULL,
    transitioned_at timestamptz NOT NULL DEFAULT now(),
    notes text,
    UNIQUE (previous_goal_id, new_goal_id),
    CONSTRAINT goal_transition_distinct_ck CHECK (previous_goal_id <> new_goal_id)
);

CREATE TABLE goal_coaching_periods (
    fitness_goal_id uuid NOT NULL REFERENCES fitness_goals(id) ON DELETE CASCADE,
    coaching_period_id uuid NOT NULL REFERENCES coaching_periods(id) ON DELETE RESTRICT,
    linked_at timestamptz NOT NULL DEFAULT now(),
    unlinked_at timestamptz,
    link_reason varchar(100),
    PRIMARY KEY (fitness_goal_id, coaching_period_id),
    CONSTRAINT goal_coaching_link_dates_ck CHECK (unlinked_at IS NULL OR unlinked_at >= linked_at)
);

