# Database documentation

PostgreSQL is the system of record for normalized business data. pgvector supports retrieval for versioned Knowledge Chunks. Redis stores cache and temporary state. Object storage holds binary media while PostgreSQL stores ownership, type, size, object key, and lifecycle metadata.

## Documentation and executable schema

- This directory explains the conceptual data model and migration rules.
- `services/backend/src/main/resources/db/migration` contains executable Flyway migrations.
- `database/design/domain-map.md` maps physical tables to business boundaries and authority.
- Applied migrations are immutable.
- Entity/table names in design documents are conceptual until a migration and code contract establish the physical name.

## Data groups

- identity, roles, permissions, sessions, and account lifecycle;
- Student and Trainer profiles, applications, verification, certifications, capacity, and availability;
- Fitness Goals, Targets, Proposals, Versions, and Transitions;
- Coaching Relationships, Periods, and Data Sharing Permissions;
- Exercise/content catalog and media references;
- Workout Plans/Versions, Planned Workouts, Actual Workouts, and set logs;
- Appointments, recurrence, Change Requests, conflicts, and supervision;
- Metric Definitions, Measurements, sources, validation, quality, integrations, and continuity;
- Progress signals, review cycles, and Attention Signals;
- Nutrition Goals, Proposals, Target Versions, Daily Targets/Overrides, Meals, and Food Logs;
- chat, notification, AI metadata, Knowledge, moderation, support, audit, jobs, and configuration.

See [data model](data-model.md) and [migration guidelines](migration-guidelines.md).
