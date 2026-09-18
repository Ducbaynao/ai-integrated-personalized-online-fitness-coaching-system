# Database agent instructions

- Read `database/README.md` and `database/design/invariants.md`
  before changing database code.
- Treat Flyway migrations as the schema source of truth.
- Never modify V1–V16 after they have been applied.
- New schema changes must use V17 or later.
- Preserve history and immutable version records.
- Do not grant AI, Trainer or Admin authority beyond documented scope.
- Do not represent missing measurements as zero.
- Add constraints and indexes for new invariants where practical.
- Update relevant database documentation with every schema change.
- Verify migrations against PostgreSQL 18 with pgvector.
- Never commit credentials, PostgreSQL volumes or real database backups.