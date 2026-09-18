---
name: fitness-backend-data
description: Architect or implement the Spring Boot modular monolith, REST/WebSocket APIs, PostgreSQL schema, Flyway migrations, transactions, Redis, object storage, FastAPI integration, background jobs, and evolutionary scaling.
---

# Fitness Backend and Data

Read `digital-fitness-core` and all domain skills touched by the change.

## Architecture

Build a Spring Boot modular monolith with boundaries for auth, user, student, trainer, goal, coaching, exercise/content, workout, schedule, measurement, progress, nutrition, chat, notification, AI orchestration, audit, administration, moderation, and support. Enforce boundaries in package/module dependencies and transactions; do not create a distributed system prematurely.

FastAPI is a separate AI workload but not business authority. Spring authorizes, selects context, validates output, owns transactions, and persists normalized business data.

## API and domain behavior

Version APIs under `/api/v1`. Publish OpenAPI. Use consistent errors with code, message, timestamp, request ID, and field errors. Use WebSocket for realtime chat while persisting history.

Implement authority and invariants in application/domain services, not controllers or UI. Use database constraints plus transactional validation for uniqueness, effective periods, conflict re-checks, approval transitions, and immutable historical versions.

## Data stores

- PostgreSQL is source of truth for identity, domain, AI metadata, and audit.
- pgvector stores RAG embeddings initially.
- Redis serves cache, rate limits, OTP/tokens, presence, temporary job state, and short-lived AI cache; design invalidation and never rely on it as durable truth.
- Object storage holds avatars, photos, food images, exercise media, and chat attachments; PostgreSQL stores owner, URL/key, type, size, and metadata.

Use Flyway for every schema change; disable uncontrolled production auto-DDL. Prefer normalized lifecycle/history records. Use soft delete/retention/anonymization where required.

## Performance and evolution

Create indexes from real query patterns, especially Student/time, Trainer/time, conversation/time, and log/time access. Consider partitioning high-volume logs only when justified. Add read replicas for read-heavy analytics later.

Run notification delivery, food image processing, AI generation, reminders, and statistics asynchronously. Start with simple schedulers/background mechanisms; add a broker only when needed. Make jobs idempotent, observable, retry-safe, and authority-aware.

Review every change for data migration, backward-compatible API behavior, transaction boundaries, authorization, audit, concurrency, and rollback/recovery.
