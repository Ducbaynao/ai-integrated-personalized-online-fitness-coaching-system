-- Forward Migration V42: Seed active baseline embedding model and version for RAG (A-27)
SET search_path TO fitness, public;

-- Register active test double embedding model (simulated/deterministic-1536)
INSERT INTO ai_models (id, provider, model_code, capability, is_active, created_at)
VALUES (
    'e0000000-0000-0000-0000-000000000001',
    'simulated',
    'simulated/deterministic-1536',
    'EMBEDDING',
    true,
    now()
)
ON CONFLICT (provider, model_code, capability) DO UPDATE SET is_active = true;

-- Register active test double embedding model version 1.0.0
INSERT INTO ai_model_versions (id, ai_model_id, version_label, configuration, activated_at, created_at)
SELECT
    'e1000000-0000-0000-0000-000000000001',
    id,
    '1.0.0',
    '{"dimension": 1536, "metric": "COSINE", "model_space": "simulated/deterministic-1536/1.0.0", "generator": "DeterministicEmbeddingGenerator"}'::jsonb,
    now(),
    now()
FROM ai_models
WHERE provider = 'simulated' AND model_code = 'simulated/deterministic-1536' AND capability = 'EMBEDDING'
ON CONFLICT (ai_model_id, version_label) DO NOTHING;

-- Register production OpenAI model entry (reserved, marked inactive by default until live credentials and model selection are approved)
INSERT INTO ai_models (id, provider, model_code, capability, is_active, created_at)
VALUES (
    'e0000000-0000-0000-0000-000000000002',
    'openai',
    'text-embedding-3-small',
    'EMBEDDING',
    false,
    now()
)
ON CONFLICT (provider, model_code, capability) DO UPDATE SET is_active = false;

INSERT INTO ai_model_versions (id, ai_model_id, version_label, configuration, activated_at, created_at)
SELECT
    'e1000000-0000-0000-0000-000000000002',
    id,
    '1.0.0',
    '{"dimension": 1536, "metric": "COSINE", "model_space": "openai/text-embedding-3-small/1536"}'::jsonb,
    null,
    now()
FROM ai_models
WHERE provider = 'openai' AND model_code = 'text-embedding-3-small' AND capability = 'EMBEDDING'
ON CONFLICT (ai_model_id, version_label) DO NOTHING;
