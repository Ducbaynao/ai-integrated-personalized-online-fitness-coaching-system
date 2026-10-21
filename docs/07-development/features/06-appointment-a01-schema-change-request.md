# B06 Appointment / B08 notification — A01 Schema Change Request

**Status:** V45 CANDIDATE WRITTEN / A01_REVIEW_PENDING, not merged or production-applied. **Base:** `origin/main@9279057cbf45d21b3b6677f8f4a99a6785b8e6a8` (2026-10-20). Person B may write and test a forward migration candidate; Person A owns independent schema review before merge. Review is not a prerequisite for local implementation. Do not claim an end-to-end flow until executable contracts, backend, Mobile and integration tests pass.

Candidate file: `services/backend/src/main/resources/db/migration/V45__appointment_requests_and_lifecycle_notification_identity.sql`. It uses one new `appointment_requests_v2` table for CREATE and RESCHEDULE instead of mutating the V5 historical `reschedule_requests` table; the illustrative SQL below is retained as design history, not exact V45 DDL. V45 also adds a composite period/party FK, append-only receipt/event triggers and source subject columns on notifications. A01 should review these details and the observed upgrade tests before merge.

## Current executable boundary

- V5 `appointments` (lines 262–287) has Student, nullable Trainer/relationship/occurrence, UTC start/end and display timezone, but no Coaching Period, optimistic version, local-time intent or chosen UTC offsets. V5's exclusion constraint protects Trainer intervals in `SCHEDULED`/`CONFIRMED`, **not Student intervals**. `reschedule_requests` (289–303) lacks version, period, timezone/local intent and idempotent receipt. `appointment_history` (305–318) holds a basic status/time change but not complete before/after provenance.
- **Merged-base baseline only:** V16 (395–411) checked `COACH_REQUIRED` on execution by linked Appointment ID, non-null Trainer and `CONFIRMED`/`COMPLETED` status only; it did not compare Student, Trainer, Coaching Period or interval. V43 notification uniqueness covered `CHAT_MESSAGE` only. The candidate Schedule-owned query, Workout materializer and Notification implementation in this worktree narrow those rules; their runtime behavior is verified separately from A01 schema approval.
- **Merged-base baseline only:** B06 first-flow materialization rejected `COACH_REQUIRED`. The candidate Workout materializer now accepts an explicit qualifying Appointment and atomically links it to a new occurrence; Appointment mutation still blocks a dependent `COACH_REQUIRED` occurrence. This remains unmerged until V45 review and checkpoint PR.

## Proposed forward-only DDL shape (illustrative; A01 selects the actual migration version)

```sql
-- Additive, nullable for historical rows; do not infer local intent or a period.
ALTER TABLE fitness.appointments
  ADD COLUMN coaching_period_id uuid REFERENCES fitness.coaching_periods(id) ON DELETE RESTRICT,
  ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  ADD COLUMN local_start timestamp without time zone,
  ADD COLUMN local_end timestamp without time zone,
  ADD COLUMN start_utc_offset varchar(6),
  ADD COLUMN end_utc_offset varchar(6),
  ADD COLUMN guarded_v2 boolean NOT NULL DEFAULT false;

CREATE TABLE fitness.appointment_requests_v2 (
  id uuid PRIMARY KEY,
  student_id uuid NOT NULL REFERENCES fitness.student_profiles(user_id) ON DELETE RESTRICT,
  trainer_id uuid NOT NULL REFERENCES fitness.trainer_profiles(user_id) ON DELETE RESTRICT,
  coaching_period_id uuid NOT NULL REFERENCES fitness.coaching_periods(id) ON DELETE RESTRICT,
  planned_workout_id uuid REFERENCES fitness.planned_workouts(id) ON DELETE RESTRICT,
  requested_by uuid NOT NULL REFERENCES fitness.users(id) ON DELETE RESTRICT,
  local_start timestamp without time zone NOT NULL,
  local_end timestamp without time zone NOT NULL,
  timezone varchar(64) NOT NULL,
  start_utc_offset varchar(6) NOT NULL,
  end_utc_offset varchar(6) NOT NULL,
  start_at timestamptz NOT NULL,
  end_at timestamptz NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  appointment_id uuid UNIQUE REFERENCES fitness.appointments(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  decided_at timestamptz,
  decided_by uuid REFERENCES fitness.users(id) ON DELETE RESTRICT,
  CHECK (end_at > start_at),
  CHECK (requested_by IN (student_id, trainer_id))
);

-- Existing V5 reschedule requests remain historical; new rows carry explicit intent.
ALTER TABLE fitness.reschedule_requests
  ADD COLUMN coaching_period_id uuid REFERENCES fitness.coaching_periods(id) ON DELETE RESTRICT,
  ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  ADD COLUMN local_start timestamp without time zone,
  ADD COLUMN local_end timestamp without time zone,
  ADD COLUMN timezone varchar(64),
  ADD COLUMN start_utc_offset varchar(6),
  ADD COLUMN end_utc_offset varchar(6);

ALTER TABLE fitness.appointments
  ADD CONSTRAINT appointment_guarded_v2_complete_ck CHECK (
    NOT guarded_v2 OR (trainer_id IS NOT NULL AND coaching_period_id IS NOT NULL
      AND local_start IS NOT NULL AND local_end IS NOT NULL
      AND start_utc_offset IS NOT NULL AND end_utc_offset IS NOT NULL)
  );

-- Only new guarded rows are constrained here; every writer still checks legacy rows.
ALTER TABLE fitness.appointments
  ADD CONSTRAINT appointment_student_no_overlap_v2
  EXCLUDE USING gist (
    student_id WITH =,
    tstzrange(start_at,end_at,'[)') WITH &&
  ) WHERE (guarded_v2 AND status IN ('SCHEDULED','CONFIRMED'));

CREATE TABLE fitness.appointment_command_receipts_v2 (
  actor_id uuid NOT NULL REFERENCES fitness.users(id) ON DELETE RESTRICT,
  command_key uuid NOT NULL,
  command_type varchar(40) NOT NULL,
  payload_hash varchar(64) NOT NULL,
  request_id uuid REFERENCES fitness.appointment_requests_v2(id) ON DELETE RESTRICT,
  reschedule_request_id uuid REFERENCES fitness.reschedule_requests(id) ON DELETE RESTRICT,
  appointment_id uuid REFERENCES fitness.appointments(id) ON DELETE RESTRICT,
  response_json jsonb NOT NULL,
  committed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (actor_id, command_key)
);

CREATE TABLE fitness.appointment_events_v2 (
  id uuid PRIMARY KEY,
  appointment_id uuid REFERENCES fitness.appointments(id) ON DELETE RESTRICT,
  request_id uuid REFERENCES fitness.appointment_requests_v2(id) ON DELETE RESTRICT,
  reschedule_request_id uuid REFERENCES fitness.reschedule_requests(id) ON DELETE RESTRICT,
  event_type varchar(50) NOT NULL,
  actor_id uuid NOT NULL REFERENCES fitness.users(id) ON DELETE RESTRICT,
  old_state jsonb,
  new_state jsonb,
  reason text,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK (appointment_id IS NOT NULL OR request_id IS NOT NULL OR reschedule_request_id IS NOT NULL)
);

ALTER TABLE fitness.notifications
  ADD COLUMN source_event_id uuid;
CREATE UNIQUE INDEX uq_lifecycle_event_notification_v2
  ON fitness.notifications(source_event_id,recipient_user_id,event_type)
  WHERE source_event_id IS NOT NULL;

CREATE INDEX ix_appointment_requests_student_v2
  ON fitness.appointment_requests_v2(student_id,created_at DESC,id DESC);
CREATE INDEX ix_appointment_requests_trainer_v2
  ON fitness.appointment_requests_v2(trainer_id,created_at DESC,id DESC);
```

`source_event_id` must identify one immutable committed domain event, **not** a mutable aggregate ID. The final dedupe key is `(source_event_id, recipient_user_id, event_type)`; old Chat rows retain null and their V43 uniqueness rule. The new event writer must require a non-null source event ID. A01 may add a database CHECK after auditing any historical non-Chat rows; do not make an upgrade-breaking assumption. A delivered notification must retain source subject/period metadata sufficient for current-authority concealment, without storing sensitive preview data. Existing Chat rows must remain readable.

For conflicts, add a database-backed Student half-open interval exclusion for **new guarded** accepted Appointments, with `guarded_v2=true` and status `SCHEDULED`/`CONFIRMED`; retain V5's Trainer exclusion. The candidate index requires `btree_gist` and a verified legacy audit. Do not apply a global constraint to historical rows without querying overlap first. Every new accept must also lock the Student boundary and inspect conflicting unguarded legacy rows, rejecting an unknown/missing interval rather than fabricating one. A01 should decide the exact partial predicate after auditing historic statuses; a CHECK must require complete local intent, offsets, period and parties when `guarded_v2=true`.

## Approved qualifying predicate and transaction/lock review

For new `COACH_REQUIRED` confirmation and START, a qualifying Appointment is exactly `CONFIRMED` and has the same Student, Trainer and **current** Coaching Period; the occurrence interval `[plannedStartAt,plannedEndAt)` is contained in Appointment `[startAt,endAt)`. Null end/identity never qualifies; `COMPLETED` is historical only. START additionally requires authoritative PostgreSQL time in Appointment `[startAt,endAt)` with no grace period. Merged V16 and its B05 query accepted `COMPLETED` without pair/period/time checks; the unmerged V45 candidate and Schedule-owned query now narrow this guard together, subject to A01 review. In the Student direct confirmation flow, a pre-existing confirmed Appointment is supplied by real ID per `COACH_REQUIRED` item; the Workout-owned materializer locks it, creates the real occurrence, and assigns `appointments.planned_workout_id` **in the same transaction** after all pair/period/interval checks. The focused PostgreSQL batch test covers failed qualification rollback, successful linking and replay. Trainer schedule proposals remain excluded because V32 does not persist an Appointment ID. Neither the client nor a second writer invents an occurrence ID or links outside that transaction.

The **implemented** direct batch takes the Student-profile lock, then plan/version locks, then the qualifying Appointment row lock. Appointment cancellation checks the current Coaching Period under a share lock, locks its actor-scoped receipt key, locks the observed occurrence if already linked, then locks the Appointment row; it does **not** currently take the Student-profile lock. The Appointment row is therefore the rendezvous for direct-batch-versus-cancel, not an asserted universal Student-first lock order. Recheck period/party/permission, request/Appointment/occurrence versions and database time **after** the relevant locks. Appointment accept validates both Student and Trainer half-open intervals against PostgreSQL time and exclusion constraints before inserting Appointment, request decision, audit, notification/outbox and receipt in one transaction. Appointment reschedule request and ACCEPT both require current `startAt` still in the future. Cancellation by either party requires a reason and the same time guard; reject it while a `COACH_REQUIRED` occurrence still depends on that Appointment. Another command key may race; exactly one wins, with stable conflict mapping and no SQL detail. No direct cross-module repository access: Schedule owns Appointment storage; Workout exposes occurrence state through a port; Coaching owns period authority; Notification owns its insert/query port.

## Required upgrade and regression tests

1. Clean-install and V44-to-new-version upgrade on disposable pgvector PostgreSQL; preserve every V5 row and audit any legacy null period/local intent or conflicting intervals without backfill. Test migration refusal or partial-index behavior with overlapping historical rows. `AppointmentV45MigrationUpgradeTest` covers clean install plus a V44 legacy Appointment/notification upgrade; broader conflict fixtures remain required.
2. HTTP/PostgreSQL request/accept/reject/replay/stale version/authority loss; concurrent accept for same Student/Trainer, different command keys, Student and Trainer conflicts, DST gap/ambiguous offset, and rollback of Appointment plus notification.
3. Race linked Appointment cancel/move against B05 START, B06 direct/proposal confirmation and occurrence change. Assert no committed `COACH_REQUIRED` execution or confirmed occurrence without a qualifying link, and no 500 for expected database conflicts.
4. Former-Trainer list/detail/count concealment, Student history, notification dedupe/revoke/deep-link concealment, and V43 Chat regression. Runtime integration tests may use the V45 candidate on disposable PostgreSQL before A01 review; independent A01 schema review remains required before merge. No local `digital_fitness` database is permitted.

## Approved event coverage and A01 decision gate

B08's 2026-10-20 matrix now names Coaching relationship/resume requests, Fitness Goal, B06 batch/change and B07 Nutrition Strategy proposals, and new Appointment creation/reschedule/cancel events with their recipients. No B04 plan-publish notification is approved. Each successful source transition needs a stable immutable event ID and in-transaction Notification write (or transactional outbox), not an aggregate ID. Exact replay creates neither a second event nor notification. At commit, check recipient authority; inbox and deep link recheck it again. Former Trainer receives no live old-period result. A01 must review `source_event_id` persistence and uniqueness plus source/period metadata suitable for safe read filtering. V43 Chat data and semantics remain unchanged.

A01 must independently review V45 and inspect V5/V13/V16 plus upgrade data for null/ambiguous legacy period/local intent, Student/Trainer interval overlap, linked `COACH_REQUIRED` anomalies and non-Chat notification rows. No guessed backfill or global constraint over unchecked legacy rows. V45 candidate is not evidence that dependent endpoints or guards are executable; their runtime tests are separate gates.

### Focused A01 review packet after local race verification

- **Partial exclusion and legacy:** `appointment_student_no_overlap_v2` covers only `guarded_v2 AND status='CONFIRMED'`. The V44-to-V45 upgrade test preserves two overlapping legacy rows with `guarded_v2=false`; it does not silently repair them. A disposable PostgreSQL regression first proved that an INSERT omitting `guarded_v2` committed after V45, including across an overlapping legacy Student interval: the default `false` bypassed both the completeness CHECK and partial exclusion. V45 candidate now has a `BEFORE INSERT` trigger (`appointment_guarded_v2_insert_ck`) that rejects both omitted and explicit `false` with SQLSTATE 23514 while leaving pre-V45 rows untouched; explicit `true` must then satisfy the guarded CHECK/exclusion. The test-only `LegacyAppointmentFixture` temporarily disables only this V45 trigger inside a disposable-DB transaction; earlier FK and lifecycle guards remain active. It is not a production writer or migration backfill. A01 should review the trigger, partial predicate, upgrade evidence and V45 `RAISE NOTICE` audit counts before production migration. New accept transactions still inspect legacy Student/Trainer intervals.
- **Missing source identity/interval:** legacy period/local intent/offsets remain nullable; no guessed backfill. Review the guarded-row completeness CHECK, insert guard and guarded-TRUE-to-FALSE downgrade trigger, and the `(coaching_period_id,student_id,trainer_id)` composite FK against historical null Trainer/period values. The B05 execution trigger requires a current confirmed, containing Appointment with complete pair/period/end and DB time inside the Appointment.
- **Immutability/dedupe:** inspect append-only triggers on `appointment_events_v2` and `appointment_command_receipts_v2`, actor-scoped receipt key, and `(source_event_id,recipient_user_id,event_type)` partial unique index. Historical Chat notification rows retain null source event/subject columns.
- **Observed lock/race result:** `WorkoutScheduleBatchIntegrationTest` now holds the Appointment row in an independent PostgreSQL transaction, confirms two HTTP commands are waiting on its row lock, then releases them in both orders. Batch-first committed one linked `SCHEDULED` occurrence and one batch receipt/audit; cancel returned 409 without a cancel receipt/event. Cancel-first committed one `CANCELLED` Appointment event/receipt; batch returned 409 with no occurrence, link, batch receipt or audit. Both orders returned no 500/deadlock. A01 should review whether this Appointment-row rendezvous and the additional START/change/accept paths are sufficient under V45 triggers; no claim is made that every pairwise writer race has a dedicated test.
