# Testing guide

Testing must prove business authority and history preservation, not only CRUD success.

## Backend

Use JUnit, Mockito where isolation is useful, Spring Boot tests for wiring/security, and Testcontainers for PostgreSQL/Flyway/integration behavior.

Required categories include:

- authentication, refresh rotation, session revocation, roles, permissions, and object authorization;
- common-account multi-role onboarding and Trainer eligibility;
- Goal Proposal approval, Goal Version versus new Goal, Transition, pause/resume, and history preservation;
- Coaching Period transitions, Trainer change, pre-coaching data scope, and former-Trainer access loss;
- self-directed plan/template/AI proposal and human-coach plan authority;
- significant versus minor Workout Plan changes;
- planned versus performed date, completion versus schedule adherence, and missed/rescheduled execution;
- recurring appointment scope, conflict detection/revalidation, and supervision requirements;
- Measurement normalization, outliers, quality, source provenance, deduplication, and missing-data behavior;
- training inactivity, return-to-training rules, trend calculations, and Attention Signals;
- Nutrition ownership, proposal, target version/effective date, day-type resolution, override, completeness, and history;
- AI Recommendation validation, approval, source-version conflict, and audit;
- Admin permission denial, privileged access, data correction, moderation/support, and immutable audit.

## AI service

Use pytest for:

- Context Builder minimization and authorization contract;
- explicit missing/stale/suspect data handling;
- long-inactivity and measurement-quality rules;
- structured-output schema validation;
- invalid exercise/equipment/reference rejection;
- RAG retrieval restricted to active Knowledge Versions;
- unsupported-inference guards;
- prompt/model version metadata and deterministic evaluation fixtures;
- Nutrition AI estimate provenance and confirmation boundary.

Model-dependent tests should separate deterministic contract/rule assertions from probabilistic quality evaluation. Production business correctness must never depend only on an exact natural-language model response.

## Client applications

Use React Native Testing Library/Jest for Mobile and the corresponding React testing stack for Admin Web. Test permissions and lifecycle states as presentation behavior, while treating backend authorization as the security boundary.

Important flows include onboarding, Goal proposal decisions, Workout logging, reschedule requests, incomplete data states, recommendation decisions, Trainer Attention Signals, Admin verification review, and privileged-action reauthentication.

## Contract and migration tests

- Validate OpenAPI and WebSocket/AI schemas:
  ```bash
  npm run validate:contracts
  npm run validate:ux-docs
  ```
- Run Flyway migrations against an isolated disposable PostgreSQL 18 + pgvector database:
  ```bash
  npm run test:migrations
  ```
- Test unique/check/reference constraints and expected indexes.
- Verify backward compatibility for supported app/API versions.

## CI sequence

The CI pipeline runs independent parallel jobs that report failures clearly:

1. **Client Admin Web**: `npm ci`, `npm run lint:admin`, `npm run build:admin`
2. **Client Mobile**: `npm ci --prefix apps/mobile`, `npm run lint:mobile`, `npm run typecheck:mobile`
3. **Backend**: `./mvnw -B test` (Java 21)
4. **AI Service**: `python -m ruff check .`, `python -m pytest` (Python 3.12)
5. **Contracts & Docs**: `npm run validate:contracts`, `npm run validate:ux-docs`
6. **Database Migrations Check**: Flyway migration verification against disposable PostgreSQL 18 + pgvector container

