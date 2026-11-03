# B08 Account Erasure — V55 A01 schema review packet (MERGED; APPROVED_BY_PERSON_A)

V55 was merged via PR #133 at `8c8aa0cf35c3ef8e2ead8cfec094b32dc386a619`. **A01 V55 schema design: APPROVED_BY_PERSON_A**, source: **user-reported direct approval** from the project owner. This records the owner's confirmation, not a GitHub review or an inference from merge/CI. No message text, approval time, rollout window or `lock_timeout` value was supplied.

## DDL and data behavior

`V55__measurement_erasure_tombstones.sql` adds nullable `measurements.erased_at` and `erasure_request_id` (FK to `account_erasure_requests`). It replaces `measurement_one_value_ck` so ordinary observations still require exactly one value; only an evidenced tombstone may have none. The V12 value validator and V16 immutable-observation trigger are replaced forward, preserving their ordinary behavior while allowing a one-way transition for an existing row only when the request belongs to that Student and `users.status=DISABLED`. The V12 `current_valid_measurements` view is replaced forward to exclude tombstones even if a historical `validation_status='VALID'` remains. A tombstone rejects values, unit, notes, external/raw-record references and quality/confidence fields. Subsequent mutation of a tombstone is rejected. There is no legacy backfill or new retention timer.

The Progress-owned port runs only inside the existing Account request lease/hold transaction and clears these columns for `measurements.student_id=userId`. It retains `measurements.id`, `student_id`, metric, measurement time, source and FK history. This is a **re-linkable processing tombstone**, not anonymous data or a Progress attestation. `measurement_batches`, `raw_ingestion_records.payload`, validation reasons, progress evidence, photos and object versions remain unresolved. No read API for raw measurements exists in the inspected current backend; this is not proof future reads or exports are concealed.

## A01 approval boundary and rollout work

- Person A's approval closes the V55 schema-design gate for the CHECK, trigger and view changes described above. The production rollout window, Flyway-session `lock_timeout`, SQL permissions and concurrent-writer lock behavior still need an operational plan; do not infer a value or a production rehearsal from schema approval.
- Check V54→V55 upgrade against representative legacy observations and downstream FK/read queries. The fresh-install and upgrade Testcontainers tests cover the valid ordinary row, evidenced tombstone and attempted resurrection, not production write load.
- Decide the final tombstone/retention and indirect-identity treatment for the still-present Student FK; A01 schema approval does not attest these owner-domain outcomes, a 24-hour SLA or overall erasure completion.

Both `account.erasure.request-enabled` and `account.erasure.worker-enabled` remain default OFF. `DOMAIN_REVIEW_PENDING` remains the only honest post-current-scope state until all owners, active objects, backup and provider evidence are attested.
