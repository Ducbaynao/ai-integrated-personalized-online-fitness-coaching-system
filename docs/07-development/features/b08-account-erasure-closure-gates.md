# B08 Account Erasure closure — source-checked residual gates

Base: `origin/main@ac956d77cded957e17f27b2bb6c25b087d531557` (PR #132 merge). This checkpoint is a **partial local candidate**, not an enabled erasure workflow. `account.erasure.request-enabled` and `account.erasure.worker-enabled` remain default OFF. No domain has issued a persisted COMPLETE attestation; requests remain `DOMAIN_REVIEW_PENDING`.

## What the owner ports do, and do not prove

| Domain | Narrow local effect with explicit provenance | Residual data still tied to the account or requiring owner review |
| --- | --- | --- |
| Workout/Schedule | PR #132 clears author-proven `workout_plan_versions.change_summary` only when V16 has not locked the version. | Locked summaries, mutable plan/session/exercise/occurrence/Appointment notes, proposal reasons, receipt/event JSON and Student/Trainer FKs remain. Time/status/outcome facts needed by the counterpart are preserved pending a specific retention/de-identification design. |
| Nutrition | `meals.title/notes` with explicit `created_by`; this candidate also clears unlocked `nutrition_goal_versions.change_summary` with explicit `created_by`. | `meals.student_id`, food item names/notes, calories/macros and nutrient rows, goal/target values, correction JSON, food-analysis text/images and private foods remain. A meal title cleanup is neither nutrient erasure nor an approved indefinite retention purpose. Locked version summaries remain review-required. |
| Measurement/Progress | PR #132 revokes owned integration connection identifiers/credential references. V55 candidate gives measurements with explicit `student_id` a one-way request-linked value tombstone, clearing numeric/text/boolean value, unit, note, external/raw reference and quality/confidence. | Tombstones retain `student_id` and historical skeleton, so are re-linkable. `measurement_batches`, validation-event reasons, raw ingestion JSON, photos/media, progress snapshots/metric values, Attention/Review evidence and orphaned ingestion batches remain. No physical object deletion or Progress COMPLETE attestation follows. |

Current Account export is account/settings plus sender-authored Chat, not these domain tables. Current Account cutoff blocks fresh access/refresh after DISABLED; shared Coaching authority conceals Trainer access to a DISABLED Student, Nutrition Basic checks ACTIVE, and Attention/Review checks account state. Those checks are not an audit of every future direct-ID/export/read path. The 11-known-FK Chat media guard does not establish absence of non-FK links or production S3 versions/replicas.

## One blocker/owner handoff table

| Owner | Evidence missing and concrete unblock step | Consequence until verified |
| --- | --- | --- |
| Person B Workout/Schedule + product owner | Field-author provenance for mutable/locked text and receipt JSON; approved counterpart-history retention/de-identification; direct-ID/list/export review. Design an evidenced correction path for V16-locked version summaries without bypassing immutable audit. | No Workout/Schedule COMPLETE attestation. |
| Person B Nutrition + product owner | Classify item names/notes, user-reported versus catalog facts, calories/macros, target/actual and correction JSON; define purpose/period for retained personal values. Design constraint-safe food-analysis media/object worklist and locked-version correction. | No Nutrition COMPLETE attestation; no presumption that training history permits indefinite retention. |
| Person B Measurement/Progress + Person A A01 | Review V55 CHECK and V12/V16 trigger transition; classify batch/raw/validation/Progress JSON and all photo objects, including nullable/shared owners. Add owner worklist with verified S3 deletion and read/export audits. | V55 is A01_REVIEW_PENDING; no Progress COMPLETE attestation. |
| Person A Account/Auth/Profile + AI owner | Supply fenced, idempotent conceal/minimize/attest ports for MFA, sessions, audit, profile/verification media, prompts/context/vector and provider copies, with FK/JSON/read/export tests. | Cross-owner data remain unverified; V50/V54 schema approval is not cleanup attestation. |
| Project owner + Person A + operations | Name two independent hold principals and step-up/permission/audit/lease protocol; approve a fenced recovery operation for OWNER/STORAGE_REVIEW_REQUIRED. Attest every production bucket version/replica and restore-ledger replay; establish provider-copy evidence and approved purge/retention behavior. | No hold mutation endpoint, erasure enablement, ACTIVE_DATA_CLEANED, 24-hour SLA claim or all-copies-erased statement. |

The V55 [A01 packet](b08-account-erasure-v55-a01-review.md) covers the forward schema candidate. Testcontainers/S3Mock can prove transaction and adapter behavior, not production bucket, backup, Expo/provider or device state.
