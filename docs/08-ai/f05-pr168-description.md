### Summary

This PR implements and refines Phase 2 Feature F05 (**AI Workout Rescheduling**) on Mobile along with appointment coordination, authority guards, heuristic presentation, retry identity preservation, and appointment-to-schedule routing according to approved contract and architecture guidelines.

### Scope & Highlights

1. **Contract & Types ([aiWorkoutReschedule.ts](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/types/aiWorkoutReschedule.ts))**:
   - Complete TypeScript schemas matching OpenAPI and `f05-workout-reschedule.schema.json`.
   - Distinct lifecycle status (`PENDING`, `APPLIED`, `REJECTED`, `EXPIRED`, `CANCELLED`) and decision authority (`STUDENT`, `TRAINER`).

2. **API Service Client ([aiWorkoutRescheduleApi.ts](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/services/aiWorkoutRescheduleApi.ts))**:
   - Endpoints for `generate()`, `getDetail()`, `accept()`, and `reject()`.
   - Vietnamese error mappings for 409 overlaps, version conflicts, coach-required supervision constraints, and 403 authority errors.

3. **Core Reschedule Modal ([AiWorkoutRescheduleModal.tsx](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/features/workout-schedule/components/AiWorkoutRescheduleModal.tsx))**:
   - Shared component for Student and Trainer journeys.
   - Heuristic slot picker (`MORNING` [09:00], `AFTERNOON` [14:00], `EVENING` [18:00]) with explicit disclaimer copy clarifying heuristic suggestions vs confirmed availability.
   - Structured review card with UTC offsets, local times, IANA timezone, AI reasoning, and dynamic countdown of remaining minutes until `proposal.expiresAt`.
   - **Idempotent Retry Identity & Expired Replay**: Reuses exact same `commandKey` across network timeouts (status 0). If a retry is pending (`pendingAcceptCommandKey`), `canAccept` allows replaying even if client clock passed `expiresAt`, adhering to backend Step 3 replay before Step 4 expiry guard. Displays safe replay receipt (`replayed: true`).
   - **Stale Async Guards**: React refs (`activeStudentIdRef`, `activeOccurrenceIdRef`, `isMountedRef`) prevent out-of-order or late async responses from leaking when student, occurrence, or modal state changes.

4. **Screen Integration & Authority Guards**:
   - **Student Workout Detail ([StudentWorkoutScheduleDetailScreen.tsx](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/features/workout-schedule/StudentWorkoutScheduleDetailScreen.tsx))**: Added "Đề xuất dời lịch tập (AI)" button, dynamically resolves `coachingMode` via `useRelationships`, keeps receipt modal open until explicitly closed, then navigates to replacement entity.
   - **Trainer Workout Schedule ([TrainerWorkoutScheduleScreen.tsx](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/features/workout-schedule/TrainerWorkoutScheduleScreen.tsx))**: Added "Đề xuất dời lịch (AI)" button guarded strictly by `MANAGE` authority on `WORKOUT_PLAN` (distinguished from `canContribute`), preserves receipt visibility.
   - **Appointment Coordination ([AppointmentScreens.tsx](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/features/appointments/AppointmentScreens.tsx))**:
     - Student: Routes directly to linked workout occurrence (`/workouts/schedule/${item.plannedWorkoutId}`) to generate fresh F05 proposal.
     - Trainer: Dynamically resolves active coaching relationship for `studentId` using `useRelationships` and routes directly to `/coaching/${activeRelationship.id}/workout-schedule` (preserving student and occurrence context), with fallback to `/coaching`.
   - **Web Environment Configuration ([env.ts](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/config/env.ts))**: Automatically maps Android emulator host `10.0.2.2` to `localhost` when running on Web (`platformOS === 'web'`).

5. **Backend Runtime Hygiene & Provenance Safeguards**:
   - **Timezone Provenance (`F05ContextBuilder.java`)**: Fails fast with `AiRunLifecycleConflictException` if `occurrence.timezone()` is missing or blank, preventing silent fallbacks that would mask legacy records incapable of being rescheduled.
   - **Sanitized Logging (`HttpF05AiServiceClient.java`)**: Stripped response body dumping on 4xx errors to eliminate risk of logging personal data or validation payloads.
   - **Fixture Isolation (`verify_f05_mobile_live_integration.py` & `seed_f05_dev_fixtures.sql`)**: Safe SQL seed with valid timezones and dynamic test occurrence generation with guaranteed cleanup in `finally:`.

### Acceptance & Verification Matrix

| Acceptance / Test Scenario | Evidence / Verification Level | Status |
|---|---|:---:|
| **T01 / T27 — PROPOSAL Lifecycle** | Component test `AiWorkoutRescheduleModal.test.tsx` (11 tests); Backend test `AiWorkoutRescheduleIntegrationTest.java` (5 tests); Live API Scenario 1; Interactive UI Scenario 1 ([ui_01_student_self_directed_receipt.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_01_student_self_directed_receipt.png)) | **PASS (Mock, Integration, Live API & Expo Web UI)** |
| **T02 — Mode Authority Guards** | Component test `AiWorkoutRescheduleModal.test.tsx` (Student blocked in `HUMAN_COACH`, Trainer allowed with `MANAGE`, Student allowed in `SELF_DIRECTED`); Live API Scenario 2 & 3; Interactive UI Scenario 2 ([ui_02_student_human_coach_blocked.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_02_student_human_coach_blocked.png)) | **PASS (Component, Live API & Expo Web UI)** |
| **T03 / T04 — Trainer Authority & Scope** | Component test `trainerWorkoutScheduleScreen.test.tsx` (hides button without `MANAGE`); API client mapping for 403; Live API Scenario 3; Interactive UI Scenario 3 ([ui_03_trainer_apply_success.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_03_trainer_apply_success.png)) | **PASS (Component, Live API & Expo Web UI)** |
| **T05 — Version Drift & Conflicts** | Component test `AiWorkoutRescheduleModal.test.tsx` (409 triggers fresh proposal prompt); Live API Scenario 5 (Controlled Fault Injection); Interactive UI Scenario 5 ([ui_05_conflict_toast.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_05_conflict_toast.png)) | **PASS (Component, Live API & Expo Web UI)** |
| **T06 / T25 — Idempotent Retry & Replay** | Component test `AiWorkoutRescheduleModal.test.tsx` (timeout replay with same `commandKey` & expired replay); Live API Scenario 7 & 8; Interactive UI Scenario 7 ([ui_07_ui_retry_response_drop.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_07_ui_retry_response_drop.png) - network drop simulated, real UI retry button clicked with matching commandKey, safe replay receipt displayed, DB count=1) | **PASS (Mock, Integration, Live API & Expo Web UI)** |
| **T09 — History Preservation** | Applied receipt displays `resultingEntityId` without altering past completed workouts; DB status readback verified; Interactive UI Scenario 4 ([ui_04_reject_preserved.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_04_reject_preserved.png)) | **PASS (Component, Live API & Expo Web UI)** |
| **T10 / BL-03 — Appointment Coordination** | Component test `appointmentScreens.test.tsx` (role-based routing to linked workout & active relationship schedule); Live API Scenario 6 (appointment drift 409 fault injection); Interactive UI Scenario 6 ([ui_06_appointment_two_sided_flow.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_06_appointment_two_sided_flow.png) - full bilateral appointment reschedule, trainer UI navigation to student schedule, F05 proposal generated and applied, DB appointment relinked to successor with version 2) | **PASS (Component, Live API & Expo Web UI)** |
| **T23 — Proposal Expiration TTL** | Dynamic remaining minutes countdown & blocking new accept after 30-min expiration; Live API Scenario 8 | **PASS (Component, Live API & Expo Web UI)** |
| **Stale Async Guard** | Component test `AiWorkoutRescheduleModal.test.tsx` (mount/unmount and identity switch safeguards with React refs) | **PASS (Component Tests)** |
| **Live API & Domain Integration** | Full 8-scenario run (`verify_f05_mobile_live_integration.py`) verifying Mobile API contract -> Spring Boot (8080) -> FastAPI (8000) -> PostgreSQL (5433) | **PASS (8/8 Scenarios)** |
| **Expo Web Interactive UI Verification** | End-to-end interactive UI verification via Google Chrome Chromium / Playwright (`verify_f05_mobile_ui_interactive.py`) against live services | **PASS (7/7 Scenarios)** |
| **Native Device Runtime (Physical/AVD)** | Testing on physical Android/iOS devices or Android Virtual Devices (AVDs) | **OPEN / UNVERIFIED** (Headless Windows CLI without display) |
| **Real LLM Model Validation** | Evaluation with production OpenAI/Gemini models | **OPEN / Non-goal** (simulated provider used per guidelines) |

### Automated Verification

- **Mobile Test Suites (96 suites, 831 tests PASS)**:
  - `aiWorkoutRescheduleApi.test.ts` (18/18 PASS)
  - `AiWorkoutRescheduleModal.test.tsx` (11/11 PASS)
  - `workoutScheduleChange.test.tsx` (11/11 PASS)
  - `trainerWorkoutScheduleScreen.test.tsx` (2/2 PASS)
  - `appointmentScreens.test.tsx` (6/6 PASS)
  - `envConfig.test.ts` (3/3 PASS)
  - 90 other mobile test suites (780/780 PASS)
- **Backend Unit Tests (34/34 PASS)**:
  - `AiWorkoutRescheduleServiceUnitTest` (24/24 PASS)
  - `F05ContextBuilderUnitTest` (10/10 PASS, including timezone provenance test)
- **AI Service Pytest Suite**: 90/90 PASS
- **TypeScript (`tsc --noEmit`)**: 0 errors
- **Linter (`expo lint`)**: 0 errors, 0 warnings
- **Contracts (`redocly` & Ajv 2020)**: All 10 schemas and 48 fixtures valid
- **UX Documentation**: All 18 inventory files valid
- **Git diff**: Clean format and line endings

### Documentation & Evidence Artifacts
- Handover Document: [`docs/08-ai/f05-mobile-handover-20261123.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/f05-mobile-handover-20261123.md)
- Live Integration Report: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_report_20261123.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_report_20261123.md)
- Live API Evidence JSON: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json)
- Interactive UI Evidence JSON: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_ui_interactive_20261124.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_ui_interactive_20261124.json)
- Screenshots:
  - [ui_01_student_self_directed_receipt.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_01_student_self_directed_receipt.png)
  - [ui_02_student_human_coach_blocked.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_02_student_human_coach_blocked.png)
  - [ui_03_trainer_apply_success.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_03_trainer_apply_success.png)
  - [ui_04_reject_preserved.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_04_reject_preserved.png)
  - [ui_05_conflict_toast.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_05_conflict_toast.png)
  - [ui_06_appointment_two_sided_flow.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_06_appointment_two_sided_flow.png)
  - [ui_07_ui_retry_response_drop.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_07_ui_retry_response_drop.png)

### Open Boundaries / Non-goals for this PR
- **Live LLM Provider**: Remains simulated / mock integration; real provider evaluation is out of scope.
- **Availability Engine & Recovery Rules (D06 / OD-3)**: Retains heuristic slots; full biological recovery rules and calendar availability search remain open.
- **Native Physical Device Testing**: Testing on physical Android/iOS hardware remains open for release phase manual QA.
- **Sign-off**: Official acceptance of F05 / P2-M4 remains open for Project Owner review.

*Note: This is a draft PR opened for code review. Merge or deployment is NOT requested.*
