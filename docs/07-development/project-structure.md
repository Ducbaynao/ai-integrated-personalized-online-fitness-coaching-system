# Project structure

The repository is a monorepo. Each top-level path has a distinct responsibility.

```text
apps/
  mobile/             Expo application for Student and Trainer
  admin-web/          React/Vite application for Administrator
services/
  backend/            Spring Boot modular monolith and business authority
  ai-service/         FastAPI AI, RAG, rule, and vision workloads
packages/             Shared TypeScript packages for client applications
contracts/            OpenAPI, WebSocket, and AI structured-output contracts
database/             Flyway migrations, seed data, and executable DB assets
infrastructure/       Local/deployment infrastructure configuration
docs/                 Product and engineering documentation
tools/                Repository tooling and scripts
```

## Where changes belong

- Product intent, domain rules, lifecycles, and architecture: `docs`.
- Executable database changes: `database/migrations`.
- API/event/model contracts: `contracts`.
- Business logic, authorization, transactions, and orchestration: `services/backend`.
- Model prompts, retrieval, structured-output adapters, evaluation, and Python AI integrations: `services/ai-service`.
- Student/Trainer presentation: `apps/mobile`.
- Platform-governance presentation: `apps/admin-web`.

## Backend module conventions

Each domain module should keep a clear separation between API adapters, application/use-case services, domain logic, and persistence/integration adapters. Controllers translate transport requests and responses; they do not implement business decisions.

Cross-module behavior uses published interfaces, application services, or events. A module must not reach into another module's repository. External API DTOs are separate from persistence entities.

## Change checklist for coding agents

Before implementing a feature:

1. Identify the owning domain and decision authority.
2. Read the corresponding docs and repository/component `AGENTS.md`.
3. Check whether the change introduces a lifecycle transition, version, effective date, permission, audit event, or contract change.
4. Update Flyway migration instead of relying on schema auto-generation.
5. Update OpenAPI/event/structured-output contracts when interfaces change.
6. Add unit, integration, authorization, and lifecycle tests for changed rules.
7. Update documentation when product behavior changes.

Avoid duplicating the full product description inside source-code comments. Comments should explain local implementation reasoning and link to the relevant domain document when necessary.

