# A01 schema review request — V48 return-signal activation (candidate)

Status: **A01_REVIEW_PENDING**. This document is a review packet, not Person A approval. The product decision and runtime change belong to Person B B08; Person A owns independent migration/schema review before merge.

## Why V48 exists

`attention_signals.signal_type` and `notifications.related_entity_type` are already extensible varchar columns. V44 already supplies signal identity, evidence JSON, version/history and current-period indexes; V45 already supplies notification dedupe by `(source_event_id, recipient_user_id, event_type)`. No new signal or notification table is needed. The approved historical-backfill rule does need an immutable database cutover so a delayed first scheduler run cannot send notices for old completed workouts. V48 creates one row in `attention_rule_activations`, stamped by PostgreSQL during migration, and protects it with the existing append-only trigger.

## Upgrade and legacy audit

- V48 neither updates nor backfills `workout_session_logs`, `attention_signals`, `notifications`, or existing Student timezone confirmations. Missing completion end or missing confirmed IANA timezone stays unknown.
- An eligible pair completed before the V48 cutover may create a historical signal but **no notice**. A pair whose later actual completion is strictly after cutover may notify; equality is historical. The source completion identities/versions, local dates, timezone and calendar-day gap are stored in signal evidence.
- Existing V44 unique source indexes dedupe `student_id + rule + source_key` (with or without period); the producer additionally checks across periods before inserting a return signal. Existing V45 partial unique index dedupes notification event/recipient/type. Source events and notices share the producer transaction; realtime is an after-commit invalidation.
- Workout history remains in Workout-owned persistence; Progress reads only the `WorkoutCompletionEvidenceQuery` port. The scheduler does not infer completion from a missing log or a planned workout.
- Interactive return-status reads now use the Workout-owned `completedForStudent(studentId)` SQL query with `student_id=? AND status='COMPLETED'`; the global ordered read is limited to scheduler evaluation. V12 `idx_workout_logs_student_performed` and V29 `ix_workout_execution_student_time` both lead with `student_id`. The disposable PostgreSQL integration test checks these indexes and `EXPLAIN` confirms a student-qualified plan on its small fixture. This does not prove production-scale index selection or throughput; no additional migration is proposed without that evidence.

## A01 questions / evidence gate

1. Confirm PostgreSQL migration-time `clock_timestamp()` is an acceptable immutable cutover across clean install and V47→V48 upgrade, including rollback/retry of a failed migration.
2. Confirm the existing V44 source-key uniqueness and V45 notification event index cover concurrent workers without a new index, and that the append-only trigger prevents moving the cutover after deployment.
3. Review the completed-workout read query's scale and whether an owner-side index or incremental cursor is required before production-sized deployment. No production throughput measurement is claimed here.

Automated evidence to attach before merge: V48 clean-install via `AttentionReviewIntegrationTest`, V47→V48 `V48ReturnSignalMigrationUpgradeTest`, concurrent scheduler/rollback/authority tests, contract/UX validation and `git diff --check`. Do not infer A01 sign-off from those tests or CI.
