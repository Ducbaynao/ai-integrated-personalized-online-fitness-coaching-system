# PR Description: Phase 1 Person A — AI Lockdown, Trainer Verification Lifecycle & Fitness Goal Management

**Branch:** `feat/phase1-a-trainer-verification`  
**Base:** `a7143be` on `main`  
**Target:** Local PR Description (No remote push or PR creation per git authorization policy)  
**Author:** Person A (Backend, Identity, Verification, Progress, Governance)  
**Status:** **Status B — Sẵn sàng review (Chưa đủ điều kiện nghiệm thu E2E đầy đủ do thiếu hạ tầng daemon scanner / thiết bị thật)**  

---

## 1. Executive Summary

This PR closes the Phase 1 core functional gaps assigned to Person A, following the review inventory in `docs/07-development/b09-person-b-review-2026-11-03.md` and the architecture invariants in `AGENTS.md`:

1. **AI Test Lockdown (SEC-01):** Prevents automated test suites from leaking live external OpenAI requests even if real keys exist in `.env`, while keeping production runtime behavior completely unrestricted without requiring `RUN_LIVE_OPENAI_TESTS`.
2. **Trainer Verification Lifecycle (PRI-01 / M1, M8):** Delivers the complete end-to-end flow:
   - Mobile: Document upload validation (`scan_status = 'CLEAN'`), application submission, status polling, rejection reason display, and resubmission.
   - Admin Web: Verification queue, filtering, application inspection, approval with notes, rejection requiring non-blank reason, and immutable audit logs.
   - Backend: RBAC, state transitions (`PENDING` → `VERIFIED` / `REJECTED`), optimistic/pessimistic double-decision lock (409), and trainer search discovery invariant (`/api/v1/coaching/trainers` only shows verified trainers).
3. **Physical Fitness Goals & Proposals (PRI-02 / M2):**
   - Mobile Student: Create initial goal, adjust version with reason, transition journey day 1, conflict banner with reload button on concurrent modification (409).
   - Mobile Trainer: Navigate from active student coaching profile (`/coaching/[relationshipId]/goal`), inspect student's active goal and proposal history, and submit goal modification proposals with reason.
   - Backend: Complete student-trainer authorization (`GoalTrainerAuthorityPort`), data sharing permission check (`FITNESS_GOAL:VIEW`), student proposal acceptance/rejection with immutable versioning, and OpenAPI contract synchronization.

---

## 2. Changes by Component

### A. AI Service (`services/ai-service`)
- `app/integrations/openai_provider.py`: Refactored test lockdown check so that it is only enforced within automated test runners or when explicit mock configurations are active; production runtime initializes normally.
- `tests/conftest.py`: Added autouse fixture preventing live external OpenAI network egress in tests unless `RUN_LIVE_OPENAI_TESTS=true` is set.
- `tests/test_ai_lockdown.py`: Added 6 regression tests verifying simulated mode default, live block without opt-in, and opt-in enablement.

### B. Backend Platform (`services/backend`)
- **Media & Document Verification:**
  - `MediaQueryPort.java` & `MediaPersistenceAdapter.java`: Added `allMediaExistOwnedAndClean(...)` requiring `scan_status = 'CLEAN'`, while separating general `allMediaExistAndOwnedBy(...)` without scan constraints. Added unit tests in `MediaPersistenceAdapterUnitTest.java`.
  - `TrainerDocumentStorageService.java`: Implemented verification document upload, file signature magic byte validation (PDF, PNG, JPEG), ClamAV `zINSTREAM` scanning, and document content storage.
  - Endpoints added:
    - `POST /api/v1/trainer-applications/documents` (multipart document upload for trainers)
    - `GET /api/v1/admin/trainer-applications/{applicationId}/documents` (admin document list)
    - `GET /api/v1/admin/trainer-applications/{applicationId}/documents/{mediaId}/download` (admin document download with `ADMIN_TRAINER_DOCUMENT_ACCESSED` audit)
    - `GET /api/v1/admin/trainer-applications/{applicationId}/certificates` (admin certificate list)
- **Trainer Applications:**
  - `TrainerApplicationService.java`: Enforced required rejection reasons, concurrency protection on admin decisions, verified status updates on trainer profile, and audit event publishing.
  - `AdminTrainerApplicationIntegrationTest.java`: 26 tests verifying admin queue, filtering, approve/reject flows, audit logging, and 409 conflict handling.
  - `TrainerApplicationIntegrationTest.java`: 17 tests verifying complete lifecycle including discovery filtering.
  - `TrainerApplicationServiceUnitTest.java`: 23 unit tests for application transitions.
  - `AdminTrainerVerificationServiceUnitTest.java`: 17 unit tests for admin verification logic.
- **Fitness Goals & Proposals:**
  - `GetStudentCurrentGoalForTrainerUseCase.java` & `GetStudentGoalProposalsForTrainerUseCase.java`: Ports for trainer retrieval of scoped student goals and proposals.
  - `FitnessGoalProposalService.java`: Implemented trainer query methods enforcing `GoalTrainerAuthorityPort.verifyTrainerCanViewProposal` (validates relationship, active `HUMAN_COACH` period, and `FITNESS_GOAL:VIEW` permission).
  - `FitnessGoalProposalController.java`: Added endpoints:
    - `GET /api/v1/students/{studentId}/fitness-goals/current` (`@PreAuthorize("hasRole('TRAINER')")`)
    - `GET /api/v1/students/{studentId}/fitness-goal-proposals` (`@PreAuthorize("hasRole('TRAINER')")`)
    - `POST /api/v1/fitness-goals/{goalId}/proposals` (`POST /api/v1/students/{studentId}/fitness-goal-proposals`)
  - Integration & Unit Tests:
    - `FitnessGoalProposalIntegrationTest.java`: 41 tests passing.
    - `FitnessGoalProposalControllerUnitTest.java`: 8 tests passing.
    - `FitnessGoalProposalServiceUnitTest.java`: 26 tests passing.

### C. Mobile Application (`apps/mobile`)
- **Trainer Verification:**
  - `services/trainerApplicationApi.ts`: Added `uploadDocument(fileUri, filename, mimeType)` with `FormData` and `submitApplication` taking `documentMediaIds`.
  - `features/trainer/TrainerProfileScreen.tsx`: Added document picker (`expo-document-picker`), upload progress, document item list with clean scan badge, document removal, rejection reason display, and resubmission.
  - Unit tests: `trainerApplicationApi.test.ts` (3 tests) & `TrainerProfileScreen.test.tsx` (17 tests).
- **Fitness Goals & Trainer Proposals:**
  - `services/fitnessGoalApi.ts`: Endpoints for student goals, versions, transitions, proposal decisions, and trainer student goal queries.
  - `features/goal/CurrentGoalScreen.tsx`: Goal display, version history, `CreateGoalModal`, `CreateGoalVersionModal`, `CreateGoalTransitionModal`, and conflict banner.
  - `features/coaching/RelationshipDetailScreen.tsx`: Added `open-student-goal-button` navigating to `/coaching/[relationshipId]/goal`.
  - `features/coaching/TrainerStudentGoalScreen.tsx`: Screen for trainer to view student goal/version and proposal history.
  - `features/goal/components/CreateGoalProposalModal.tsx`: Modal for trainer to draft proposals with reason and duration validation.
  - `app/(app)/coaching/[relationshipId]/goal.tsx`: Expo Router route linking trainer navigation.
  - Unit tests:
    - `CurrentGoalScreen.test.tsx` (33 tests PASS)
    - `fitnessGoalApi.test.ts` (20 tests PASS)
    - `TrainerStudentGoalScreen.test.tsx` (5 tests PASS)
    - `GoalProposalDetailScreen.test.tsx` (19 tests PASS)

### D. Admin Web Application (`apps/admin-web`)
- `services/apiClient.ts`: Added `apiBlobRequest` for authenticated binary downloads.
- `services/trainerApplicationApi.ts`: Added `getApplicationDocuments`, `getApplicationCertificates`, and `downloadDocument`.
- `features/trainer-applications/TrainerApplicationDetailPage.tsx`: Added certificate and document tables with format, size, clean scan badge, and document download action.
- Vitest tests: `TrainerApplicationPages.test.tsx` (7 tests PASS) & `trainerApplicationApi.test.ts` (6 tests PASS).

### E. Contracts (`contracts/openapi/openapi.yaml`)
- Added OpenAPI paths and schemas for:
  - `POST /api/v1/trainer-applications/documents`
  - `GET /api/v1/admin/trainer-applications/{applicationId}/documents`
  - `GET /api/v1/admin/trainer-applications/{applicationId}/documents/{mediaId}/download`
  - `GET /api/v1/admin/trainer-applications/{applicationId}/certificates`
  - `GET /api/v1/students/{studentId}/fitness-goals/current`
  - `GET /api/v1/students/{studentId}/fitness-goal-proposals`
  - `POST /api/v1/students/{studentId}/fitness-goal-proposals`

---

## 3. Verification & Test Evidence

| Scope | Suite | Result | Details |
|---|---|---|---|
| **AI Lockdown** | `pytest services/ai-service` | **82 passed** | Injected client & default client tests verify lockdown; live calls blocked without opt-in. |
| **Backend Suite** | `mvnw test` | **449 passed** | Full backend test suite passing (0 failures, 0 errors). |
| **Mobile Jest** | Full mobile suite | **720 passed** (89 suites) | All mobile test suites passing. |
| **Mobile TypeScript** | `npm run typecheck:mobile` | **0 errors** | Strict Expo TypeScript validation passed. |
| **Admin Web Vitest** | Admin Web Suites | **117 passed** (16 suites) | All Admin Web tests passing. |
| **Admin Web Build** | `npm run build:admin` | **0 errors** | Production Vite build succeeded. |
| **Contract Validation** | `npm run validate:contracts` | **0 errors** | Redocly OpenAPI linting and Ajv AI schemas valid. |
| **Contract Validation** | `npm run validate:contracts` | **0 errors** | Spectral OpenAPI linting clean. |
| **UX Docs Validation** | `npm run validate:ux-docs` | **0 errors** | UX specification checks passed. |

---

## 4. Invariants & Authority Rules Followed

1. **Authority:** Trainers cannot directly modify a student's active goal. All trainer initiatives MUST go through a Goal Proposal, which requires explicit Student confirmation.
2. **Coaching Scope:** Trainers can only view or propose goals for students with whom they have an active coaching relationship, active `HUMAN_COACH` period, and `FITNESS_GOAL:VIEW` permission.
3. **Immutability of History:** Creating a new goal version locks prior versions. Transitions close the current journey while maintaining timeline continuity.
4. **Safety & Antivirus:** Document submissions check `scan_status = 'CLEAN'`; unscanned or infected files are rejected.
5. **No AI Authority:** AI outputs are never sources of truth. AI service tests cannot trigger external LLM costs or unexpected external dependencies.

---

## 5. Handoff Status & Readiness Determinations

### A. Ready to Merge: PASS
- All source code, database migrations, schemas, security policies, unit tests, integration tests, contract tests, and component UI tests pass 100%.
- Zero compiler errors, zero type errors, zero schema/contract violations.
- Safe to merge into staging/development branches without regression.

### B. Eligible for Physical Acceptance: BLOCKED BY ENVIRONMENT
- **Trainer Verification (M1/M8):** Requires active live ClamAV scanner daemon (`clamd` on port 3310) and live S3 storage to verify end-to-end binary malware scanning and storage, plus a physical mobile device/AVD for manual camera and document picker verification.
- **Physical Fitness Goals (M2):** **NOT BLOCKED BY SCANNER DAEMON.** Luồng Goals không upload file hay tương tác với ClamAV. Chỉ chờ nghiệm thu thủ công trên thiết bị di động thật / AVD để hoàn tất ký duyệt acceptance.

---

## 6. Review Checklist for Reviewers

- [ ] Verify that `RUN_LIVE_OPENAI_TESTS=true` remains false in CI/CD environments.
- [ ] Verify that Trainer Application rejection requires non-empty `rejectionReason` in Admin Web.
- [ ] Verify that `/api/v1/coaching/trainers` strictly excludes non-verified trainers.
- [ ] Verify that Trainer cannot access `/api/v1/students/{studentId}/fitness-goals/current` for students outside their coaching roster.
- [ ] Verify that concurrent goal proposal acceptance returns 409 and prompts user to reload state.
