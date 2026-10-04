# Source traceability

This matrix maps the original product-description sections to the living documentation. It is used to check coverage and to prevent important business decisions from being lost when the source document evolves.

| Source sections | Subject | Living documentation |
| --- | --- | --- |
| 1-4, 92-94 | product, users, differentiation, conclusion | `00-project-overview` |
| 5 | account, authentication, multi-role, Trainer activation | `01-requirements`, `02-domain`, `05-api`, `09-security-operations` |
| 6 | Student Profile and complete Fitness Goal lifecycle | `01-requirements`, `02-domain/business-rules.md`, `02-domain/lifecycles.md` |
| 7-9 | Trainer Profile, Coaching Relationship/Period, data sharing | `00-project-overview/actors-and-responsibilities.md`, `02-domain` |
| 10-11 | Measurements and Progress Photos | `01-requirements`, `02-domain`, `04-database/data-model.md` |
| 12-19 | Exercise, Workout Plan, versioning, execution, RPE, volume, continuity | `01-requirements`, `02-domain`, `04-database` |
| 20-28 | Appointment, recurrence, conflicts, changes, timezone, chat, notification | `01-requirements`, `02-domain`, `03-architecture/data-flow.md`, `05-api` |
| 29-36 | Nutrition Goal/Target lifecycle, Food Log, Nutrition AI and database | `01-requirements`, `02-domain`, `04-database`, `08-ai` |
| 37-49 | AI Assistance, context, rules, RAG, versions, recommendations, audit, feedback | `03-architecture`, `08-ai` |
| 50-52 | Student/Trainer dashboards, workspace, attention, Progress Engine | `01-requirements`, `02-domain`, existing `06-ui-ux` |
| 53-57 | Administrator, governance, packages, audit, deletion, API versioning | `00-project-overview`, `02-domain`, `05-api`, `09-security-operations` |
| 58-70 | system components, modular monolith, AI boundary, data/storage/security | `03-architecture`, `04-database`, `05-api`, `09-security-operations` |
| 71-82 | non-functional requirements, scale, cache, jobs, observability, backup, deployment, CI/CD | `01-requirements/non-functional-requirements.md`, `07-development`, `09-security-operations` |
| 83-84 | testing and API documentation | `07-development/testing-guide.md`, `05-api` |
| 85-89 | roadmap and future Pose Estimation | `00-project-overview/scope-and-roadmap.md` |
| 90-91 | target architecture and architecture principles | `03-architecture`, `docs/README.md` |

## Review invariants

Reviewers and coding agents should search for and confirm these statements before approving broad changes:

- exactly two Coaching Modes;
- AI is assistance and has no business authority;
- Coaching Mode is period-based;
- Fitness/Nutrition history does not reset;
- Goal Version is different from a new Goal;
- Appointment is different from Planned/Actual Workout;
- planned date is different from performed date;
- completion is different from schedule adherence;
- missing Measurement/Food Log is not zero;
- Progress Engine precedes AI reasoning;
- Student owns personal Goals and strategic Nutrition Targets;
- Administrator is platform authority under least privilege;
- PostgreSQL is the system of record.

## Phase 2 planning traceability (DRAFT)

| Planning source | Repository document | Coverage |
| --- | --- | --- |
| Release scope, document version 0.4 | [Release scope](00-project-overview/phase-2-release-scope.md) | F01–F11, D01–D07, release limits and approval record |
| Feature data/authority, document version 0.4 | [Data and authority matrix](08-ai/phase-2-feature-data-authority-matrix.md) | Eleven feature contracts, context, permissions, lifecycle, retries, Food Confirmation and embedding compatibility |
| Implementation plan, document version 0.4 | [Implementation plan](07-development/phase-2-implementation-plan.md) | P2-M0–P2-M6, prerequisite evidence, ownership by issue, T01–T28 and evaluation gates |

The planning baseline commit is a historical reference. Importing these documents does not approve decisions, verify the external source DOCX, or demonstrate software completion. Product-description section coverage remains in the matrix above; planned-feature-to-test coverage is owned by implementation-plan section 12.3.
