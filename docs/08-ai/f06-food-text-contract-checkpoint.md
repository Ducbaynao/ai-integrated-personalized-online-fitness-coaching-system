# F06 food text estimate contract checkpoint

**Status:** Draft implementation for issue #164. OpenAPI, the AI output schema,
forward migration V61 and an internal simulated AI route exist on this branch.
Spring has generation/recovery plus working-tree Correct, Preview and atomic
selected-item Confirm backed by V61. Verification of these deterministic
commands does not establish live provider behavior, model/catalog quality,
Mobile behavior, completed erasure or release readiness.

## Existing source and gap

- `V7__nutrition.sql` has `food_analysis_requests` and
  `food_analysis_candidates`, but candidates point at the legacy `foods` table.
  The new Spring analysis endpoint uses the request row for command identity
  and new V61 item rows; it does not use legacy candidate rows.
- `V39__student_basic_nutrition_logging.sql` has actor-scoped command receipts;
  `NutritionBasicService` calculates catalog nutrients and writes one confirmed
  item per command. Repeating that public command cannot provide atomic
  selected-group confirmation.
- The current daily total includes non-deleted `food_log_items` regardless of
  `confirmed_by_user`. F06 estimates must remain outside that table until a
  successful Student confirmation transaction.
- `V41` catalog revisions and portions provide calculation evidence. A model
  result never supplies authoritative catalog IDs, conversion or nutrients.

## Contract decisions for review

1. Spring owns the public `/nutrition/food-analyses` contract. Only the
   authenticated owning Student can generate, read, correct, preview and confirm;
   Trainer nutrition-log sharing does not grant draft or image access.
2. Generate persists a request identity before provider I/O. The same actor,
   `commandKey` and normalized body resolve to the same draft; the owner-scoped
   `by-command-key` GET recovers a lost response without a draft ID. Different
   payload with the same key conflicts. Provider calls stay outside confirmation
   transactions.
3. F06 V1 creates a new meal at the first successful confirmation. Later
   selected groups from the same draft reuse that meal after ownership/state
   checks. The request's day, timezone, consumedAt and mealType are pinned in
   the draft and repeated at preview/confirm to detect changed client intent.
   Attaching an existing meal is not in this V1 contract.
4. A persisted preview is immutable and selection-specific. It records draft,
   item and catalog/portion revisions plus server-calculated nullable nutrient
   snapshots. Correction invalidates its use. Confirm contains the exact
   selected items and preview identity; Spring compares a canonical sorted
   fingerprint with any committed receipt.
5. An exact committed retry checks current read authority, then returns its
   stored receipt before expiry or current catalog guards. A new intent locks
   draft/items/catalog in stable order, validates every selected item, and writes
   all Food Logs, item mappings, provenance, receipt and mandatory audit in one
   transaction. Any failure rolls back the whole group. Unselected items remain
   draft and the day is not marked COMPLETE.
6. Unknown food, quantity, unit or conversion stays null. The AI output has
   names and optional quantity only; Spring matches the published catalog and
   computes nutrients. A catalog change affecting preview returns 409 and
   requires a new preview and a new Student confirmation intent. Confirmed
   historical snapshots are never recalculated on replay.

The matrix suggests 24 hours for draft expiry but does not prove a production
rollout setting. Expiry remains a configuration/rollout gate rather than a
hard-coded contract value.

## Implementation sequence and evidence boundary

1. Review the OpenAPI and AI schema shape, including error codes and the
   generation recovery path, from the Mobile consumer perspective.
2. V61 adds forward draft/item/preview storage and stable receipt identity.
   It passed a clean PostgreSQL 18/Flyway disposable migration run. V7, V39 and
   V41 were not edited; legacy rows remain nullable in the new columns.
3. Spring currently implements Student-scoped text generation, same-key
   replay, owner-scoped recovery/detail, durable failure status, and audit.
   A focused PostgreSQL/Testcontainers HTTP test checks identity, isolation and
   that no Food Log is written. Correct and preview/calculation are now
   implemented as described in the follow-ups below. Atomic selected-item
   Confirm and its receipt/provenance writes are now implemented in the working
   tree; erasure remains later work;
   candidate matching is not implemented. Reading an owner-scoped PROCESSING
   draft now marks it FAILED after the
   configured `ai.f06.processing-timeout-seconds` (default 300 seconds).
   Recovery never automatically regenerates with the same key. A provider
   response arriving after this terminal transition cannot persist items.
   This is lazy recovery on read, not a scheduled sweep; the default is a
   development setting and deployment must align it with provider deadlines.
4. The internal FastAPI route currently uses a simulated provider that returns
   one unresolved item from the supplied text, with unknown identity and amount.
   It never supplies nutrient values. The route's three focused tests pass;
   real recognition remains unimplemented. Spring validates the output shape;
   JSON Schema, Pydantic and Spring enforce at most 20 detected items, candidate
   names of 1–200 characters and unique candidate/missing-data arrays.
5. Implement the Mobile typed consumer and Correct/Confirm UI on its own branch
   after the executable DTO stabilizes. Client calls Spring only.

Passing contract lint or fixture validation proves neither backend behavior
nor catalog quality, HTTP/DB live operation, device QA or Phase 2 release gates.

## Independent review follow-up

Antigravity reviewed `95c9c6c`. Its validator discrepancy and orphaned
PROCESSING findings are addressed by the changes above. The review report
contains several statements that should not be used as implementation evidence:

- `receipt_id` is on `nutrition_command_receipts`, not an analysis draft table.
- The original focused Spring test asserts no Food Log items for the owner; it
  does not separately assert an empty meal table or prove all concurrency cases.
- Current Correct DTO permits selection/quantity correction on an existing item;
  it does not define adding/deleting items or editing the detected name.
- Internal service key configuration is `ai.service.internal-service-key`, from
  `AI_INTERNAL_SERVICE_KEY`; the review's alternative property name is incorrect.

Contract structure remains draft until Correct/Preview/Confirm are executable
and their authority, atomicity and retry behavior has integration evidence.

Historical follow-up verification on 2026-11-23 (before Correct/Preview):

- `npm run validate:contracts`: OpenAPI valid; 11 AI schemas / 52 fixtures.
- `npm run test:contracts`: 22/22, including F06 boundary validation.
- `python -m pytest -q tests/test_f06_food_text.py`: 5/5.
- Backend clean build with
  `./mvnw.cmd -q clean "-Dtest=FoodTextEstimateValidationTest,NutritionBasicIntegrationTest#textEstimate*+lateText*" test`:
  2 validator tests and 3 PostgreSQL/Testcontainers integration tests passed.
  An earlier incremental run executed stale main bytecode and failed the two
  recovery cases; the clean build compiled the changed source and passed them.
- These checks do not establish live Spring-to-FastAPI networking, device QA,
  catalog/model quality or full-repository test results.

## Correct + Preview working-tree implementation (2026-11-23)

Based on branch `feature/p2-m5-f06-full-slice-20261123`, HEAD
`88c60f4093a5b6eef738eead18a1d3a37c5ce02d`. This follow-up is uncommitted;
the historical results above are not results of this implementation run.

- `FoodTextAnalysisController` exposes the existing OpenAPI Correct and Preview
  operations and DTO fields. Nullable correction fields must be explicitly
  present; unsupported fields (including detected-name edits) are rejected.
- `FoodAnalysisReviewService` permits only an active Student with the owned,
  command-keyed draft. New operations validate lifecycle/expiry, draft/item
  revisions, existing unconfirmed item membership and supplied catalog selections.
  Correct preserves unresolved nulls, increments item and draft revisions, and
  invalidates previous previews without deleting their immutable snapshots.
- Preview validates pinned day/timezone/consumedAt/mealType, distinct selected
  IDs and exact persisted selections, published/current catalog revisions and
  revision-owned portions. `NutritionCatalogCalculation` extracts the pure
  gram/conversion/nutrient calculation also used by existing catalog logging.
  Serving and millilitre require recorded factor evidence; unknown nutrient
  values remain null, including any total containing an unknown contribution.
  No model call or public add-item command is used to calculate a preview.
- V61 and the existing actor-scoped Nutrition receipts provide sufficient
  storage; no migration or shared contract change was needed. Preview inserts
  canonical `selection_json` and an immutable full preview snapshot in the
  object-valued `nutrients_json`, including nullable nutrients and pinned source
  record/revision provenance. No existing preview row is updated. Detail returns
  the newest preview for the current draft revision and hides it if a selected
  catalog revision is no longer current/published.
- Both operations check current authority/ownership before receipt lookup.
  Exact committed replay returns the saved response before lifecycle, expiry,
  revision or current-catalog checks; changed intent with the same key is 409.
  Canonical identity includes draft/item IDs, revisions and repeated context;
  selection ordering and equivalent numeric representations do not change it.
  Preview returns 201 for creation and 200 for a committed replay. A lost
  response can be retried with the same key/body, never a generated new intent.
- Mutations run in a single Spring transaction with mandatory audit and receipt.
  The existing Student advisory lock (707) serializes draft operations; owned
  draft rows are locked before the actor receipt lock (709), then items in
  canonical ID order and catalog rows in food-ID order. Catalog rows use the
  existing `FOR SHARE` read, which conflicts with catalog writers' `FOR UPDATE`.
  Catalog writers take their actor receipt lock and food row, and never request
  the Student/draft locks in the inspected paths. This is not a proof that all
  interleavings are deadlock-free. Detail also
  takes the Student lock so revisions, items and latest preview agree.
- Validation is 400, authentication 401, denied Student capability 403 and
  owner-scoped absence 404. New stale/state/context/expiry/idempotency conflicts
  are 409; unpublished catalog reads preserve the existing catalog 404 behavior.
  Neither operation creates meals/intake or changes day completeness.

### Historical Correct/Preview verification (before Confirm)

From `services/backend`:

```powershell
.\mvnw.cmd -q -DskipTests compile
.\mvnw.cmd -q clean "-Dtest=FoodAnalysisReviewIntegrationTest,FoodTextEstimateValidationTest,NutritionBasicIntegrationTest" test
```

- Compile returned exit 0. The final clean test run explicitly captured Maven's
  `$LASTEXITCODE=0`: 27 tests, zero failures/errors/skips. Surefire reports:
  `FoodAnalysisReviewIntegrationTest` 14/14;
  `FoodTextEstimateValidationTest` 2/2;
  `NutritionBasicIntegrationTest` 11/11, including all three existing
  Generate/recovery/late-provider regressions and catalog-logging regression.
- The 25 integration tests use Spring/MockMvc and disposable PostgreSQL 18
  Testcontainers with Flyway; this is real test-database persistence with
  synthetic fixtures, not live socket HTTP or deployed provider evidence.
- New coverage checks owner/cross-owner/denied/revoked authority; stale
  draft/item/catalog; wrong and confirmed items; unresolved null selections;
  missing/unsupported DTO fields; exact replay after expiry/correction/catalog
  change and same-key changed payload; pinned context; duplicate selections;
  item-order/numeric-equivalent identity; published food/portion/conversion
  guards; all three quantity bases; nullable totals; preview invalidation and
  no duplicate preview. DB assertions check no new intake/day rows and preserve
  the meals/items/completeness of a pre-existing COMPLETE day.
- The controlled concurrency test holds a calculated preview transaction open,
  observes both a Correct writer and catalog writer waiting on PostgreSQL locks,
  then releases it and verifies the old immutable snapshot plus stale/latest
  behavior. This is one tested interleaving, not exhaustive concurrency proof.
- Development iterations first exposed incorrectly named fixture DTOs at
  compilation, then two replay assertions exposed `5E+1` versus `50` after JSONB
  readback. The fixture and canonical-number normalization were fixed. A later
  27/27 run had ambiguous PowerShell redirection status from Mockito stderr;
  the final identical clean run above captured the native exit code explicitly.
  No failure remains in the final reports. `git diff --check` was also run.

### Remaining work and verification boundary

At the end of the Correct/Preview slice, selected-item Confirm was still missing.
The implementation and verification below supersede that gap; the historical
27-test evidence above does not itself prove confirmation behavior.

Mobile UI, real candidate matching/provider quality, F06 erasure/minimization of
draft/receipt snapshots and deployment expiry configuration remain separate.
Fixtures are synthetic and are not reviewed professional food data. No live
Spring-to-FastAPI chain, socket HTTP test, CI run, Pixel_7 QA, full backend suite
or release gate is established by this follow-up. OpenAPI/AI schemas were not
changed, so their historical validation suites were not rerun in this slice.

## Atomic selected-item Confirm working-tree follow-up (2026-11-23)

This continues the existing uncommitted Correct/Preview files on `88c60f4`;
no commit, branch, PR or shared contract/migration change was made.

- `POST /api/v1/nutrition/food-analyses/{draftId}/confirmations` implements the
  current `FoodAnalysisConfirmRequest` and `FoodAnalysisConfirmationReceipt`.
  Student capability and draft ownership are checked before looking up any
  receipt. A saved intent replays before expiry, lifecycle, current revision,
  preview or catalog guards, with the same receipt ID, Log IDs, snapshots and
  resulting revisions; only the response's `replayed` flag changes to true.
  Creation is 201, replay 200. New operations cannot confirm already mapped items.
- The existing `nutrition_command_receipts` remains the only mutation dedupe
  store. Its receipt helper now passes the persisted V61 `receipt_id` to the
  command; Basic Nutrition delegates its existing receipt wrapper to that helper
  without changing its payload hashing. All receipt users take the actor lock
  (709). Generate retains its existing pre-provider identity, but checks for
  receipt-key collisions under that same lock; receipt commands check for
  Generate-key collisions in the other direction. Same actor/key with a
  different action or meaningful payload is 409, including Generate, Correct,
  Preview and manual Nutrition. Failed transactions do not consume the key.
- Confirm canonicalizes selected IDs and equivalent quantities using the same
  helper as Preview. Its fingerprint includes draft ID, preview ID/revision,
  expected draft/item/catalog revisions, full context and selections. The stored
  Preview fingerprint is reconstructed with its original key and the requested
  context/selection, preventing an old preview from being rebound even if a
  draft's context changed without a revision increment.
- A new intent checks lifecycle/expiry, expected draft revision, preview's
  draft/revisions, exact selection group, persisted unconfirmed item selections,
  published/current catalog revisions and revision-owned portion/conversion
  evidence. Catalog retirement/missing published data at Confirm becomes a 409
  catalog conflict requiring another preview. No regeneration, replacement
  preview or changed Student intent is performed automatically.
- Spring recalculates under the catalog locks and compares nullable nutrient
  amounts and pinned source JSON against the immutable preview. A discrepancy
  is 409, not an automatic change to the viewed estimate. The equal, already
  viewed nutrient/source snapshots are then written. Calculation keeps existing
  six-decimal HALF_UP storage rules and numeric(18,6) bounds; unknown nutrients
  have no fabricated zero row and remain null in receipt/read totals.
- After validating the entire group, one transaction prepares/freezes the
  existing Nutrition day context, creates the first meal or locks/checks the
  draft's attached meal (owner, creator, non-deleted state and exact context),
  inserts `CATALOG_QUANTIFIED` items with `TEXT_AI`, Student confirmation and
  catalog nutrient rows, maps each draft item, increments each selected item
  revision once and the draft revision once per successful group, recalculates
  the day as PARTIAL, and writes mandatory audit plus receipt. Unselected items
  and their revisions are unchanged. All mapped items yield CONFIRMED; a
  remaining item yields PARTIALLY_CONFIRMED via detail. Later groups reuse the
  attached meal. Phase 1 day-confirm is not called.
- Provenance uses existing storage: catalog revision/portion, confirmed actor
  and timestamp, draft-item mapping, immutable preview/receipt, and JSON source
  references in the Food Log's existing `notes` field (draft/item/preview/receipt
  IDs and pinned catalog source, not raw input text). No new storage was needed.
  Any validation, numeric, persistence, summary, receipt or audit failure rolls
  back the whole group, including its meal, nutrients, mappings and revisions.
- Lock order is Student (707), owned draft row, actor receipt (709), selected
  items in ID order, catalog foods in ID order (`FOR SHARE`), day context/target
  freeze and attached meal. Correct/Preview and all existing manual Nutrition
  writes share 707, serializing the same Student before their differing inner
  row orders can conflict. Catalog writers take 709 then a food `FOR UPDATE`,
  without acquiring Student/draft/day locks. Generate now also takes 707 then
  709. Different-key overlapping/disjoint groups with an old draft revision
  conflict; a disjoint group needs a fresh preview/intent at the new revision.

Erasure/minimization of F06 draft, preview, receipt and provenance snapshots is
still an open gate. This is not completion of backend F06, Mobile or release.

### Confirm verification actually run in this follow-up

Environment: Microsoft OpenJDK 21.0.8, Spring Boot 4.1.1, Maven wrapper,
Docker PostgreSQL 18.6 (`pgvector/pgvector:pg18`), test profile and Flyway through
V61. No migration or contract/schema file changed.

Compile from the worktree root returned exit 0:

```powershell
.\services\backend\mvnw.cmd -q -f services/backend/pom.xml -DskipTests compile
```

Final clean verification from `services/backend`:

```powershell
& .\mvnw.cmd -q clean "-Dtest=FoodAnalysisConfirmationIntegrationTest,FoodAnalysisReviewIntegrationTest,FoodTextEstimateValidationTest,NutritionBasicIntegrationTest,NutritionStrategyIntegrationTest" test *> target-f06-confirm-final.log
$f06ConfirmExit = $LASTEXITCODE
Write-Output "MAVEN_EXIT_CODE=$f06ConfirmExit"
exit $f06ConfirmExit
```

Native Maven exit **0**; **56 tests**, zero failures/errors/skips:

| Suite | Result |
|---|---|
| FoodAnalysisConfirmationIntegrationTest | 19/19 |
| FoodAnalysisReviewIntegrationTest | 14/14 |
| FoodTextEstimateValidationTest | 2/2 |
| NutritionBasicIntegrationTest | 11/11 |
| NutritionStrategyIntegrationTest | 10/10 |

The three handed-over suites were rerun, including Generate/recovery/late-provider
and manual catalog-logging regressions. Strategy was added because both its
receipt helper and target-day freezing are dependencies of this change. The
54 integration tests use MockMvc/Spring plus real disposable PostgreSQL
Testcontainers with synthetic fixtures; two tests are validator unit tests.

Confirm tests cover selected groups, unselected draft preservation, later meal
reuse, final confirmation/revisions, TEXT_AI/confirmed flags, nullable nutrient
rows, source references, draft-to-Log mappings, receipt ID and audit persistence,
and PARTIAL day state. Denial tests cover auth/ownership/revoked capability,
stale/expired draft, stale item/preview/catalog, retirement, context/group
mismatch, duplicate/foreign selections, correction invalidation, reconfirmation,
and unusable attached meals. Replay tests cover equivalent ordering/numbers,
expiry, completed lifecycle, catalog revision/retirement, unchanged snapshots,
same-key changed payload/action and Generate/manual receipt namespace conflicts.

Database triggers inject a second-item persistence failure and a late mandatory
audit failure; assertions verify whole-group rollback and successful subsequent
retry with the original key. Numeric tests verify tiny-volume rounding/nulls and
controlled 400/rollback for a daily numeric(18,6) overflow. A separate test
changes draft context without a revision to verify the stored Preview fingerprint
still rejects rebinding. Controlled concurrency observes PostgreSQL lock waits
before release for duplicate intent, overlapping different keys, disjoint groups,
and Confirm versus Correct/catalog writer. These are specific tested interleavings,
not a proof of every possible deadlock or race.

The first clean iteration ran 55 tests with one fixture assertion failure: the
stale-item fixture intentionally increased item revision, while the rollback
helper incorrectly expected the pre-fixture revision. That assertion was fixed;
the context-fingerprint regression was then added. The final 56-test run above
passed. `git diff --check` was also run. The earlier 27/27 Antigravity/Correct-
Preview evidence remains historical and is not counted as a new run here.

Not run: full backend suite, live socket HTTP, deployed Spring-to-FastAPI chain,
CI, Mobile/Pixel_7, erasure tests or professional catalog/model evaluation.
Contract validation was not rerun because OpenAPI/AI schemas did not change;
Flyway was exercised on clean disposable databases by the focused suites.
Before Mobile/release, review this uncommitted implementation, complete the F06
erasure/provenance minimization gate, stabilize the consumer against these DTOs,
and verify live transport, deployment settings, reviewed catalog/provider quality
and applicable release gates separately. No release or P2 milestone is closed.

## Independent integrated review checkpoint before Erasure

Antigravity reviewed the uncommitted Correct/Preview/Confirm working tree based
on `88c60f4` and reported no findings in its inspected scope. Reported evidence:
focused backend clean suite 56/56, contract tests 22/22 and FastAPI F06 5/5.
These are historical independent-review results supplied in the handoff, not
tests newly run while preparing this checkpoint commit. They do not establish
the full backend suite, every concurrency interleaving, live Spring-to-FastAPI
HTTP, CI, Pixel_7 or release readiness. No code changed after that review during
checkpoint preparation, so the focused suite was not rerun solely for commit.

The source currently uses `FOR SHARE` in `NutritionCatalogService.publishedForLogging`.
`FOR NO KEY UPDATE` would be a different, exclusive row-lock mode, not a shared
read lock. Stable UUID order alone is not a deadlock proof; the inspected outer
Student/actor locks and competing writer paths also matter. Erasure, candidate
matching, professional catalog/model review, Mobile and release gates remain
open at this checkpoint. Erasure changes will be reviewed separately and are
not part of this reviewed commit.
