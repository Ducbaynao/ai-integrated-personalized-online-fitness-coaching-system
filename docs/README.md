# Project documentation

This directory is the living product and engineering specification for the AI-Integrated Personalized Online Fitness Coaching System. It translates the project description into smaller, reviewable documents that define product scope, business authority, domain lifecycles, architecture, data, APIs, AI behavior, security, and development practices.

The documentation describes the target product. The roadmap controls delivery order; it does not remove target-product boundaries from the design.

## Reading order

1. [Project overview](00-project-overview/README.md)
2. [Scope and roadmap](00-project-overview/scope-and-roadmap.md)
3. [Actors and responsibilities](00-project-overview/actors-and-responsibilities.md)
4. [Requirements](01-requirements/README.md)
5. [Domain model](02-domain/README.md)
6. [Architecture](03-architecture/system-architecture.md)
7. [Database documentation](04-database/README.md)
8. [API conventions](05-api/README.md)
9. [UI and UX documentation](06-ui-ux/README.md)
10. [Development guide](07-development/setup.md)
11. [Phase 1 implementation plan](07-development/phase-1-implementation-plan.md)
12. [AI subsystem](08-ai/README.md)
13. [Security and operations](09-security-operations/README.md)
14. [Source traceability](source-traceability.md)

## Phase 2 planning

Read the Phase 2 documents in this order:

1. [Release scope](00-project-overview/phase-2-release-scope.md): F01–F11, limits, D01–D07 and approval record.
2. [Feature data and authority matrix](08-ai/phase-2-feature-data-authority-matrix.md): inputs, ownership, result types, source guards, transactions and retries.
3. [Implementation plan](07-development/phase-2-implementation-plan.md): P2-M0–P2-M6, prerequisites, responsibilities and T01–T28.

These documents are in Vietnamese and use the same domain identifiers as the existing specifications. Their status is **APPROVED**, version 1.0, by the project owner on 2026-10-04. D01–D07 are settled design/release decisions; approval does not demonstrate implementation, specialist review or release readiness. Phase 1 milestones retain their existing names; the `P2-` prefix identifies Phase 2 milestones. Assignments and test evidence belong in issues/PRs.

## Sources of truth

- PostgreSQL is the source of truth for normalized business data.
- Flyway migrations in `services/backend/src/main/resources/db/migration` are the executable schema history.
- OpenAPI and event schemas in `contracts` are executable interface contracts.
- These documents define product intent, invariants, ownership, and implementation constraints.
- `AGENTS.md` defines repository-wide instructions for coding agents.

If implementation and documentation disagree, do not silently choose one. Verify the intended business rule, update the relevant code or contract, and update these documents in the same change.

## Non-negotiable product principles

- The only coaching modes are `HUMAN_COACH` and `SELF_DIRECTED`.
- AI Assistance is not a coaching mode and has no business authority.
- Coaching mode belongs to a Coaching Period, not permanently to the Student Profile.
- Fitness history survives Goal, Coaching Period, Trainer, and Workout Plan transitions.
- Fitness Goal and Nutrition Goal belong to the Student; strategic changes use proposals and Student confirmation.
- Trainer authority over a Student requires an active relationship, valid capability state, and permitted data scope.
- Administrator is platform authority, not coaching authority.
- Missing data is unknown, never zero.
- Progress Engine produces validated signals before AI context is built.
- Appointment, Planned Workout, and Actual Workout have independent lifecycles.
