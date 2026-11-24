# Dev-B F06 Mobile checkpoint — 2026-11-24

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

## P7-021 resolved in working tree; combined P7-019/P7-021 review scope - 2026-11-25 ICT

Base HEAD remains `f299540367b9b883414a4ac2bec06daee8e155c4` on
`feature/p2-dev-b-f06-mobile-client-20261124`. This is **uncommitted working-tree
source**, self-verified below, not newly reviewed by Antigravity. P7-019's native
Correct closure and all original FAIL artifacts remain intact. Older sections
below record the state at their respective checkpoints, not current blockers.

### Semantics and narrow fix

Source and the failing regression show that fresh Confirm previously used the
**persisted preview selection**, not unsaved editor values. P7-021 was ambiguous
UI allowing the old snapshot while showing a newer form; no dirty-editor device
Confirm or wrong intake was demonstrated. The pre-fix mock observed quantity 1
sent after editing the form to 2, confirming that distinction.

`FoodAnalysisScreen.tsx` now compares the editor with the current saved item,
including item revision, food/catalog revision, quantity, basis and portion.
Every editor change invalidates the displayed preview and existing reviewVersion /
request identity rejects a superseded Preview response. A dirty editor shows an
explicit Save-then-Preview instruction and blocks **new** Preview/Confirm. It
does not save, preview, recalculate nutrients or replace a command key/body.

Cancel/revert is deliberately conservative: once edited, an old preview never
resurrects; a fresh Preview of the saved state is required. Opening an unchanged
editor alone does not discard a valid preview. Saved unknown Correct/Preview/
Confirm intents survive edits/Cancel; **Confirm replay bypasses the new dirty
form guard** and reuses the exact saved key/body. Late Preview/retry results
cannot authorize a new Confirm after editing. No shared UI/backend/wire/config
or P7-020 safe-area changes.

### Actual automated verification

Commands run from `apps/mobile`:

```powershell
npm test -- --runInBand src/__tests__/foodAnalysisScreen.test.tsx --testNamePattern="P7-021"
npm test -- --runInBand src/__tests__/foodTextAnalysisApi.test.ts src/__tests__/foodAnalysisScreen.test.tsx src/__tests__/nutritionScreens.test.tsx src/__tests__/apiClient.test.ts
npm run typecheck
npm run lint
```

- Before production fix: **9 failed, 3 passed, 40 skipped, 52 total**, exit 1.
- After fix: **116/116, 4 suites, no skips**, exit 0: client 48 + screen 52 +
  Nutrition 9 + shared API client 7. Typecheck and lint exit 0.
- Twelve P7-021 cases cover quantity/food/revision/basis/portion, Save -> new
  Preview -> correct quantity/revisions, Cancel/revert, late Preview, saved
  Preview replay and timed-out Confirm replay with/without Cancel. Tests exercise
  rendered UI handlers with native-shaped events; P7-019 regression still passes.
- Final `git diff --check` is recorded in `p7021-verification.json`.
- Historical Anti 103/103 and earlier P7-019 104/104 are separate evidence;
  **these new changes still require independent review together**. No full
  Mobile/backend suite or HTTP harness was repeated.

### Actual Pixel_7 retest (not mock)

Pixel_7 `emulator-5580`, Android 16/API 36, Expo Go 57.0.9. Existing Expo Go Reload
menu fetched the working tree, retaining synthetic Student B. Spring 18080 uses
backend dependency `17c485fa6d248fff58c7dac00c931536ff6c7720`, source FastAPI
simulated fallback 18001, disposable container `f06-mobile-qa-20261124` / DB f06qa
(host 15486). No production data, direct API mutations, erasure or source/config
fault injection. Synthetic catalog is not professionally approved data/model
recognition evidence. Source hash and read-only DB snapshots accompany captures.

A separate draft `ba169412-ba59-4093-96ef-23db7874101b` for 2026-11-24 was created
through UI; prior confirmed 2026-11-25 fixture remains unchanged. Evidence below
is PNG/XML under `artifacts/f06-mobile-live-20261124/pixel7/`.

| Scenario | Device result | Evidence |
|---|---|---|
| Reload, same account, entry/context, Generate | PASS | `93`-`103`; new isolated synthetic description |
| Correct 1.25 SERVING/40g portion (P7-019 retained), Preview | PASS | `106`-`115`; saved item revision 1/draft 2, 200 kcal |
| Change quantity to 2 without Save | PASS guard | `118`-`119`: old preview/Confirm removed, Preview disabled, Save/Preview alert; `p7021-dirty-db.json` still 1.25, no Food Log |
| Cancel after dirty | PASS conservative semantics | `121`: no old preview/Confirm; fresh Preview allowed; `122` fresh saved-state preview |
| Change basis / revert to SERVING | PASS | `125` dirty guard; `126` revert does not resurrect snapshot; fresh Preview allowed |
| Save 2, new Preview, Confirm/receipt | PASS | `129`-`132`: draft 3/item 2 before Confirm, 320 kcal, receipt after actual UI Confirm |
| Nutrition retrieval, no duplicate or auto-COMPLETE, unknown vs zero | PASS for normal single-item Confirm | `136-p7021-nutrition-context`: 2 portions/320 kcal, protein 0, carb/fat unknown, PARTIAL; date had to be selected again on return |
| Food/revision/portion variants and pending Preview / Confirm replay during dirty edits | NOT RUN on device | Covered by new mocks only; no safe app fault fixture exercised |
| Partial multi-item, timeout/offline, pending account switch, process death, screen reader | NOT RUN | Existing limitations retained; background retention is not process-death evidence |

Read-only final DB: exactly one new Log `4d0d4ab8-1724-42ec-943d-b58f266e9cd4`,
quantity 2 / 80g, final item revision 3 / draft 4; one Confirm receipt
`84518bd5-3526-47ab-86aa-d987a7ed8557`. Baseline day counts 0/1 (Nov24/Nov25)
become 1/1: no second intake for this new item and prior P7-019 Log is untouched.
See `p7021-baseline-db.json`, `p7021-dirty-db.json`, `p7021-confirm-db.json`,
`p7021-final-counts-db.json`. Device mutations were only the intended flow.

P7-021: **FIXED_IN_WORKING_TREE / REGRESSION_PASS / DEVICE_GUARD_RETEST_PASS**.
No new blocker observed within this scope. P7-020 remains **DEFERRED_UI** and
unmodified; blank return-date/context remains the existing deferred UX note.
No F06/Phase 2/device-wide/release gate completion claim; no live timeout,
transaction revoke or model-quality claim from mocks/normal-flow QA.

Runtime retained for review: Metro PID 22764, Spring 17492, FastAPI 20912;
Pixel_7 launcher 40860/QEMU 3544, emulator-5580; disposable DB container above.
No process/container was stopped or purged. Recheck process identity before any
later cleanup; do not stop unrelated runtime. No Git stage/commit/push or PR edits.

## P7-019 working-tree fix and device continuation — 2026-11-25 ICT

Base HEAD remains `f299540367b9b883414a4ac2bec06daee8e155c4`, same branch. The
source described here is **uncommitted working tree**, not that immutable SHA
and not newly reviewed by Antigravity. All original FAIL evidence is retained.

### Narrow fix and regression

`FoodAnalysisScreen.tsx:1082` now uses `onPress={() => handleSaveCorrection()}`.
Only the fresh UI callback changed; the action still receives explicit saved
intent from the retry closure. Shared WorkoutUi, backend/OpenAPI/Flyway,
dependencies/config and P7-020 safe area are unchanged. Generate/Recover accept
no retry argument; Preview/Confirm and all retry buttons already use closures.
No second occurrence of this optional-intent/native-event defect was found.

Screen-test helper now passes a native-shaped press event through the rendered
button under the real WorkoutButton; no WorkoutUi mock discards the argument.
New regression asserts full Correct body (1.25, key, draft/item revisions,
catalog/revision/portion), no event body, then network failure → changed form →
exact saved-body identity replay and only one key creation. Existing tests now
also receive events; their assertions were preserved.

Commands actually run from `apps/mobile`:

```powershell
npm test -- --runInBand src/__tests__/foodAnalysisScreen.test.tsx --testNamePattern="native Correct press"
npm test -- --runInBand src/__tests__/foodTextAnalysisApi.test.ts src/__tests__/foodAnalysisScreen.test.tsx src/__tests__/nutritionScreens.test.tsx src/__tests__/apiClient.test.ts
npm run typecheck
npm run lint
```

- Before source fix: **1 failed / 39 skipped**, exit 1, expected full body but
  received `undefined`. A preliminary selector setup attempt failed to locate
  Pressable by component identity; selector corrected before this authoritative
  product reproduction, not counted as the product failure evidence.
- After fix: **104/104**, 4 suites, no skipped tests, exit 0 (48 client + 40 screen
  + 9 Nutrition + 7 shared client). Typecheck and lint exit 0.
- `git diff --check` passed after code/doc changes, with LF/CRLF warnings only.
  No full Mobile/backend suite or whole HTTP harness repeated.
- [Verification manifest](../08-ai/artifacts/f06-mobile-live-20261124/p7019-verification.json)
  and sibling before/after/typecheck/lint logs record commands and code hashes.
  Historical Anti 103/103 is unchanged; new checks are self-verification.

### Actual Pixel_7 retest

Same synthetic B, Pixel_7 emulator-5580/API36/Expo Go57.0.9; Spring 18080 →
source FastAPI simulated fallback → disposable f06qa. Runtime identities were
rechecked; no service restart or policy-rejected launch retried. Expo Go's
existing Reload menu action fetched source, retained login and returned Home.
Correct now producing local comma validation and successful dot persistence
provides device evidence of the new handler, beyond reload status alone.
Source SHA-256 is recorded in DB snapshots/verification manifest.

Evidence names below are PNG/XML under the existing `pixel7/` folder.

| Scenario | Actual result | Evidence / limit |
|---|---|---|
| Reload → same account → Nutrition/F06/context/Generate | PASS | `52`–`60`, new day 2026-11-25 / Asia/Ho_Chi_Minh / LUNCH; synthetic unmatched draft |
| Comma `1,25` | PASS: rejected, not silently converted | `65`–`70`, local quantity validation; `after-fix-comma-db.json` leaves quantity null/revision 0 |
| Correct valid `1.25 SERVING`/40g portion | PASS: P7-019 retest | `72`–`74`; `after-fix-correct-db.json`: quantity 1.25, itemRevision 1/draftRevision 2, no Food Log |
| Preview 1.25 | PASS | `77`–`78`, `after-fix-preview125-db.json`: 200 kcal, protein 0, carb/fat null |
| Unsaved editor change 1.25 → 1.5 invalidates preview | **FAIL: new P7-021** | `81-quantity150`: form 1.5, old 1.25/200 preview and enabled Confirm remain. Dirty-editor Confirm NOT executed; no wrong write demonstrated |
| Save 1.5 invalidates old preview; new Preview | PASS for saved correction | `83` removes preview/Confirm; `84`–`85` show 240 kcal, quantity 1.5, new preview revision 2 |
| Confirm → receipt | PASS for this single item | `86-confirm-receipt`; DB draftRevision 4/itemRevision 3, receipt `dc279d57-da3e-4a5c-8daa-475274911275`, one Log `0a50784a-7854-42dc-a7cd-bf3a20665ef0`, 1.5 SERVING/60g |
| Nutrition retrieval; unknown vs real zero; no duplicate/no auto-COMPLETE | PASS for observed normal Confirm | `90-nutrition-refetched`: one item, 240 kcal, protein 0, carb/fat Chưa xác định, Đang ghi (PARTIAL). Return button opened blank date; original date was selected/reconfirmed before retrieval, not an automatic context-preservation PASS |
| Background/Recents resume | PASS for retention only | `91`–`92`, Nutrition still shows same item; Expo CLI warning recurs |
| Process death | NOT RUN / attempt did not kill | `am kill` after Home left Expo Go PID 3260 alive. No claim of process restart/recovery; no force-stop or new persistence |
| Partial selected-item group | NOT RUN | Current simulated draft has one item; no artificial multi-item seed/API write to substitute for UI |
| Controlled timeout/exact replay; pending account switch/revoke | NOT RUN from app | No app-routed controlled loss/revoke mechanism exercised; historical harness/mock remains separate |
| Screen reader/full decimal keyboard layout | NOT RUN | Enabled accessibility services null; device IME handwriting toolbar, input injected through Android. Semantic labels/XML captured but not a screen-reader PASS |

Read-only [post-Confirm DB snapshot](../08-ai/artifacts/f06-mobile-live-20261124/pixel7/after-fix-confirm-db.json)
and [receipts](../08-ai/artifacts/f06-mobile-live-20261124/pixel7/after-fix-receipts-db.json)
support UI evidence. No direct API mutation replaced app actions. Catalog/40g
portion and provider are simulated/synthetic, not specialist/model validation.

### Status, open finding and runtime

- P7-019: **fixed in working tree, regression PASS, device Correct retest PASS**.
  New fix is not committed/pushed or independently reviewed.
- P7-020 remains deferred UI, untouched. Old failures remain below as history.
- **P7-021 remains open:** quantity onChange deliberately skips preview
  invalidation, startEditItem leaves preview, fresh Confirm checks persisted
  draft/preview but not dirty editor. The observed dirty-edit guard gap fails
  that requested subflow; no dirty Confirm write was attempted. Full steps and
  severity/limits are in existing ignored local backlog. It is a different cause
  than P7-019, so not fixed under this turn's narrow authorization.
- Receipt return-date UX and original AI missing-data labels after correction
  are noted against existing date/copy backlog, not silently fixed or called
  data loss. No automatic memory recovery promised; unknown pending-intent
  survival after process death remains unverified.
- Runtime retained: Pixel_7 launcher 40860/QEMU 3544, Metro 22764, Spring 17492,
  FastAPI 20912, task DB container. No cleanup, purge, Erasure flag or unrelated
  process change. App left on Nutrition with the synthetic confirmed item.
- No stage/commit/push/branch/PR operation; existing MD/evidence/five scripts
  retained. This is not all-F06 Pixel_7 PASS or milestone/release completion.

## Initial actual-device QA — 2026-11-24/25 ICT (historical before fix)

This supersedes earlier “app not loaded / all device NOT RUN” notes only for
the scenarios exercised below. Historical mock/HTTP evidence is preserved.
No F06, milestone, release or Draft readiness gate is closed.

### Source, environment and existing backlog

- Verified branch `feature/p2-dev-b-f06-mobile-client-20261124`, HEAD
  `f299540367b9b883414a4ac2bec06daee8e155c4`. Existing Markdown/evidence retained.
- Actual Pixel_7 `emulator-5580`, Android 16/API 36, model sdk_gphone64_x86_64,
  Expo Go 57.0.9, windowed AVD. Used user's existing synthetic Student B session
  (ACTIVE, verified/onboarded), no logout/reset or password entry in this turn.
- Metro PID 22764 / IPv4 18081, project via `exp://10.0.2.2:18081`; Spring PID
  17492 / 18080, unchanged backend dependency `17c485f`; source FastAPI PID
  20912 / 18001, simulated fallback. Verified JDBC from Spring PID to host
  15486, mapped to task-labelled `f06-mobile-qa-20261124`, DB `f06qa`.
  Catalog and 40g conversion are synthetic, not approved professional data.
- Directly read ignored root `PIXEL7_MANUAL_QA_ISSUES.local.md` and
  `DEV_B_PHASE1_HANDOFF.local.md`; searched root/docs/linked QA/UI/handoff and
  Phase 2 Markdown including ignored files, excluding Git/dependencies/build/
  cache directories. Existing P7-009/011/013 cover date/timezone/meal UX,
  P7-012/016 font/copy, P7-004 design and P7-014 Home. No exact old instruction
  scheduling changes “end of Phase 2” was found. Keep those existing entries;
  do not invent a prior deadline, duplicate backlog or upgrade UI preferences
  to authority/intake blockers without evidence.

### Actual UI scenarios

ADB touch/type/scroll/Back operated the actual emulator. Paths below are in
`docs/08-ai/artifacts/f06-mobile-live-20261124/pixel7/`; numbered captures have
PNG and XML. DB queries were read-only support, not substitutes for app actions.

| Scenario | Result | Actual evidence / boundary |
|---|---|---|
| Student session, Nutrition → F06 → Back | PASS | `05-nutrition`, `08-f06-entry`, `44-back-nutrition`; no logout/reset |
| Day/timezone/meal context passed from Nutrition | PASS for observed values | `08-f06-entry`, `observed-form-values.json`: 2026-11-24, Asia/Ho_Chi_Minh, OTHER; no assumption of time-field transfer |
| Explicit context; empty text blocks Generate | PASS | `11-context-confirm`, Generate disabled until text input |
| UI Generate → rendered draft/detail, unknown amount/identity | PASS | `15-generate-response`, `16-draft-scroll`: ESTIMATE/READY, draft #1, item selection disabled until quantified; not lost-response recovery |
| Catalog food/SERVING/portion selection | PASS for local form only | `19-catalog-select`, `20-serving-basis`: synthetic food and 40g portion; persistence fails below |
| Correct with valid 1.25 | **FAIL: P7-019 blocker** | `26-dot-quantity`, `28-dot-save`, `29-dot-save-error`; invalid-quantity message, unchanged item. Native event interpreted as retry intent |
| Decimal comma/dot input | PASS for entry only; validation NOT VERIFIED | `21-decimal-comma`, `26-dot-quantity`: 1,25 and 1.25 captured; Correct blocked. Field uses decimal-pad in source, but device showed handwriting IME toolbar; physical numeric-key layout not verified |
| Scroll/editor reachability; safe area | Scroll PASS; safe area **FAIL: P7-020 deferred UI** | `12-input-focus`, heading overlaps status icons; fields/buttons remain reachable |
| DST overlap and meal/context selection | PASS for UI only | `34-dst-zone-retry`–`38-dst-confirmed`: New York 2026-11-01 01:30, -04/-05 shown, -05 and LUNCH selected, context confirmed; no DST Confirm persisted |
| Home → Recents → existing app | PASS for form retention only | `39-background`, `41-recent-tasks`, `42-resumed`: values/offset/meal retained; Expo CLI warning also observed (old P7-010) |
| Nutrition empty/unknown; no premature intake | PASS for observed pre-Confirm state | `49-day-empty`: Chưa ghi, nutrients Chưa xác định, no items. DB confirms 0 previews/confirmed items; day-confirm button not used |
| Back/unmount → F06 reentry | PASS as observation of published limitation | `50-reentry-memory-limit`: local draft/description gone, Nutrition context restored. Not process-death recovery |
| Preview; quantity edit invalidates Preview; Confirm/receipt; selected partial group; post-Confirm refetch/no duplicate/no auto-COMPLETE | NOT RUN | Blocked by P7-019; simulated provider gives one unmatched item. No corrected-draft seeding or direct API writes to fake UI coverage |
| Generate unknown-outcome recovery; offline/timeout/exact retry; pending-request account switch/revoke | NOT RUN | No app-routed controlled loss/revoke fixture exercised. Historical harness/mock coverage stays distinct; production source/config unchanged |
| Process death; screen reader; complete loading/error matrix | NOT RUN | Background retention is not process death; no controlled latency. Only observed disabled/error/unknown states checked |

### Findings and next coding pass

Detailed steps/expected/actual/severity/old-note relation are added to the
existing ignored `PIXEL7_MANUAL_QA_ISSUES.local.md`, not a new backlog file:

- **P7-019 BLOCKER:** `FoodAnalysisScreen.tsx:341` takes optional retry intent,
  line 1082 directly binds `onPress={handleSaveCorrection}`. `WorkoutUi.tsx:10`
  forwards the native press event. Its truthiness takes the saved-intent branch,
  reading `retryIntent.body` rather than validating/building a new request.
  Valid-dot save repeatedly fails. DB draft
  `446d2d57-7535-4760-8d12-6ab8bfe7c61f` stays draftRevision 1, itemRevision 0,
  selectedFood/quantity null, 0 previews and confirmed items. Visible message
  maps to source's 400 catch branch; no independent wire capture/status taken.
  Existing screen-test helper at line 154 calls `.props.onPress()` with no native
  event, leaving this boundary uncovered despite historical 103/103. Future fix
  must separate press event from saved intent and regress actual Pressable event,
  valid full body, unsafe/invalid new-intent guards and exact replay; then repeat
  downstream device flow. No wrong intake/authority leak demonstrated here.
- **P7-020 DEFERRED UI:** F06/Nutrition title overlaps status bar. Same class as
  old Profile P7-005, newly verified surface, not proof that Profile fix regressed.
  Scroll/Back work; no data/authority impact. Keep in end-of-Phase-2 UI collection;
  do not drop explicit domain-context confirmation to address old timezone UX.
- Existing **P7-010** Expo CLI warning recurs despite Metro listening: development
  environment limit, not domain defect. Vietnamese IME altered ASCII words; exact
  timezone entry succeeded after language switch (related P7-007), not a model
  recognition defect. No credentials/tokens were captured in new evidence.

Read-only evidence: [DB after failed Correct](../08-ai/artifacts/f06-mobile-live-20261124/pixel7/device-db-after-correct-failure.json),
[observed form values](../08-ai/artifacts/f06-mobile-live-20261124/pixel7/observed-form-values.json).
Historical Anti 103/103 and HTTP 8/8 were not repeated and do not independently
review these new findings. No Jest/typecheck/lint/backend/full harness ran in
this turn; only device/source/runtime/read-only DB checks and documentation
`git diff --check`. No whole-flow Pixel_7 PASS, F06 completion or model readiness.

### Runtime and Git disposition

Retained for inspection/reproduction: windowed Pixel_7 launcher 40860/QEMU 3544,
Metro 22764, Spring 17492, FastAPI 20912, task DB container. No process/container
cleanup in this QA turn, no purge/Erasure flags changed. Cleanup later must
target only task resources and retain DB/evidence. Agent did not restart Metro
or retry prior policy-rejected launches. No source/dependency/config/Git/PR
change. Five pre-existing untracked scripts retain known hashes; index untouched.
Both local notes remain ignored; issue-log changes must not be staged.

## Scope and dependency

- Branch: `feature/p2-dev-b-f06-mobile-client-20261124`.
- Backend dependency: PR #165 at **`17c485fa6d248fff58c7dac00c931536ff6c7720`**.
  This checkpoint neither merges that PR nor declares F06/P2-M5/release complete.
- Student-only text ESTIMATE in both coaching modes. Entry from Nutrition Today
  preserves day/timezone/meal type; route `/(app)/nutrition/food-analysis` uses
  explicit local meal time and timezone/offset. No Trainer/Admin confirmation.
- Typed Spring client covers Generate, command-key recovery, draft detail,
  Correct, Preview and selected-item Confirm. No FastAPI calls from Mobile,
  shared wire-format changes, backend changes or automatic day confirmation.
- Unknown quantity/nutrients stay null, zero revisions/known zero nutrients
  survive serialization, and confirmed intake is shown only after a receipt.
  No optimistic Food Log update or client nutrient calculation.

## Executable semantics and safeguards

Spring `FoodAnalysisReviewService.preview` uses the actor-scoped command receipt,
persists `food_analysis_previews` with command key/fingerprint/snapshot, and
returns the saved snapshot on exact replay. It is **not a pure calculation**.
A Preview timeout/network failure may follow a committed snapshot, just as a
Correct failure may follow a committed correction and a Confirm failure may
follow committed intake. The technical review's earlier pure-calculation
description must not be used to discard this uncertainty.

- Mutation key/body are saved before I/O. Retry sends the saved body without
  rebuilding from current form, selections, revisions, catalog or clock.
  No automatic mutation resend, including shared-client 401 refresh/resend.
- Generate loss uses explicit command-key recovery; a concealed/not-yet-visible
  404 is not proof the Generate never arrived. No automatic regeneration.
- Preview exposes an explicit old-intent retry. A deliberate new Preview creates
  a new key/body. Changing selection/context invalidates its display generation;
  replaying an old context can recover its snapshot but cannot authorize Confirm
  against the current form. Starting a newer Preview supersedes callbacks from
  the older request without claiming its backend work was cancelled.
- Correct network errors retain the original intent as well as timeouts. Fresh
  Save is blocked until the unresolved intent is reconciled. An unresolved Confirm
  similarly blocks fresh mutations; Preview/form changes do not delete its intent.
- Every **new** Correct checks safe nonnegative draft/current/edit-item revisions.
  Preview checks draft/item revisions and quantity. New Confirm checks current
  draft/item and preview/selection revisions (preview revision at least 1), plus
  the exact selected identities/amounts/bases/portions against the viewed preview.
  Unsafe revisions report a revision/device limitation, never a quantity fix.
  Saved replay skips reconstruction from new server revisions, including after
  expiry; Spring still decides authority, identity, conflicts and replay validity.
- An observed account/capability/auth-status change remounts the screen's session
  scope. A → B → A cannot revive callbacks from the first A. Mount identity plus
  per-request tokens guard success, catch, post-invalidation continuation and
  finally. Old finally cannot clear a new request's busy/submitting state.
- Query keys partition private drafts by actor and draft. Late detail responses
  check session validity; 401/403/concealed 404 hide cached private detail and
  clear local intents/receipt. Capability loss/unmount prevents late mutation
  callbacks from publishing data or restoring intent/receipt into a new session.
  AbortController is a transport signal, not evidence of backend rollback.

## Review history versus new verification

**Historical independent evidence supplied by Antigravity:** focused Mobile
**75/75**, typecheck/lint/diff check passed. That reviewed working tree still had
the Correct revision and A → B → A gaps. This evidence does **not** review the
changes made in this turn; the new guards, request identity and Preview intent
handling need independent review.

Before implementation, new UI regression tests reproduced **15 failed / 11
passed** in the screen suite. They exercised real rendered action handlers;
existing assertions were not weakened. After the fixes and additional targeted
coverage, the requested final command from `apps/mobile` was:

```powershell
npm test -- --runInBand src/__tests__/foodTextAnalysisApi.test.ts src/__tests__/foodAnalysisScreen.test.tsx src/__tests__/nutritionScreens.test.tsx src/__tests__/apiClient.test.ts
npm run typecheck
npm run lint
```

Final result: **103/103**, four suites, zero failures/skips; Jest exit **0**.
Breakdown: typed client 48, F06 screen 39, Nutrition screens 9, shared API client 7.
Typecheck and lint exit **0**. `git diff --check` and
`git diff --cached --check` both passed before checkpoint commit. The tests use mocked fetch/query/API
boundaries, including deferred promises and injected timeout/network failures;
they are not live HTTP/DB or device evidence. Backend suites were not repeated.

Regression coverage includes unsafe new Correct/Confirm revisions and mismatched
selection; current item changing while editing; A → B → A success/error/finally;
old post-invalidation continuations; Preview timeout/network exact retry after
form edits; new Preview key/context; late success/error before and after the
new preview; Correct network retry; Confirm replay after expiry/unsafe new data;
capability loss, query 403, unmount and continued actor cache isolation.

## Precision and recovery limits

- The Mobile quantity cap **999,999** with up to six decimal places is a
  conservative **client technical restriction**, not an approved Nutrition domain
  policy. Backend supports a wider range; some valid server quantities are
  intentionally blocked for new Mobile intents. This cap does not turn JS binary
  floating point into exact decimal arithmetic. Mobile never recalculates intake.
- OpenAPI revisions are int64, while V61 stores bigint and numeric(18,6).
  JS `number` cannot represent the whole wire range. For example JSON parsing
  `9007199254740993` yields `9007199254740992`, and `999999999999.999999` yields
  `1000000000000`. New unsafe revision intents fail closed. There is no shared
  string/bigint/decimal wire change in this slice; broader precision needs owner
  review with the backend/OpenAPI owners.
- Intents and receipts are memory-only. They are deliberately discarded on
  session/capability transition or unmount; no sensitive unencrypted persistence
  is added. Process death/navigation can lose the local command key/body. Generate
  recovery works only while its key is available; exact Correct/Preview/Confirm
  replay requires retaining the original intent. No invented lookup endpoint or
  promise of automatic recovery after memory loss.

## Remaining review and live/device gates at checkpoint creation (historical)

Independent review of these new changes remains open. Run live Spring HTTP with
actual session switches, revoked Student authority, lost responses after database
commit, receipt replay after expiry/catalog changes, and atomic selected-item
confirmation with competing writers. Validate real catalog/conversion evidence;
fixtures and model output are not professionally approved nutrition data.

Pixel_7/emulator interaction, accessibility/layout, real transport timeouts,
app background/process termination and memory-recovery UX remain unverified.
No full Mobile/backend, live Spring–FastAPI, CI, device PASS, rollout authorization
or F06 completion is claimed. Backend Erasure flags remain OFF and its broader
DOMAIN_REVIEW_PENDING/provider/backup/release gates remain separate.

Checkpoint excludes the five pre-existing Vietnamese scripts. No Mobile PR,
merge/rebase/force-push or PR #165 modification is part of this handoff.

## Draft PR and runtime follow-up — 2026-11-24, 21:12 ICT

This follow-up supersedes the open-review/live status above while preserving the
checkpoint evidence. Antigravity independently reviewed exact checkpoint
`f299540367b9b883414a4ac2bec06daee8e155c4`: focused **103/103**, typecheck/lint
passed, no findings within the inspected scope (evidence supplied by the user).
Those suites were not rerun solely to open the PR in this turn.

- [Draft Mobile PR #169](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/pull/169).
- Base: `feature/p2-m5-f06-full-slice-20261123`, remote SHA
  `17c485fa6d248fff58c7dac00c931536ff6c7720`; dependency PR #165 remains OPEN/Draft.
- Head: `feature/p2-dev-b-f06-mobile-client-20261124`, remote/local SHA
  `f299540367b9b883414a4ac2bec06daee8e155c4`. Dependency is a verified ancestor;
  delta is one commit / 11 Mobile+documentation files, with no backend/scripts.
- Remote main observed at `c289ff044c0ac7c5bd2f3036b013acac09f986f8`.
  GitHub compare shows backend ahead 6 / behind 4, merge base `673ce513`.
  Main was not fetched/merged/rebased; main integration readiness is not attested.
  Retarget/integration needs review after the backend dependency merges.

### New runtime evidence: HTTP harness, not Mobile app execution

Eight HTTP scenarios passed from **14:12:30–14:12:35 UTC** on 2026-11-24.
Runtime: Docker 29.8.1 `desktop-linux`, task-owned disposable PostgreSQL 18.6
(`pgvector/pgvector:pg18`), Flyway through V62, Java 21.0.8 / Spring Boot 4.1.1
built from B06 with `./mvnw.cmd -q -DskipTests package` (exit 0), dev profile on
18080, `AI_SERVICE_MODE=http`, and source FastAPI on 18001. FastAPI's source F06
provider is a simulated fallback, not an evaluated model. Spring-to-FastAPI used
a real socket HTTP request (AI access log 200). No B05/other-checkout source,
shared DB, erasure flags or product code changed.

[Sanitized evidence](../08-ai/artifacts/f06-mobile-live-20261124/http-evidence.json)
contains timestamps, synthetic IDs and outcomes. Transient harness/logs are in
`%TEMP%/f06-mobile-qa-20261124/`; private test configuration is excluded from the
repository/report. Harness SHA-256:
`E71D7F0E2338641714A9121AA33C08D1F2CB7076E7AA5E4B60AC6DC5A9DD3CD7`.

| Scenario | Result | Evidence / boundary |
|---|---|---|
| Generate → command-key recovery/detail | PASS, HTTP harness | 201 then 200, same draft `178bfe78-c6b0-4600-bfc2-73a78dbf30cf`; unknown amount null, zero pre-confirm intake |
| Other Student session reads that draft | PASS, HTTP harness | 404; synthetic signed JWT accounts, not login/registration coverage |
| Correct → persisted Preview/replay | PASS, HTTP harness | Preview `04c37515-0edf-439a-a3c7-0f6d83b3b8e6`; 1.25 synthetic cups × 40g gives server snapshot energy 200, protein 0, carb/fat null; identical replay |
| Confirm response lost AFTER commit → replay | PASS, HTTP harness/DB | Proxy observes upstream 201 and independently visible DB receipt count 1 before closing downstream socket. Retry returns receipt `37a4d7a2-8378-43df-a0fc-f5909bc37c89`, same Food Log `b08e6b11-6cb3-42cc-b076-35b731bb86f7`, replayed=true, intake count stays 1 |
| Unknown nutrients and partial group | PASS, HTTP harness/DB | Unselected item stays draft, PARTIALLY_CONFIRMED, day PARTIAL. Second item is a disposable SQL fixture matching integration-test setup, not multi-item model recognition |
| Replay after expiry/catalog change | PASS, HTTP harness | Task-owned SQL moves expiry into past; real catalog RETIRE command; original Confirm key/body still returns original receipt/snapshot |
| Same key with changed quantity | PASS, HTTP harness | 409, no extra intake |
| Revoke while request pending | PASS, transport boundary only | Proxy holds before forwarding into Spring, task-owned account is DISABLED, then release gets 401 and count remains 1. Not mid-transaction revoke or UI session-switch evidence |
| Real Mobile service/app transport and A → B → A UI | NOT RUN | Metro launch rejected by automatic approval review; harness/Jest does not replace app evidence |
| Pixel_7 entry, decimal keyboard, scroll/safe area, loading/offline/retry/receipt | NOT RUN | AVD boots, F06 app not loaded; no Pixel_7 PASS |
| Background/process death / memory recovery | NOT RUN | Same Metro prerequisite; no persistent storage added or automatic recovery promised |

Catalog IDs, source URL and 40g conversion are synthetic fixture facts, explicitly
not professionally reviewed. The test used fresh SQL accounts and locally signed
test JWTs; real registration/password login/token refresh were not exercised.
The controlled proxy does not change Spring/domain contracts.

### Device blocker, gates and next task

Docker daemon was checked with `docker info`. ADB initially had no device, AVD
inventory found Pixel_7, and that configured AVD was booted without wipe/reset.
Observed: `emulator-5580`, Android 16/API 36, model `sdk_gphone64_x86_64`, build
`google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.D1/13818094:user/release-keys`,
1080×2400 at density 420, installed Expo Go 57.0.9. This is environment inventory,
not an app pass. No cached bundle from another checkout was used.

Automatic approval review rejected Metro startup through both Expo CLI and the
repo's start script, using localhost port 18081 and the isolated test API URL.
The tool returned only `blocked by policy`, without a detailed reason. No further
launch workarounds were attempted. Smallest next task: make an approved Metro
launch available for this exact source/test API, then run the listed F06 app
scenarios on Pixel_7 with screenshots and observed IDs.

No product defect was demonstrated by the eight passing HTTP scenarios. Full
Mobile/backend, CI, model/catalog evaluation, mid-transaction competing writers
and device interactions were not run. T06/T14/T15/T26/T27 and D06 still require
their complete applicable acceptance/professional evidence; the approved P2-M5
completion criteria and implementation-plan section 11 release gates are not
closed by this PR/evidence. Anti recommendations are not extra owner-approved
merge gates. Erasure rollout stays OFF and DOMAIN_REVIEW_PENDING.

This follow-up remains unstaged/uncommitted. No new commit/push, PR #165 edit,
branch change or merge was performed. The Phase 1 local handoff was inspected
as historical evidence and left unchanged; the approved Phase 2 plan defines
acceptance rather than personal progress and also stays unchanged.

After verification the task-owned backend, FastAPI and Pixel_7 emulator were
stopped. The task-labelled PostgreSQL container was stopped with its disposable
data retained for review; no volume/database purge, Docker reset or unrelated
service shutdown was performed. Logs and private test configuration remain in
the local temporary task directory, outside Git.

## Pixel_7 continuation: manual Metro handoff pending — 2026-11-24

Rechecked branch/HEAD: unchanged at `f299540367b9b883414a4ac2bec06daee8e155c4`.
All prior Markdown diffs and the eight-case HTTP evidence were retained; no
focused tests or full HTTP harness were rerun. No code/dependency/PR/Git mutation.

The retained task-labelled PostgreSQL container was restarted without purge;
the existing B06 backend JAR and source FastAPI were started with the same
test configuration (`AI_SERVICE_MODE=http`, simulated F06 provider). Backend
readiness and AI health both returned HTTP 200. Erasure settings were unchanged.
Pixel_7 was booted again: `emulator-5580`, Android 16/API 36, model
`sdk_gphone64_x86_64`, build `BE2A.250530.026.D1/13818094`, Expo Go 57.0.9.
Two new synthetic Students with password login and a synthetic published catalog
item/40g test portion were prepared in the disposable DB. Login preflight HTTP
200 is harness setup evidence, not app-login coverage. No credentials are in
the repository artifact. The quantity/conversion remains a technical fixture,
not professionally reviewed nutrition data.

[Preparation evidence](../08-ai/artifacts/f06-mobile-live-20261124/device-preparation.json)
records the checks. Metro was still not listening on 18081 at the final check.
The agent did not retry its previously policy-rejected launch or use a wrapper,
other shell, renamed command or background workaround. The user was given the
standard package-script command to run manually, with the terminal left open:

```powershell
Set-Location -LiteralPath 'C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\b06-phase1-schedule-appointment-closure\apps\mobile'
$env:EXPO_PUBLIC_API_BASE_URL='http://10.0.2.2:18080/api/v1'
npm run start -- --localhost --port 18081
```

ADB reverse `tcp:18081 -> tcp:18081` is ready. App work may continue only after
observing Metro ready. Entry/navigation, input/decimal keyboard, Generate/detail/
recovery, Correct/Preview/Confirm, partial/unknown, receipt/refetch, offline/retry,
pending-request session switch, background/process-death, DST and accessibility
are all **NOT RUN in this continuation**. Mid-transaction revoke also remains
NOT RUN. No new product defect or PASS Pixel_7 is inferred from preparation.

Backend, FastAPI, DB and emulator are deliberately left running for this manual
handoff; this supersedes the earlier stopped-runtime disposition. Smallest next
step is user-started Metro readiness, then actual app QA with screenshots/IDs.
The historical Anti 103/103 and HTTP 8/8 retain their original scope/date.
No F06, milestone, release or Draft readiness gate is closed. Documentation and
preparation evidence remain unstaged/uncommitted; five untracked scripts are
unchanged.

## Metro verified; manual Expo Go launch pending — 2026-11-24

After the user reported Metro ready, the agent verified HTTP 200 with
`packager-status:running` from `http://localhost:18081/status`. The host listener
is `[::1]:18081`; the IPv4 probe `127.0.0.1:18081` did not connect. The existing
ADB reverse is retained; this alone does not prove Android-to-Metro connectivity.

A combined command containing Metro read checks, an ADB VIEW launch of Expo Go,
source reads and artifact-directory preparation was rejected before execution
by automatic approval review with only `blocked by policy`. The tool did not
identify the offending subcommand or provide a more detailed reason. No alternate
shell/wrapper/background/GUI mechanism was used to retry app launch. Independent
read-only status checks succeeded. The user was asked to open Expo Go manually:

```powershell
adb -s emulator-5580 shell am start -a android.intent.action.VIEW -d 'exp://127.0.0.1:18081' host.exp.exponent
```

At the final observed check the foreground remained NexusLauncher, not F06.
[Metro connection evidence](../08-ai/artifacts/f06-mobile-live-20261124/metro-connection.json)
records the verified host status and the unverified app connection separately.

| Requested app scenario | Current result |
|---|---|
| Nutrition → Food Analysis entry/back | NOT RUN: awaiting permitted/manual app launch |
| Text/decimal keyboard, scroll/safe area | NOT RUN |
| Generate/detail/recovery; Correct/Preview/Confirm | NOT RUN from app |
| Partial/unknown; receipt and Nutrition refetch | NOT RUN from app |
| Offline/timeout exact-intent retry; pending-request logout/account switch | NOT RUN from app |
| Background/process death; DST/accessibility | NOT RUN |
| Mid-transaction revoke | NOT RUN; prior proxy-before-forward case remains distinct |

No product defect is established by this environment/policy block. Historical
Anti 103/103 and HTTP harness 8/8 are unchanged and not renamed device evidence.
No PASS Pixel_7, F06 completion, release gate or PR state change is claimed.
Runtime is kept running pending this manual handoff: backend PID 17492,
FastAPI PID 20912, emulator PID 21220/serial emulator-5580, task-owned container
`f06-mobile-qa-20261124`, user-started Metro PID 7668. Cleanup is deferred until
the paused QA resumes/ends; the agent must not stop the user-started Metro
without the appropriate handoff. No code or Git/PR mutation occurred.

## Pixel_7 manual open observed: project download blocked — 2026-11-24

The user executed the Expo Go VIEW intent manually. The observed foreground is
`host.exp.exponent.experience.ErrorActivity`, showing **Something went wrong**;
the error-log view says **Uncaught Error: java.io.IOException: Failed to download
remote update**. The project has not reached the F06 sign-in/UI. This supersedes
the prior launcher-only observation; it is not a successful app connection.

Evidence:

- [Initial error screenshot](../08-ai/artifacts/f06-mobile-live-20261124/pixel7/01-expo-open-error.png).
- [Error log screenshot](../08-ai/artifacts/f06-mobile-live-20261124/pixel7/02-expo-error-log.png)
  and [UI dump](../08-ai/artifacts/f06-mobile-live-20261124/pixel7/02-expo-error-log.xml).
- [Connection checks](../08-ai/artifacts/f06-mobile-live-20261124/pixel7/connection-failure.json).

| Actual check in this continuation | Result |
|---|---|
| Host Metro status | PASS, HTTP 200 / packager-status:running, listener `[::1]:18081` |
| Emulator → backend readiness at `10.0.2.2:18080` | PASS, actual emulator TCP/HTTP probe returns HTTP/1.1 200; not F06 app fetch evidence |
| Emulator → Metro via existing reverse `127.0.0.1:18081` | FAIL to obtain HTTP response; same toybox HTTP probe used for backend succeeds |
| Expo Go project download | FAIL, error screenshot above; environment/transport blocker |
| F06 entry/navigation, inputs/decimal keyboard, scroll/safe area | NOT RUN, JavaScript project has not loaded |
| Generate/recovery, Correct/Preview/Confirm, partial/unknown/receipt/refetch | NOT RUN from app |
| Offline/retry, pending-request session isolation, background/process death, DST/accessibility | NOT RUN from app |

ENV-01 reproduction: host Metro was started with localhost mode and resolved to
IPv6-only `::1`; ADB reverse exists; open `exp://127.0.0.1:18081` in Expo Go.
Observed: failed remote-update download, host status 200 but no emulator Metro
HTTP response. Impact: all actual F06 app QA blocked. IPv4/IPv6 mismatch is a
supported hypothesis, not a confirmed root cause until a corrected listener
loads the project. No F06 source defect or dependency incompatibility is proven;
do not change production code/dependencies to address this evidence alone.

The agent asked the user to restart Metro manually with the same API URL and
`$env:NODE_OPTIONS = (($env:NODE_OPTIONS + ' --dns-result-order=ipv4first').Trim())`
before the standard start command. The installed Node help documents this option.
No agent Metro restart, shell/wrapper/proxy workaround or dependency change was
attempted. Next: observe IPv4 listener and emulator HTTP response, then reload
the already opened project and proceed with QA if it loads.

Runtime remains running for the pending manual handoff; no cleanup yet. No
source/Git/PR changes. Historical evidence and all five scripts remain intact;
no PASS Pixel_7 or F06/release completion is claimed.

## IPv4 restart verified; direct emulator route available — 2026-11-24

The user restarted Metro with IPv4-first DNS. The observed listener is now
`127.0.0.1:18081`, PID 39000; host `/status` returns HTTP 200. An Android shell
HTTP/1.1 probe with explicit CRLF to `10.0.2.2:18081/status` also returns HTTP
200 and `packager-status:running`. The same probe via `127.0.0.1:18081` still
receives no HTTP response, including after recreating only the existing
`tcp:18081 -> tcp:18081` ADB reverse. The reverse failure root cause is unresolved;
IPv4 binding alone has not proven that Expo Go can download the project.

No reload of the old URL was attempted. Because the earlier automatic approval
review rejected the app-open action, the user was asked to manually open
`exp://10.0.2.2:18081` in Expo Go. This uses the direct route just verified;
no proxy, Metro launch workaround, source or dependency change was introduced.
See [IPv4 recheck evidence](../08-ai/artifacts/f06-mobile-live-20261124/pixel7/ipv4-recheck.json).

| Check | Result |
|---|---|
| IPv4 host Metro and emulator direct HTTP status | PASS: actual HTTP 200; transport check only |
| Emulator HTTP over ADB reverse | FAIL: no response; mapping retained |
| Expo Go download after IPv4 restart | NOT RUN: awaiting manual direct-URL open |
| All F06 app scenarios | NOT RUN: project not yet observed loaded |

Backend PID 17492, FastAPI PID 20912, retained DB container and Pixel_7 remain
running for this handoff; boot_completed is 1. Metro PID 39000 is user-owned.
No cleanup, test-suite rerun, source edit, Git write or PR change occurred.
Prior screenshots/errors and historical mock/HTTP results remain separate.

### Follow-up authorization and observed app-open gate

The user authorized autonomous task-runtime/UI operation within system policy.
At 2026-11-24 16:06 UTC the UI dump still shows the original Expo Go native
download-error log (22:44:39), not the app. A host manifest request with Android
headers and Host `10.0.2.2:18081` returns HTTP 200, runtime `exposdk:57.0.0`,
and a bundle URL on `10.0.2.2:18081`. This is manifest/transport evidence only;
the bundle has not been observed loading in Expo Go.

The prior automatic approval rejection of the ADB project-open action supplied
only `blocked by policy`. No approval-escalation mechanism is available in this
session. That action was not retried or replaced with a GUI/wrapper workaround;
manual open of the direct URL remains the dependency for app QA. All requested
F06 app scenarios remain NOT RUN, and no further product defect is established.
Evidence: [gate/manifest check](../08-ai/artifacts/f06-mobile-live-20261124/pixel7/manual-open-gate.json)
and [current UI dump](../08-ai/artifacts/f06-mobile-live-20261124/pixel7/03-still-native-error.xml).
Runtime is retained for the handoff; no cleanup or test-suite rerun occurred.

### Manual direct-URL open reached sign-in; emulator has no desktop window

After the user's manual open of `exp://10.0.2.2:18081`, the actual Android UI
now shows the app's Vietnamese sign-in screen, including a session-expired
banner. Project loading is therefore observed; login/backend F06 operations
are not yet verified. Evidence: `pixel7/04-app-login.png` and matching XML in
the existing artifact directory. No credentials were entered in this step.

The emulator reports `windowtype=headless`; both its launcher and QEMU command
lines include `-no-window`. There is no existing Pixel_7 desktop window to
foreground. No additional emulator was launched. A restart of this same AVD
with a window would interrupt app memory state, so the user was asked to choose
that restart or retain the current headless session. Metro/backend/DB remain
running. F06 scenarios remain NOT RUN; sign-in visibility is not a F06 flow PASS.

The user subsequently authorized restarting this same AVD visibly. The agent
stopped emulator-5580, verified the old Pixel_7 processes had exited, then
started Pixel_7 on port 5580 without `-no-window`. It now reports
`windowtype=qtwindow`, boot_completed=1 and exposes desktop title
`Android Emulator - Pixel_7:5580` (launcher PID 40860, QEMU PID 3544).
No concurrent/additional emulator was created. Foreground activation could not
be verified because the computer-use native pipe was unavailable on both checks.
Backend/FastAPI PIDs remain 17492/20912; DB is retained. Metro port 18081 is now
not listening and its prior PID has exited; the agent did not stop/restart it.
See `pixel7/visible-restart.json`. App memory state was interrupted as authorized;
F06 app scenarios remain NOT RUN. No source or Git operation occurred.
