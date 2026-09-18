---
name: fitness-ai-assistance
description: Design or implement the FastAPI AI Assistance service, context building, rules, RAG, structured outputs, recommendation approval, nutrition vision, auditing, prompt/model versions, evaluation, and safety boundaries.
---

# Fitness AI Assistance

Read `digital-fitness-core` first.

## Authority boundary

AI is a shared assistant for `SELF_DIRECTED` and `HUMAN_COACH`, not a coaching mode, analytics authority, database authority, or final decision-maker. Spring Boot owns business validation and persistence. Student or Trainer approves according to Coaching Mode and domain authority.

Never let FastAPI read the entire application database directly. Use: client -> Spring Boot authorization/context selection -> FastAPI -> structured result -> Spring validation -> recommendation/proposal -> human approval -> transactional application.

## Context Builder

Select only data needed for the request. Include applicable profile, Goal/version, Coaching Period, workout history/plan, performance/RPE, equipment, availability, nutrition, Progress Engine signals, and knowledge evidence.

Represent availability, recency, method, source, validation, and confidence. Include last workout, days since last workout, inactivity duration, and continuity for progression/recovery tasks. Ask for missing required inputs. Never encode missing as zero or infer muscle gain from weight without composition evidence.

## Pipeline

Use Context Builder -> deterministic Rule Engine -> governed knowledge retrieval -> LLM/vision -> schema-constrained output -> validator -> Recommendation/Proposal lifecycle.

Rules must block incompatible equipment, unsafe complexity, recovery conflicts, suspect/rejected measurements, unsupported inference, and normal progression after long inactivity. Prefer rules for known conditions; use the model for contextual reasoning and explanation.

Workout plans and changes must be structured with sessions, exercises, sets, reps, rest, reasons, evidence, assumptions, and uncertainties. Use `PENDING`, `ACCEPTED`, and `REJECTED`; AI itself never applies the change.

## RAG and operations

Retrieve only published/active Knowledge Versions. Store citations/references sufficient to explain the basis. PostgreSQL/pgvector is the initial vector store.

Audit significant AI Runs with request type, model/version, prompt version, relevant input references, output, validation/rule result, status, latency, tokens, and resulting decision. Operational states may include success, validation failure, model error, timeout, rule blocked, and processing error.

Evaluation/replay may compare prompts/models on privacy-controlled datasets but must never create a live business action. Feed accepted/rejected outcomes into evaluation without silently retraining or changing production behavior.
