# B08 Account Erasure — V55 A01 schema review packet (MERGED; REVIEW_PENDING)

V55 was merged via PR #133 at `8c8aa0cf35c3ef8e2ead8cfec094b32dc386a619`. Person A's user-reported direct approval of V50/V54 does **not** approve V55; no specific V55 sign-off was supplied. Merge/CI are implementation evidence, not A01 approval.

## DDL and data behavior

`V55__measurement_erasure_tombstones.sql` adds nullable `measurements.erased_at` and `erasure_request_id` (FK to `account_erasure_requests`). It replaces `measurement_one_value_ck` so ordinary observations still require exactly one value; only an evidenced tombstone may have none. The V12 value validator and V16 immutable-observation trigger are replaced forward, preserving their ordinary behavior while allowing a one-way transition for an existing row only when the request belongs to that Student and `users.status=DISABLED`. The V12 `current_valid_measurements` view is replaced forward to exclude tombstones even if a historical `validation_status='VALID'` remains. A tombstone rejects values, unit, notes, external/raw-record references and quality/confidence fields. Subsequent mutation of a tombstone is rejected. There is no legacy backfill or new retention timer.

The Progress-owned port runs only inside the existing Account request lease/hold transaction and clears these columns for `measurements.student_id=userId`. It retains `measurements.id`, `student_id`, metric, measurement time, source and FK history. This is a **re-linkable processing tombstone**, not anonymous data or a Progress attestation. `measurement_batches`, `raw_ingestion_records.payload`, validation reasons, progress evidence, photos and object versions remain unresolved. No read API for raw measurements exists in the inspected current backend; this is not proof future reads or exports are concealed.

## A01 review and rollout questions

- Confirm dropping/re-adding the CHECK and replacing both trigger functions in one transactional Flyway migration, including trigger order, SQL permissions, lock waits and a production rollout window/`lock_timeout`. Do not infer a timeout value from this packet.
- Check V54→V55 upgrade against representative legacy observations and downstream FK/read queries. The fresh-install and upgrade Testcontainers tests cover the valid ordinary row, evidenced tombstone and attempted resurrection, not production write load.
- Decide the final tombstone/retention and indirect-identity treatment for the still-present Student FK; no 24-hour SLA or overall erasure completion follows from V55.

Both `account.erasure.request-enabled` and `account.erasure.worker-enabled` remain default OFF. `DOMAIN_REVIEW_PENDING` remains the only honest post-current-scope state until all owners, active objects, backup and provider evidence are attested.
