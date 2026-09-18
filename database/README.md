# Database

PostgreSQL 18 with pgvector is the system of record for normalized business data.

## Sources of truth

- Executable schema migrations: `services/backend/src/main/resources/db/migration`
- Development seed: `database/seed/dev_seed.sql`
- Physical domain-to-table map: `database/design/domain-map.md`
- Conceptual data model: `docs/04-database/data-model.md`
- Migration rules: `docs/04-database/migration-guidelines.md`

## Rules

- Flyway manages every persistent schema change.
- Never edit a migration already applied to a shared environment.
- Add the next ordered migration, starting from `V17` after the current migration set.
- Hibernate uses `ddl-auto=validate` outside isolated tests.
- PostgreSQL data is stored in a Docker volume and is not committed.
- Missing fitness or nutrition data is `NULL`/unavailable, never a fabricated zero.
- Media bytes belong in object storage; PostgreSQL stores metadata and object references.
- AI outputs are evidence/audit records and never become business decisions automatically.

## Local commands

From the repository root:

```powershell
docker compose up -d postgres
docker compose run --rm flyway
```

To rebuild disposable local data, use an explicit Docker volume operation only after confirming that no needed local data will be lost. Never use destructive database commands against a shared or production environment.

## Documentation responsibilities

- Update `database/design/domain-map.md` when a table is added, moved to another owning domain, or materially changes authority.
- Update `docs/04-database/data-model.md` when conceptual entities or relationships change.
- Keep seed data synthetic, repeatable, and free of secrets or personal data.
- Document backup and restoration changes in `database/backup/README.md`.
