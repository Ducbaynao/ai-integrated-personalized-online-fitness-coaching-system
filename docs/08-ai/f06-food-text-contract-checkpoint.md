# F06 food text estimate contract checkpoint

## Reviewed P7-019/P7-021 checkpoint - 2026-11-25 ICT

Antigravity independently reviewed both fixes and reported **FIXED**, with no new
finding in that review scope. Its fresh run of the four focused Mobile suites
passed **116/116**; typecheck, lint and diff check exit 0. This evidence is the
independent report supplied by the project owner, not a test rerun in this
checkpoint turn. Source/test hashes still match the previous p7021 verification
manifest; no implementation/test edits followed that reviewed version.

Pixel_7 UI/DB QA below was performed during implementation; Anti cross-checked
that evidence, not a second independent device execution. Before-fix FAIL logs
and original failure captures are preserved alongside after-fix evidence.
P7-019/P7-021 are reviewed and device-retested for their stated scenarios.
P7-020 remains deferred. Partial multi-item, controlled device timeout/replay,
pending account switching, process death and screen reader were still NOT RUN
at this checkpoint; a separate follow-up below must report any new results.

Evidence publication is selective: see the artifact INDEX.md. Login capture and
redundant intermediate/IME captures remain local, unmodified. Only synthetic
fixture data and sanitized evidence are included; local ignored backlog and five
untracked Mobile scripts are excluded. Precision/memory-only recovery, simulated
provider/catalog review, live fault/session/lifecycle and dependency/integration
limits remain open. Backend PR #165 is unchanged; Mobile #169 remains Draft on
its existing backend-branch base. No F06/P2-M5/release completion claim.

## P7-021 working-tree resolution - 2026-11-25 ICT

Base remains `f299540`; P7-019 closure is preserved. P7-021 previously allowed
old persisted preview selection to be confirmed while a newer unsaved form was
visible; neither a dirty-device Confirm nor wrong intake was demonstrated.
New editor comparison covers quantity, food/catalog revision, item revision,
basis and portion. Edits invalidate preview/reviewVersion; new Preview/Confirm
is blocked until Save/reconciliation and a matching fresh Preview. Cancel/revert
never resurrects the old snapshot. Exact saved Confirm replay remains possible
when dirty, with original key/body; other saved mutation intents are retained.

Pre-fix P7-021 regression: 9 failed/3 passed/40 skipped (52 total), exit 1.
Requested four Mobile suites after fix: **116/116**, exit 0; typecheck/lint exit 0.
This is self-verification, not new Anti review (historical Anti 103/103 preserved).
P7-019 + P7-021 should be reviewed together before a new checkpoint/PR change.

Actual Pixel_7/API36/Expo Go57.0.9, synthetic B, working-tree Reload, backend
17c485f with simulated source provider and disposable f06qa: separate draft for
Nov24; Correct 1.25 -> Preview -> unsaved 2 removes Confirm/preview and blocks new
Preview with guidance; Cancel needs fresh Preview; basis dirty/revert also tested.
Save 2 -> Preview 320 kcal -> UI Confirm/receipt -> Nutrition retrieval succeeded.
DB corroborates one new Log (2 portions/80g), one receipt; Nov24 count 0 -> 1,
Nov25 count 1 unchanged. UI retains protein 0, unknown carb/fat, day PARTIAL.
Return still required reselecting date (existing UX note, not auto-refetch-context
PASS). Evidence `pixel7/118`-`136`, p7021 DB snapshots, logs and verification manifest.

P7-021 is FIXED_IN_WORKING_TREE / REGRESSION_PASS / DEVICE_GUARD_RETEST_PASS;
original FAIL evidence is preserved below/in local backlog. P7-020 stays deferred.
Food/revision/portion variants, late responses/replay are mock-only this turn;
partial multi-item, app fault timeout/account switch, process death, screen reader
remain NOT RUN. No F06 completion, HTTP fault/device-wide/model/release PASS.
Runtime retained; no commit/push/PR/backend/shared-contract/config changes.
Full commands, scenarios and runtime identities are in the existing Mobile handoff.

## P7-019 fix and device continuation — 2026-11-25 ICT

Mobile base is still `f299540`, **working-tree fix not committed/reviewed**.
Fresh Correct now wraps its action callback so native press event cannot become
retry intent. Shared WorkoutUi/backend/contracts/Flyway/config unchanged.
Native-shaped rendered-button regression failed before fix (undefined body,
1 failed/39 skipped), then requested focused four suites passed **104/104**;
Mobile typecheck/lint exit 0 and diff check passed. Saved Correct key/body replay
after form edits remains asserted. Historical Anti 103/103 is not a review of
this new fix; full backend/Mobile/HTTP harness were not repeated.

After actual Expo Go Reload on Pixel_7/API36/Go57.0.9, same synthetic B session:
comma quantity rejected without mutation; 1.25 SERVING Correct persisted revision
1/2; Preview showed 200 kcal/protein 0/unknown carb-fat. Saved change to 1.5
removed old preview; new Preview 240 kcal → actual UI Confirm → receipt →
Nutrition read showed one item, 240/protein 0/unknown carb-fat, PARTIAL, not
COMPLETE. DB corroborates one Food Log and one Confirm receipt. Return navigation
required reselecting the date; do not claim automatic context preservation.

P7-019 device retest passes in working tree. **New P7-021 remains open:** unsaved
quantity edit leaves old Preview/Confirm enabled; saved correction invalidates
it correctly. Dirty-editor Confirm was not executed, so wrong intake is not
demonstrated. Different cause from callback/event; recorded for next code pass,
not fixed here. P7-020 safe area remains deferred/untouched. Existing ignored
local backlog holds full steps/evidence and all original failures.

Partial multi-item selection, app fault-injected timeout/retry/session switch,
process death and screen reader remain NOT RUN. Background resume retained state;
am kill left same PID alive and does not count as process-death QA. No persistence
added. Simulated provider/catalog are not recognition/professional evidence.
See [Mobile handoff](../07-development/phase-2-dev-b-f06-mobile-handoff.md) and
existing artifact `p7019-verification.json`/pixel7 captures. Runtime retained;
no Git/PR/Erasure rollout or F06/whole-flow Pixel_7/release completion claim.

## Initial device QA observation — 2026-11-24/25 ICT (before fix)

Actual Pixel_7/Android 16 API 36/Expo Go 57.0.9 QA now reached the app using
the user's existing synthetic Student B session on Mobile `f299540`. Spring
18080 → source FastAPI simulated fallback → retained disposable `f06qa` was
used. UI entry/context, Generate/rendered draft, missing-data selection guard,
local catalog/basis/portion choices, DST offset/meal choice, Back and ordinary
background retention were exercised. Nutrition showed no items and unknown
nutrients pre-Confirm; read-only DB verified no confirmed intake/previews.

**New device flow blocker P7-019:** native `Lưu chỉnh sửa` press event is treated
as optional retry intent by `FoodAnalysisScreen.tsx:341/1082`, forwarded from
`WorkoutUi.tsx:10`. Valid 1.25 SERVING cannot save. DB item stays uncorrected;
no wrong intake observed. Preview/Confirm/receipt/selected partial group and
post-Confirm refetch are therefore **NOT RUN from device**, regardless of
historical HTTP 8/8 and mock 103/103. Fix and actual-event regression belong to
a subsequent coding turn; this turn changes only documentation/evidence.

P7-020 status-bar heading overlap is deferred UI, related to prior safe-area
issue class; old date/timezone/font/copy notes remain in the existing ignored
`PIXEL7_MANUAL_QA_ISSUES.local.md`, which was found/read with the Phase 1 local
handoff. No literal old “end of Phase 2” schedule was found. Expo CLI warning
recurs as environment limit. Back/unmount loses draft/intent as documented;
process death, controlled timeout/retry and pending-request actor isolation
remain NOT RUN. No new persistence or direct API writes bypassed the blocker.

See [latest Mobile handoff](../07-development/phase-2-dev-b-f06-mobile-handoff.md)
for scenario/evidence table, source references, runtime IDs and limits. Runtime
is retained; no cleanup, Git/PR/source/config/dependency mutation, suite rerun,
Erasure rollout, whole-flow Pixel_7 PASS or F06/release completion is claimed.

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

## F06 Erasure / Data Minimization — reviewed checkpoint

The reviewed checkpoint above was committed and pushed as
`d153424b5ddcf8e827424a68aab37fb5e627369c`. PR #165's head was verified at that
SHA and remains Draft. Its description now covers the implemented runtime and
historical independent review. This section records the subsequent Erasure work, independently reviewed by Antigravity before the separate checkpoint below. The five pre-existing Mobile scripts are excluded.

### Applied policy and source inventory

Policy is the approved 2026-11-02 Nutrition decision in
`docs/07-development/features/08-chat-notification.md`: remove requester food
names, notes and analysis; personal nutrition facts require an explicitly
approved retention purpose, otherwise remove/unlink them and retain only
minimum processing evidence. The companion
`b08-account-erasure-approved-domain-mapping.md` requires source classification,
preservation of counterpart content and FK/processing evidence. V50's existing
Account workflow supplies request identity, cutoff, lease, hold and retry;
its evidenced audit-redaction mechanism remains the Audit owner's path.
No GDPR assumption, new retention duration, public erase command, hold API,
flag enablement or COMPLETE attestation is introduced.

| F06 store / ownership evidence | Previous coverage and gap | Candidate action |
|---|---|---|
| V7/V61 `food_analysis_requests` Student-owned TEXT with command key | Original Nutrition port replaced `input_text` only; repeated context and arbitrary command key/fingerprint survived | Replace text with ERASED; remove clinical date/time/timezone/meal/expiry, replace key/hash with non-content tombstones; cancel nonterminal work, retain stable request/meal IDs, lifecycle/revisions and processing chronology with erasure-request evidence |
| V61 draft items; recognized/candidate names, estimated/selected amount and basis, confidence, missingData/limitations | No cleanup; model text is still personal analysis even when not authored verbatim by the Student | Clear all analysis/selection fields and free-text arrays; keep item IDs/order/revision and confirmed Food Log mapping |
| V7 legacy candidate rows attached to these F06 requests | New F06 does not write them, but legacy copies could exist | Redact names, bounds, quantity/unit, confidence and food match; retain processing identity/action/FK skeleton. Unrelated legacy/IMAGE requests remain outside this slice |
| V61 Preview JSON, selection, key/hash | Immutable in ordinary runtime, no DB append-only trigger; contains exact selections, personal nutrient amounts and source links | Clear both JSON snapshots and sanitize key/hash in the fenced workflow; keep preview ID/revisions/createdAt for reconciliation |
| V39/V61 Nutrition receipt bodies and keys | Correct returns Draft; Confirm returns intake; later Basic commands can copy F06 intake through `readMine` | Minimize F06 and known Student-owned Basic command response bodies, key/hash for this subject; keep receipt ID/action/createdAt. No normal replay may bypass current authority |
| F06-mapped Food Log item, nutrient and provenance/notes rows | Old port only removed meal text; item snapshots/actual amounts remained | Delete nutrient rows; remove name, amount/unit/grams, confidence, private/catalog refs and notes; use valid DESCRIPTION_ONLY/ERASED processing skeleton, retaining item/meal IDs and confirmation processing evidence. Governed catalog rows are not deleted |
| Draft-linked meal and affected actual summaries/day status | Non-null clinical meal context could not represent erasure; aggregates could retain copied actuals | Tombstone owned meals only when every item is mapped to this subject's F06 drafts. Drop affected derived nutrient summaries; drop day-status facts only when no unmapped item shares the day. Unattributed/shared meal/day context stays review-required, not falsely cleaned |
| V39 append-only `nutrition_item_corrections` for F06-mapped Logs | JSON can retain original amount/name/provenance even after current Log cleanup | V62 records a prior-row hash, then a restricted trigger permits only before/after JSON redaction to empty objects for the disabled subject's real request and mapped F06 item. Versions/IDs/chronology remain; ordinary UPDATE/DELETE still fail |
| Audit and AI/provider data | V50 Audit port already redacts actor/subject evidence using append-only redaction records. Current F06 adapter creates no persisted AI Run/context/vector copy; `runId` is null in this implementation | Reuse Audit port without bypassing its trigger. No speculative cross-module SQL against AI stores. Existing linked legacy AI/media, external provider copies, backup and non-F06 Nutrition target/history cleanup remain owner/operations gates |

Retained UUIDs/FKs, event times and evidence hashes are re-linkable processing
records, not anonymous data. Broader Nutrition target/frozen-target history,
unattributed/manual/counterpart content, generic AI stores and objects are not
attested by this F06 port. The existing minimum-receipt/audit retention policy
does not authorize retaining the removed clinical payload; purge scheduling and
backup/provider evidence remain separate, as before.

### Schema need and runtime composition

V61's command-keyed draft context CHECK and V7's non-null meal date/type could
not represent unknown/erased context without either invented values or broken
FK/mapping history. V39's correction trigger correctly rejected any ordinary
JSON edit. Forward V62 therefore adds request-linked, one-way draft/meal
tombstones, preserves context requirements for normal rows and adds the narrow
evidenced correction-redaction path modelled on V50. V7/V39/V41/V61 are unchanged.
This new shared migration and Account-owned port require owner/integration review
before merge; they are not covered by the earlier independent code review.

`AccountErasureService.minimizeCurrentScope` invokes the new Nutrition port in
the existing lease/hold-fenced transaction. Cleanup failure rolls back all of
that transaction and the existing worker reports RETRYABLE. Repeat/restart does
not recreate payloads, change already-erased request identity or duplicate
redaction evidence. Account flags stay default OFF; successful current-scope
processing remains DOMAIN_REVIEW_PENDING, with backup/provider unverified.

### Concurrency and read/replay fencing

- `AccountErasureFence` is an Account-owned query port implemented by
  `UserRoleService`; Nutrition does not inspect Account persistence directly.
  It checks ACTIVE with no erasure request and offers a transaction-bound
  `FOR SHARE` lock plus a disabled-subject/request cleanup guard.
- F06 Generate identity/finalization/detail and Nutrition Student mutations
  take 707 then Account `FOR SHARE`; receipt commands subsequently take 709.
  This shared Account lock conflicts with cutoff's existing **exclusive**
  `FOR NO KEY UPDATE`. A started domain transaction completes before cutoff;
  a command waiting behind cutoff rechecks and denies before writing/replaying.
- Account cutoff does not acquire Nutrition's 707/709. The inspected cutoff
  Auth/Chat/Notification ports do not request them. The worker fences its request
  lease/hold, invokes Audit, then Nutrition takes 707, the Account cleanup guard
  and 709. Domain authority probes read erasure requests without a row lock,
  so they do not wait for the worker's request row while holding Nutrition locks.
  This order was inspected and specific interleavings tested; it is not a claim
  that every possible interleaving is deadlock-free.
- Provider I/O stays outside DB transactions. Generate checks authority again
  before starting I/O and on finalization. A normal, invalid or failed provider
  result arriving after cleanup cannot change the terminal request or publish
  items. An already-dispatched provider call cannot be retracted; provider-copy
  verification remains unverified. No network erasure guarantee is claimed.
- Read, recovery, generation and receipt replay reject a subject with an erasure
  request even if its account is accidentally set ACTIVE later. Minimized
  snapshots are not deserialized or restored to rebuild a receipt.

### Verification actually run for this Erasure candidate

Environment: Microsoft OpenJDK 21.0.8, Spring Boot 4.1.1, Maven wrapper,
PostgreSQL 18.6 Testcontainers (`pgvector/pgvector:pg18`) and Flyway. The initial
`./mvnw.cmd -q -DskipTests compile` returned native exit 0. The first clean
iteration ran 80 tests with zero failures/errors/skips. After adding the one-way
tombstone guard, the F06-only day-status check and the two migration tests, the
final command from `services/backend` was:

```powershell
& .\mvnw.cmd -q clean "-Dtest=FoodAnalysisErasureIntegrationTest,V62FoodErasureMigrationTest,AccountErasureHttpIntegrationTest,FoodAnalysisConfirmationIntegrationTest,FoodAnalysisReviewIntegrationTest,FoodTextEstimateValidationTest,NutritionBasicIntegrationTest,NutritionStrategyIntegrationTest" test *> target-f06-erasure-final.log
$f06ErasureExit = $LASTEXITCODE
Write-Output "MAVEN_EXIT_CODE=$f06ErasureExit"
exit $f06ErasureExit
```

Native exit **0**, **82/82**, zero failures/errors/skips:

| Suite | Tests |
|---|---:|
| FoodAnalysisErasureIntegrationTest | 10 |
| V62FoodErasureMigrationTest | 2 |
| AccountErasureHttpIntegrationTest | 14 |
| FoodAnalysisConfirmationIntegrationTest | 19 |
| FoodAnalysisReviewIntegrationTest | 14 |
| FoodTextEstimateValidationTest | 2 |
| NutritionBasicIntegrationTest | 11 |
| NutritionStrategyIntegrationTest | 10 |

The new tests exercise unconfirmed/partial/full drafts, JSON/receipt copies,
mapped confirmed Logs and nullable amounts, minimum processing history,
source-isolated counterpart data, audited correction redaction with trigger
bypass denied, workflow restart/stale lease/hold/retry, post-erasure read/replay
denial and accidental account reactivation. Controlled synchronization covers
normal/invalid/failed late provider results, Correct/Preview/Confirm/replay holding
their transactions while cutoff waits, and three mutations queued behind cutoff.
An injected cleanup failure verifies transaction rollback and worker retry.
Migration checks cover clean install, V61 upgrade preserving ordinary legacy
context, normal context constraints, explicit tombstones, restoration rejection
and Flyway validation. No failing test remained in either complete iteration;
the cleanup failure and SQL rejections in those tests are intentional assertions.

These are focused JUnit/Spring/MockMvc and real disposable-PostgreSQL checks,
not full backend, live HTTP Spring-to-FastAPI, CI, provider-copy deletion or
Pixel_7 evidence. No production migration was run. OpenAPI/AI schemas did not
change, so contract and Python suites were not rerun here; their 22/22 and 5/5
review results remain historical. Shared V62/Account port review, broader owner
domains, catalog/model validation, Mobile, deployment/retention/backup/provider
gates and release verification remain open. Account Erasure flags remain OFF
by default, and this does not close backend F06 or a Phase 2/release milestone.

### Independent review and checkpoint preparation — 2026-11-24

Antigravity independently reran the focused clean backend suite with Docker
active: **82/82**, zero failures, errors or skips, and reported no findings
within the inspected scope. This is historical independent review evidence
provided before checkpoint preparation, not a test run in this preparation
turn. Contract **22/22** and Python **5/5** remain earlier historical evidence;
neither was rerun here. The reviewed backend code is unchanged while preparing
this checkpoint; only this review record was added. Exact paths and staged diff
are checked before commit; no backend suite is repeated solely for Git handoff.

The checkpoint includes the reviewed V62, Account fence and Nutrition cleanup,
without claiming absolute safety or completion of broader erasure. Flags remain
**OFF**, processing remains **DOMAIN_REVIEW_PENDING**, and owner/integration,
retention, backup/provider and release gates remain open. No full backend,
live Spring–FastAPI, CI, Pixel_7 or rollout authorization is attested.

## Dependent Mobile Draft PR and limited live verification — 2026-11-24

Dev-B Mobile checkpoint `f299540367b9b883414a4ac2bec06daee8e155c4` is now
[Draft PR #169](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/pull/169),
stacked on backend branch `feature/p2-m5-f06-full-slice-20261123` at
`17c485fa6d248fff58c7dac00c931536ff6c7720` (PR #165 still OPEN/Draft).
The verified delta contains only 11 Mobile/documentation files. Antigravity's
independent 103/103/typecheck/lint review of f299540 is historical evidence
provided by the user, not a rerun in this PR/runtime follow-up.

New live HTTP harness evidence: eight scenarios passed against Spring and
source FastAPI over real sockets plus a fresh task-owned PostgreSQL 18.6/V62 DB.
FastAPI uses its simulated F06 fallback; catalog, 40g portion and accounts are
synthetic fixtures. Confirm response loss was injected after observing upstream
201 and a separately visible committed receipt. Replay kept the same receipt/
Log IDs and one intake, including after expiry and catalog retirement. Other
coverage: Generate/recovery/detail, Correct/Preview replay, unknown nutrients/
partial selection, changed-payload 409, foreign-owner 404, and account disable
while a request was held before forwarding (401). This does not attest
mid-transaction revocation, a real nutrition model/catalog or Mobile app behavior.

Pixel_7 AVD/Android 16 API 36 booted, but app scenarios are NOT RUN because
Metro launch was rejected by automatic approval review (`blocked by policy`,
no detailed reason supplied). No harness/Jest result is called PASS Pixel_7.
See [the current Dev-B Mobile handoff](../07-development/phase-2-dev-b-f06-mobile-handoff.md)
for evidence, fixture limits, base/main divergence and the smallest device task.
This is personal/feature progress, not integration, F06/P2-M5 completion or
release/Erasure rollout approval. Flags remain OFF, DOMAIN_REVIEW_PENDING, and
provider/backup/catalog/model gates remain open.

### Pixel_7 continuation: environment ready, waiting for manual Metro

On 2026-11-24 the existing B06/`f299540` working tree and all previous evidence
were preserved. The task-owned retained DB, backend and source FastAPI were
restarted in the same simulated-provider HTTP configuration; readiness/health
returned 200. Pixel_7 booted Android 16/API 36 with Expo Go 57.0.9. New synthetic
password-login Students and a catalog/40g test portion were prepared solely for
device QA. This is setup evidence, not app or specialist validation.

Metro is not listening on 18081. No policy-rejected launch was retried or worked
around. The user has the exact package/runbook command to start it manually;
the agent must observe readiness before proceeding with app work. Runtime is
left running for that handoff. All actual app/device scenarios, including
pending-request session switch and process death, remain NOT RUN. Historical
103/103 mock and 8/8 HTTP results were not repeated or relabelled. See the
[Mobile handoff](../07-development/phase-2-dev-b-f06-mobile-handoff.md) and its
preparation JSON for the command, checks and next step. No milestone/release
gate or PR state changed; new documentation remains unstaged/uncommitted.

### Metro now verified; actual app launch still pending

The user started Metro manually. Host verification returned HTTP 200 and
`packager-status:running` on localhost:18081 (IPv6 loopback listener). A combined
command containing Expo Go's ADB VIEW launch was rejected by automatic approval
review before execution (`blocked by policy`, no detailed subcommand reason).
The agent did not retry launch through another mechanism and requested a manual
Expo Go open. Foreground remains the Android launcher at the last check, so all
requested F06 app scenarios remain NOT RUN. This is not a new product finding
or PASS Pixel_7. See the Mobile handoff/Metro JSON for evidence and manual step.
Runtime is retained for this paused QA; cleanup has not yet been performed.
No source, dependency, Git commit or PR state changed.

### Actual Pixel_7 open failed before project load

After the user manually opened Expo Go on emulator-5580, the observed native
error log showed `java.io.IOException: Failed to download remote update`.
Host Metro status returns HTTP 200 but listens only on IPv6 `::1:18081`.
An emulator HTTP probe reaches backend `10.0.2.2:18080` with HTTP 200, while
the Metro probe over existing ADB reverse receives no HTTP response. This is
an environment/transport blocker (IPv4/IPv6 mismatch hypothesis), not evidence
of a F06 source defect or app/backend integration success. F06 app scenarios
remain NOT RUN. Screenshots, UI dump, reproduction and manual IPv4-priority
Metro handoff are recorded in the existing Mobile handoff and pixel7 evidence
folder. Runtime is retained pending that manual step; no code/dependency/Git/PR
mutation or new Pixel_7 PASS/release claim occurred.

### IPv4 Metro recheck; app verification remains pending

After the user's IPv4-first Metro restart, host listener `127.0.0.1:18081`
(PID 39000) and emulator direct `10.0.2.2:18081/status` both return HTTP 200 /
`packager-status:running`. ADB reverse localhost still yields no HTTP response
after recreating its existing mapping; its root cause remains unresolved.
The agent has not reloaded the old project URL and requested manual Expo Go open
of the verified direct route `exp://10.0.2.2:18081`, respecting the prior app-open
approval rejection. F06 app scenarios remain NOT RUN, not device PASS. Existing
runtime/DB are retained; no cleanup, source/dependency/Git/PR change or suite
rerun occurred. See the Mobile handoff and `pixel7/ipv4-recheck.json` for checks.

Follow-up observation at 2026-11-24 16:06 UTC still shows the original native
Expo Go error log. Host Android manifest fetch returns HTTP 200 with runtime
`exposdk:57.0.0` and direct emulator-host bundle URL; app loading remains
unverified. User authorization does not override the prior `blocked by policy`
project-open rejection, and this session has no approval-escalation mechanism.
The agent did not retry/bypass that action. Manual direct-URL open is still
required; all F06 app scenarios remain NOT RUN. Evidence is recorded in the
Mobile handoff and `pixel7/manual-open-gate.json`; runtime is retained.

The user's subsequent manual direct-URL open reached the actual app sign-in
screen (see `pixel7/04-app-login.png`/XML). F06 login/mutations remain untested.
Pixel_7 reports `windowtype=headless`, explaining the absent desktop window;
no second emulator was started. The choice to restart this same AVD visibly or
retain its current memory state is pending; other task runtime is retained.
