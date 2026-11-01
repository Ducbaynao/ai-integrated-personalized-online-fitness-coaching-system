# B08 timed reminders — V54 A01 schema review packet (LOCAL CANDIDATE)

Person A owns A01 schema review. This candidate has **not** been approved by A01 and is not a deployed retention policy. `V54__timed_reminder_preferences.sql` follows AI V52 and Chat V53 on `origin/main@b471e25`.

## DDL and upgrade

- `notifications.inbox_visible` defaults to `true`, preserving every existing inbox row. A push-only reminder stores a private delivery anchor with `inbox_visible=false`; `GET /notifications/me` and READ filter it out. Ordinary in-app notifications are unchanged.
- `timed_reminders` stores source kind/ID, observed source version, recipient, channel, due/start instants, outcome and notification link. Its unique key covers kind/ID, source version, recipient, channel and reminder instant. No preference rows or reminders are backfilled; absence of either IN_APP or PUSH consent means OFF.
- Partial `idx_appointment_reminder_due` supports the global confirmed-Appointment 60-minute scan. The existing `idx_planned_workouts_pending` supports the scheduled-workout scan. `idx_timed_reminders_notification` supports dispatch rechecks and source invalidation.
- Two AFTER UPDATE triggers mark only `QUEUED` push attempts `SKIPPED` if an Appointment/Planned Workout version, time, status or workout supervision changes. A claimed `SENDING` attempt is not invalidated: Expo may already have received its payload, and finalization must retain any ticket/error for receipt reconciliation. Emitted inbox history remains historical; no source lifecycle or workout execution is changed.

## Transactions and rollout

Schedule/Workout owner ports acquire source `FOR SHARE` before reminder writes. Source writers already lock their source row; the trigger updates only `QUEUED` push attempts in the same source transaction. Materializer identity relies on a unique constraint and `ON CONFLICT DO NOTHING`; two workers cannot create two notices for one identity. Expo I/O stays outside the database transaction in the existing leased worker. Before provider I/O, the worker checks source version/state, account, authority, device, consent, quiet hours and source start. A source change or opt-out after this check cannot retract a submitted push: the in-flight `SENDING` attempt retains the provider ticket or failure, including an unknown outcome, instead of falsely becoming `SKIPPED`. An accepted ticket remains eligible for receipt reconciliation but does not prove device delivery. A claimed attempt whose source becomes invalid before the final preflight is skipped there; no new send is made. Deep links recheck current authority.

The migration uses `ALTER TABLE notifications`, `CREATE TABLE`, `CREATE INDEX`, `CREATE FUNCTION` and `CREATE TRIGGER` on hot source tables. A01 should review lock acquisition/rollout timing, particularly the `ALTER TABLE` lock and trigger installation under concurrent Appointment/Workout writes. No `lock_timeout`, maintenance window or production traffic measurement has been approved in this packet. Clean-install and V53→V54 upgrade tests must pass on disposable PostgreSQL before merge. No local `digital_fitness` database is used.

## Review questions

1. Are the two source triggers and their source-row → push-attempt lock order safe under expected production write load, or is a transactionally equivalent owner-writer hook required?
2. What migration window and lock timeout should operations use for V54? These are deployment values, not inferred product policy.
3. Confirm that push-only reminder anchors must remain hidden from the in-app inbox while still allowing authority-checked delivery diagnostics; no TTL or purge is introduced here.
