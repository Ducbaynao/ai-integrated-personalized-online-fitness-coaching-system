# Dev-B F06 Mobile checkpoint — 2026-11-24

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

## Remaining review and live/device gates

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
