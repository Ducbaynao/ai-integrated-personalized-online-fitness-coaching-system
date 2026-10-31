# V52 Flyway collision — hotfix decision packet

Status: **LOCAL_CANDIDATE / PERSON_A_ORDER_APPROVED_DIRECTLY / SHARED_ENVIRONMENT_HISTORY_UNKNOWN**. The project owner reports Person A approved unchanged AI V52 → Chat V53 through a direct conversation. This packet does not invent a quote, timestamp, GitHub approval, environment inventory, rollout setting or authorization to repair Flyway history.

## Verified repository and CI facts

- `origin/main@70a500134d195b630bc87eec08ac2a356fbfe035` merged PR #122 after PR #123. It contains both `V52__chat_linked_resource_parent.sql` and `V52__ai_recommendation_source_snapshot.sql`.
- Merge-SHA CI run `41170743581` failed Database Migrations Check and Backend: Flyway reports more than one migration with version 52. Contracts/Docs, Mobile, Admin and AI jobs passed. The passing checks do not establish a bootable backend.
- The Chat V52 schema was approved by Person A through a direct message as reported by the project owner; the text and timestamp are not available here. That approval does not approve changing the AI migration version or settle production rollout values.
- The project owner's read-only reconciliation of 2026-10-31 reports that dev `5433` / `digital_fitness`, schema `fitness`, has successfully applied `V52__ai_recommendation_source_snapshot.sql` at `2026-10-31 01:42:37 UTC` with checksum `-990909199`, after AI V51, and has no V53. **We did not connect to that database.** A disposable copy of an older dev volume had Flyway history but no V51/V52/V53; that copy was deleted.
- GitHub's deployment API returned no deployment records and its environment API listed only `copilot`. The repository CI workflow uses disposable PostgreSQL. Shared dev, staging and production deployment lists and schema histories are **UNKNOWN**. The dev 5433 observation must not be generalized to those environments.

## Read-only inventory required from each authorized environment owner

For every persistent dev, staging and production database, record environment name, deployed commit, and the version 51/52/53 rows (`version`, `description`, `script`, `checksum`, `success`, `installed_on`) from `fitness.flyway_schema_history` or the actual Flyway history table. Also record whether `fitness.ai_recommendations.source_snapshot` exists and whether `fitness.messages.shared_parent_id`, `shared_guarded_v1`, `trg_guard_chat_shared_resource` and `chat_shared_resource_shape_ck` exist. The owner must identify the actual history-table schema first. Treat absent access or incomplete inventory as **unknown**, not as "not applied". Do not run repair, mutate history, or test against a persistent database.

## Compatibility matrix to approve before changing migration files

| Observed state in an environment | Candidate path; not yet approved | Guard |
|---|---|---|
| At V51, neither V52 applied | Candidate: apply unchanged AI V52, then byte-identical Chat SQL at V53. | Prove clean install and V51 upgrade. |
| AI V52 applied, Chat not applied (confirmed for dev 5433) | Candidate: retain AI V52 file/checksum and apply Chat V53. | Do not touch dev DB in this checkpoint; prove the state on disposable PostgreSQL. |
| Chat V52 applied with its original checksum, AI not applied | The candidate would misidentify the existing V52 history as AI and fail validation. | **Unresolved** until Person A and environment owner approve a compatible migration path. No repair/rename by assumption. |
| Different persistent environments have Chat V52 and AI V52 respectively | One linear versioned migration set cannot validate both histories as-is. | **Unresolved**; inventory and approved environment-specific reconciliation required. |
| Manual/partial DDL or failed history row | Inventory actual columns, trigger, constraints and failed history before designing a forward reconciliation. | Do not guess from a column's presence or edit Flyway history. |

V53 was the first free forward version on `origin/main@70a500134d195b630bc87eec08ac2a356fbfe035`. The candidate changes only the Chat migration filename, test name/assertions and review documentation. AI V52 file and Chat SQL body are unchanged. If another branch occupies V53, reconcile before integration. The Chat V52 schema approval does not by itself approve the new order.

**Remaining inventory/rollout gate:** Person A has approved the order **unchanged AI V52 → byte-identical Chat V53** according to the project owner. The approval does not establish which persistent shared dev, staging or production databases exist, whether any has Chat V52 in Flyway history, or a `lock_timeout`/rollout procedure. Obtain that inventory from the authorized environment owners before merge. If any Chat-V52-applied environment exists, obtain a compatible plan for that state; this candidate has not proven one. The AI SQL and its checksum remain unchanged.

## Disposable test and review gate after a path is approved

Test a clean install, V51→AI V52→Chat V53 upgrade, and an already-AI-V52 state on disposable PostgreSQL. Verify Flyway checksums and the AI column plus Chat guard/legacy behavior. A Chat-V52-applied state is not claimed supported without separate evidence and an approved path. Run Database Migrations CI and focused/full Backend gates on the candidate source. Independent Antigravity review of the seven-file candidate passed as reported by the project owner; the subsequent changes only record Person A's direct approval and require documentation validation. A Draft hotfix PR may be opened for this candidate, but it must not merge while shared persistent histories or rollout parameters remain unknown.

Candidate-source evidence (2026-10-31): `V53ChatLinkedResourceMigrationTest` 3/3 PASS on Testcontainers; `npm run test:migrations` PASS, disposable Compose project removed; focused Chat 19/19 and AI persistence 5/5 PASS. `./mvnw -B test` in one JVM failed when native memory allocation failed after 31 reports; it is **not** a full-suite PASS. A separate sequential 14-group run exited 0 for every group and produced exactly 167/167 expected class reports, 1,546 tests, 0 failures, 0 errors and 5 conditional skips. These tests establish candidate behavior only for the V51 and AI-V52 paths, not for an unknown Chat-V52-applied persistent database.
