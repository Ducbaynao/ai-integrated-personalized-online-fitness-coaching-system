# Migration guidelines

Flyway owns schema evolution. Hibernate automatic schema generation is allowed only in isolated tests, never as the production migration mechanism.

## Rules

1. Use ordered, descriptive migration names such as `V17__create_goal_version_tables.sql`.
2. Never modify a migration already applied to a shared environment. Add a new migration.
3. Include constraints that express stable invariants: keys, uniqueness, references, valid ranges, and non-null requirements that are truly mandatory.
4. Use nullable columns for genuinely unavailable optional fitness data; do not insert fake zero/default values.
5. Preserve history during renames, splits, and lifecycle changes. Backfills must be deterministic and reviewable.
6. Prefer expand-and-contract for changes used by running clients: add compatible structure, migrate/read both as needed, switch code, then remove obsolete structure in a later release.
7. Large backfills should be restartable, observable, and separated from long blocking schema transactions when necessary.
8. Add indexes from actual query plans and access patterns, not speculation.
9. Migration rollback strategy may be forward-fix when down migration risks data loss; document restoration/recovery implications.
10. Seed data must be safe for its target environment and must not include production secrets or personal data.

## Review checklist

- Does the migration preserve Goal, Coaching, Workout, Nutrition, Measurement, and audit history?
- Does it accidentally collapse independent lifecycles?
- Does it introduce a boolean where a target lifecycle requires explicit states/history?
- Are effective dates and source-version references retained?
- Are authorization and tenant/object scope columns available where needed?
- Are object-storage files represented by metadata/reference rather than database blobs?
- Can deployment run with the previous application version during rollout if required?
- Are backup and restoration procedures still valid?

