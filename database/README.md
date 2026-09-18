# Database

PostgreSQL 18 with pgvector is the system of record.

## Sources of truth

- Schema migrations:
  `services/backend/src/main/resources/db/migration`
- Development seed:
  `database/seed/dev_seed.sql`
- Database design documentation:
  `database/design`

## Rules

- Flyway manages all schema changes.
- Never edit an applied migration.
- Create the next migration, starting from V17.
- Hibernate uses `ddl-auto=validate`.
- PostgreSQL data is stored in a Docker volume and is not committed.
- AI outputs are audit records and never become business decisions automatically.

## Local commands

```powershell
docker compose up -d postgres
docker compose run --rm flyway


### 2. `database/design/domain-map.md`

Không cần liệt kê 198 bảng riêng lẻ. Hãy nhóm chúng theo domain:

```markdown
# Domain Map

| Domain | Main tables | Authority |
|---|---|---|
| Identity | users, roles, permissions | Platform |
| Coaching | coaching_relationships, coaching_periods | Student/Trainer |
| Fitness Goal | fitness_goals, fitness_goal_versions, goal_proposals | Student |
| Workout | workout_plans, workout_plan_versions, workout_sessions | Trainer or Student |
| Measurement | measurements, measurement_batches | Student/System |
| Nutrition | nutrition_goals, nutrition_goal_versions | Student |
| AI | ai_runs, ai_recommendations, context_snapshots | Assistance only |
| Knowledge | knowledge_documents, knowledge_versions, chunks | Admin governance |
| Administration | audit_logs, support_cases, moderation_cases | Admin |