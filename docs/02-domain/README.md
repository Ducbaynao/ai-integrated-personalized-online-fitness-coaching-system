# Domain model

The Spring Boot backend is a modular monolith organized around business boundaries. Modules collaborate through explicit application services, events, or published interfaces; a module must not directly use another module's repository.

## Domain boundaries

| Boundary | Responsibility |
| --- | --- |
| Identity | Authentication, User Account, roles, permissions, sessions, and tokens |
| Student | Student Profile, preferences, experience, availability, and declared limitations |
| Trainer | Trainer Profile, application, verification, certification, capacity, and availability |
| Goal | Fitness Goal, Goal Target, Proposal, Version, Transition, and progress scope |
| Coaching | Coaching Relationship, Coaching Period, decision authority, and Data Sharing Permission |
| Exercise and content | Exercise, variation, muscle, equipment, instructions, media, lifecycle, and canonical mapping |
| Workout | Workout Plan, plan versions, Planned Workout, Actual Workout, logs, RPE, and volume |
| Schedule | Appointment, recurrence, conflict checking, Change Request, history, timezone, and supervision |
| Measurement | Metric Definition, Measurement, source, method, provenance, validation, quality, and deduplication |
| Progress | Trends, adherence, continuity, Goal progress, Attention Signals, and review evidence |
| Nutrition | Nutrition Goal, Proposal, Target Version, Daily Target, Meal, Food Log, calculation, and completeness |
| Collaboration | Chat, conversation membership, messages, attachments, receipts, and notifications |
| AI orchestration | Context authorization, AI Run, validation, Recommendation, approval, and application |
| Administration | Admin permissions, account operations, Trainer review, configuration, and platform workflows |
| Governance | Exercise/Knowledge publishing, moderation, support, data correction, privileged access, and audit |

## Aggregate and history principles

- A User Account may reference Student and Trainer profiles independently.
- Aggregate existence is not authority. Trainer authority additionally requires eligible verification/activity state and an active relationship.
- Fitness Goal, Coaching Period, Workout Plan, Appointment, Planned Workout, Actual Workout, Measurement, and Nutrition Target have distinct lifecycles.
- Effective-dated versions preserve the meaning of historical records.
- Long-lived Student fitness history is not owned by one Goal or one Trainer.
- PostgreSQL owns normalized business truth; files are stored in object storage and temporary state may use Redis.

See [business rules](business-rules.md), [lifecycles](lifecycles.md), and [permissions and authority](permissions-and-authority.md).

