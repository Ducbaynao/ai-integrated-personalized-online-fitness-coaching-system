# P7-008 Handoff — Fitness Goal Catalog Seed, Mobile Options, Version PRIMARY Lock & Parent Identity Guard

**Date:** 2026-11-26
**Implementation Checkout:** `c:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-008-impl` (detached `HEAD` at `fe25e44188402770b235d4c928388471e0156a44`)
**Main Baseline:** `main` @ `fe25e44188402770b235d4c928388471e0156a44`

## Approved Product & Review-Closure Decisions (2026-11-26)

1. Keep both `FAT_LOSS` and `WEIGHT_LOSS` in the database `fitness.goal_types` catalog (`FAT_LOSS` = **Giảm mỡ**; `WEIGHT_LOSS` = **Giảm cân**).
2. Mobile keeps the 5 goal-type creation/proposal/transition options (`FAT_LOSS`, `MUSCLE_GAIN`, `STRENGTH`, `IMPROVE_FITNESS`, `MAINTAIN`) in a dedicated shared data module `apps/mobile/src/features/goal/goalTypeOptions.ts`; do **not** add a `WEIGHT_LOSS` chip.
3. **Version `PRIMARY` Lock (`CreateGoalVersionModal`):**
   - Never fall back to `'FAT_LOSS'` or pick the first option when `currentGoalTypeCode` is missing, `null`, or blank. Instead, show an explicit missing-PRIMARY state (`goal-version-primary-missing`), disable submit, and never invoke `onSubmit`.
   - Display the active goal's `PRIMARY` type read-only (`goal-version-primary-readonly`), with no type-selection chips or switching controls in the version modal.
   - Lock `lockedGoalType` per `(visible, sessionId)` editor session and preserve any valid `PRIMARY` code including `WEIGHT_LOSS`, `WEIGHT_GAIN`, or other catalog codes outside the 5 creation chips.
4. **Editor Parent Identity Guard (`CurrentGoalScreen`, `CreateGoalVersionModal`, `CreateGoalTransitionModal`):**
   - **Captured at open (`setGoalFormModalState`):** When opening `'version'` or `'transition'`, capture `GoalEditorContext = { target, goalId, goalTitle, goalTypeCode }` from the displayed goal (`goalEditorContext` state + `goalEditorContextRef`).
   - **Target switch while open:** If a pending GET/refresh switches the displayed `goal` to a different `goal.id` (`goal.id !== goalEditorContext.goalId`), `CurrentGoalScreen` passes `targetMismatchError` to `CreateGoalVersionModal` / `CreateGoalTransitionModal`, which immediately renders the mismatch notice (`goal-version-error-banner` / `goal-transition-error-banner`), disables the submit button, and blocks `onSubmit`. Draft A is never silently redirected to Goal B or submitted to Goal A while viewing Goal B.
   - **Verified at submit (`handleCreateVersion` / `handleCreateTransition`):** Before calling `runGoalFormMutation` or `fitnessGoalApi`, re-verify `captured.goalId === latestGoal.id` and `captured.target === 'version' | 'transition'`, and invoke the API with `captured.goalId`.
   - **Same-goal refresh / reconciliation:** When refresh/reconciliation returns the same `goal.id` (`latestGoal.id === captured.goalId`), `targetMismatchError` remains `null`, the open draft is preserved, and submit targets `captured.goalId` (preserving P7-017 conflict/retry guards without auto-updating revision).
5. Never merge or convert between `FAT_LOSS` and `WEIGHT_LOSS`, and never infer fat loss from weight decrease.

## Implemented Scope & Final File Hashes (SHA-256)

| File | Role | SHA-256 |
| --- | --- | --- |
| `services/backend/src/main/resources/db/migration/V64__seed_fitness_goal_types.sql` | Idempotent Flyway seed for 7 `fitness.goal_types` (`ON CONFLICT (code) DO NOTHING`) | `FD20637793D691E36F442D7D1DC1A088C7F459E784935113B02F8A2EC7AF932E` |
| `services/backend/src/test/java/com/fitnesscoaching/platform/V64GoalTypesSeedMigrationTest.java` | Clean V64 + V63->V64 upgrade (non-fixed IDs, metadata/inactive preservation, active Goal/Proposal FKs, no duplicates) | `F1DEA4EADBCC453CBAF5C0B42F995C881E07FA978FBC8D8C6EA81A471C85689C` |
| `services/backend/src/test/java/com/fitnesscoaching/platform/V64GoalCatalogSeedApiIntegrationTest.java` | API integration test using Flyway V64-seeded catalog (no fixture inserts of the 7 codes; 5 Mobile codes + unknown/inactive rejection) | `881BD2663A1E8C5A5A508FC9560A0E264860374590320435F05FB2C5802594A8` |
| `apps/mobile/src/features/goal/goalTypeOptions.ts` | Shared `GOAL_TYPE_OPTIONS` (5 approved codes/labels) & `formatGoalTypeDisplay` | `CBE957E465ADC3A8931C84F536C2EDC8627B72A59CCDBEA4A53DE71C4FBF1046` |
| `apps/mobile/src/features/goal/components/CreateGoalModal.tsx` | Imports `GOAL_TYPE_OPTIONS` from `@/features/goal/goalTypeOptions` (keeps error helper & session guard untouched) | `0C24487AC10F41D7A966B5AB492A468487055C53984BCD6A894C54AC00699617` |
| `apps/mobile/src/features/goal/components/CreateGoalProposalModal.tsx` | Imports `GOAL_TYPE_OPTIONS` from `@/features/goal/goalTypeOptions` | `CBE6C2EBEEF286AFE36516285981A5D125CEC005CF3EBAE1E0D3445221E5C35D` |
| `apps/mobile/src/features/goal/components/CreateGoalTransitionModal.tsx` | Imports `GOAL_TYPE_OPTIONS` + `targetMismatchError` guard & submit block | `CF9AC1DD7C5B84323C30B094F3F0E79D79F2DCBAE2A6740366F5B59C42304F54` |
| `apps/mobile/src/features/goal/components/CreateGoalVersionModal.tsx` | Read-only `PRIMARY` display, missing-`PRIMARY` submit block, per-session `lockedGoalType`, `targetMismatchError` guard | `8AD8F856D12F83A14708B2E8ECE69D97A42A180EF72F3F9634C479ABA6DAF833` |
| `apps/mobile/src/features/goal/CurrentGoalScreen.tsx` | Captures `GoalEditorContext` at open, detects target switch, re-verifies `goalId` at submit for version & transition | `2820972C98D081C0D553F13D38B24F557A73BC3365E284BB70D2A3A8E4DC8555` |
| `apps/mobile/src/__tests__/goalTypeOptions.test.tsx` | Shared module + P2-1/P2-2/context-lock/non-5-option regression suite | `F23302D05ED691E2D9876D1430F88366C9E494D98477EB9E36EE426773022234` |
| `apps/mobile/src/__tests__/goalModalP2Races.test.tsx` | Updated `CreateGoalVersionModal` race spec to pass `currentGoalTypeCode="FAT_LOSS"` while preserving P7-017 guards | `1BDA27055138D47D6D045D453D3CB76A3F5819898A3A69CCDF928B473CEE8BED` |
| `apps/mobile/src/__tests__/CurrentGoalScreen.test.tsx` | Adds deterministic parent-identity regression Cases A–E (pending refresh Goal A->B, reopen for B, same-goal refresh, transient retry, transition A->B) | `65E2D4E62C46C495E2C41A725B7A285ADB1073388B9C6E16D176342B6C051CCC` |

## Verification Evidence

- **Pre-fix Regression FAIL (Parent Identity Guard in `CurrentGoalScreen.test.tsx`):**
  - Command: `npx jest src/__tests__/CurrentGoalScreen.test.tsx --runInBand --testTimeout=30000`
  - Result: **EXIT_CODE=1**, `1 failed` suite, `3 failed, 37 passed, 40 total` (confirmed Case A version A->B and Case E transition A->B did not show mismatch banner or block submit before fix).
- **Mobile Post-fix Verification (Latest Run):**
  - Command: `npx jest src/__tests__/goalModalP2Races.test.tsx src/__tests__/goalTypeOptions.test.tsx src/__tests__/CurrentGoalScreen.test.tsx src/__tests__/GoalProposalDetailScreen.test.tsx src/__tests__/TrainerStudentGoalScreen.test.tsx src/__tests__/fitnessGoalApi.test.ts --runInBand --testTimeout=30000`
  - Result: **EXIT_CODE=0**, **6/6 suites PASS, 111/111 tests PASS**.
  - `npx tsc --noEmit`: **EXIT_CODE=0**.
  - Targeted `npx eslint` on the 9 touched mobile files (`goalTypeOptions.ts`, `CreateGoalModal.tsx`, `CreateGoalProposalModal.tsx`, `CreateGoalTransitionModal.tsx`, `CreateGoalVersionModal.tsx`, `CurrentGoalScreen.tsx`, `goalTypeOptions.test.tsx`, `goalModalP2Races.test.tsx`, `CurrentGoalScreen.test.tsx`): **EXIT_CODE=0** (`0 errors, 5 warnings`).
  - **Limitation:** Full-repo `npm run lint` (`expo lint`) is **NOT RUN / OOM** (hit V8 `Fatal process out of memory: Zone` on low-RAM host; targeted `eslint` is not reported as full lint).
- **Backend Verification (unchanged since prior run; seed/migration files untouched in parent-identity fix):**
  - `V64GoalTypesSeedMigrationTest`: **Tests run: 2, Failures: 0, Errors: 0, Skipped: 0**, `BUILD SUCCESS`, **EXIT_CODE=0**.
  - `V64GoalCatalogSeedApiIntegrationTest`: **Tests run: 1, Failures: 0, Errors: 0, Skipped: 0**, `BUILD SUCCESS`, **EXIT_CODE=0**.
  - `FitnessGoalIntegrationTest`: **Tests run: 48, Failures: 0, Errors: 0, Skipped: 0**, `BUILD SUCCESS`, **EXIT_CODE=0**.
- **Repo / Docs Checks (Latest Run):**
  - `node tools/validate-ux-docs.mjs`: `UX documentation validation passed (18 required files).`, **EXIT_CODE=0**.
  - `git diff --check`: **EXIT_CODE=0**.
  - **Device QA (Pixel_7 / live runtime):** `DEFERRED_FOR_RAM`.


## Independent Review and Main Integration — 2026-11-26

- Review checkout: `p7-008-review-r3`, branch `fix/p7-008-goal-type-catalog`, based on verified current `origin/main` at `fe25e44188402770b235d4c928388471e0156a44`. Applied only P7-008 delta; no conflicts or additional product logic changes. All 12 code/test/migration hashes above match the implementation checkout and integrated source.
- Final parent-target P2 **FIXED**. Independent harness runs the actual parent and both modal sources with deferred API promises: **10 PASS / 0 FAIL, exit 0**. It checks A-to-B rejection at UI and parent, fresh B submission, same-A draft preservation, reconciliation failure/GET-only retry, stale completion isolation, route and focus cleanup. API transport/native host surfaces are mocked; this is not device or live persistence evidence.
- Focused Mobile: **6 suites, 111/111 PASS, exit 0**, `--runInBand --testTimeout=30000`, Node heap 1280 MB. Typecheck exit 0 with heap 1280 MB. Targeted ESLint over 9 files exit 0, **0 errors / 5 warnings**, all `@typescript-eslint/no-require-imports` at `CurrentGoalScreen.test.tsx:2126,2169,2227,2277,2328` (test ScrollView imports, no hook-dependency/session-guard defect). Warnings were reviewed and not suppressed.
- Backend SQL and both V64 test hashes are unchanged from the prior independent review on the identical main baseline: migration 2 + seeded API 1 + FitnessGoal 48 = **51/51 PASS, 0 failure/error/skip**, using `forkCount=0` and a 384 MB heap. Not rerun in this final Mobile-only round. CI uses its normal configuration and must independently verify the full suite.
- UX docs validation passed (18 files); diff check passed. V64 is available on main and checked related concurrency branch. Full local Expo lint remains **NOT RUN** this round; implementation OOM is historical, not a PASS. Full CI remains required.
- Main run #408 remains FAIL at this review: `WorkoutExecutionConcurrencyIntegrationTest.setCommitBeforeCompleteIsVisibleToTerminalDerivation:210`, expected `SUCCESS`, actual `RAW:CannotAcquireLockException` (2173 tests, 1 failure, 0 errors, 5 skipped). Separate task; no concurrency source/test changes included.
- Evidence: `docs/08-ai/artifacts/p7-008-independent-review-20261126/`. Preserved the original independent parent FAIL log alongside the final PASS log and executable harness. The JSON separates current verification, prior Backend review, and implementation self-check history.
- Code checkpoint is eligible; new PR remains Draft with base main. CI status belongs in the PR body after the run exists, not inferred from local tests. Device/native/visual QA remains **DEFERRED_FOR_RAM**. Existing native-storage partial-write/process-death limitations remain unchanged. No claim of complete Goal/Phase 2/release acceptance.
