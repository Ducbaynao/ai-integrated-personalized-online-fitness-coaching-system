### Summary

This PR implements and refines Phase 2 Feature F05 (**AI Workout Rescheduling**) on Mobile along with appointment coordination, authority guards, heuristic presentation, retry identity preservation, and appointment-to-schedule routing according to approved contract and architecture guidelines.

### Scope & Highlights

1. **Contract & Types (`apps/mobile/src/types/aiWorkoutReschedule.ts`)**:
   - Complete TypeScript schemas matching OpenAPI and `f05-workout-reschedule.schema.json`.
   - Distinct lifecycle status (`PENDING`, `APPLIED`, `REJECTED`, `EXPIRED`, `CANCELLED`) and decision authority (`STUDENT`, `TRAINER`).

2. **API Service Client (`apps/mobile/src/services/aiWorkoutRescheduleApi.ts`)**:
   - Endpoints for `generate()`, `getDetail()`, `accept()`, and `reject()`.
   - Vietnamese error mappings for 409 overlaps, version conflicts, coach-required supervision constraints, and 403 authority errors.

3. **Core Reschedule Modal (`apps/mobile/src/features/workout-schedule/components/AiWorkoutRescheduleModal.tsx`)**:
   - Shared component for Student and Trainer journeys.
   - Heuristic slot picker (`MORNING` [09:00], `AFTERNOON` [14:00], `EVENING` [18:00]) with explicit disclaimer copy clarifying heuristic suggestions vs confirmed availability.
   - Structured review card with UTC offsets, local times, IANA timezone, AI reasoning, and dynamic countdown of remaining minutes until `proposal.expiresAt`.
   - **Idempotent Retry Identity & Expired Replay**: Reuses exact same `commandKey` across network timeouts (status 0). If a retry is pending (`pendingAcceptCommandKey`), `canAccept` allows replaying even if client clock passed `expiresAt`, adhering to backend Step 3 replay before Step 4 expiry guard. Displays safe replay receipt (`replayed: true`).
   - **Stale Async Guards**: Prevents out-of-order or late async responses from leaking when student, occurrence, or modal state changes.

4. **Screen Integration & Authority Guards**:
   - **Student Workout Detail (`StudentWorkoutScheduleDetailScreen.tsx`)**: Added "Đề xuất dời lịch tập (AI)" button, modal hookup, and navigation to replacement entity on apply.
   - **Trainer Workout Schedule (`TrainerWorkoutScheduleScreen.tsx`)**: Added "Đề xuất dời lịch (AI)" button guarded strictly by `MANAGE` authority on `WORKOUT_PLAN` (distinguished from `canContribute`).
   - **Appointment Coordination (`AppointmentScreens.tsx`)**:
     - Student: Routes directly to linked workout occurrence (`/workouts/schedule/${item.plannedWorkoutId}`) to generate fresh F05 proposal.
     - Trainer: Dynamically resolves active coaching relationship for `studentId` using `useRelationships` and routes directly to `/coaching/${activeRelationship.id}/workout-schedule` (preserving student and occurrence context), with fallback to `/coaching`.

5. **Backend Runtime Hygiene & Provenance Safeguards**:
   - **Timezone Provenance (`F05ContextBuilder.java`)**: Fails fast with `AiRunLifecycleConflictException` if `occurrence.timezone()` is missing or blank, preventing silent fallbacks that would mask legacy records incapable of being rescheduled.
   - **Sanitized Logging (`HttpF05AiServiceClient.java`)**: Stripped response body dumping on 4xx errors to eliminate risk of logging personal data or validation payloads.
   - **Fixture Isolation (`verify_f05_mobile_live_integration.py`)**: Removed blanket deletions/updates on shared accounts; uses unused session slots and dynamic safe appointment slots with automatic teardown in `finally:`.

### Acceptance & Verification Matrix

| Acceptance / Test Scenario | Evidence / Verification Level | Status |
|---|---|:---:|
| **T01 / T27 — PROPOSAL Lifecycle** | Component test `AiWorkoutRescheduleModal.test.tsx` (11 tests); Backend test `AiWorkoutRescheduleIntegrationTest.java` (5 tests); Live API Scenario 1 | **PASS (Mock, Integration & Live)** |
| **T02 — Mode Authority Guards** | Component test `AiWorkoutRescheduleModal.test.tsx` (Student blocked in `HUMAN_COACH`, Trainer allowed with `MANAGE`, Student allowed in `SELF_DIRECTED`); Live API Scenario 2 & 3 | **PASS (Component & Live Tests)** |
| **T03 / T04 — Loss of Authority & Scope** | Component test `trainerWorkoutScheduleScreen.test.tsx` (hides button without `MANAGE`); API client mapping for 403; Live API Scenario 3 | **PASS (Component & Live Tests)** |
| **T05 — Version Drift & Conflicts** | Component test `AiWorkoutRescheduleModal.test.tsx` (409 triggers fresh proposal prompt); Live API Scenario 5 (Controlled Fault Injection) | **PASS (Component & Live Tests)** |
| **T06 / T25 — Idempotent Retry & Replay** | Component test `AiWorkoutRescheduleModal.test.tsx` (timeout replay with same `commandKey` & expired replay); Live API Scenario 7 & 8 | **PASS (Mock, Integration & Live)** |
| **T09 — History Preservation** | Applied receipt displays `resultingEntityId` without altering past completed workouts; DB status readback verified | **PASS (Component & Live Tests)** |
| **T10 / BL-03 — Appointment Coordination** | Component test `appointmentScreens.test.tsx` (role-based routing to linked workout & active relationship schedule); Live API Scenario 6 (Controlled Fault Injection) | **PASS (Component & Live Tests)** |
| **T23 — Proposal Expiration TTL** | Dynamic remaining minutes countdown & blocking new accept after 30-min expiration; Live API Scenario 8 | **PASS (Component & Live Tests)** |
| **Stale Async Guard** | Component test `AiWorkoutRescheduleModal.test.tsx` (mount/unmount and identity switch safeguards) | **PASS (Component Tests)** |
| **Live API & Domain Integration** | Full 8-scenario run (`verify_f05_mobile_live_integration.py`) verifying Mobile API contract -> Spring Boot (8080) -> FastAPI (8000) -> PostgreSQL (5433) | **PASS (8/8 Scenarios)** |
| **Mobile UI Live Runtime** | Interactive UI execution on device/emulator (Login -> Schedule -> Modal -> Accept -> Toast) | **NOT RUN / BLOCKED** (Windows CLI headless environment without display for Android Emulator and without browser runner Playwright/Puppeteer in repo) |
| **Real LLM Model Validation** | Evaluation with production OpenAI/Gemini models | **OPEN / Non-goal** (simulated provider used per guidelines) |

### Automated Verification

- **Mobile Test Suites (48/48 PASS)**:
  - `aiWorkoutRescheduleApi.test.ts` (18/18 PASS)
  - `AiWorkoutRescheduleModal.test.tsx` (11/11 PASS)
  - `workoutScheduleChange.test.tsx` (11/11 PASS)
  - `trainerWorkoutScheduleScreen.test.tsx` (2/2 PASS)
  - `appointmentScreens.test.tsx` (6/6 PASS)
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
- Detailed handover document: [`docs/08-ai/f05-mobile-handover-20261123.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/f05-mobile-handover-20261123.md)
- Live Integration Report: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_report_20261123.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_report_20261123.md)
- JSON Evidence: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json)

### Open Boundaries / Non-goals for this PR
- **Live LLM Provider**: Remains simulated / mock integration; real provider evaluation is out of scope.
- **Availability Engine & Recovery Rules (D06 / OD-3)**: Retains heuristic slots; full biological recovery rules and calendar availability search remain open.
- **Sign-off**: Official acceptance of F05 / P2-M4 remains open for Project Owner review.

*Note: This is a draft PR opened for code review. Merge or deployment is NOT requested.*
