# P7-003 / P7-013 / P7-009 (Date Portion) Handoff — Shared Date Picker & Calendar Controls

**Date:** 2026-11-26
**Checkout:** `c:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-003-013-date-inputs`
**Branch:** `fix/p7-003-013-date-inputs`
**Main Baseline:** `7fbedab6f6aff83a21f351e063d6948d62cd4e71`

## Scope Classification

### Already Present Before This Change
- Pure time-only (`HH:mm`) inputs (`NutritionScreen`, `FoodAnalysisScreen`, `PushSettingsScreen`) and read-only date displays (`GoalOverviewTab`, `GoalTargetsTab`, `GoalProposalDetailScreen`, `TrainerStudentGoalScreen`, `StudentProfileScreen` view mode, `ProgressDashboardScreen`, `MeasurementsScreen`, `AttentionReviewScreen`, `SharingPermissionsScreen`): preserved untouched.
- Calendar window offset navigation (`StudentWorkoutScheduleScreen`, `TrainerWorkoutScheduleScreen`) and explicit IANA timezone + DST UTC offset disambiguation (`proposalTime.ts`, `validProposalOffsets`): preserved untouched.

### Newly Implemented (In Scope)
- **`apps/mobile/src/features/shared/dateInputUtils.ts`:** Pure Gregorian calendar utilities (`parseDateOnly`, `formatDateOnly`, `formatVietnameseDateDisplay`, `getTodayDateOnly`, `isLeapYear`, `isMondayDateOnly`, `getCalendarGridDays`, `splitLocalDateTime`, `joinLocalDateTime`, `localDateRangeBoundaryIso`, `isoTimestampToDateOnly`, `replaceDateInIsoTimestamp`). Zero UTC round-trip for date-only values; `localDateRangeBoundaryIso(..., 'end', tz)` uses exclusive next-day `startOf('day')` matching SQL `start_at < until`; `replaceDateInIsoTimestamp` preserves unchanged instants verbatim and keeps wall-clock time-of-day across date changes/DST.
- **`apps/mobile/src/components/ui/DatePickerModal.tsx`:** Pure React Native + Web calendar picker modal using design tokens, `SafeAreaView`, responsive layout via `useWindowDimensions()` guaranteeing `>= 44×44` day touch targets (`46px` at `360px` viewport width, zero horizontal overflow, plus vertical `ScrollView` safety), `days` / `months` / `years` selector modes extending pre-1920 cleanly without inventing a business minimum year, transparent notice when switching month/year leaves a non-existent or out-of-bounds day (clears draft & disables Confirm instead of silent clamping; Cancel preserves original value), strict `minDate`/`maxDate`/`mondayOnly` guards, and per-open immutable `modalOpId` + unmount invalidation.
- **`apps/mobile/src/components/ui/DateInputField.tsx`:** Accessible trigger (`DD/MM/YYYY` Vietnamese display, `Chưa chọn` when empty, inline `Xóa` button when `allowClear`, immutable per-open operation identity `opId` keyed to `sessionKey` + `isMountedRef` so close/reopen, `disabled`, `sessionKey` changes, and unmount permanently invalidate stale confirm/clear/cancel callbacks).
- **`apps/mobile/src/components/ui/LocalDateTimeInputField.tsx`:** Pairs `DateInputField` (calendar date picker) with an `HH:mm` `<TextInput>` for local date-time (`YYYY-MM-DDTHH:mm`) forms, preserving timezone selection and DST offset disambiguation.

### Not Implemented / Out of Scope in This Batch
- Timezone UX simplification (`P7-011`) and meal selector size/copy (`P7-009` meal/timezone parts), `P7-012`/`P7-016` app-wide font/copy, `P7-004` Figma light/dark redesign, `ACE`, OpenAPI/Flyway/backend changes, and Admin Web (`apps/admin-web`, out of `P7-013` Mobile+Mobile-Web scope).
- Live device QA on `Pixel_7` / Expo Web runtime visual & keyboard check: **`DEFERRED_FOR_RAM`** (emulator/QA stack not launched; layout width/overflow verified programmatically via `useWindowDimensions` mock at `360×640`).

## Inventory of Date & Date-Time Controls in `apps/mobile`

| File & Line (approx) | Field / Purpose | Kind | Actor | Optional / Bounds / Notes | Implementation |
| --- | --- | --- | --- | --- | --- |
| `StudentOnboardingScreen.tsx:212` | `dateOfBirth` (Ngày sinh) | Date-only (`YYYY-MM-DD` / `null`) | Student | Optional, `allowClear`, `maxDate=today`, không áp trần dưới 1920, `initialBrowseDate=1995-06-15`, no default when empty | `DateInputField` (`dob-input`) |
| `StudentProfileScreen.tsx:435` | `editDob` (Ngày sinh) | Date-only (`YYYY-MM-DD` / `null`) | Student | Optional (`PATCH` sends `null` on clear), `maxDate=today`, không áp trần dưới 1920, view-mode `view-dob` preserved | `DateInputField` (`edit-dob-input`) |
| `NutritionScreen.tsx:200` | `day` (Ngày ghi) | Date-only | Student | Empty until chosen; resets `confirmedContext=false` on change | `DateInputField` (`nutrition-day-input`) |
| `FoodAnalysisScreen.tsx:804` | `day` (Ngày ghi) | Date-only | Student | Coherent initialization in `params.timezone \|\| user.timezone`; `localTime` (`HH:mm`) untouched | `DateInputField` (`food-analysis-day-input`) |
| `NutritionStrategyScreen.tsx:238,291,351` | `effectiveFrom`, Student `day`, Trainer `day` | Date-only | Student / Trainer | Empty initially; preserves timeline preview & frozen day confirmation semantics | `DateInputField` (`strategy-effective-from-input`, `strategy-student-day-input`, `strategy-trainer-day-input`) |
| `TrainerNutritionScreen.tsx:35` | `day` (Ngày xem) | Date-only | Trainer | Empty initially; read-only log lookup preserved | `DateInputField` (`trainer-nutrition-day-input`) |
| `ProgressSummaryScreen.tsx:539-559` | `fromDate`, `toDate` | Date-only | Student / Trainer | Pre-filled by presets (`7`/`14`/`30`); picker sets `CUSTOM`; keeps `fromDate <= toDate` check | `DateInputField` (`from-date-input`, `to-date-input`) |
| `AiWorkoutProposalModal.tsx:580` | `startDate` (Ngày bắt đầu giáo án) | Date-only | Student / Trainer | Defaults to tomorrow (`getTomorrowLocalDate()`); clears field error on change; resets on `(visible, studentId)` with `formSessionSeq` isolation | `DateInputField` (`ai-proposal-start-date-input`) |
| `RecurrenceCreateScreen.tsx:145,168,179` | `anchor` (Thứ Hai series) & `firstLocalStart`/`End` | Date-only (`mondayOnly`) + Local date-time | Student / Trainer | Keeps Monday rule, 30-day job note, and explicit `localStart`/`End` | `DateInputField` (`series-anchor`) + `LocalDateTimeInputField` |
| `StudentConfirmBatchScreen.tsx:179,211` | `anchor` (Thứ Hai đợt lịch) & `Start`/`End` | Date-only (`mondayOnly`) + Local date-time | Student | Keeps Monday check, DST `validProposalOffsets`, future `90d` note & Appointment lock | `DateInputField` (`week-anchor`) + `LocalDateTimeInputField` |
| `TrainerCreateScheduleProposalScreen.tsx:201,235` | `anchor` (Thứ Hai đề xuất) & `Start`/`End` | Date-only (`mondayOnly`) + Local date-time | Trainer | Keeps Monday validation, DST offsets, `COACH_REQUIRED` Appointment matching | `DateInputField` (`week-anchor`) + `LocalDateTimeInputField` |
| `StudentWorkoutScheduleDetailScreen.tsx:239` | Reschedule `Start`/`End` | Local date-time | Student | Keeps IANA timezone, DST offsets, and version review guards | `LocalDateTimeInputField` |
| `TrainerCreateChangeProposalScreen.tsx:149` | Change proposal `Start`/`End` | Local date-time | Trainer | Keeps IANA timezone, DST offsets, and `COACH_REQUIRED` block | `LocalDateTimeInputField` |
| `AppointmentScreens.tsx:86,105,213,225` | Filter `from`/`until` & Proposal `localStart`/`End` | ISO instant filter + Local date-time | Student / Trainer | Unchanged filter date preserves exact ISO instant; date change preserves time-of-day in timezone via full 7-part round-trip (`replaceDateInIsoTimestamp`), rejecting nonexistent DST gap wall times without fallback to start-of-day while displaying an inline alert; reachable empty-`until` branch stores `inclusiveDate` so UI displays selected day while query sends exclusive next-day `startOf('day')` (`localDateRangeBoundaryIso`); proposal keeps explicit timezone + DST offsets | `DateInputField` + `LocalDateTimeInputField` |

## P2 Review Fixes & Regression Summary

1. **P2-1 Stale picker callbacks (`DateInputField`, `DatePickerModal`, `AiWorkoutProposalModal`):** Replaced mutable latest-token checks with an immutable per-open `opId` bound into callback closures, `isMountedRef` cleanup on unmount, synchronous `modalOpId` state inside `DatePickerModal`, and `formSessionSeq` in `AiWorkoutProposalModal`'s `sessionKey`.
2. **P2-2 Pre-1920 DOB & silent day clamping (`DatePickerModal`, `StudentOnboardingScreen`, `StudentProfileScreen`):** Removed `yearRange={{ min: 1920 }}` on DOB screens, stopped `yearRange.min` from clamping valid pre-1920 values (`minYear` expands dynamically + `"Xem thêm năm trước"` button), and unified `handleSelectMonth`/`handleSelectYear` into `applyBrowseYearMonthChange` which clears `draftDate` and shows an alert notice when a day does not exist in the target month instead of silently clamping `31 -> 28/29`.
3. **P2-3 Appointment filter time/offset, DST gap round-trip & empty-`until` branch (`dateInputUtils`, `AppointmentScreens`):**
   - `localDateRangeBoundaryIso(..., 'end', tz)` uses exclusive next-day `startOf('day')` matching backend SQL `start_at < until`.
   - `replaceDateInIsoTimestamp` checks full 7-part local component round-trip (`year`, `month`, `day`, `hour`, `minute`, `second`, `millisecond`) in both IANA and local TZ branches so nonexistent DST spring-forward gap times (e.g. `02:30:45.123` on `2026-03-08` in `America/New_York`) return `null` instead of normalizing to `03:30`.
   - `AppointmentHomeScreen` extracts `applyDateFilterChange`: replacement failure (`null`) never falls back to `localDateRangeBoundaryIso`, preserves the prior filter/query untouched, surfaces an accessible inline alert (`error` on `DateInputField`), and clears automatically on the next valid date.
   - **Empty-`until` reachability conclusion:** Because `AppointmentHomeScreen` initializes `from`/`until` asynchronously inside `useEffect` via `setTimeout(..., 0)` (not synchronous), the empty-`until` branch (`until === ''`) is programmatically/UI reachable before that macrotask executes (without changing `sessionKey`, so an already-opened picker remains a valid, non-stale operation; code-inspection/controlled-timer finding, not claimed as an observed physical device defect). Tracking `{ iso, inclusiveDate }` separates inclusive UI display (`inclusiveDate`) from exclusive query boundary (`iso`) only for that whole-day selection mode, without altering normal timestamp filter semantics or subtracting 1 day globally; the background mount timer skips overwriting an explicit user selection (`prev.iso ? prev : ...`).
4. **P2-4 360px touch-target width & horizontal overflow (`DatePickerModal`):** Added `useWindowDimensions()` responsive spacing/cell sizing rendering explicit 7-cell week rows (`46px` width and `>= 44px` height per day cell at `360px` viewport width with zero horizontal overflow and zero overlapping `hitSlop`) plus `ScrollView` wrapping inside the dialog.

## Verification & File Hashes (SHA-256)

### Automated Checks (Latest Run After All Source/Test Edits)
- **Pre-fix Regression Capture (before source edits):**
  - R1: `npx jest src/__tests__/dateInputs.test.tsx src/__tests__/AiWorkoutProposalModal.test.tsx src/__tests__/appointmentScreens.test.tsx src/__tests__/studentProfileScreen.test.tsx --runInBand --no-color` plus isolated `AiWorkoutProposalModal` `-t "P2-1"` (`--testTimeout=30000`) reproduced FAILs across P2-1..P2-4.
  - R2: `npx jest src/__tests__/dateInputs.test.tsx src/__tests__/appointmentScreens.test.tsx --runInBand --testTimeout=30000 --no-color` reproduced FAILs before R2 fix: `replaceDateInIsoTimestamp('2026-03-07T07:30:45.123Z', '2026-03-08', 'America/New_York')` returned `"2026-03-08T07:30:45.123Z"` instead of `null`; `AppointmentHomeScreen` DST gap triggered an extra normalized query (`Expected: 1`, `Received: 2`); empty-`until` displayed `"16/11/2026"` instead of `"15/11/2026"`.
- **Focused Post-fix Jest (`dateInputs.test.tsx` + `appointmentScreens.test.tsx`, `--runInBand --testTimeout=30000`):** `Test Suites: 2 passed, 2 total`, `Tests: 18 passed, 18 total`, **EXIT_CODE=0**.
- **Post-fix Jest (`--runInBand --testTimeout=30000`, 15 suites):** `Test Suites: 15 passed, 15 total`, `Tests: 197 passed, 197 total`, **EXIT_CODE=0**.
  - Suites: `dateInputs`, `studentOnboardingFlow`, `studentProfileScreen`, `nutritionScreens`, `nutritionStrategyScreen`, `foodAnalysisScreen`, `ProgressSummaryScreen`, `AiWorkoutProposalModal`, `studentDirectScheduling`, `trainerCreateScheduleProposal`, `workoutScheduleChange`, `trainerChangeProposalScreens`, `appointmentScreens`, `workoutRecurrenceScreens`, `accessibilityAndTokens`.
- **TypeScript (`npx tsc --noEmit`):** **EXIT_CODE=0**.
- **Targeted ESLint (`npx eslint` on 31 touched source/test TS/TSX files):** **EXIT_CODE=0** (`0` errors, `0` warnings).
- **Full Mobile Lint (`npm run lint` / `expo lint`):** **EXIT_CODE=0** (`0` errors; 5 pre-existing `@typescript-eslint/no-require-imports` warnings in untouched `CurrentGoalScreen.test.tsx`).
- **UX Docs Validation (`node tools/validate-ux-docs.mjs`):** `UX documentation validation passed (18 required files).`, **EXIT_CODE=0**.
- **Git Hygiene (`git diff --check`):** **EXIT_CODE=0**.
- **Backend / Live QA / Pixel_7 Visual & Keyboard Check:** **NOT RUN / `DEFERRED_FOR_RAM`** (no backend contract/Flyway changes made).

### File Hashes (SHA-256)
- `1C6E3BB4B3C494868A5BAADC4F915F9ADC15FF24E56DE4FBF48E5D65E8A78355`  `apps/mobile/src/features/shared/dateInputUtils.ts`
- `5AD6D91456FF16C577D14BDBA509B26A5F3A32D0CBB383B68D4F27FD8AA30E1D`  `apps/mobile/src/components/ui/DatePickerModal.tsx`
- `C3F027040DA569294A32B3E457FEA105023A53040A946BC06D7DD292F06EF0E6`  `apps/mobile/src/components/ui/DateInputField.tsx`
- `B4F74AC6AD1F1DC23990C30AE4915CD8BE939F10FE2B12DE90E19D2796A068B5`  `apps/mobile/src/components/ui/LocalDateTimeInputField.tsx`
- `19D08B30788C37220AD1DFA6233528B52A793F71FCAA7F5205EC7373C54D606E`  `apps/mobile/src/features/student/StudentOnboardingScreen.tsx`
- `461B99AEF5EF44994800651E2EF60F17B70BAD31E77E68634B1C9DD07C2F4A17`  `apps/mobile/src/features/student/StudentProfileScreen.tsx`
- `3AE7DD597492DB52175B9F44690ADAD5F9B3C4D98204AB7508ED3F5DB6304FD0`  `apps/mobile/src/features/nutrition/NutritionScreen.tsx`
- `7DD8470D6EFDC9123D3A4DE566870A4C04C1612FE905D955FBAD7247EA7374A5`  `apps/mobile/src/features/nutrition/FoodAnalysisScreen.tsx`
- `7CA239596D6A4E90B3FA0C3ACA3824A52B8255B766626240F5D77099E17C6267`  `apps/mobile/src/features/nutrition/NutritionStrategyScreen.tsx`
- `A695B8535285583A0E3A58653A4B379DEF704D13ABFB763728C46FABAF0FCEBB`  `apps/mobile/src/features/nutrition/TrainerNutritionScreen.tsx`
- `521BF84DDA6D64055ADFA07600BB55E54E1E6D78AA678FC308CE505A43190A87`  `apps/mobile/src/features/progress-summary/ProgressSummaryScreen.tsx`
- `89F59981D373D403D27E962AD9EDF0BA5D0405EF5FE4E50D5D48E15C13CB6C5A`  `apps/mobile/src/features/workout-plan/components/AiWorkoutProposalModal.tsx`
- `090B2AE72DC40E8ADEAA3E2B4F4F202742E518899063657FE9462814FF318893`  `apps/mobile/src/features/appointments/AppointmentScreens.tsx`
- `9F8F1583D4A661BBE746C2CD9B239196A7161909883E7901F06052A9AA304CEF`  `apps/mobile/src/features/workout-recurrence/RecurrenceCreateScreen.tsx`
- `35C6C24987391D30F7325BAB69D589741FCD25740E229645F33A406FA7B51B90`  `apps/mobile/src/features/workout-schedule/StudentConfirmBatchScreen.tsx`
- `F90BD5E4970010A507D6FF283DE0688AEFBD742841F35FA75CEB374A9695D3CA`  `apps/mobile/src/features/workout-schedule/StudentWorkoutScheduleDetailScreen.tsx`
- `FC199CFD62E766B228158D2E208306DDFEC2664831E2375BE0E4365C9AE77DBC`  `apps/mobile/src/features/workout-schedule-change-proposals/TrainerCreateChangeProposalScreen.tsx`
- `CEBF06EC75970CCFA459577957957CC6318F4F9195AEA89D8DC02E22CCB85602`  `apps/mobile/src/features/workout-schedule-proposals/TrainerCreateScheduleProposalScreen.tsx`
- `542A09111C3380C7E460AE731D549055C2EF8C8C06DD93F8EB1EB78B3CA07248`  `apps/mobile/src/__tests__/dateInputs.test.tsx`
- `80EA6650961ECB395614966A1911BBF46531FDBFC26A805FE44948646D70DCBC`  `apps/mobile/src/__tests__/studentOnboardingFlow.test.tsx`
- `B10C3F44F03886B5918E5926F8C4113AACB585550FCE78352FFACA688DCB54B2`  `apps/mobile/src/__tests__/studentProfileScreen.test.tsx`
- `9DDA731C739B37238A23C34A7EF99EB66EECD3D4EA01F152A10C25E6CCA3B26F`  `apps/mobile/src/__tests__/nutritionScreens.test.tsx`
- `D7F597DA1BFEF43DB08B31F40DDF65578AED9C943FA144FF771342120DB9FB99`  `apps/mobile/src/__tests__/nutritionStrategyScreen.test.tsx`
- `F5BCEBFF0E0FD04809D1FE7FCEDAE0C461D94297D3FEDDFB31E90932862DE722`  `apps/mobile/src/__tests__/foodAnalysisScreen.test.tsx`
- `ABFB7FFB44D824DF192649909858A07AD4BB66A8CC32F27335C19474C6B2D24C`  `apps/mobile/src/__tests__/ProgressSummaryScreen.test.tsx`
- `91B965F5C2373856AB6C805A81CF8E3EB150E63543C70A4B231675FCFB83C98B`  `apps/mobile/src/__tests__/AiWorkoutProposalModal.test.tsx`
- `5E3920A63EC37340785AC93625140A186DB82E8D90AEC9DE280FDA3161D540B2`  `apps/mobile/src/__tests__/studentDirectScheduling.test.tsx`
- `4D626D66506FAD18F76914AE664E3EE0F88923E19284F2F11BA5093E48F9C19F`  `apps/mobile/src/__tests__/trainerCreateScheduleProposal.test.tsx`
- `C736C5D22E9213EE37C9D6015298A92DFD03564C73EF404EC9EC690BC5D98BE3`  `apps/mobile/src/__tests__/workoutScheduleChange.test.tsx`
- `E18A6C39EBD5C3C07C350C052CE4C2614D389EF6B2BE454509610976ACACD665`  `apps/mobile/src/__tests__/trainerChangeProposalScreens.test.tsx`
- `26C872EB7720A27B992632619D0650C9944D0558346EDFE30F5EDC440E1C939C`  `apps/mobile/src/__tests__/appointmentScreens.test.tsx`

## Independent review and main integration — 2026-11-26

- Integration branch: `codex/fix-p7-003-009-013-date-inputs`; current remote/main baseline: `7fbedab6f6aff83a21f351e063d6948d62cd4e71`.
- The exact scoped delta applies cleanly to main. All 31 source/test hashes above match both the implementation checkout and integration checkout; no product logic was changed during integration. Source handoff SHA-256 before this review addition: `40EC8CE7002F679652FFDD5A22B1E0D4F99BC4034070E01CF0D952F9EAD1D161`.
- Appointment DST gap: helper returns null; real screen source keeps the prior filter/query, presents an accessible error, accepts a subsequent valid date, and clears the error. Same-day timestamp strings retain precision verbatim.
- Empty-until controlled timer: inclusive display and exclusive ISO boundary remain consistent on reopen, same-day confirm and another date selection; delayed initialization preserves explicit selection. Existing timestamp filters retain timestamp semantics.
- Callback lifecycle, pre-1920 DOB and 360px layout conclusions remain valid with unchanged hashes. No blocking finding remains in this reviewed scope.
- Independently executed on main + patch: 15 Jest suites, 197/197 PASS, exit 0 (`--runInBand --testTimeout=30000`); independent source harness 23/23 PASS, exit 0; Mobile typecheck and full Expo lint exit 0. Full lint retains 5 existing `@typescript-eslint/no-require-imports` warnings in untouched `CurrentGoalScreen.test.tsx` (2126, 2169, 2227, 2277, 2328). Node heap limited with `NODE_OPTIONS=--max-old-space-size=1280`; heavy checks run sequentially.
- UX documentation validation: 18 required files, exit 0; diff check exit 0. Backend local, Docker, emulator and QA stack were not run.
- Independent evidence: `docs/08-ai/artifacts/p7-003-009-013-independent-review-20261126/`. Original pre-fix FAIL logs are retained separately from passing post-fix evidence. The harness loads actual component/helper/screen source with mocked platform/auth/query/transport boundaries; it is not native-device or Web-runtime QA.
- Android/Web runtime, visual, keyboard and Pixel_7 verification remain **DEFERRED_FOR_RAM**. P7-011 timezone UX, remaining P7-009 work, Figma/ACE and release acceptance are outside this checkpoint. New PR CI will be reported in the PR body on its exact SHA; local tests are not CI results. Draft PR only; no merge authorized by this checkpoint.
