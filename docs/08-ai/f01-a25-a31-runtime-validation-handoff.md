# F01 AI Runtime Validation and End-to-End Verification Handoff (A-25 / A-31)

This handoff records the implementation of **A-25** (Runtime output validation) and **A-31** (End-to-end pipeline verification with FastAPI and simulated provider) for **F01 General Fitness Knowledge** inquiry.

> [!IMPORTANT]
> - Branch: `feat/p2-a25-a31-f01-runtime-validation` created from `feat/p2-a26-f01-fastapi-mock`.
> - All changes from A-28, A-22/A-23, A-26, and A-25/A-31 remain uncommitted in the working tree for user review. No Git commits, merges, resets, or stash operations were performed.
> - The knowledge corpus remains **DRAFT (ACTIVE = 0)**. Real embedding, semantic retrieval, and claim-level grounding verification belong to **A-27** and **A-52**.

---

## 1. Scope and Responsibilities

- **A-25 (Runtime Output Validation)**:
  - Spring-side strict validation of AI service response payload before terminal persistence and client response.
  - Strict RFC 3339 date-time parsing on `result.asOf` with calendar/timezone bounds and explicit leap second rejection (`0 <= second <= 59`).
  - Validation of `runStatus`, `resultType`, `reasonCode`, `error`, and `answer` exclusivity and combinations.
  - Enforcement of citation structure: canonical `documentId`, `documentVersion` (semver), `sourceId`, and `locator.sourcePath`.
  - Boundary guard: verifies citation `sourceId` belongs to `envelope.knowledgeScope().allowedSourceIds()` (`CANONICAL_APPROVED_SOURCES`), and that `sourcePath` prevents directory traversal while allowing valid relative paths (e.g. `data/knowledge/curated/...`).
  - Guarantees unvalidated model output is **never** returned to the caller or saved as valid terminal result.
  - Records validation outcomes into `fitness.ai_validation_results` under validator code `F01_SCHEMA_DOMAIN_VALIDATOR`.

- **A-31 (End-to-End Verification with FastAPI & Simulated Provider)**:
  - **HTTP Stub Wire Contract Verification (`AiKnowledgeQueryHttpEndToEndIntegrationTest`)**: In-process `HttpServer` stub verifying wire-level protocol behavior, header authentication (`X-Internal-Service-Key`), correlation header propagation (`X-Correlation-ID`), timeout handling (504), provider errors (502), schema invalidity rejection, and scope violation rejection.
  - **Real FastAPI Process Verification (`AiKnowledgeQueryRealFastApiIntegrationTest`)**: Spawns actual Python virtual environment `uvicorn` process running `app.main:app` on a dynamic port, establishing a true network boundary between Spring Boot and FastAPI with Pydantic serialization, security dependencies, and deterministic simulated provider.
  - Distinguishes request envelope defects (FastAPI HTTP 422 -> `AiProviderException` / `PROCESSING_ERROR`) from model output schema defects (`AiOutputValidationException` / `VALIDATION_FAILED`).
  - Feature toggle support (`ai.service.enabled`): when disabled, requests fail fast with `AiFeatureDisabledException` (HTTP 503 `SERVICE_UNAVAILABLE`) before Run creation or outbound network calls.

---

## 2. Key Architecture Decisions and Invariants

1. **Database Lifecycle vs Public Contract `runStatus`**:
   - Database enum `fitness.ai_run_status` includes: `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `REJECTED_BY_VALIDATOR`, `CANCELLED`.
   - Contract JSON Schema and OpenAPI specify `runStatus`: `["SUCCEEDED", "FAILED", "REJECTED_BY_VALIDATOR"]`.
   - The string `"COMPLETED"` reported in early draft notes was an informal descriptive label; the actual code and contracts strictly use `SUCCEEDED`, `FAILED`, and `REJECTED_BY_VALIDATOR`.
2. **Persistence Table Clarification**:
   - Earlier draft summaries colloquially mentioned `ai_run_provenance`. In the actual Flyway migration (`V9__ai_and_knowledge.sql`), provenance is modeled across:
     - `fitness.ai_context_snapshots` (request envelope snapshot & SHA-256 hash).
     - `fitness.ai_run_input_references` (referenced domain entities like active exercises).
     - `fitness.ai_run_knowledge_chunks` (knowledge chunk citations).
     - `fitness.ai_validation_results` (validator outcomes, severity, and details).
   - Validation records for F01 are persisted to `fitness.ai_validation_results` with `validator_code = 'F01_SCHEMA_DOMAIN_VALIDATOR'`.
3. **HTTP 422 vs Model Output Invariant Failure**:
   - HTTP 422 from FastAPI represents a validation failure on the *outbound request envelope* produced by Spring. This is an internal integration/provider error (`AiProviderException`), transitioning the Run to `FAILED` with `PROCESSING_ERROR`.
   - When the AI service returns 200 OK with payload, Spring's `F01RuntimeOutputValidator` validates the generated output against domain and contract invariants. If output violates constraints (e.g. invalid citations, scope leakage, leap seconds, prohibited proposal/apply actions), it raises `AiOutputValidationException`, transitioning the Run to `FAILED` with `VALIDATION_FAILED`.
4. **Citation Scope Containment**:
   - Citations must only point to source IDs authorized in the outbound envelope's `allowedSourceIds` (e.g. `WHO-PA-SB-2020`, `NIDDK-WEIGHT-FACTORS-2023`, `ISSN-PROTEIN-EXERCISE-2017`, `ODS-EXERCISE-SUPPLEMENTS-2024`). Unauthorized sources are rejected at runtime.
   - Note: Syntactically valid citation structure does NOT prove the claim is factually grounded in the corpus. Groundedness verification over ACTIVE corpus belongs to **A-27** and **A-52**.
5. **Task Classification Alignment**:
   - **A-27**: Retrieval / Indexing pipeline (outbox processor, chunking, embeddings, vector search over ACTIVE corpus).
   - **A-49**: AI evaluation runner / automated benchmark harness.
   - **A-52**: Retrieval and model quality evaluation (precision/recall, citation accuracy, claim-level groundedness, hallucinations).

---

## 3. Test Coverage Summary

- **Contract Tests (`tools/validate-contracts.test.mjs`)**:
  - 21/21 passing (includes RFC 3339 leap second rejection, scope boundary validation, schema alignment).
- **Backend Unit & Integration Tests**:
  - `F01RuntimeOutputValidatorTest`: 8/8 passed (includes relative path boundaries and missingData checks).
  - `HttpF01AiServiceClientTest`: 5/5 passed.
  - `AiRunF01PersistenceIntegrationTest`: 7/7 passed.
  - `AiKnowledgeQueryOrchestrationIntegrationTest`: 9/9 passed.
  - `AiKnowledgeQueryHttpEndToEndIntegrationTest`: 8/8 passed (wire contract HTTP stub server).
  - `AiKnowledgeQueryRealFastApiIntegrationTest`: 4/4 passed (spawns real FastAPI uvicorn process).
  - Total backend tests executed: 41 passing, 0 failures.
- **FastAPI / Pytest**:
  - `tests/test_health.py` & `tests/test_internal_knowledge.py`: 10/10 passed.
  - Ruff linter: clean (`All checks passed!`).
