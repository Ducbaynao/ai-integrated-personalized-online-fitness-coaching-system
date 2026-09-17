# AI service

FastAPI service for context building, deterministic rules, retrieval, structured recommendation generation, and output validation.

The service never writes business state directly. Spring Boot authenticates requests, builds the allowed data scope, persists AI runs, validates recommendations, and controls approval.

Planned internal packages:

- `api`: HTTP endpoints and dependencies
- `core`: configuration and logging
- `schemas`: structured request and response models
- `services/context_builder`: transforms approved backend context
- `services/recommendation`: recommendation use cases
- `services/validation`: output and safety validation
- `rag`: retrieval, embeddings, chunking, and knowledge access
- `rules`: deterministic fitness and data-quality rules
- `prompts`: versioned prompt templates
- `integrations`: LLM, embedding, and vision providers
