# CI #408 — Workout execution concurrency review

Date: 2026-11-26. Independent review: no blocking finding in the two-file patch.

## Baseline and scope

- Anti checkout was read-only at `fe25e44188402770b235d4c928388471e0156a44`.
- Integrated cleanly on current main `c74e5da3ade8ada98b555eca5f29a943d640940d`, which includes merged P7-008. Main changes since Anti baseline do not touch the two patch files. Main CI #410 PASS was verified but is not evidence that #408 is fixed.
- Branch: `codex/fix-workout-execution-concurrency-408`. Only command-service isolation/terminal exception mapping and deterministic concurrency tests change. No migration, dependency, contract, Mobile or unrelated concurrency source changes.

## Cause and controlled reproduction

The initial execution read establishes a REPEATABLE_READ snapshot before waiting for the student mutex. A set writer commits execution version 1 while complete waits. The subsequent `SELECT id FROM fitness.workout_session_logs WHERE id=? FOR UPDATE` targets a row updated since that snapshot and PostgreSQL raises SQLState **40001**, translated by Spring into `CannotAcquireLockException`. A fresh expected version of 1 therefore failed before the version check. This is an ordering-dependent defect, not a diagnosis of flaky CI.

The original Anti scratch logs `repro-interleaving.log` and `repro-deterministic-fail.log` were located and reviewed. A separate reviewer harness also reproduced the baseline failure: **1 test, 1 assertion failure, 0 errors/skips, exit 1**, expected `SUCCESS`, actual `RAW:CannotAcquireLockException`; it recorded SQLState 40001 and the database-observed blocked student-lock query.

The control used only a baseline class compiled into the review checkout's ignored build output. Source files were never reverted or edited. The patched class was restored by compilation before final verification. Latches and `pg_stat_activity` / `pg_blocking_pids` establish the ordering; no sleep or elapsed-time success assertion is used.

PostgreSQL behavior reference: [PostgreSQL 18 transaction isolation](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-REPEATABLE-READ).

## Isolation and lock invariants reviewed

- `start`: student mutex precedes active-execution/idempotency checks and occurrence freeze; occurrence version, startability, supervision appointment locking, immutable baseline and unique execution constraints remain.
- `skip`: shared student/actor and occurrence locks retain expected-version and unsealed-occurrence checks.
- `upsertSet`: the initial read supplies immutable ownership only; the aggregate is reloaded under student/execution locks. Set identity/replay and version checks, root version bump and audit remain.
- `substitute`: student/execution locks, version check and authoring-reference locks remain; prescribed baseline is preserved. The independent test also checks valid distinct substitution, stale version and rejected unknown variation without a partial version change.
- `complete` / `abort`: student -> occurrence -> execution/children ordering remains. The locked aggregate is reread at READ_COMMITTED, then expectedVersion/lifecycle/terminal derivation are checked before writes. Schedule and adjustment writers use the same student/occurrence boundary, so they cannot alter this decision concurrently.
- No invariant was found to rely solely on a repeatable snapshot once the existing shared mutex/row locks are held. No optimistic success or hidden mutation retry was added.

`lockExecutionOrConflict` maps only nested SQLException SQLState `40001`. Independent tests verify complete and abort mapping; `40P01`, `55P03` and `23505` are not relabeled. The domain RuntimeException escapes the transactional service immediately, causing rollback; no database operation continues on the failed transaction. Existing error advice maps `WORKOUT_EXECUTION_VERSION_CONFLICT` to HTTP 409 with a safe message. An outer REQUIRED caller can still impose REPEATABLE_READ; the independent outer-RR case verifies safe terminal conflict and rollback rather than pretending the service overrides its caller's isolation.

## Independent verification on main + patch

- Focused suites: **135 tests, 0 failures, 0 errors, 0 skipped, exit 0**, Surefire reports checked. Includes controller 23, access 8, command 1, query 8, domain 3, module boundary 4, concurrency 27, persistence 7, schedule change 21, schedule proposal 33. Existing assertions and SUCCESS expectation remain; stale-version regression is additive. Test callers now exercise the service transaction boundary, while explicit outer-snapshot cases remain.
- Separate scratch harness: **7 tests, 0 failures/errors/skips, exit 0**. Fresh completion succeeds and derives COMPLETED from the committed set; stale expected version returns domain conflict; forced outer-RR serialization maps to conflict. Receipt/audit, root version, planned status and retained set assertions verify no partial terminal write. Includes SQLState classifier and substitution coverage.
- The first supplemental substitution fixture reused the prescribed variation with a non-null reason, violating the existing substitution constraint. Only the scratch fixture was corrected to a distinct valid variation; the original log is retained. This was not treated as a regression caused by the isolation patch.
- Sequential Maven options: `MAVEN_OPTS=-Xms32m -Xmx384m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m`, `-DforkCount=0`. The focused command uses the ten suites above. Scratch harness uses `surefire:test` after explicit javac compilation into ignored test build output. This differs from normal CI fork configuration.
- Full Backend local NOT RUN due to RAM constraints; full CI is required on the checkpoint SHA. Diff check passed. Scratch logs/source under the reviewer's `ci408-independent` directory are retained, including original FAIL and corrected-fixture PASS; raw diagnostic logs are not published because they are not needed for the narrow source checkpoint.

## Source hashes and preservation

SHA-256 of reviewed working files (before/after review and clean patch application):

- `WorkoutExecutionCommandService.java`: `E3A6D4516DB95ED1A14C5BBFF0A5662ED4DD8A50E827AAA418BFC64C3531818F`
- `WorkoutExecutionConcurrencyIntegrationTest.java`: `B15E4EF14061DD60BF7AAD19195E4A2E9A6289166B01069E3DEB38F58FF81470`

Anti checkout, QA worktree, existing evidence/backlog and active runtime/DB/container workloads are preserved. Only isolated Testcontainers databases were used. No emulator or QA stack was started. New PR must stay Draft; CI status belongs in its body, with no merge or protection bypass in this task. A passing run alone does not establish release/device acceptance or resolve unrelated limits.
