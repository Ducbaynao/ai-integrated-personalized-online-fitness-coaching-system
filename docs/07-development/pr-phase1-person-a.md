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
  - `MediaPersistenceAdapter.java`: Added validation rejecting documents whose `scan_status` is not `CLEAN`.
- **Trainer Applications:**
  - `TrainerApplicationService.java`: Enforced required rejection reasons, concurrency protection on admin decisions, verified status updates on trainer profile, and audit event publishing.
  - `AdminTrainerApplicationIntegrationTest.java`: 26 tests verifying admin queue, filtering, approve/reject flows, audit logging, and 409 conflict handling.
  - `TrainerApplicationIntegrationTest.java`: 17 tests verifying complete lifecycle including discovery filtering.
  - `TrainerApplicationServiceUnitTest.java`: 23 unit tests for application transitions.
- **Fitness Goals & Proposals:**
  - `GetStudentCurrentGoalForTrainerUseCase.java` & `GetStudentGoalProposalsForTrainerUseCase.java`: Ports for trainer retrieval of scoped student goals and proposals.
  - `FitnessGoalProposalService.java`: Implemented trainer query methods enforcing `GoalTrainerAuthorityPort.verifyTrainerCanViewProposal` (validates relationship, active `HUMAN_COACH` period, and `FITNESS_GOAL:VIEW` permission).
  - `FitnessGoalProposalController.java`: Added endpoints:
    - `GET /api/v1/students/{studentId}/fitness-goals/current` (`@PreAuthorize("hasRole('TRAINER')")`)
    - `GET /api/v1/students/{studentId}/fitness-goal-proposals` (`@PreAuthorize("hasRole('TRAINER')")`)
  - Integration & Unit Tests:
    - `FitnessGoalProposalIntegrationTest.java`: 41 tests passing.
    - `FitnessGoalProposalControllerUnitTest.java`: 8 tests passing.
    - `FitnessGoalProposalServiceUnitTest.java`: 26 tests passing.

### C. Mobile Application (`apps/mobile`)
- **Trainer Verification:**
  - `services/trainerApplicationApi.ts`: API client for trainer application submission and status retrieval.
  - `features/trainer/TrainerProfileScreen.tsx`: Verification banner, submit application modal, rejection reason display, resubmit button.
  - Unit tests: `trainerApplicationApi.test.ts` (2 tests) & `TrainerProfileScreen.test.tsx` (12 tests).
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
- `services/trainerApplicationApi.ts`: Admin API client for application list, details, and approve/reject decisions.
- `features/trainer-applications/TrainerApplicationListPage.tsx`: Paginated application queue with status filter.
- `features/trainer-applications/TrainerApplicationDetailPage.tsx`: Application detail review, documents preview, approval modal, and rejection reason form.
- `App.tsx` & `components/AdminLayout.tsx`: Routed `/trainer-applications` with sidebar link and badges.
- Vitest tests: `TrainerApplicationPages.test.tsx` (6 tests PASS) & `trainerApplicationApi.test.ts` (3 tests PASS).

### E. Contracts (`contracts/openapi/openapi.yaml`)
- Added OpenAPI paths and schemas for:
  - `GET /api/v1/students/{studentId}/fitness-goals/current`
  - `GET /api/v1/students/{studentId}/fitness-goal-proposals`
  - `POST /api/v1/students/{studentId}/fitness-goal-proposals`

---

## 3. Verification & Test Evidence

| Scope | Suite | Result | Details |
|---|---|---|---|
| **AI Lockdown** | `pytest services/ai-service` | **79 passed** | Full test suite passes; live calls blocked without opt-in. |
| **Backend Integration** | `FitnessGoalProposalIntegrationTest` | **41 passed** | Full proposal lifecycle, student acceptance/rejection, concurrency conflicts. |
| **Backend Integration** | `TrainerApplicationIntegrationTest` | **17 passed** | Submit → Reject → Resubmit → Approve → Discovery. |
| **Backend Integration** | `AdminTrainerApplicationIntegrationTest` | **26 passed** | Admin RBAC, approval, rejection reasons, audit trail. |
| **Backend Unit** | `FitnessGoalProposalControllerUnitTest` | **8 passed** | Web MVC layer authorization & validation. |
| **Backend Unit** | `FitnessGoalProposalServiceUnitTest` | **26 passed** | Service layer authority checks & business logic. |
| **Backend Unit** | `TrainerApplicationServiceUnitTest` | **23 passed** | Application status state machine. |
| **Mobile Jest** | Person A Mobile Suites | **91 passed** | 6 suites (`trainerApplicationApi`, `fitnessGoalApi`, `TrainerProfileScreen`, `GoalProposalDetailScreen`, `TrainerStudentGoalScreen`, `CurrentGoalScreen`). |
| **Mobile TypeScript** | `npm run typecheck:mobile` | **0 errors** | Strict Expo TypeScript validation passed. |
| **Admin Web Vitest** | Admin Web Suites | **113 passed** | All Admin Web tests pass including Trainer Applications. |
| **Admin Web Build** | `npm run build:admin` | **0 errors** | Production Vite build succeeded. |
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

## 5. Handoff Status: Status B

**Conclusion: Status B (Sẵn sàng review, chưa đủ điều kiện nghiệm thu E2E đầy đủ do thiếu hạ tầng daemon scanner / thiết bị thật).**

- **Why Status B:**
  All automated tests (unit, integration, contract, UI component) pass at 100%. However, because the development workstation does not currently have active persistent background daemons for ClamAV antivirus scanning or attached physical Android/iOS devices/emulators to run physical E2E device flows, formal device-level acceptance remains open pending staging/infrastructure availability.

---

## 6. Review Checklist for Reviewers

- [ ] Verify that `RUN_LIVE_OPENAI_TESTS=true` remains false in CI/CD environments.
- [ ] Verify that Trainer Application rejection requires non-empty `rejectionReason` in Admin Web.
- [ ] Verify that `/api/v1/coaching/trainers` strictly excludes non-verified trainers.
- [ ] Verify that Trainer cannot access `/api/v1/students/{studentId}/fitness-goals/current` for students outside their coaching roster.
- [ ] Verify that concurrent goal proposal acceptance returns 409 and prompts user to reload state.
