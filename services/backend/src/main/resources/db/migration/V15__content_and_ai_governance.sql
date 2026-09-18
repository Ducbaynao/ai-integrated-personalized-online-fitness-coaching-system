SET search_path TO fitness, public;

CREATE TABLE exercise_canonical_mappings (
    duplicate_exercise_id uuid PRIMARY KEY REFERENCES exercises(id) ON DELETE RESTRICT,
    canonical_exercise_id uuid NOT NULL REFERENCES exercises(id) ON DELETE RESTRICT,
    mapped_by uuid REFERENCES users(id) ON DELETE SET NULL,
    reason text NOT NULL,
    mapped_at timestamptz NOT NULL DEFAULT now(),
    CHECK (duplicate_exercise_id <> canonical_exercise_id)
);
CREATE TABLE exercise_merge_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    duplicate_exercise_id uuid NOT NULL REFERENCES exercises(id) ON DELETE RESTRICT,
    canonical_exercise_id uuid NOT NULL REFERENCES exercises(id) ON DELETE RESTRICT,
    performed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    reference_counts jsonb NOT NULL DEFAULT '{}'::jsonb,
    reason text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE knowledge_versions
    ADD COLUMN reviewed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN reviewed_at timestamptz,
    ADD COLUMN published_by uuid REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN published_at timestamptz;
CREATE TABLE knowledge_version_reviews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_version_id uuid NOT NULL REFERENCES knowledge_versions(id) ON DELETE CASCADE,
    reviewer_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    decision varchar(20) NOT NULL CHECK (decision IN ('APPROVED','CHANGES_REQUESTED','REJECTED')),
    notes text,
    reviewed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE knowledge_processing_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_version_id uuid NOT NULL REFERENCES knowledge_versions(id) ON DELETE CASCADE,
    run_type varchar(20) NOT NULL CHECK (run_type IN ('IMPORT','CHUNK','EMBED','REINDEX','VALIDATE')),
    status varchar(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    model_version_id uuid REFERENCES ai_model_versions(id) ON DELETE SET NULL,
    statistics jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_message text,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE ai_runs
    ADD COLUMN purpose varchar(30) NOT NULL DEFAULT 'BUSINESS_ASSISTANCE'
        CHECK (purpose IN ('BUSINESS_ASSISTANCE','EVALUATION','REPLAY','REGRESSION_TEST')),
    ADD COLUMN evaluation_only boolean NOT NULL DEFAULT false;

CREATE TABLE ai_evaluation_datasets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(180) NOT NULL,
    version varchar(60) NOT NULL,
    data_classification varchar(30) NOT NULL CHECK (data_classification IN ('SYNTHETIC','DEIDENTIFIED','RESTRICTED')),
    description text,
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (name, version)
);
CREATE TABLE ai_evaluation_cases (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id uuid NOT NULL REFERENCES ai_evaluation_datasets(id) ON DELETE CASCADE,
    case_key varchar(100) NOT NULL,
    input_payload jsonb NOT NULL,
    expected_behavior jsonb NOT NULL,
    rubric jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (dataset_id, case_key)
);
CREATE TABLE ai_evaluation_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id uuid NOT NULL REFERENCES ai_evaluation_datasets(id) ON DELETE RESTRICT,
    model_version_id uuid NOT NULL REFERENCES ai_model_versions(id) ON DELETE RESTRICT,
    prompt_version_id uuid REFERENCES prompt_versions(id) ON DELETE SET NULL,
    status varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    requested_by uuid REFERENCES users(id) ON DELETE SET NULL,
    aggregate_metrics jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE ai_evaluation_results (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_run_id uuid NOT NULL REFERENCES ai_evaluation_runs(id) ON DELETE CASCADE,
    evaluation_case_id uuid NOT NULL REFERENCES ai_evaluation_cases(id) ON DELETE RESTRICT,
    ai_run_id uuid REFERENCES ai_runs(id) ON DELETE SET NULL,
    passed boolean NOT NULL,
    score numeric(8,4),
    metrics jsonb NOT NULL DEFAULT '{}'::jsonb,
    notes text,
    evaluated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (evaluation_run_id, evaluation_case_id)
);
CREATE TABLE ai_replay_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_ai_run_id uuid NOT NULL REFERENCES ai_runs(id) ON DELETE RESTRICT,
    target_model_version_id uuid REFERENCES ai_model_versions(id) ON DELETE SET NULL,
    target_prompt_version_id uuid REFERENCES prompt_versions(id) ON DELETE SET NULL,
    privacy_transform varchar(30) NOT NULL CHECK (privacy_transform IN ('NONE','DEIDENTIFY','SYNTHETIC_REPLACEMENT')),
    requested_by uuid REFERENCES users(id) ON DELETE SET NULL,
    resulting_ai_run_id uuid REFERENCES ai_runs(id) ON DELETE SET NULL,
    status varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE TABLE ai_evaluation_comparisons (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    baseline_run_id uuid NOT NULL REFERENCES ai_evaluation_runs(id) ON DELETE CASCADE,
    candidate_run_id uuid NOT NULL REFERENCES ai_evaluation_runs(id) ON DELETE CASCADE,
    comparison_metrics jsonb NOT NULL,
    regression_detected boolean NOT NULL,
    compared_by uuid REFERENCES users(id) ON DELETE SET NULL,
    compared_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (baseline_run_id, candidate_run_id),
    CHECK (baseline_run_id <> candidate_run_id)
);

CREATE OR REPLACE FUNCTION validate_knowledge_publish()
RETURNS trigger LANGUAGE plpgsql SET search_path = fitness, public AS $$
BEGIN
    IF NEW.status = 'ACTIVE' AND OLD.status <> 'ACTIVE' THEN
        IF NEW.reviewed_by IS NULL OR NEW.reviewed_at IS NULL OR NEW.published_by IS NULL OR NEW.published_at IS NULL
           OR NOT EXISTS (SELECT 1 FROM knowledge_version_reviews r WHERE r.knowledge_version_id = NEW.id AND r.decision = 'APPROVED') THEN
            RAISE EXCEPTION 'Knowledge version requires approved review and publisher before activation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_validate_knowledge_publish BEFORE UPDATE OF status ON knowledge_versions FOR EACH ROW EXECUTE FUNCTION validate_knowledge_publish();

CREATE TRIGGER exercise_merge_events_append_only BEFORE UPDATE OR DELETE ON exercise_merge_events FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER knowledge_version_reviews_append_only BEFORE UPDATE OR DELETE ON knowledge_version_reviews FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
CREATE TRIGGER ai_evaluation_results_append_only BEFORE UPDATE OR DELETE ON ai_evaluation_results FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();

CREATE INDEX idx_exercise_canonical_mappings_canonical ON exercise_canonical_mappings(canonical_exercise_id);
CREATE INDEX idx_knowledge_processing_version_time ON knowledge_processing_runs(knowledge_version_id, created_at DESC);
CREATE INDEX idx_ai_evaluation_runs_dataset_time ON ai_evaluation_runs(dataset_id, created_at DESC);
CREATE INDEX idx_ai_replay_source_time ON ai_replay_requests(source_ai_run_id, created_at DESC);

CREATE VIEW active_knowledge_chunks AS
SELECT c.* FROM knowledge_chunks c JOIN knowledge_versions v ON v.id = c.knowledge_version_id WHERE v.status = 'ACTIVE';

CREATE VIEW trainer_capacity_summary AS
SELECT t.user_id AS trainer_id,
       count(r.id) FILTER (WHERE r.status = 'ACTIVE') AS active_student_count,
       t.is_accepting_students
FROM trainer_profiles t
LEFT JOIN coaching_relationships r ON r.trainer_id = t.user_id
GROUP BY t.user_id, t.is_accepting_students;
