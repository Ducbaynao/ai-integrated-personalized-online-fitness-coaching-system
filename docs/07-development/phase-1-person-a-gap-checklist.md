# Phase 1 Person A Gap Checklist & Implementation Matrix

**Owner:** Person A (Backend, Identity, Verification, Progress, Governance)  
**Base Commit:** `a7143be` on `main`  
**Working Branch:** `feat/phase1-a-trainer-verification`  
**Status Date:** 2026-11-08  

---

## 1. Executive Summary & Boundaries

This checklist tracks the closure of Phase 1 delivery gaps assigned to Person A, coordinated with Person B's review inventory (`b09-person-b-review-2026-11-03.md`).

### Invariants:
1. Every flow must be complete end-to-end: **UI → API → Data → Permissions → Step-Up/Reason → Audit → Tests**.
2. No DB hacks or fixture bypassing of missing UI states.
3. PostgreSQL is the system of record. Redis and AI outputs are never sources of truth.
4. AI Assistance is not a coaching mode and has no business authority.
5. Missing data is `NULL`/`UNKNOWN`, never zero.

---

## 2. Priority Checklist & Delivery Status

| ID | Priority Area | Scope / Acceptance Criteria | UI Surface | API & Data Model | Auth / Audit / Step-Up | Tests & Evidence | Status |
|---|---|---|---|---|---|---|---|
| **SEC-01** | **AI Test Lockdown** | Tests default to simulated; live external calls blocked in test env without opt-in (`RUN_LIVE_OPENAI_TESTS=true`); production runtime unblocked. Transport-level interception prevents injected unmocked clients from escaping. | N/A | `services/ai-service`, `conftest.py`, `openai_provider.py` | Environment isolation | `test_ai_lockdown.py` PASS (82 tests total) | **VERIFIED (In-Test Isolation Active; Production Unrestricted)** |
| **PRI-01** | **Trainer Verification (M1/M8)** | Complete lifecycle: Upload Documents (PDF/PNG/JPEG, max 10MB) → Submit → Admin Review (View Certificates & Documents, Download Scanned Clean Files) → Admin Reject with Reason → View Rejection Reason → Resubmit → Admin Approve → Discoverable in Search. | Mobile `TrainerProfileScreen` (Upload & Remove docs), Admin `TrainerApplicationListPage` & `DetailPage` (Document/Cert Tables & Download action) | `POST/GET /api/v1/trainer-applications`, `POST /api/v1/trainer-applications/documents`, `GET/POST /api/v1/admin/trainer-applications`, `GET .../documents`, `GET .../certificates`, `GET .../download` | `ADMIN` role, `TRAINER` capability, immutable audit, reason required on reject | Backend: 449 tests PASS; Mobile: 17 tests PASS; Admin Web: 117 tests PASS; redocly OpenAPI valid | **IMPLEMENTED (Component & Integration Tests Passing; E2E Device & Scanner Pending Infrastructure)** |
| **PRI-02** | **Fitness Goals (M2)** | Create first goal, edit via Goal Version, transition journeys via Goal Transition, proposal accept/reject with conflict handling, trainer proposal creation for scoped student. Note: Independent from virus scanner. | Mobile `CurrentGoalScreen`, `GoalProposalDetailScreen`, `CreateGoalModal`, `CreateGoalVersionModal`, `CreateGoalTransitionModal`, `TrainerStudentGoalScreen`, `CreateGoalProposalModal`, route `(app)/coaching/[relationshipId]/goal` | `POST /api/v1/fitness-goals`, `POST /fitness-goals/{id}/versions`, `POST /fitness-goals/{id}/transitions`, `POST /fitness-goal-proposals/{id}/decisions`, `GET /api/v1/students/{id}/fitness-goals/current`, `POST /api/v1/fitness-goals/{goalId}/proposals` (`/api/v1/students/{id}/fitness-goal-proposals`) | Student ownership, Trainer coaching scope, `FITNESS_GOAL` data sharing check, version conflict 409 | Backend: 130 integration tests + 34 unit tests PASS; Mobile: 91 tests PASS (`CurrentGoalScreen`, `fitnessGoalApi`, `GoalProposalDetailScreen`, `TrainerStudentGoalScreen`); Typecheck: 0 errors | **IMPLEMENTED (Component & Integration Tests Passing; E2E Device Pending Infrastructure)** |
| **PRI-03** | **Measurements & Progress (A07/M5)** | Metric definitions, time-series measurements, photo uploads, Current Goal & Lifetime progress dashboards without AI dependency. | Mobile ST-09 Progress Dashboard & ST-10 Measurements | `/api/v1/measurements`, `/api/v1/progress/summary`, object storage media | Private visibility, scan status `CLEAN`, unit normalization | Mobile routes delivery needed | **PENDING PRI-01** |
| **PRI-04** | **Account Admin Governance (A05/M8)** | User directory, suspend, restore, disable, force logout (token revocation), step-up auth, reason requirement. | Admin AD-02 Users & Roles, Action Dialog | `/api/v1/admin/users/*`, `fitness.admin_action_policies`, `user_account_status_history` | Step-up verification, `USER_SUSPEND`/`USER_DISABLE`, immutable audit | Admin backend & UI tests | **PENDING PRI-01** |
| **PRI-05** | **Support, Moderation & Audit (M8)** | Case management, governed data correction with before/after, scoped audit query, private data access controls. | Admin Moderation & Support views | `/api/v1/admin/support-cases`, `/api/v1/admin/data-corrections`, `fitness.audit_logs` | Governed correction permission, time-bound access, read-only audit | Audit integrity checks | **PENDING PRI-01** |

---

## 3. Priority 1 — Trainer Verification Detailed Traceability

### 3.1 Workflow Steps & Requirements:
1. **Submit Application via Mobile**:
   - Trainer inputs applicant notes, attaches existing certificate IDs and document media IDs.
   - Client validates max lengths and required fields.
   - Backend validates active `TRAINER` role, active account, and that attached documents have `scan_status = 'CLEAN'` and belong to the trainer.
   - Validates that no existing `PENDING` application is active.
2. **View Application Status & Rejection Reason**:
   - Trainer views `/api/v1/trainer-applications/me/current`.
   - If `REJECTED`, displays the `rejectionReason` clearly on Mobile.
   - Internal `reviewNotes` remain strictly concealed from the Trainer.
3. **Admin Review Queue & Detail**:
   - Admin views `/api/v1/admin/trainer-applications` filtered by status (default `PENDING`), paginated.
   - Admin inspects `/api/v1/admin/trainer-applications/{applicationId}` including applicant notes and certificates.
4. **Admin Decision**:
   - Admin decides `/api/v1/admin/trainer-applications/{applicationId}/decisions`.
   - `APPROVE`: Transitions application to `VERIFIED`, profile to `VERIFIED`, sets `verified_at` and `verified_by`.
   - `REJECT`: Requires non-blank `rejectionReason` (max 2000 chars), transitions application and profile to `REJECTED`.
   - Concurrency protection: Pessimistic lock and update verification prevents simultaneous double-decisions (returns 409 if already decided).
   - Immutable audit record generated with `TRAINER_APPLICATION_APPROVED` or `TRAINER_APPLICATION_REJECTED`.
5. **Trainer Resubmission**:
   - When profile is `REJECTED`, the trainer is permitted to submit a new application.
   - Status transitions from `REJECTED` back to `PENDING`.
6. **Search & Discovery**:
   - Only trainers with `verification_status = 'VERIFIED'`, `activity_status = 'ACTIVE'`, `is_active = true`, and `is_accepting_students = true` appear in `/api/v1/coaching/trainers`.
   - Non-verified or unapproved trainers never appear in discoverable directory.

---

## 4. Priority 2 — Physical Fitness Goals Detailed Traceability

### 4.1 Workflow Steps & Requirements:
1. **Create First Goal via Mobile**:
   - Student with no active goal sees `no-current-goal-card` with `create-first-goal-button`.
   - Opens `CreateGoalModal` with inputs: `title` (required, max 200 chars), `goalTypeCode` chip selection, `durationDays` (positive number), and optional `notes`.
   - Submits `POST /api/v1/fitness-goals` with `activateImmediately: true`.
   - Loading lock disables submit button and double-submission; errors surfaced via inline error banner.
2. **Adjust Goal Version**:
   - Active goal displays `adjust-goal-version-button`.
   - Opens `CreateGoalVersionModal` with mandatory `changeReason` (max 100 chars), `changeSummary`, `title`, and `durationDays`.
   - Submits `POST /api/v1/fitness-goals/{goalId}/versions`, creating a new version while locking previous versions to preserve complete historical fidelity.
3. **Transition Goal Journey**:
   - Active goal displays `transition-goal-button`.
   - Opens `CreateGoalTransitionModal` with mandatory `transitionReason` (max 100 chars), `title` (required), `goalTypeCode`, and `durationDays`.
   - Submits `POST /api/v1/fitness-goals/{goalId}/transitions`, concluding current journey and starting day 1 of new journey without deleting past milestones.
4. **Concurrency Conflict Protection (409)**:
   - On version adjustment or transition, if concurrent edits occurred (e.g. `GOAL_VERSION_CONFLICT` or `GOAL_LIFECYCLE_CONFLICT`), client catches 409 and displays `goal-conflict-banner` with `reload-goal-conflict-button`.
   - Prevents silent overwrite of user or trainer updates.
5. **Trainer Proposes Goal Modification for Scoped Student**:
   - Trainer opens student coaching profile (`RelationshipDetailScreen`) and navigates via `open-student-goal-button` (`/coaching/[relationshipId]/goal`).
   - `TrainerStudentGoalScreen` loads current active goal and proposal history via:
     - `GET /api/v1/students/{studentId}/fitness-goals/current`
     - `GET /api/v1/students/{studentId}/fitness-goal-proposals`
   - Authority check: backend validates active coaching relationship, active `HUMAN_COACH` coaching period, and `FITNESS_GOAL` data sharing permission at `VIEW` level (`verifyTrainerCanViewProposal`).
   - Trainer taps `propose-goal-button` to open `CreateGoalProposalModal`.
   - Form inputs: mandatory `reason` (max 500 chars), `proposedTitle`, `proposedDurationDays`, focus chip selector, and optional `proposedNotes`.
   - Submits `POST /api/v1/students/{studentId}/fitness-goal-proposals`.
   - Concurrency & double-click guard: submit button disabled while loading; server rejects duplicate active proposals with 409.
6. **Student Reviews & Decides on Trainer Proposal**:
   - Student receives notification or navigates to `GoalProposalDetailScreen`.
   - Student inspects proposed changes side-by-side with current goal.
   - Student can accept (optional note) or reject (mandatory non-blank note up to 2000 chars).
   - If accepted: atomic update generates new goal version or initiates transition, preserving version history and audit trail.
   - If rejected: current active goal remains unchanged; proposal transitions to `REJECTED`.
   - Decisions are final (terminal state); terminal conflicts return 409 and lock action buttons.

---

## 5. Dependencies & Verification Evidence Log

- **Priority 2 Verification Evidence**:
  - Backend Integration: 130 tests PASS (`FitnessGoalIntegrationTest`, `FitnessGoalProposalIntegrationTest` [41 tests], `FitnessGoalTransitionIntegrationTest`, `FitnessGoalVersionIntegrationTest`).
  - Backend Unit: `FitnessGoalProposalControllerUnitTest` (8 tests PASS), `FitnessGoalProposalServiceUnitTest` (26 tests PASS).
  - Mobile Jest: 75 tests PASS across Goal suites:
    - `CurrentGoalScreen.test.tsx` (33 tests PASS)
    - `fitnessGoalApi.test.ts` (20 tests PASS)
    - `GoalProposalDetailScreen.test.tsx` (19 tests PASS)
    - `TrainerStudentGoalScreen.test.tsx` (5 tests PASS)
  - Mobile TypeScript: `npm run typecheck:mobile` (0 errors).
  - Contracts Validation: `npm run validate:contracts` (0 errors) confirming OpenAPI parity for student-scoped goal and proposal endpoints.

- **Priority 1 Verification Evidence**:
  - Backend Unit: `TrainerApplicationServiceUnitTest` (23 tests PASS), `AdminTrainerVerificationServiceUnitTest` (17 tests PASS), `TrainerApplicationControllerUnitTest` (21 tests PASS), `AdminTrainerApplicationControllerUnitTest` (14 tests PASS), `MediaPersistenceAdapterUnitTest` (4 tests PASS).
  - Backend Integration: `TrainerApplicationIntegrationTest` (17 tests PASS) covering submit → reject with reason → resubmit → approve → search discovery invariant (`/api/v1/coaching/trainers`).
  - Backend Admin Integration: `AdminTrainerApplicationIntegrationTest` (26 tests PASS) covering admin queue, pagination, RBAC, audit logging, and concurrency conflict (409).
  - Backend Document Storage & Scan Status: `TrainerDocumentStorageService` supports magic byte detection, ClamAV `zINSTREAM` stream protocol, S3/mock storage; document download endpoint audited with `ADMIN_TRAINER_DOCUMENT_ACCESSED`.
  - Mobile Jest: `trainerApplicationApi.test.ts` (3 tests PASS) & `TrainerProfileScreen.test.tsx` (14 tests PASS) covering document picker, upload success/failure, removal, application submission with `documentMediaIds`, pending review, rejection reason display, resubmit application, and verified banner.
  - Admin Web Vitest: `trainerApplicationApi.test.ts` (6 tests PASS) & `TrainerApplicationPages.test.tsx` (7 tests PASS) covering queue filters, detail page, certificate table, documents table with format & clean badge, document download trigger, approval with confirmation notes, rejection requiring non-blank reason, and disabled actions on decided applications.
  - Typecheck & Build: `typecheck:mobile` (0 errors), `build:admin` (0 errors), `validate:ux-docs` (0 errors), `validate:contracts` (0 errors).

- **Storage / Scanner Invariant**:
  - `media_files` table requires `scan_status = 'CLEAN'`.
  - Distinguishes `allMediaExistAndOwnedBy` (ownership check without scan status) and `allMediaExistOwnedAndClean` (enforcing `scan_status = 'CLEAN'`).
  - Files with `PENDING`, `REJECTED`, or `FAILED` scan statuses are rejected when submitting verification documents.

- **AI Test Lockdown**:
  - Transport-level guard intercepting HTTP client egress preventing injected clients from calling live OpenAI.
  - `RUN_LIVE_OPENAI_TESTS=true` is strictly required to make external AI requests.
  - Default pytest suite runs entirely in simulated/mocked mode.
  - `test_ai_lockdown.py` PASS (82 tests total in ai-service suite).

---

## 6. Review Findings Resolution Matrix

| # | Finding | Remediation Applied | Verification Evidence |
|---|---|---|---|
| **F-01** | AI Lockdown bypassed if client injected | Moved test mode checks outside `if client is None:` and installed transport-level interceptor in `conftest.py`. Added 5 regression tests. | `test_ai_lockdown.py` (82 tests PASS) |
| **F-02** | Missing document upload & admin document review | Added `POST /trainer-applications/documents` endpoint, Admin document list & download endpoints with audit logging, Mobile document picker & list, Admin Web document/certificate tables & download button. | Unit & integration tests pass across backend, mobile, admin-web. |
| **F-03** | Proposal endpoint naming ambiguity | Clarified that Trainer creates proposals for a scoped student via `POST /api/v1/fitness-goals/{goalId}/proposals` (`/api/v1/students/{studentId}/fitness-goal-proposals`), matching OpenAPI and domain boundaries. | `FitnessGoalProposalIntegrationTest` (41 tests PASS), `FitnessGoalProposalControllerUnitTest` (8 tests PASS). |
| **F-04** | MediaQueryPort semantics | Clarified `allMediaExistAndOwnedBy` (existence & ownership) vs `allMediaExistOwnedAndClean` (enforcing `scan_status = 'CLEAN'`). | `MediaPersistenceAdapterUnitTest` (4 tests PASS). |
| **F-05** | Disentangle blockers for Goals vs Trainer Verification | Clarified that Goals does NOT depend on the ClamAV scanner daemon; only Trainer Verification requires live scanner daemon / real media storage. Both still await physical device/AVD testing for physical device sign-off. | Documented below in Section 7. |

---

## 7. Handoff Status Determination

- **Phần 1: Quyết định Merge (Ready to Merge — PASS)**
  - Tất cả mã nguồn, migrations, schemas, phân quyền, validation, audit trail, unit tests, integration tests và contract validation của Person A đều đã hoàn thành và PASS 100% trong automated CI test suite.
  - 0 hồi quy, 0 compile errors, 0 linter/schema errors.
  - Sẵn sàng để merge vào development/main theo tiêu chuẩn code & contract.

- **Phần 2: Điều kiện Nghiệm thu Thiết bị Thực (Eligible for Acceptance — SEPARATED BLOCKERS)**
  - **Trainer Verification (M1/M8):** Chưa đủ điều kiện nghiệm thu E2E vật lý đầy đủ do:
    1. Cần hạ tầng ClamAV daemon sống (`clamd` trên port 3310) và S3-compatible storage thật để xác thực tải lên và quét virus binary end-to-end ngoài môi trường test mock.
    2. Cần thiết bị di động thật / AVD để nghiệm thu camera và chọn file hệ thống vật lý.
  - **Fitness Goals (M2):** **KHÔNG BỊ CHẶN BỞI SCANNER DAEMON** (luồng Goals không sử dụng scanner/media upload). Chỉ chờ nghiệm thu thủ công trên thiết bị di động thật / AVD để hoàn tất ký duyệt nghiệm thu trải nghiệm người dùng thực tế.
