SET search_path TO fitness, public;

CREATE TABLE ai_models (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(80) NOT NULL,
    model_code varchar(160) NOT NULL,
    capability varchar(40) NOT NULL CHECK (capability IN ('LLM', 'EMBEDDING', 'VISION', 'POSE_ESTIMATION', 'CLASSIFICATION')),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, model_code, capability)
);

CREATE TABLE ai_model_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ai_model_id uuid NOT NULL REFERENCES ai_models(id) ON DELETE CASCADE,
    version_label varchar(120) NOT NULL,
    configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    activated_at timestamptz,
    retired_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (ai_model_id, version_label),
    CONSTRAINT ai_model_version_dates_ck CHECK (retired_at IS NULL OR activated_at IS NULL OR retired_at >= activated_at)
);

CREATE TABLE prompt_templates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(100) NOT NULL UNIQUE,
    name varchar(180) NOT NULL,
    purpose varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE prompt_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_template_id uuid NOT NULL REFERENCES prompt_templates(id) ON DELETE CASCADE,
    version_number integer NOT NULL CHECK (version_number > 0),
    system_prompt text NOT NULL,
    user_prompt_template text,
    output_schema jsonb,
    status knowledge_status NOT NULL DEFAULT 'DRAFT',
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz,
    archived_at timestamptz,
    UNIQUE (prompt_template_id, version_number)
);

CREATE TABLE knowledge_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title varchar(300) NOT NULL,
    source_name varchar(200),
    source_url text,
    author varchar(200),
    document_type varchar(60),
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE knowledge_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_document_id uuid NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    version_number integer NOT NULL CHECK (version_number > 0),
    status knowledge_status NOT NULL DEFAULT 'DRAFT',
    content_hash varchar(64) NOT NULL,
    source_published_at timestamptz,
    activated_at timestamptz,
    archived_at timestamptz,
    created_by uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (knowledge_document_id, version_number),
    UNIQUE (knowledge_document_id, content_hash)
);

CREATE TABLE knowledge_chunks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_version_id uuid NOT NULL REFERENCES knowledge_versions(id) ON DELETE CASCADE,
    chunk_index integer NOT NULL CHECK (chunk_index >= 0),
    heading_path text,
    content text NOT NULL,
    token_count integer CHECK (token_count IS NULL OR token_count >= 0),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (knowledge_version_id, chunk_index)
);

CREATE TABLE knowledge_embeddings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_chunk_id uuid NOT NULL REFERENCES knowledge_chunks(id) ON DELETE CASCADE,
    embedding_model_version_id uuid NOT NULL REFERENCES ai_model_versions(id) ON DELETE RESTRICT,
    embedding vector(1536) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (knowledge_chunk_id, embedding_model_version_id)
);

CREATE TABLE ai_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    requested_by uuid REFERENCES users(id) ON DELETE SET NULL,
    student_id uuid REFERENCES student_profiles(user_id) ON DELETE SET NULL,
    coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE SET NULL,
    request_type varchar(100) NOT NULL,
    status ai_run_status NOT NULL DEFAULT 'QUEUED',
    model_version_id uuid REFERENCES ai_model_versions(id) ON DELETE SET NULL,
    prompt_version_id uuid REFERENCES prompt_versions(id) ON DELETE SET NULL,
    correlation_id uuid,
    input_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    output_payload jsonb,
    error_code varchar(100),
    error_message text,
    input_token_count integer CHECK (input_token_count IS NULL OR input_token_count >= 0),
    output_token_count integer CHECK (output_token_count IS NULL OR output_token_count >= 0),
    latency_ms integer CHECK (latency_ms IS NULL OR latency_ms >= 0),
    queued_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ai_context_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ai_run_id uuid NOT NULL REFERENCES ai_runs(id) ON DELETE CASCADE,
    context_version varchar(60) NOT NULL,
    context_payload jsonb NOT NULL,
    data_availability jsonb NOT NULL DEFAULT '{}'::jsonb,
    data_quality_summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    training_continuity_summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    context_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (ai_run_id)
);

CREATE TABLE ai_run_input_references (
    ai_run_id uuid NOT NULL REFERENCES ai_runs(id) ON DELETE CASCADE,
    entity_type varchar(80) NOT NULL,
    entity_id uuid NOT NULL,
    reference_role varchar(60) NOT NULL,
    PRIMARY KEY (ai_run_id, entity_type, entity_id, reference_role)
);

CREATE TABLE ai_run_knowledge_chunks (
    ai_run_id uuid NOT NULL REFERENCES ai_runs(id) ON DELETE CASCADE,
    knowledge_chunk_id uuid NOT NULL REFERENCES knowledge_chunks(id) ON DELETE RESTRICT,
    rank integer NOT NULL CHECK (rank > 0),
    similarity_score numeric(12,8),
    PRIMARY KEY (ai_run_id, knowledge_chunk_id)
);

CREATE TABLE ai_validation_results (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ai_run_id uuid NOT NULL REFERENCES ai_runs(id) ON DELETE CASCADE,
    validator_code varchar(100) NOT NULL,
    passed boolean NOT NULL,
    severity severity_level NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (ai_run_id, validator_code)
);

CREATE TABLE ai_recommendations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ai_run_id uuid NOT NULL REFERENCES ai_runs(id) ON DELETE RESTRICT,
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    coaching_period_id uuid REFERENCES coaching_periods(id) ON DELETE SET NULL,
    recommendation_type varchar(100) NOT NULL,
    title varchar(200) NOT NULL,
    explanation text NOT NULL,
    structured_payload jsonb NOT NULL,
    status recommendation_status NOT NULL DEFAULT 'PENDING',
    decision_authority varchar(20) NOT NULL CHECK (decision_authority IN ('STUDENT', 'TRAINER')),
    target_entity_type varchar(80),
    target_entity_id uuid,
    decided_by uuid REFERENCES users(id) ON DELETE SET NULL,
    decided_at timestamptz,
    decision_note text,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ai_recommendation_applications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ai_recommendation_id uuid NOT NULL REFERENCES ai_recommendations(id) ON DELETE RESTRICT,
    applied_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    resulting_entity_type varchar(80) NOT NULL,
    resulting_entity_id uuid NOT NULL,
    application_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    applied_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ai_feedback (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ai_run_id uuid REFERENCES ai_runs(id) ON DELETE CASCADE,
    ai_recommendation_id uuid REFERENCES ai_recommendations(id) ON DELETE CASCADE,
    submitted_by uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rating smallint CHECK (rating BETWEEN 1 AND 5),
    outcome varchar(30) CHECK (outcome IN ('HELPFUL', 'NOT_HELPFUL', 'UNSAFE', 'INCORRECT', 'APPLIED_SUCCESSFULLY')),
    comment text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ai_feedback_target_ck CHECK (ai_run_id IS NOT NULL OR ai_recommendation_id IS NOT NULL)
);

CREATE TABLE ai_threads (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    student_id uuid REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    title varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz
);

CREATE TABLE ai_thread_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ai_thread_id uuid NOT NULL REFERENCES ai_threads(id) ON DELETE CASCADE,
    role varchar(20) NOT NULL CHECK (role IN ('SYSTEM', 'USER', 'ASSISTANT', 'TOOL')),
    content text,
    structured_content jsonb,
    ai_run_id uuid REFERENCES ai_runs(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ai_thread_message_content_ck CHECK (content IS NOT NULL OR structured_content IS NOT NULL)
);

ALTER TABLE workout_plan_versions
    ADD CONSTRAINT fk_workout_version_ai_recommendation
    FOREIGN KEY (source_ai_recommendation_id) REFERENCES ai_recommendations(id) ON DELETE SET NULL;

ALTER TABLE food_analysis_requests
    ADD CONSTRAINT fk_food_analysis_ai_run
    FOREIGN KEY (ai_run_id) REFERENCES ai_runs(id) ON DELETE SET NULL;

ALTER TABLE goal_proposals ADD COLUMN source_ai_recommendation_id uuid REFERENCES ai_recommendations(id) ON DELETE SET NULL;

-- HNSW is suitable for active RAG data and does not require training lists.
CREATE INDEX idx_knowledge_embeddings_hnsw
    ON knowledge_embeddings USING hnsw (embedding vector_cosine_ops);

