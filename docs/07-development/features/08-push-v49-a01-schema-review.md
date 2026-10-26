# B08 V49 — A01 schema review packet (candidate, not approval)

Owner of migration review: Person A (A01). Person B implemented the candidate on `feature/b08-chat-attachment-push-notification`; neither CI nor merge substitutes for A01 sign-off.

## Change and upgrade

`V49__push_opt_in_and_provider_receipts.sql` adds `push_devices` and `push_delivery_attempts` with unique `(user_id,device_key)`, unique Expo token, unique `(notification_id,device_id)`, a partial due-queue index `(status,next_attempt_at,id)` and a partial expired-lease index `(status,lease_expires_at)`. The attempt also has a fenced claim token, lease expiry, one-send maximum and 20-receipt-check maximum. `SENDING` and `CHECKING_RECEIPT` require both claim token and lease by CHECK; other states cannot retain a claim. It does not alter V8 notification rows or preferences, backfill devices, or enable push for any account. The V8 `notification_preferences` PUSH row must be explicitly enabled; absence means OFF. V8 IN_APP remains independent. Internal claim states are projected as `QUEUED` or `TICKET_ACCEPTED` in the public read API. No status claims that an Expo ticket/receipt proves device delivery.

Upgrade test `V49PushMigrationUpgradeTest` migrates a disposable pgvector database to V48, inserts an existing notice, applies V49, then verifies the notice survives, due/claim columns and index exist, and no preference/device/attempt is invented. Full clean install is exercised by HTTP/PostgreSQL Chat/Push integration tests at the newest migration. No legacy history or object binary is rewritten.

## Transaction and concurrency

The source domain fact, generic in-app notification and optional per-device queue rows commit in the same transaction. Unique constraints dedupe attempts on replay. Enqueue stores `next_attempt_at` from PostgreSQL time and the confirmed IANA quiet-hours interval, including midnight/DST. Each short worker transaction selects **one due row** with `FOR UPDATE OF a SKIP LOCKED LIMIT 1`, writes `SENDING` plus claim token/one-minute lease, then commits; a worker processes up to 20 rows per invocation, claiming immediately before each HTTP call so later rows cannot expire while waiting behind a slow provider. Device, opt-in and notice authority are rechecked before the outbound call. **No provider HTTP occurs inside that transaction or any row lock.** Finalization uses a separate short transaction with claim token and lease fencing. A crashed/expired send claim or uncertain ticket is marked `FAILED/TICKET_OUTCOME_UNKNOWN` and never blindly resent (one send attempt maximum). Receipt checks likewise claim/commit before HTTP; unavailable receipts are deferred 30 seconds with at most 20 checks. An expired receipt lease is safe to retry because reading a receipt cannot send another push. An explicit device callback requires current read authority and active device. Expo ticket/receipt cannot assert device delivery. Provider HTTP has bounded timeouts; throughput and ambiguous-result observability remain deployment concerns.

## Review questions

1. Confirm the two new tables, FKs, unique constraints and partial queue index are appropriate for existing V8 data, including account deletion behavior.
2. Confirm using V8 `notification_preferences.event_type` for the seven explicit UX categories and V8 `notification_deliveries.PUSH` as an aggregate provider-ticket status does not conflict with other planned writers.
3. Review one-minute claim lease, one-send/20-receipt limits, expiry-to-unknown handling, and the due queue index against production throughput. PostgreSQL tests cover 20 quiet rows ahead of a due row, simultaneous workers, provider delay/opt-out, expired claim, unavailable receipt and replay. A revoke between the final authority recheck and an already-started external HTTP request cannot be recalled; the fenced finalizer does not report delivery after opt-out. Deployment load testing remains to be verified.
4. Confirm private bucket policy, clamd internal-only network access, EAS project ID/FCM credentials and secret injection before enabling `EXPO_PUSH_ENABLED`. S3Mock verifies adapter protocol only, not production ACLs; no production delivery is claimed.

Retention, export, account erasure, backup deletion, timed reminders and attachment deletion remain separate unapproved release decisions. V49 adds no TTL/deletion job.
