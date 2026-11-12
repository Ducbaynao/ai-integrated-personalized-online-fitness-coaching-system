# Phase 1 Person A Gap Checklist & Implementation Matrix

**Owner:** Person A (Backend, Identity, Verification, Progress, Governance)  
**Base Commit:** `1cc3ffa` on `main` (PR #149 merged + CI fix `00f5ce8`)
**Working Branch:** `feat/phase1-a07-measurement-progress`
**Status Date:** 2026-11-09

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
| **PRI-02** | **Fitness Goals (M2)** | Create first goal, edit via Goal Version, transition journeys via Goal Transition, proposal accept/reject with conflict handling, trainer proposal creation for scoped student. Note: Independent from virus scanner. | Mobile `CurrentGoalScreen`, `GoalProposalDetailScreen`, `CreateGoalModal`, `CreateGoalVersionModal`, `CreateGoalTransitionModal`, `TrainerStudentGoalScreen`, `CreateGoalProposalModal`, route `(app)/coaching/[relationshipId]/goal` | `POST /api/v1/fitness-goals`, `POST /fitness-goals/{id}/versions`, `POST /fitness-goals/{id}/transitions`, `POST /fitness-goal-proposals/{id}/decisions`, `GET /api/v1/students/{studentId}/fitness-goals/current`, `GET /api/v1/students/{studentId}/fitness-goal-proposals`, `POST /api/v1/fitness-goals/{goalId}/proposals` | Student ownership, Trainer coaching scope, `FITNESS_GOAL` data sharing check, version conflict 409 | Backend: 130 integration tests + 34 unit tests PASS; Mobile: 91 tests PASS (`CurrentGoalScreen`, `fitnessGoalApi`, `GoalProposalDetailScreen`, `TrainerStudentGoalScreen`); Typecheck: 0 errors | **IMPLEMENTED (Component & Integration Tests Passing; E2E Device Pending Infrastructure)** |
| **PRI-03** | **Measurements & Progress (A07/M5)** | Metric definitions, canonical units, measurements recording & lifecycle correction, progress photos with stream AV scan, deterministic Progress Summary (Current Goal & Lifetime) without AI dependency. | Mobile ST-09 Progress Dashboard, ST-10 Measurements, Add/Correct/Upload modals, route `(app)/coaching/[relationshipId]/progress` | `POST/GET /api/v1/measurements`, `POST .../correction`, `POST/GET /api/v1/measurements/progress-photos`, `GET .../file`, `GET /api/v1/progress/summary`, V56 migration | Student ownership, Trainer coaching scope (`MEASUREMENT` & `PROGRESS_REPORT` sharing), AV scan status, audit trail | Backend: 22 integration tests in `MeasurementApiIntegrationTest` + 17 in `ProgressEngineIntegrationTest` + 15 in `MeasurementProgressIntegrationTest` + 28 in `WorkoutProgressAggregationIntegrationTest` + 15 in AI F04 tests PASS (97 backend tests); Mobile: 49 tests PASS (22 new + 27 regression); Contracts: 21 tests PASS, redocly valid; Typecheck: 0 errors; Lint: 0 errors | **AUTOMATED TESTED & SUBAGENT REVIEWED (Independent sub-agent review complete; Human peer review, physical device UI acceptance, and live ClamAV/S3 operational verification pending)** |
| **PRI-04** | **Account Admin Governance (A05/M8)** | User directory, suspend, restore, disable, force logout (strict token revocation), role management, step-up auth, reason requirement, last admin protection under concurrency. | Admin AD-02 Users & Roles (`UsersListPage`, `UserDetailPage`), `AdminActionDialog`, `DashboardPage` | `/api/v1/admin/users/*`, `/api/v1/admin/auth/step-up`, `/api/v1/admin/dashboard/summary`, `V57` migration | Step-up verification, `USER_SUSPEND`/`USER_DISABLE`/`USER_ROLE_MANAGE`/`SESSION_REVOKE`, immutable audit | Backend: 27 integration tests in `AdminAccountAdministrationIntegrationTest` PASS; Regression: 58 tests in `AuthSessionIntegrationTest` & unit tests PASS, 27 tests in `CurrentUserIntegrationTest` & `ContentManagementIntegrationTest` PASS, 14 tests in `AccountErasureHttpIntegrationTest` PASS; Admin Web: 136 tests PASS, Typecheck 0 errors, Lint 0 errors; Contracts: 21 tests PASS, redocly valid | **IMPLEMENTED & AUTOMATED TESTED (Strict session invalidation, database-backed authority derivation, and component tests verified; Manual browser UI acceptance on live environment pending)** |
| **PRI-05** | **Support, Moderation & Audit (M8)** | Case management, governed data correction with before/after, scoped audit query, private data access controls. | Admin Support (`SupportCaseListPage`, `SupportCaseDetailPage`), Moderation (`ModerationCaseListPage`, `ModerationCaseDetailPage`), Data Correction (`DataCorrectionListPage`), Audit (`AuditLogListPage`), Privileged Access (`PrivilegedAccessPage`, `PrivilegedAccessBanner`) | `/api/v1/admin/support-cases/*`, `/api/v1/admin/moderation-cases/*`, `/api/v1/admin/privileged-access/*`, `/api/v1/admin/data-corrections/*`, `/api/v1/admin/audit-logs/*`, `V58` + `V59` migrations | `SUPPORT_MANAGE`, `MODERATION_MANAGE`, `PRIVILEGED_ACCESS_MANAGE`, `DATA_CORRECTION_EXECUTE`, `AUDIT_VIEW`, step-up authentication, time-bound privileged session, trigger protection | Integration: 23/23 tests in `AdminSupportModerationAuditIntegrationTest` PASS; Architecture: 1/1 test in `SupportModuleArchitectureTest` PASS; Regression: 29/29 tests in `AdminAccountAdministrationIntegrationTest` PASS, 22/22 tests in `MeasurementApiIntegrationTest` PASS; Frontend: 21 test files, 148/148 tests PASS; Contract: valid OpenAPI & AI schemas; UI Acceptance: 7 verified artifacts in `docs/08-ai/artifacts/support-moderation-audit-acceptance/` | **VERIFIED & PR #152 REVIEW FINDINGS RESOLVED (23/23 tests, V59 migration, clean architecture)** |

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
   - Submits `POST /api/v1/fitness-goals/{goalId}/proposals`.
   - Concurrency & double-click guard: submit button disabled while loading; server rejects duplicate active proposals with 409.
6. **Student Reviews & Decides on Trainer Proposal**:
   - Student receives notification or navigates to `GoalProposalDetailScreen`.
   - Student inspects proposed changes side-by-side with current goal.
   - Student can accept (optional note) or reject (mandatory non-blank note up to 2000 chars).
   - If accepted: atomic update generates new goal version or initiates transition, preserving version history and audit trail.
   - If rejected: current active goal remains unchanged; proposal transitions to `REJECTED`.
   - Decisions are final (terminal state); terminal conflicts return 409 and lock action buttons.

---

## 5. Priority 3 — Measurements & Progress Detailed Traceability (A07/M5)

### 5.1 Workflow Steps & Requirements:
1. **Metric Catalogs & Units (V56)**:
   - Migration `V56__measurement_catalogs_and_progress_seed.sql` seeds canonical metric definitions (`WEIGHT`, `HEIGHT`, `BODY_FAT_PERCENTAGE`, `CHEST`, `WAIST`, `HIPS`, `BICEPS_LEFT`, `BICEPS_RIGHT`, `THIGH_LEFT`, `THIGH_RIGHT`), measurement units (`KG`, `LBS`, `CM`, `INCH`, `PERCENT`), methods (`MANUAL_ENTRY`, `TAPE_MEASURE`, `SCALE`, `BIOIMPEDANCE`), and sources (`STUDENT_MOBILE`, `TRAINER_WORKSPACE`, `IMPORT`).
   - Endpoint `GET /api/v1/metrics` serves active definitions with canonical unit codes, symbols, dimensions, and min/max validation boundaries.
2. **Measurement Recording & Validation**:
   - Student records body metrics via `POST /api/v1/measurements`.
   - Dimension check: enforces compatibility between entered `unitCode` and metric default unit dimension (rejects incompatible units with 400 `UNIT_INCOMPATIBLE`).
   - Rejects future measurement timestamps (`measuredAt > now()`).
   - Values within `[valid_min, valid_max]` receive validation status `VALID`; out-of-bounds values trigger trigger status `SUSPECT` with descriptive note for coaching attention.
3. **Lifecycle Correction & Audit**:
   - Correction endpoint `POST /api/v1/measurements/{id}/correction` enforces mandatory non-blank correction `reason`.
   - Preserves complete historical provenance: inserts new measurement version linked via `supersededById`, updates old record's validation status to `SUPERSEDED`, and appends an immutable audit event `MEASUREMENT_CORRECTED`.
4. **Progress Photos Storage & Antivirus Scanning**:
   - Upload endpoint `POST /api/v1/measurements/progress-photos` accepts binary photos with metadata (`viewType`, `weightAtTime`, `takenAt`, `notes`).
   - Fail-closed ClamAV antivirus stream scan via `zINSTREAM`.
   - Storage in private S3 bucket under object path `progress-photos/{studentId}/{uuid}.{ext}`; DB holds metadata with `scan_status = 'CLEAN'`.
   - Authorized download/streaming URL `GET /api/v1/measurements/progress-photos/{id}/file` protected by least-privilege student & trainer scoping.
   - Soft-delete via `DELETE /api/v1/measurements/progress-photos/{id}`.
5. **Deterministic Progress Summary (Current Goal & Lifetime)**:
   - Endpoint `GET /api/v1/progress/summary` accepts `scope=CURRENT_GOAL` or `scope=LIFETIME`.
   - Grounded entirely in Progress Engine facts; completely decoupled from AI provider/service availability.
   - Computes metric deltas (`baselineValue`, `latestValue`, `deltaValue`, `trendDirection`).
   - Computes workout adherence rate (`rawCompletionRate`), working sets completed, and total volume (kg).
   - Evaluates training continuity (`RECENT_ACTIVITY_OBSERVED`, `POTENTIAL_GAP`, `INACTIVITY_RISK`) and days since last workout.
   - Missing data invariant: missing values are `NULL`/`UNKNOWN` (never substituted with 0).
6. **Mobile UI Surfaces (ST-09 & ST-10)**:
   - `MeasurementsScreen` (ST-10): filter chips by metric, measurement cards with status badges (`VALID`, `SUSPECT`, `SUPERSEDED`), pull-to-refresh, empty & error states.
   - `AddMeasurementModal`: metric selector, unit-aware numeric input, instant validation.
   - `CorrectMeasurementModal`: initial vs new value preview, mandatory reason input, audit preservation.
   - `ProgressDashboardScreen` (ST-09): Current Goal vs Lifetime scope toggle, goal targets card, metric deltas table, adherence & volume stats grid, continuity card, progress photo horizontal gallery, and optional F04 AI Summary link.
   - `UploadProgressPhotoModal`: view type selection (`FRONT`, `BACK`, `SIDE_LEFT`, `SIDE_RIGHT`, `OTHER`), weight at time, notes, security disclaimer.
   - `TrainerStudentProgressScreen`: trainer view of student progress scoped to active coaching relationship (`/coaching/[relationshipId]/progress`).

---

## 6. Dependencies & Verification Evidence Log

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
  - Backend Unit: `TrainerDocumentStorageServiceTest` (20 tests PASS), `TrainerApplicationServiceUnitTest` (27 tests PASS), `AdminTrainerVerificationServiceUnitTest` (22 tests PASS), `TrainerApplicationControllerUnitTest` (19 tests PASS), `AdminTrainerApplicationControllerUnitTest` (20 tests PASS), `MediaPersistenceAdapterUnitTest` (4 tests PASS). Total 112 unit tests PASS across Trainer verification and media modules.
  - Backend Integration: `TrainerApplicationIntegrationTest` (17 tests PASS) covering submit → reject with reason → resubmit → approve → search discovery invariant (`/api/v1/coaching/trainers`).
  - Backend Admin Integration: `AdminTrainerApplicationIntegrationTest` (26 tests PASS) covering admin queue, pagination, RBAC, audit logging, and concurrency conflict (409).
  - Backend Document Storage & Scan Status: `TrainerDocumentStorageService` is strictly fail-closed without in-memory RAM fallback. ClamAV `zINSTREAM` stream protocol enforces HTTP 503 `SCANNER_UNAVAILABLE` when host is missing or unreachable; S3 operations enforce HTTP 503 `STORAGE_UNAVAILABLE` and HTTP 404 `TRAINER_DOCUMENT_NOT_FOUND`; database failure during upload triggers immediate S3 orphan deletion; document download endpoint audited with `ADMIN_TRAINER_DOCUMENT_ACCESSED`.
  - Mobile Jest: `trainerApplicationApi.test.ts` (3 tests PASS) & `TrainerProfileScreen.test.tsx` (14 tests PASS) covering document picker, upload success/failure, removal, application submission with `documentMediaIds`, pending review, rejection reason display, resubmit application, and verified banner.
  - Admin Web Vitest: `trainerApplicationApi.test.ts` (6 tests PASS) & `TrainerApplicationPages.test.tsx` (7 tests PASS) covering queue filters, detail page, certificate table, documents table with format & clean badge, document download trigger, approval with confirmation notes, rejection requiring non-blank reason, and disabled actions on decided applications.
  - Typecheck & Build: `typecheck:mobile` (0 errors), `build:admin` (0 errors), `validate:ux-docs` (0 errors), `validate:contracts` (0 errors).

- **Storage / Scanner Invariant**:
  - `media_files` table requires `scan_status = 'CLEAN'`.
  - Distinguishes `allMediaExistAndOwnedBy` (ownership check without scan status) and `allMediaExistOwnedAndClean` (enforcing `scan_status = 'CLEAN'`).
  - Files with `PENDING`, `REJECTED`, or `FAILED` scan statuses are rejected when submitting verification documents.
  - Fail-closed storage: No RAM/ConcurrentHashMap fallback. Missing S3 configuration, unreachability, or S3 error throws `StorageUnavailableException` (HTTP 503 `STORAGE_UNAVAILABLE`). Missing object on download throws `TrainerDocumentNotFoundException` (HTTP 404 `TRAINER_DOCUMENT_NOT_FOUND`).
  - Fail-closed scanner: Missing ClamAV configuration, connection failure, socket timeout, or unexpected protocol reply throws `ScannerUnavailableException` (HTTP 503 `SCANNER_UNAVAILABLE`). Malware detection throws `ApplicationValidationException` with `MALWARE_DETECTED` (HTTP 400).
  - Clean orphan rollback: If S3 upload succeeds but persisting media record to database fails, S3 object is immediately deleted via `deleteFile`.

- **AI Test Lockdown**:
  - Transport-level guard intercepting HTTP client egress preventing injected clients from calling live OpenAI.
  - `RUN_LIVE_OPENAI_TESTS=true` is strictly required to make external AI requests.
  - Default pytest suite runs entirely in simulated/mocked mode.
  - `test_ai_lockdown.py` PASS (82 tests total in ai-service suite).

---

## 6. Review Findings Resolution Matrix

| # | Finding | Remediation Applied | Verification Evidence |
|---|---|---|---|
| **F-01** | AI Lockdown bypassed if client injected | Moved test mode checks outside `if client is None:` and installed transport-level interceptor in `conftest.py`. Added 5 regression tests. | `test_ai_lockdown.py` (82 tests PASS in suite, 6 tests re-verified in self-review) |
| **F-02** | Missing document upload & admin document review | Added `POST /trainer-applications/documents` endpoint, Admin document list & download endpoints with audit logging, Mobile document picker & list, Admin Web document/certificate tables & download button. | Unit & integration tests pass across backend, mobile, admin-web. |
| **F-03** | Proposal endpoint naming clarification | Corrected documentation: Trainer creates proposals for a scoped student's active goal via `POST /api/v1/fitness-goals/{goalId}/proposals`, while `GET /api/v1/students/{studentId}/fitness-goal-proposals` lists proposals for that student. | `FitnessGoalProposalIntegrationTest` (41 tests PASS), `FitnessGoalProposalControllerUnitTest` (8 tests PASS). |
| **F-04** | MediaQueryPort semantics | Clarified `allMediaExistAndOwnedBy` (existence & ownership) vs `allMediaExistOwnedAndClean` (enforcing `scan_status = 'CLEAN'`). | `MediaPersistenceAdapterUnitTest` (4 tests PASS). |
| **F-05** | Disentangle blockers for Goals vs Trainer Verification | Clarified that Goals does NOT depend on the ClamAV scanner daemon; only Trainer Verification requires live scanner daemon / real media storage. Both still await manual UI acceptance testing on physical device / AVD. | Documented below in Section 7 & 8. |
| **F-NEW-01** | Documentation endpoint alias misstatement | Đính chính sai sót trong báo cáo tự rà soát trước đó về "alias POST theo studentId": code và contract chỉ hỗ trợ POST theo goalId, không có alias POST theo studentId. Không sửa code/contract. | Tài liệu đã đồng bộ hoàn toàn với controller và OpenAPI. |
| **FINDING-STORAGE-01** | In-memory RAM storage fallback risk of data loss after restart | Đã xóa bỏ hoàn toàn `localCache` (ConcurrentHashMap) và việc nuốt lỗi S3; thêm `StorageUnavailableException` (HTTP 503 `STORAGE_UNAVAILABLE`) khi thiếu cấu hình S3 hoặc lỗi kết nối; thêm `TrainerDocumentNotFoundException` (HTTP 404 `TRAINER_DOCUMENT_NOT_FOUND`) khi không tìm thấy tệp trên S3; bổ sung cơ chế xóa tệp S3 mồ côi (`deleteFile`) khi lưu database thất bại. | `TrainerDocumentStorageServiceTest` (20 tests PASS), `TrainerApplicationServiceUnitTest` (27 tests PASS), `AdminTrainerVerificationServiceUnitTest` (22 tests PASS), `AdminTrainerApplicationIntegrationTest` (26 tests PASS). |
| **FINDING-SCANNER-01** | ClamAV scanner silent clean on unconfigured host | Đã xóa logic trả về "CLEAN" khi thiếu host clamd; chuyển sang fail-closed: nếu `CHAT_CLAMD_INTERNAL_HOST` để trống, socket timeout, connection refused hoặc lỗi giao thức thì ném `ScannerUnavailableException` (HTTP 503 `SCANNER_UNAVAILABLE`). Phát hiện malware ném `ApplicationValidationException` với mã `MALWARE_DETECTED` (HTTP 400). Định dạng không hợp lệ (như chuỗi EICAR trần) bị từ chối ở bước kiểm tra magic bytes (HTTP 400). | `TrainerDocumentStorageServiceTest` (20 tests PASS bao gồm kiểm thử timeout, connection refused, malware detection, protocol error, magic byte validation). |
| **FINDING-A07-01** | Scope Conflation & Progress Photo Authorization Leaks | `MeasurementAccessPolicy` trước đó hardcode kiểm tra `DataScope.BODY_METRICS`. `ProgressPhotoStorageService` gọi nhầm `BODY_METRICS` thay vì `PROGRESS_PHOTOS`. Sửa đổi: Bổ sung `evaluatePhotoAccess` và `evaluatePhotoWriteAccess` với scope độc lập `DataScope.PROGRESS_PHOTOS`. Trong `loadPhotoFile`, kiểm tra chặt chẽ cửa sổ chia sẻ dữ liệu (`historyFrom`, `historyUntil`); nếu ảnh chụp ngoài khoảng thời gian được cấp phép, từ chối với 403 `MEASUREMENT_ACCESS_DENIED`. | `MeasurementApiIntegrationTest` (15 tests PASS bao gồm kiểm thử quyền HLV trong/ngoài cửa sổ chia sẻ, từ chối chéo học viên). |
| **FINDING-A07-02** | Fitness Goal Cross-Student Leakage in Progress Dashboard | `ProgressDashboardController` gọi `currentGoalUseCase.getCurrentFitnessGoal(targetStudentId, true)` mà không kiểm tra quyền `DataScope.FITNESS_GOAL` của actor đối với học viên mục tiêu. Sửa đổi: Tiêm `CoachingAuthorityQuery`; nếu actor là bên thứ ba (HLV) và không được cấp quyền `FITNESS_GOAL`, trường `goal` được giữ `null`. | `MeasurementApiIntegrationTest.getProgressSummary_privacyAndScopeSeparation` PASS (kiểm tra HLV có vs không có scope `FITNESS_GOAL`). |
| **FINDING-A07-03** | Truncation of Lifetime Scope History via Hardcoded 3-Year Cutoff | `ProgressDashboardController` hardcode `effectiveFrom = LocalDate.now().minusYears(3)` cho scope `LIFETIME`, vi phạm bất biến lịch sử thể chất trọn đời. Sửa đổi: Sử dụng ngày tạo tài khoản/hồ sơ học viên (`StudentProfile.createdAt`) từ `GetStudentProfileUseCase` hoặc mốc `2000-01-01` khi tính toán trọn đời. | `MeasurementApiIntegrationTest.getProgressSummary_independentFromAi` PASS (scope `LIFETIME` trả về `AVAILABLE` độc lập với AI). |
| **FINDING-A07-04** | Measurement Correction Incomplete Lifecycle & Audit Gap | Luồng correction thiếu cập nhật trạng thái `SUPERSEDED` trên bản ghi cũ và thiếu ghi nhận bản ghi audit bất biến vào `fitness.audit_logs`. Sửa đổi: Bản ghi mới lưu với trạng thái `VALID` (DB trigger chuyển `SUSPECT` nếu ngoài biên độ), bản ghi cũ cập nhật `superseded_by_id = newId` và `validation_status = 'SUPERSEDED'`. Tiêm `AuditService` ghi nhận sự kiện `MEASUREMENT_CORRECTED` với targetId trỏ vào bản ghi cũ và metadata chứa lý do cùng ID thay thế. | `MeasurementApiIntegrationTest.correctMeasurement_createsSupersededHistory` PASS (xác minh cả cột DB và bản ghi trong `fitness.audit_logs`). |
| **FINDING-A07-05** | PostgreSQL Read-Only Transaction Error on Trainer Access | `MeasurementService.getHistory`, `ProgressPhotoStorageService.listProgressPhotos`, và `loadPhotoFile` được đánh dấu `@Transactional(readOnly = true)`. Khi HLV truy cập, `CoachingAuthorityService.evaluateCurrent` gọi `lockAuthorityBoundary` thực hiện `SELECT ... FOR SHARE`, gây lỗi `PSQLException: ERROR: cannot execute SELECT FOR SHARE in a read-only transaction` (SQLState 25006). Sửa đổi: Chuyển sang `@Transactional` (read-write) tương tự pattern của `FitnessGoalProposalService` để cho phép pessimistic shared lock. | `MeasurementApiIntegrationTest.getMeasurements_trainerPermissions` và `getPhotoFile_sharingWindowAndPrivacyRules` PASS. |
| **FINDING-A07-06** | Mobile Navigation Gaps & Technical UI Exposure | Màn hình `HomeScreen.tsx` và `RelationshipDetailScreen.tsx` thiếu các nút điều hướng trực tiếp tới `/progress`, `/measurements`, và `/coaching/[relationshipId]/progress`. Modal upload ảnh lộ chi tiết kỹ thuật "quét bảo mật ClamAV". Sửa đổi: Thêm đầy đủ nút điều hướng và thay thế text UI thân thiện "quét kiểm tra an toàn". | Mobile test suites (6 suites / 49 tests PASS), `typecheck:mobile` (0 errors), `lint:mobile` (0 errors). |

---

## 7. Handoff Status & Evidentiary Determination

> [!NOTE]
> **Tuyên bố Phạm vi Đánh giá:**  
> Kết luận dưới đây là kết quả **Self-Review** của chính agent thực hiện triển khai và sửa lỗi. Đây không phải là kết luận độc lập từ bên thứ ba và đang **chờ reviewer khác xác nhận**.

- **Phần 1: Trạng thái Mã nguồn & Đủ điều kiện Review (Ready for Review)**
  - Self-review chưa phát hiện finding chặn merge trong phạm vi đã kiểm tra.
  - Mã nguồn tuân thủ các quy tắc kiến trúc trong `AGENTS.md`, không có lỗi biên dịch, không có lỗi linter/OpenAPI schema.
  - **Phân định kết quả kiểm thử:**
    - *Kiểm thử thực sự chạy lại trong phiên self-review hiện tại:* `test_ai_lockdown.py` (6 tests PASS), Admin Web TrainerApplication vitest (13 tests PASS), Mobile TrainerProfileScreen jest (17 tests PASS), Backend Media + Trainer unit tests (79 tests PASS), Contracts linting (0 errors).
    - *Kiểm thử kế thừa từ phiên monorepo trước đó:* Backend full suite (449 tests PASS), AI service full suite (82 tests PASS), Mobile full suite (720 tests / 89 suites PASS), Admin Web full suite (117 tests / 16 suites PASS).
    - *Lưu ý:* Việc các bài kiểm thử tự động vượt qua là điều kiện cần nhưng không đồng nghĩa với việc khẳng định tuyệt đối "không có hồi quy" trong mọi trường hợp biên runtime.

- **Phần 2: Trạng thái Nghiệm thu UI E2E (Chưa hoàn tất nghiệm thu UI E2E — Pending Acceptance)**
  - Chưa khẳng định hoàn thành 100% tính năng khi chưa chạy nghiệm thu tương tác trực tiếp trên giao diện thực tế.
  - Chưa khẳng định đã kiểm chứng storage/scanner thật khi mới chỉ kiểm chứng qua lớp mock/local cache trong automated unit tests.
  - **Blockers thực tế đã quan sát (Factual Observations):**
    1. **Mục tiêu Thể chất (Goals - M2):** Không bị chặn bởi ClamAV daemon hay S3 storage (luồng Goals không upload binary). Chưa hoàn tất nghiệm thu UI E2E trên thiết bị di động thật hoặc Android Virtual Device (AVD) / iOS Simulator.
    2. **Xác minh Huấn luyện viên (Trainer Verification - M1/M8):**
       - Thiếu hạ tầng daemon ClamAV thật (`clamd` port 3310) và lưu trữ tương thích S3 thật (ví dụ MinIO cục bộ) đang chạy dạng background service để xác thực quét malware nhị phân trực tiếp qua stream `zINSTREAM`.
       - Chưa chạy nghiệm thu thủ công các bước chọn file và tương tác UI trên thiết bị di động thật / AVD.

---

## 8. Hướng dẫn Nghiệm thu Thực tế Có thể Chạy Được (Runnable Acceptance Guide)

Phần này cung cấp tài liệu chi tiết để reviewer hoặc tester vận hành độc lập các luồng kiểm thử giao diện và tích hợp thực tế.

### 8.1 Lệnh khởi chạy các dịch vụ (Theo cấu hình thực tế của repository)

Build tool của backend được xác nhận là **Maven** (`pom.xml`, Java 21, Spring Boot 4.1.1); wrapper là `./mvnw` (Linux/macOS) và `mvnw.cmd` (Windows). Repository không sử dụng Gradle.

```bash
# 1. Khởi chạy Backend (Spring Boot)
# Working Directory: services/backend
cd services/backend
./mvnw spring-boot:run
# Trên Windows: .\mvnw.cmd spring-boot:run
# Hoặc từ repo root: .\services\backend\mvnw.cmd -f services/backend/pom.xml spring-boot:run
# Hoặc chạy script có sẵn: .\services\backend\run-dev.ps1

# 2. Khởi chạy Admin Web (Vite + React)
# Working Directory: Repo root
npm run dev:admin
# Hoặc từ apps/admin-web:
# cd apps/admin-web && npm run dev
# Mở trình duyệt tại: http://localhost:5173

# 3. Khởi chạy Mobile (Expo / React Native)
# Working Directory: Repo root
npm run dev:mobile
# Hoặc từ apps/mobile:
# cd apps/mobile && npx expo start
# Nhấn 'a' để mở Android Virtual Device (AVD) hoặc quét mã QR qua Expo Go
```

### 8.2 Biến cấu hình cần thiết (Mẫu không nhạy cảm)

Khớp chính xác với cấu hình `services/backend/src/main/resources/application.yml` và `docker-compose.yml`:

- **Backend (`application-dev.yml` hoặc biến môi trường):**
  - `DATABASE_URL=jdbc:postgresql://localhost:5433/digital_fitness` (Port 5433 theo docker-compose)
  - `DATABASE_USERNAME=fitness_app`
  - `DATABASE_PASSWORD=change-me`
  - `CHAT_S3_ENDPOINT=http://localhost:9000` (Điểm cuối MinIO local)
  - `CHAT_S3_PRIVATE_BUCKET=fitness-media`
  - `CHAT_S3_ACCESS_KEY=local-minio-key`
  - `CHAT_S3_SECRET_KEY=local-minio-secret`
  - `CHAT_CLAMD_INTERNAL_HOST=localhost`
  - `CHAT_CLAMD_INTERNAL_PORT=3310`
  - `AI_SERVICE_BASE_URL=http://localhost:8000`
- **Mobile (`apps/mobile/.env`):**
  - `EXPO_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1`
- **Admin Web (`apps/admin-web/.env`):**
  - `VITE_API_BASE_URL=http://localhost:8080/api/v1`

### 8.3 Health check & Xác nhận dịch vụ sẵn sàng

- **Backend Actuator Health:** `curl -s http://localhost:8080/actuator/health` -> Phản hồi `{"status":"UP"}`. Endpoint quản lý cấu hình tại `management.endpoints.web.exposure.include: health,info`.
- **S3-compatible Storage (MinIO local) & Cơ chế Fail-Closed:** `curl -s http://localhost:9000/minio/health/live` -> HTTP 200.
  - Cơ chế fail-closed của storage: `TrainerDocumentStorageService` không sử dụng cache RAM / fallback in-memory. Nếu thiếu cấu hình S3 hoặc MinIO tắt/lỗi, backend ném `StorageUnavailableException` và trả về **HTTP 503 Service Unavailable** với mã lỗi `STORAGE_UNAVAILABLE`. Khi tải tài liệu, nếu không tìm thấy tệp trên S3, backend ném `TrainerDocumentNotFoundException` và trả về **HTTP 404 Not Found** với mã lỗi `TRAINER_DOCUMENT_NOT_FOUND`.
  - Dọn dẹp tệp mồ côi (Orphan Cleanup): Nếu upload S3 thành công nhưng lưu metadata vào database gặp lỗi, backend tự động gọi `deleteFile` để dọn dẹp tệp trên S3.
- **ClamAV daemon & Cơ chế Fail-Closed:**
  - Kiểm tra kết nối TCP tới daemon: Mở socket TCP tới `localhost:3310` gửi `PING\0`, nhận phản hồi `PONG`.
  - Cơ chế fail-closed của scanner: Nếu `CHAT_CLAMD_INTERNAL_HOST` để trống, socket timeout, connection refused hoặc lỗi giao thức, `TrainerDocumentStorageService` ném `ScannerUnavailableException`. `GlobalExceptionHandler` bắt exception này và trả về **HTTP 503 Service Unavailable** với thân JSON:
    ```json
    {
      "errorCode": "SCANNER_UNAVAILABLE",
      "message": "Antivirus scanner is currently unavailable"
    }
    ```
  - Khi phát hiện malware (`FOUND`), backend ném `ApplicationValidationException` trả về **HTTP 400 Bad Request** với thân JSON:
    ```json
    {
      "errorCode": "VALIDATION_FAILED",
      "message": "Uploaded file is infected with malware",
      "fieldErrors": [
        {
          "field": "file",
          "code": "MALWARE_DETECTED",
          "message": "Uploaded file contains malware"
        }
      ]
    }
    ```
  - Tệp có chuỗi EICAR dạng văn bản thuần bị từ chối ngay ở bước xác thực magic bytes với HTTP 400 `VALIDATION_FAILED` (mã lỗi `InvalidType`). Chỉ các tệp PDF/PNG/JPEG hợp lệ mới được đưa qua stream ClamAV.

### 8.4 Chuẩn bị tài khoản kiểm thử (Roles & Capabilities)

1. **Tài khoản HLV (Trainer):**
   - Tài khoản người dùng có vai trò `TRAINER`, trạng thái `ACTIVE`.
   - Có hồ sơ HLV ở trạng thái `NOT_SUBMITTED` (đối với kiểm thử nộp mới) hoặc `REJECTED` (đối với kiểm thử nộp lại).
   - Có mối quan hệ huấn luyện viên - học viên đang hoạt động với Học viên 1.
2. **Tài khoản Học viên (Student - Học viên 1):**
   - Tài khoản người dùng có vai trò `STUDENT`, trạng thái `ACTIVE`.
   - Đang có một mục tiêu thể chất đang hoạt động (Active Fitness Goal).
3. **Tài khoản Quản trị viên (Admin):**
   - Tài khoản người dùng có vai trò `ADMIN`, trạng thái `ACTIVE`.

---

### 8.5 Kịch bản Nghiệm thu Luồng 1: Mục tiêu Thể chất (Physical Fitness Goals)
*Lưu ý: Luồng này hoàn toàn KHÔNG phụ thuộc vào scanner hay storage media.*

1. **HLV tạo đề xuất điều chỉnh mục tiêu:**
   - **Thao tác:** Đăng nhập HLV trên Mobile -> Vào `RelationshipDetailScreen` của Học viên 1 -> Bấm nút điều hướng mục tiêu học viên (`(app)/coaching/[relationshipId]/goal`).
   - **Xác nhận UI:** Màn hình `TrainerStudentGoalScreen` tải mục tiêu hiện tại (`GET /api/v1/students/{studentId}/fitness-goals/current`) và lịch sử đề xuất (`GET /api/v1/students/{studentId}/fitness-goal-proposals`).
   - **Thao tác:** Bấm nút "Đề xuất điều chỉnh mục tiêu" (`propose-goal-button`) -> Mở modal `CreateGoalProposalModal` -> Nhập lý do (bắt buộc), tiêu đề mới, thời gian dự kiến -> Bấm "Gửi đề xuất".
   - **Kỳ vọng:** Client gọi `POST /api/v1/fitness-goals/{goalId}/proposals`. Modal đóng lại, danh sách đề xuất hiển thị thêm đề xuất mới ở trạng thái `PENDING`. Nút gửi bị vô hiệu hóa trong lúc loading để tránh double-submit.
2. **Học viên xem xét và quyết định đề xuất:**
   - **Thao tác:** Đăng nhập Học viên trên Mobile -> Vào `CurrentGoalScreen` -> Chuyển sang chi tiết đề xuất `GoalProposalDetailScreen`.
   - **Xác nhận UI:** Hiển thị so sánh song song mục tiêu hiện tại và thay đổi được đề xuất kèm lý do của HLV.
   - **Kịch bản Chấp thuận (Accept):** Bấm "Chấp nhận đề xuất" -> Client gọi `POST /api/v1/fitness-goal-proposals/{proposalId}/decisions` với `decision = 'ACCEPT'`. Mục tiêu lập tức chuyển sang Version mới (hoặc Transition journey); đề xuất chuyển sang trạng thái `ACCEPTED`.
   - **Kịch bản Từ chối (Reject):** Bấm "Từ chối đề xuất" -> Nhập lý do từ chối (bắt buộc) -> Bấm xác nhận từ chối. Đề xuất chuyển sang `REJECTED`, mục tiêu hiện hành giữ nguyên không thay đổi.
3. **Kiểm tra tính nhất quán & Bằng chứng:**
   - Tải lại màn hình để kiểm tra lịch sử mục tiêu không bị ghi đè dữ liệu cũ.
   - **Bằng chứng cần lưu:** Ảnh chụp màn hình HLV tạo đề xuất, màn hình học viên đối chiếu quyết định, màn hình mục tiêu sau khi cập nhật version.

---

### 8.6 Kịch bản Nghiệm thu Luồng 2: Xác minh Huấn luyện viên (Trainer Verification)
*Lưu ý: Luồng này yêu cầu hạ tầng lưu trữ tương thích S3 và ClamAV daemon đang chạy.*

1. **HLV đính kèm tài liệu & nộp hồ sơ:**
   - **Thao tác:** Đăng nhập HLV trên Mobile -> Vào `TrainerProfileScreen`.
   - **Xác nhận UI:** Trạng thái hồ sơ hiển thị `NOT_SUBMITTED`.
   - **Thao tác:** Bấm "+ Attach Document" -> Dùng Document Picker chọn tệp PDF, PNG hoặc JPEG hợp lệ (< 10MB).
   - **Kỳ vọng:** Client gọi `POST /api/v1/trainer-applications/documents`. Backend quét virus qua ClamAV `zINSTREAM`, lưu vào storage `verification-docs` với quyền `PRIVATE`, và trả về `TrainerDocumentUploadResponse`. Tệp xuất hiện trong danh sách đính kèm với nhãn "Scanned & Clean".
   - **Thao tác:** Nhấn nút "X" trên một tài liệu để kiểm tra tính năng bỏ đính kèm (chỉ loại bỏ khỏi danh sách nộp, không xóa dữ liệu lịch sử).
   - **Thao tác:** Nhập ghi chú ứng viên -> Bấm "Submit Application".
   - **Kỳ vọng:** Client gọi `POST /api/v1/trainer-applications` kèm `documentMediaIds`. Trạng thái chuyển thành `PENDING`, hiển thị banner "APPLICATION UNDER REVIEW".
2. **Admin kiểm tra hồ sơ, tài liệu & quyết định:**
   - **Thao tác:** Đăng nhập Admin trên trình duyệt -> Truy cập `http://localhost:5173/trainer-applications`.
   - **Xác nhận UI:** Danh sách hiển thị hồ sơ HLV vừa nộp với trạng thái `PENDING`. Bấm vào chi tiết hồ sơ (`TrainerApplicationDetailPage`).
   - **Xác nhận UI:** Hiển thị thông tin HLV, ghi chú ứng viên, bảng chứng chỉ chuyên môn và bảng tài liệu xác thực kèm badge `CLEAN`.
   - **Thao tác:** Bấm nút "Tải xuống" bên cạnh tài liệu.
   - **Kỳ vọng:** Client gọi `GET /api/v1/admin/trainer-applications/{applicationId}/documents/{mediaId}/download`. File tải về máy nguyên vẹn. Backend ghi nhận bản ghi audit bất biến `ADMIN_TRAINER_DOCUMENT_ACCESSED`. Response header chứa `Cache-Control: no-store` và `Content-Disposition: attachment`.
   - **Thử nghiệm Từ chối (Reject):** Bấm "Từ chối hồ sơ" -> Nhập lý do từ chối -> Xác nhận. Hồ sơ chuyển `REJECTED`, ghi vết audit `TRAINER_APPLICATION_REJECTED`.
3. **HLV nhận thông báo từ chối & nộp lại (Resubmit):**
   - **Thao tác:** HLV mở lại `TrainerProfileScreen` trên Mobile.
   - **Xác nhận UI:** Hiển thị thông báo bị từ chối kèm lý do chính xác từ Admin.
   - **Thao tác:** Đính kèm tài liệu mới bổ sung -> Nhập giải trình -> Bấm "Resubmit Application".
   - **Kỳ vọng:** Hồ sơ chuyển lại thành `PENDING`.
4. **Admin phê duyệt (Approve) & Bật tìm kiếm:**
   - **Thao tác:** Admin mở lại hồ sơ trên Admin Web -> Bấm "Phê duyệt hồ sơ" -> Nhập ghi chú duyệt -> Xác nhận.
   - **Kỳ vọng:** Hồ sơ và profile HLV chuyển sang `VERIFIED`. Ghi vết audit `TRAINER_APPLICATION_APPROVED`.
   - **Thao tác:** HLV trên Mobile bật công tắc "Accepting Students".
   - **Kỳ vọng:** Gọi `GET /api/v1/coaching/trainers`, HLV xuất hiện trong danh sách tìm kiếm công khai.
5. **Bằng chứng cần lưu:**
   - Ảnh chụp tải tài liệu trên Mobile, bảng tài liệu trên Admin Web, tệp tải về máy qua Admin, bản ghi audit trong `fitness.audit_logs`.
   - *Lưu ý nguyên tắc:* Không dùng cập nhật database trực tiếp để giả lập trạng thái scanner hay bỏ qua các bước giao diện. Nghiệp vụ chỉ yêu cầu Document Picker (không bắt buộc camera vật lý). Môi trường cục bộ tương thích S3 (như MinIO) là đáp ứng kiến trúc, không bắt buộc cloud AWS S3.

### 8.7 Kịch bản Nghiệm thu Luồng 3: Số đo & Tiến độ Thể chất (Measurements & Progress - A07/M5)

1. **Học viên ghi nhận số đo mới:**
   - **Thao tác:** Đăng nhập Học viên trên Mobile -> Vào `HomeScreen` -> Bấm "Nhật ký số đo cơ thể →" (`/measurements`).
   - **Xác nhận UI:** Màn hình `MeasurementsScreen` tải danh sách số đo (`GET /api/v1/measurements`) và danh mục chỉ số (`GET /api/v1/metrics`).
   - **Thao tác:** Bấm "+ Thêm số đo" (`add-measurement-btn`) -> Chọn chỉ số "Weight", nhập `74.5`, chọn đơn vị `KG`, thời điểm đo -> Bấm "Lưu số đo".
   - **Kỳ vọng:** Gọi `POST /api/v1/measurements`. Số đo mới xuất hiện đầu danh sách với badge `VALID`.
   - **Thử nghiệm ngoài biên độ (Suspect trigger):** Nhập cân nặng `600 kg` (vượt ngưỡng `valid_max = 500`). Bản ghi được lưu nhưng trigger DB tự động gán trạng thái `SUSPECT` kèm cảnh báo màu vàng.

2. **Học viên hiệu chỉnh số đo (Correction Lifecycle):**
   - **Thao tác:** Trên thẻ số đo vừa nhập, bấm nút "Sửa" (`correct-btn`).
   - **Xác nhận UI:** Mở modal `CorrectMeasurementModal` hiển thị giá trị cũ.
   - **Thao tác:** Nhập giá trị đã hiệu chỉnh `74.0`, lý do hiệu chỉnh (bắt buộc, ví dụ "Nhập nhầm do đọc lệch cân") -> Bấm "Xác nhận hiệu chỉnh".
   - **Kỳ vọng:** Gọi `POST /api/v1/measurements/{id}/correction`. Bản ghi cũ chuyển sang trạng thái `SUPERSEDED` và không thể hiệu chỉnh lần 2 (409 Conflict). Bản ghi mới được tạo với liên kết `supersededById`. Backend tạo bản ghi audit bất biến `MEASUREMENT_CORRECTED` trong `fitness.audit_logs`.

3. **Học viên tải lên ảnh tiến độ (Progress Photos):**
   - **Thao tác:** Vào "Bảng điều khiển tiến độ →" (`/progress`) -> Bấm "+ Thêm ảnh" (`upload-photo-btn`).
   - **Xác nhận UI:** Mở `UploadProgressPhotoModal`, chọn góc chụp `FRONT`, nhập cân nặng tại thời điểm `74.0 kg`, ghi chú -> Chọn ảnh từ thư viện ảnh -> Bấm "Tải lên ảnh".
   - **Kỳ vọng:** Gọi `POST /api/v1/measurements/progress-photos`. Backend quét virus qua ClamAV `zINSTREAM`, lưu S3 và trả về `ProgressPhotoResponse`. Ảnh xuất hiện trong dải ảnh tiến độ ngang.
   - **Kiểm tra xóa:** Bấm nút xóa trên ảnh -> Xác nhận -> Gọi `DELETE /api/v1/measurements/progress-photos/{id}`. Ảnh bị soft-delete và biến mất khỏi danh sách.

4. **Bảng điều khiển tiến độ (Progress Dashboard - ST-09):**
   - **Xác nhận UI:** Màn hình `ProgressDashboardScreen` gọi `GET /api/v1/progress/summary?scope=CURRENT_GOAL`. Hiển thị thẻ Mục tiêu hiện tại, bảng biến thiên số đo (`MetricTrendDelta`), lưới chỉ số tuân thủ buổi tập (`rawCompletionRate`, `totalVolumeKg`), và tín hiệu duy trì tập luyện (`ContinuitySignal`). Hoàn toàn độc lập với AI.
   - **Thao tác:** Bấm chuyển tab "Toàn bộ thời gian (Lifetime)" -> Gọi `GET /api/v1/progress/summary?scope=LIFETIME`. Thẻ mục tiêu ẩn đi, hiển thị toàn bộ biến thiên số đo từ khi tạo tài khoản học viên.

5. **HLV kiểm tra tiến độ học viên theo quan hệ huấn luyện:**
   - **Thao tác:** Đăng nhập HLV trên Mobile -> Vào `RelationshipDetailScreen` của học viên -> Bấm "Tiến độ và số đo học viên" (`/coaching/[relationshipId]/progress`).
   - **Xác nhận UI:** Mở `TrainerStudentProgressScreen` ở chế độ chỉ đọc (`readOnly = true`), ẩn các nút thêm/sửa/xóa số đo và upload ảnh.
   - **Kỳ vọng bảo mật & riêng tư:**
     - Nếu học viên chỉ chia sẻ `BODY_METRICS`: HLV xem được số đo nhưng thẻ mục tiêu hiển thị `null`.
     - Nếu học viên chia sẻ `PROGRESS_PHOTOS` với khoảng thời gian `[historyFrom, historyUntil]`: HLV chỉ xem và tải được ảnh nằm trong khoảng thời gian này. Ảnh ngoài khoảng thời gian bị từ chối 403 `MEASUREMENT_ACCESS_DENIED`.
     - Học viên khác gọi API với `studentId` của học viên này lập tức bị từ chối 403 `MEASUREMENT_ACCESS_DENIED` / `PROGRESS_ACCESS_DENIED`.

---

## 9. Lịch sử Xử lý FINDING-READONLY-TX-01 & Kiểm chứng Hạ tầng Thực tế

### 9.1 Chi tiết Finding & Nguyên nhân gốc
- **Mã:** `FINDING-READONLY-TX-01`
- **Lỗi:** Gọi `GET /api/v1/students/{studentId}/fitness-goals/current` hoặc `GET /api/v1/students/{studentId}/fitness-goal-proposals` bằng tài khoản Trainer gây HTTP 500 do `PSQLException: ERROR: cannot execute SELECT FOR SHARE in a read-only transaction` (SQLState 25006).
- **Nguyên nhân:** Class `FitnessGoalProposalService` có `@Transactional(readOnly = true)`. Quyền truy cập của HLV được kiểm tra qua `trainerAuthorityPort.verifyTrainerCanViewProposal()` -> `CoachingAuthorityService.evaluateCurrent()` -> `store.lockAuthorityBoundary(studentId, actorId)` -> `SELECT user_id FROM fitness.student_profiles WHERE user_id = ? FOR SHARE;`. PostgreSQL cấm hoàn toàn khóa dòng trong giao dịch chỉ đọc.
- **Nguyên nhân test trước bỏ sót:** Test cho hai endpoint này trước đó là unit test mock (`@MockBean` usecase hoặc `@Mock GoalTrainerAuthorityPort`), không có integration test nào gọi hai endpoint này qua Spring context và PostgreSQL thật.

### 9.2 Cách sửa tối thiểu & Bảo toàn kiến trúc
- Bổ sung `@Transactional` (read-write) vào `getStudentCurrentGoalForTrainer` và `getStudentProposalsForTrainer` trong `FitnessGoalProposalService.java`.
- Kế thừa mẫu thiết kế sẵn có trong `WorkoutPlanQueryService` và `NutritionBasicService.readForTrainer`.
- Bảo toàn nguyên vẹn cơ chế khóa chia sẻ `FOR SHARE` để đảm bảo tính an toàn đồng thời khi học viên thay đổi/thu hồi quyền chia sẻ.

### 9.3 Kết quả Kiểm chứng
- Đã bổ sung 5 integration test trên PostgreSQL Testcontainers trong `FitnessGoalProposalIntegrationTest.java`: Tất cả đều **PASS**.
- Đã chạy 132 unit test module goal: **132 PASS, 0 FAIL**.
- Đã kiểm chứng trực tiếp trên backend chạy live cổng 8080 kết nối PostgreSQL thật cổng 5433: Cả hai endpoint đều trả lời **HTTP 200 OK** với dữ liệu đầy đủ.
- **Phân định rõ ràng:** Đã hoàn thành nghiệm thu API trên hạ tầng dịch vụ thật; phần thao tác UI trên thiết bị di động Native vẫn cần bước nghiệm thu trực tiếp bằng mắt/tay của reviewer.
- **Về Object Storage:** Đang sử dụng container `adobe/s3mock:latest` (port 9000); không coi đây là bằng chứng tương thích production AWS S3/MinIO. Tính bền vững sau restart container chưa được thử nghiệm.

---

## 10. Lịch sử Rà soát & Xử lý Finding Nghiệm thu A07/M5 (Measurements & Progress)

### 10.1 Danh sách Finding & Bản sửa lỗi Kiến trúc

1. **FINDING-A07-LIFETIME-BOUNDARY (Bảo toàn lịch sử quá khứ):**
   - *Vấn đề:* Trước đây tính mốc bắt đầu của `LIFETIME` dashboard dựa trên `StudentProfile.createdAt` kèm fallback tùy ý `2000-01-01`. Nếu học viên nhập số đo lịch sử (hoặc bài tập lịch sử) có thời điểm đo trước ngày tạo tài khoản, các số đo hợp lệ đó bị cắt bỏ ngầm khỏi thống kê xu hướng.
   - *Khắc phục:* Bổ sung phương thức `MeasurementProgressQuery.getEarliestObservationTime(actorId, studentId)` truy vấn `MIN(measured_at)` (đồng thời tôn trọng giới hạn `historyFrom` của HLV nếu áp dụng). `ProgressDashboardController` tính `effectiveFrom` là giá trị nhỏ nhất giữa `StudentProfile.createdAt`, `earliestObservationTime`, và `earliestWorkout`. Loại bỏ hoàn toàn mốc `2000-01-01`.

2. **FINDING-A07-CONCURRENT-CORRECTION (Chống rẽ nhánh lịch sử đa kế vị):**
   - *Vấn đề:* Khi có nhiều yêu cầu hiệu chỉnh đồng thời cùng một số đo gốc, nếu chỉ kiểm tra điều kiện ở tầng ứng dụng thì có thể xảy ra race condition tạo ra nhiều bản ghi kế vị cho cùng một bản ghi cũ.
   - *Khắc phục:* Bổ sung điều kiện phòng vệ ở mức câu lệnh SQL `UPDATE fitness.measurements SET superseded_by_id = :newId WHERE id = :oldId AND superseded_by_id IS NULL`, kiểm tra số dòng cập nhật chính xác bằng 1. Nếu `updated == 0`, lập tức ném `MeasurementFailure(MEASUREMENT_CORRECTION_CONFLICT)` trả về HTTP 409 Conflict.

3. **FINDING-A07-AUDIT-ROLLBACK (Tính nguyên tử của Giao dịch Hiệu chỉnh):**
   - *Vấn đề:* Đảm bảo việc ghi audit trail bất biến `MEASUREMENT_CORRECTED` nằm cùng transaction với thao tác cập nhật số đo và targetId trỏ đúng vào bản ghi bị hiệu chỉnh.
   - *Khắc phục:* `MeasurementService.correctMeasurement` chạy trong `@Transactional`, ghi audit đồng bộ. Kiểm thử tích hợp đã chứng minh nếu hệ thống audit phát sinh lỗi (`AuditWriteFailureException`), toàn bộ giao dịch cập nhật số đo bị rollback 100%, không để lại trạng thái rách dữ liệu.

4. **FINDING-A07-PHOTO-SOFT-DELETE-MAPPER (Lỗi ánh xạ cột PostgreSQL):**
   - *Vấn đề:* Trong `ProgressPhotoStorageService.deletePhoto`, row mapper gọi `rs.getObject("mediaId")` thay vì `rs.getObject("media_id")`, gây `PSQLException: The column name mediaId was not found in this ResultSet`.
   - *Khắc phục:* Sửa chính xác thành `(UUID) rs.getObject("media_id")`.

5. **FINDING-A07-READONLY-TX (Khóa FOR SHARE trong giao dịch chỉ đọc):**
   - *Vấn đề:* Tương tự FINDING-READONLY-TX-01, các phương thức `getHistory`, `listProgressPhotos`, và `loadPhotoFile` trong `MeasurementService` và `ProgressPhotoStorageService` có `@Transactional(readOnly = true)`. Khi HLV gọi, cơ chế phân quyền kích hoạt `SELECT ... FOR SHARE` gây lỗi PostgreSQL 25006.
   - *Khắc phục:* Chuyển thành `@Transactional` (read-write) theo đúng chuẩn kiến trúc của nền tảng.

6. **FINDING-A07-ERROR-ADVICE (Xử lý lỗi ProgressFailure):**
   - *Vấn đề:* `ProgressDashboardController` ném `ProgressFailure` khi từ chối quyền truy cập nhưng `MeasurementErrorAdvice` thiếu handler tương ứng.
   - *Khắc phục:* Bổ sung `@ExceptionHandler(ProgressFailure.class)` trả về HTTP 403 Forbidden với mã lỗi `PROGRESS_ACCESS_DENIED`.

7. **FINDING-A07-GET-BY-ID-AUTH (Tối ưu hóa và kiểm soát cửa sổ lịch sử cho getMeasurementById):**
   - *Vấn đề (phát hiện bởi Independent Code Reviewer subagent):* `MeasurementController.getMeasurementById` kiểm tra quyền gián tiếp qua `getHistory` gây dư thừa 1 câu lệnh đếm dữ liệu và không kiểm tra chặt chẽ `historyFrom`/`historyUntil` cho bản ghi đơn lẻ.
   - *Khắc phục:* Bổ sung phương thức `MeasurementService.getMeasurementById(actorId, id)`, gọi trực tiếp `accessPolicy.evaluateAccess(actorId, studentId, BODY_METRICS)` và kiểm tra `historyFrom`/`historyUntil` trực tiếp trên `measuredAt` của bản ghi, tối ưu hiệu năng và nâng cao tính bảo mật.

### 10.2 Tổng hợp Bằng chứng Tự động hóa Thực tế (Automated Verification)

- **Backend Integration & Unit Tests (97 tests đã chạy trong phiên và PASS 100%):**
  - `MeasurementApiIntegrationTest`: **22/22 tests PASS** (Gồm 15 test luồng chính + 7 test ranh giới & rủi ro cao: concurrent correction 409 conflict, audit rollback, lifetime historical observation coverage, goal isolation paired testing trên cả CURRENT_GOAL và LIFETIME, photo soft-delete hiding/404, dynamic permission revocation, storage/scanner fail-closed).
  - `ProgressEngineIntegrationTest`: **17/17 tests PASS** (Động học tính toán tiến độ, trend delta, tín hiệu liên tục).
  - `MeasurementProgressIntegrationTest`: **15/15 tests PASS** (Source queries, watermark tính toán bằng SHA-256, VALID/SUSPECT/SUPERSEDED, clipping theo HLV time-scope, Repeatable Read snapshot isolation).
  - `WorkoutProgressAggregationIntegrationTest`: **28/28 tests PASS** (Tổng hợp khối lượng và tuân thủ buổi tập theo cửa sổ thời gian).
  - `AiProgressSummarizationServiceUnitTest` & `AiProgressSummaryControllerUnitTest`: **15/15 tests PASS** (F04 Context Builder, xác thực đầu ra thời gian thực, timeout và side-channel leak defense).
- **Mobile Tests (Expo / Jest):**
  - 6 test suites, **49/49 tests PASS** (`measurementApi.test.ts`, `aiProgressSummaryApi.test.ts`, `progressSummaryNavigation.test.tsx`, `ProgressDashboardScreen.test.tsx`, `MeasurementsScreen.test.tsx`, `ProgressSummaryScreen.test.tsx`).
  - Typecheck (`tsc --noEmit`): **0 errors**.
  - Lint (`expo lint`): **0 errors**.
- **Contract & Schema Validation:**
  - `npm run validate:contracts`: Redocly OpenAPI hợp lệ, 9 AI schemas & 40 contract fixtures đạt chuẩn Ajv 2020.
  - `npm run test:contracts`: **21/21 tests PASS**.

### 10.3 Kết quả Kiểm tra Migration V56 (Catalogs & Seeds)

- **File Migration:** `V56__measurement_catalogs_and_progress_seed.sql`.
- **Thứ tự & Va chạm:** Tiếp nối trực tiếp sau `V55__measurement_erasure_tombstones.sql` trên `main` (commit `1cc3ffa`). Không có va chạm số hiệu phiên bản trên local repository.
- **Nội dung Seed:** Khởi tạo chuẩn xác 14 đơn vị đo (`measurement_units`), 16 chỉ số định lượng (`metric_definitions`), 7 phương pháp đo (`measurement_methods`), và 9 nguồn thu thập dữ liệu (`measurement_sources`). Toàn bộ sử dụng `ON CONFLICT (code) DO NOTHING;` đảm bảo tính lũy kế (idempotent).
- **Bằng chứng Thực thi:** Đã áp dụng thành công trên cơ sở dữ liệu PostgreSQL Testcontainers trong các lượt chạy của `MeasurementApiIntegrationTest`, `ProgressEngineIntegrationTest`, `MeasurementProgressIntegrationTest`, và `WorkoutProgressAggregationIntegrationTest`.

### 10.4 Báo cáo Review Độc lập & Giới hạn Bàn giao (Review Gates & Limitations)

1. **Rà soát Độc lập (Independent Sub-agent Review):**
   - Thực hiện bởi Sub-agent đóng vai trò `Independent Code Reviewer` (ID: `71633fb6-cc9c-44d3-8914-e50d0cfcb2ea`) ở chế độ chỉ đọc (read-only).
   - Đánh giá kiến trúc: Tuân thủ nghiêm ngặt Modular Monolith, DDD, nguyên tắc dữ liệu thiếu là `NULL`/`UNKNOWN` (không gán 0).
   - Đã xử lý triệt để khuyến nghị về `getMeasurementById` tại `MeasurementService.java` và `MeasurementController.java`.
   - *Giới hạn:* Đây là review độc lập bằng agent tự động; **không** thay thế cho bước phê duyệt chính thức của reviewer con người (Human Peer Review Pending).
2. **Kiểm chứng Giao diện Thực tế (UI Acceptance):**
   - Trạng thái: **UI ACCEPTANCE PENDING**.
   - Các màn hình `MeasurementsScreen`, `ProgressDashboardScreen`, `TrainerStudentProgressScreen` đã được bao phủ bởi 49 Jest tests và kiểm tra tĩnh TypeScript/Lint 0 lỗi.
   - Cần reviewer kiểm thử trực quan trên thiết bị Native vật lý theo kịch bản 10 bước tại Mục 8.7 trước khi phát hành.
3. **Kiểm chứng Hạ tầng ClamAV & Object Storage:**
   - Trạng thái: **OPERATIONAL VERIFICATION PENDING**.
   - Được kiểm thử và bao phủ 100% trong môi trường Testcontainers / S3Mock / Adapter Unit Tests (chứng minh fail-closed khi daemon offline hoặc gặp malware).
   - Việc tích hợp với ClamAV daemon thật và dịch vụ lưu trữ S3/MinIO production cần thực hiện trên môi trường Staging/Production thực tế.

### Khắc phục upload ảnh trên Android — 09/11/2026

- Người dùng phát hiện form upload yêu cầu nhập URI thủ công và báo lỗi mạng. Code còn fallback sang `file://mock-progress-photo.jpg`, gán cố định JPEG và gửi `takenAt` dạng ngày thay vì Instant.
- Đã thay ô URI bằng DocumentPicker JPEG/PNG với `copyToCacheDirectory: true`, preview ảnh và tên tệp. Không gửi khi chưa chọn ảnh; kiểm tra giới hạn 10 MB; giữ đúng tên/MIME, gửi `takenAt` ISO Instant và giữ form khi request thất bại. Bỏ tuyên bố mã hóa chưa được chứng minh trong thông báo UI.
- Đã chạy 3 suite `UploadProgressPhotoModal`, `ProgressDashboardScreen`, `measurementApi`: 22 test đạt, gồm 5 regression test mới cho modal. Mobile TypeScript, lint và `git diff --check` đạt.
- Môi trường local A07 đã có PostgreSQL riêng migrate V56, Backend health UP, ClamAV thật phản hồi PONG và S3Mock ghi/đọc probe thành công. Những kiểm tra dependency này chưa thay thế bằng chứng upload ảnh thành công qua UI; chờ người dùng chọn lại ảnh và xác nhận kết quả end-to-end.

### Khắc phục multipart Expo và đọc danh sách ảnh — 09/11/2026

- Sau khi chọn ảnh thật, upload vẫn thất bại trước khi đến Backend: Expo SDK 57 mặc định dùng Expo fetch; serializer này không hỗ trợ multipart file dạng `{ uri, name, type }`. Đã chuyển luồng upload ảnh sang đọc byte của file cục bộ thành Blob, đặt đúng MIME/tên file như pattern Chat hiện có. Regression test xác minh Blob và kích thước byte, thay vì chỉ kiểm tra `FormData` tồn tại.
- Đã thao tác nút upload trong emulator với ảnh mẫu `a07-test.png`, cân nặng 70 kg, qua ClamAV daemon thật và S3Mock local. Backend trả HTTP 201; bước list sau upload phát hiện thêm `ClassCastException` do cast trực tiếp `weight_unit_id` từ JDBC Integer sang Short.
- Đã sửa mapper nhận Number và chuyển sang Short, bảo toàn null; mở rộng integration test upload kèm cân nặng rồi list để tái hiện HTTP 500 trước sửa và xác nhận HTTP 200 sau sửa.
- Kiểm chứng sau sửa: MeasurementApiIntegrationTest 22/22 đạt trên PostgreSQL Testcontainers (BUILD SUCCESS); 4 suite Mobile liên quan gồm 27 test đạt; TypeScript và lint đạt. Backend A07 local đã được khởi động lại với bản sửa; không xóa bản ghi ảnh vừa upload.
- Đây là bằng chứng cho luồng ảnh mẫu trên local, không phải nghiệm thu toàn bộ A07/M5 hay storage production.

### Khắc phục safe area Nhật ký số đo Android — 09/11/2026

- Người dùng xác nhận Dashboard tiến độ hoạt động tốt; Nhật ký số đo chưa nghiệm thu được vì nút nhập nằm trong vùng thanh trạng thái/camera Android.
- Đã bọc MeasurementsScreen bằng SafeAreaView của react-native-safe-area-context; tách tiêu đề/nút nhập khỏi hàng bộ lọc, cho header xuống dòng khi thiếu chỗ và bảo đảm nút có chiều cao tối thiểu 44.
- Đã quan sát trực tiếp Pixel 8: thanh thao tác nằm dưới thanh trạng thái, bấm nút Nhập số đo mở được modal Ghi nhận số đo mới. Chưa nhập/lưu dữ liệu thay người dùng.
- 5 test MeasurementsScreen, Mobile TypeScript, lint và git diff --check đạt. Nghiệm thu nhập/correction số đo thực tế vẫn chờ người dùng tiếp tục thao tác.

### Xác nhận nghiệm thu chức năng của người dùng — 09/11/2026

- Người dùng đã xác nhận Dashboard tiến độ hoạt động tốt trong phiên thử nghiệm local trên Android emulator.
- Sau khi sửa safe area, người dùng xác nhận: “tôi đã kiểm thử chức năng nhật kí số đo cơ thể và xác nhận nó đã hoàn thành”. Ghi nhận Nhật ký số đo cơ thể đã được người dùng kiểm thử và chấp nhận về chức năng.
- Xác nhận này cập nhật trạng thái chờ kiểm thử Nhật ký số đo ở mục trước. Người dùng chưa cung cấp danh sách ca kiểm thử chi tiết; không suy diễn rằng mọi nhánh correction/concurrency, phân quyền Trainer hoặc môi trường production đã được nghiệm thu UI.
- Bản sửa vẫn thuộc working tree của `feat/phase1-a07-measurement-progress`, chưa có commit nghiệm thu mới. Review/CI trên commit cuối cùng và quyết định merge được theo dõi riêng.

### Khắc phục lỗi CI Backend PR #150 — 09/11/2026

- **Job CI thất bại:** [Backend Job 128972470315](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/actions/runs/42559465862/job/128972470315) trên Run 42559465862 (PR #150). Head PR là `1c6b693`; CI chạy merge ref `bf51ff9`, ghép head này với main `863bd89`. Job thất bại tại `./mvnw -B test`: 1.813 test, 1 failure, 0 error, 5 skipped. GitHub hiện ghi nhận PR open, chưa merge; lần chạy mới nhất được connector trả về vẫn là run thất bại này.
- **Triệu chứng & Lỗi cốt lõi:** Lệnh `./mvnw -B test` thất bại với duy nhất 1 lỗi trên 1813 tests:
  ```text
  [ERROR] FitnessGoalProposalIntegrationTest.revalidationOnAccept_inactiveCatalogItems_failsWith400:1081 Status expected:<400> but was:<200>
  ```
- **Nguyên nhân gốc:**
  - Test giả định ID tự sinh `1` luôn đại diện cho `WEIGHT`. V56 seed catalog bằng `INSERT ... SELECT ... JOIN` không cam kết thứ tự cấp ID; ID tự sinh không phải định danh nghiệp vụ cố định. Không cần ép thứ tự migration để đáp ứng giả định của test.
  - Trong `FitnessGoalProposalIntegrationTest`, test case `revalidationOnAccept_inactiveCatalogItems_failsWith400` truyền cứng `metricDefinitionId = 1` nhưng lại gọi `UPDATE fitness.metric_definitions SET is_active = false WHERE code = 'WEIGHT'`. Vì ID 1 là `STRENGTH_LOAD` vẫn còn `is_active = true`, bước revalidation khi chấp thuận đề xuất mục tiêu không bị chặn với HTTP 400 mà thành công với HTTP 200.
- **Bản sửa tối thiểu & bảo toàn kiến trúc:**
  1. Giữ nguyên V56 đã commit và đã áp dụng ở database A07 local. Theo xác nhận của người dùng trong phiên này, đã bỏ riêng thay đổi sẵn có ép thứ tự seed; không sửa checksum/Flyway history, không reset database hoặc chạy migration vào database dùng chung.
  2. Trong `FitnessGoalProposalIntegrationTest.java`: Giữ helper `weightMetricId()` truy vấn động `SELECT id FROM fitness.metric_definitions WHERE code = 'WEIGHT'` (pattern `kgUnitId()` có sẵn). Mở rộng bản sửa sẵn có từ 3 chỗ sang toàn bộ 19 target fixture dùng ID `1` trong cùng class, tránh các ca kiểm thử khác âm thầm dùng nhầm metric.
  3. Đã tái hiện trước sửa trên bản sao Backend cô lập tại `D:\Projects\pr150-ci-verification`, dùng test và V56 của HEAD đã commit: một test chạy, một failure đúng assertion 400/200, BUILD FAILURE. Giữ toàn bộ working tree gốc trong lúc tái hiện.
- **Kết quả kiểm chứng chạy lại trong phiên này:** Chạy `mvn '-Dtest=FitnessGoalProposalIntegrationTest,MeasurementApiIntegrationTest,MeasurementProgressIntegrationTest,ProgressEngineIntegrationTest' test` trong bản sao Backend cô lập `D:\Projects\pr150-ci-verification`, sau khi chép test đã sửa và giữ V56 gốc; PostgreSQL 18/pgvector qua Testcontainers. Tổng **100 test, 0 failure, 0 error, 0 skipped; BUILD SUCCESS, exit code 0**. Log trước/sau sửa nằm tại `baseline.log` và `fixed.log` trong thư mục này.
  - `FitnessGoalProposalIntegrationTest`: **46/46 tests PASS** (gồm ca kiểm thử bị lỗi trước đó đã chuyển xanh).
  - `MeasurementApiIntegrationTest`: **22/22 tests PASS**.
  - `MeasurementProgressIntegrationTest`: **15/15 tests PASS**.
  - `ProgressEngineIntegrationTest`: **17/17 tests PASS**.
  - `Contracts & OpenAPI`: kết quả **Valid, 21/21 tests PASS** là bằng chứng sẵn có trước phiên này, không chạy lại vì bản sửa cuối chỉ đổi test và tài liệu.
  - `git diff --check`: **0 issues**.
- **Bảo toàn xác nhận người dùng:** Giữ nguyên vẹn xác nhận kiểm thử chức năng của người dùng đối với Dashboard tiến độ và Nhật ký số đo cơ thể trên Android emulator. Chỉ kiểm chứng lại các bài test bị ảnh hưởng trực tiếp bởi thay đổi danh mục và revalidation đề xuất mục tiêu.
- **Giới hạn:** Không chạy lại toàn bộ 1.813 test Backend hoặc merge ref trên GitHub sau sửa. Local HEAD vẫn là `1c6b693`; main `863bd89` chưa có trong Git object local. Đã dùng GitHub compare xác nhận phần main tiến thêm chỉ thay AppointmentHttpIntegrationTest, appointmentScreens.test.tsx và tài liệu B06, không thay code runtime/migration. Không tự fetch/pull/merge. CI cần chạy lại sau khi người dùng commit/push bản sửa; chưa kết luận GitHub CI xanh.

---

## 9. Báo cáo kiểm chứng và hồ sơ bàn giao A05/M8 — Quản trị tài khoản (Account Administration)

### 9.1 Tổng quan phạm vi và kiến trúc triển khai

Milestone **A05/M8** bao gồm toàn bộ chức năng quản trị tài khoản người dùng, phân quyền RBAC quản trị, xác thực nâng cao Step-Up Authentication, vòng đời tài khoản (Suspend, Restore, Disable), thu hồi phiên đăng nhập (Force Logout), quản lý phân quyền vai trò (Role Management), bảo vệ quản trị viên cuối cùng (Last Admin Protection), và ghi nhận nhật ký kiểm toán bất biến (Audit Logging).

#### Thành phần Backend (Spring Boot 4.1.1, Java 21, Maven):
- **Controller:**
  - `AdminUserController`: `/api/v1/admin/users` (list, detail, suspend, restore, disable, force-logout, roles). Được bảo vệ bởi `@PreAuthorize("hasRole('ADMIN')")`.
  - `AdminAuthController`: `/api/v1/admin/auth/step-up` (cấp phiên Step-Up 15 phút sau khi xác thực mật khẩu quản trị viên).
  - `AdminDashboardController`: `/api/v1/admin/dashboard/summary` (tổng hợp số liệu người dùng, phân bố trạng thái tài khoản, hồ sơ HLV chờ duyệt).
- **Application Services & Domain Ports:**
  - `AdminUserLifecycleService`: Điều phối các tác vụ vòng đời tài khoản với `@Transactional`, kiểm tra quyền hạn hiệu lực (`USER_SUSPEND`, `USER_DISABLE`, `USER_ROLE_MANAGE`, `SESSION_REVOKE`), kiểm tra Step-Up bắt buộc, bảo vệ self-action (không cho phép tự suspend, disable, thu hồi quyền admin, hoặc force logout bản thân qua endpoint admin), bảo vệ Last Admin Protection, và gọi `AuditService` ghi nhận `fitness.audit_logs`.
  - `AdminStepUpService`: Kiểm tra mật khẩu quản trị viên hiện hành, lưu bản ghi `fitness.step_up_authentications` với TTL 15 phút, xác thực `action_code` và người dùng hợp lệ.
  - `AdminUserQueryService` & `AdminDashboardService`: Truy vấn danh sách và chi tiết người dùng, thống kê bảng điều khiển.
- **Persistence Adapter:**
  - `AdminUserPersistenceAdapter`: Thao tác trực tiếp với PostgreSQL qua `NamedParameterJdbcTemplate`, hỗ trợ phân trang, tìm kiếm theo tên/email/phone, lọc theo role và trạng thái tài khoản.
  - `countActiveAdmins()`: Thực hiện khóa bi quan hàng `'ADMIN'` trong bảng `fitness.roles` (`SELECT id FROM fitness.roles WHERE code = 'ADMIN' FOR UPDATE`) trước khi đếm, đảm bảo tuần tự hóa tuyệt đối dưới tác động đồng thời (concurrency safety) và chống race condition khi hai admin cùng lúc thao tác vô hiệu hóa/hạ quyền nhau.
- **Database Migration V57:**
  - File: `services/backend/src/main/resources/db/migration/V57__admin_account_governance_permissions.sql`.
  - Cấp 4 quyền hạn quản trị: `USER_SUSPEND`, `USER_DISABLE`, `USER_ROLE_MANAGE`, `SESSION_REVOKE`.
  - Gán quyền cho vai trò `ADMIN` với `ON CONFLICT DO NOTHING`.
  - Đăng ký chính sách `RESTORE_ACCOUNT` trong `admin_action_policies`.

#### Thành phần Admin Web (React 19, TypeScript, Vite):
- `UsersListPage`: Danh sách tài khoản người dùng, bảng phân trang, tìm kiếm theo từ khóa (email, tên, số điện thoại), bộ lọc trạng thái (`ACTIVE`, `SUSPENDED`, `DISABLED`, `PENDING_VERIFICATION`), bộ lọc vai trò (`STUDENT`, `TRAINER`, `ADMIN`), nút reset bộ lọc, chỉ báo trạng thái hồ sơ HLV.
- `UserDetailPage`: Chi tiết người dùng, thông tin cá nhân, hồ sơ Student, hồ sơ Trainer, lịch sử chuyển đổi trạng thái tài khoản (`AccountStatusHistory`), phân quyền hiện tại, và các nút tác vụ (Khóa tài khoản, Khôi phục, Vô hiệu hóa vĩnh viễn, Buộc đăng xuất, Phân/hủy vai trò).
- `AdminActionDialog`: Modal xác thực tác vụ nhạy cảm, yêu cầu nhập lý do hành động (tối thiểu 5 ký tự không tính khoảng trắng) và mật khẩu Step-Up của Admin. Hỗ trợ phím Escape, bẫy focus, giải phóng bộ nhớ khi đóng.
- `userAdminApi`: Client dịch vụ tích hợp OpenAPI typesafe với cơ chế xử lý lỗi chuẩn hóa.
- `DashboardPage`: Hiển thị số liệu tổng quan người dùng theo trạng thái và cảnh báo cần xử lý.

### 9.2 Ma trận phân quyền & Xác thực nâng cao (Step-Up)

| Endpoint | Quyền hạn yêu cầu | Step-Up bắt buộc | Mã hành động Step-Up | Hành vi khi vi phạm |
|---|---|---|---|---|
| `GET /api/v1/admin/users` | `ROLE_ADMIN` | Không | N/A | HTTP 403 Forbidden |
| `GET /api/v1/admin/users/{id}` | `ROLE_ADMIN` | Không | N/A | HTTP 403 Forbidden (hoặc 404 nếu không tìm thấy user) |
| `POST /api/v1/admin/users/{id}/suspend` | `USER_SUSPEND` | Có (Mật khẩu hoặc Token) | `SUSPEND_ACCOUNT` | HTTP 400 (Self-action / Reason), 401 (Wrong password), 403 (No perm / expired step-up), 409 (Already suspended / Last admin) |
| `POST /api/v1/admin/users/{id}/restore` | `USER_SUSPEND` | Có (Mật khẩu hoặc Token) | `RESTORE_ACCOUNT` | HTTP 400 (Reason < 5 chars), 401 (Wrong password), 403 (No perm), 409 (Already active / Disabled non-restorable) |
| `POST /api/v1/admin/users/{id}/disable` | `USER_DISABLE` | Có (Mật khẩu hoặc Token) | `DISABLE_ACCOUNT` | HTTP 400 (Self-action / Reason), 401 (Wrong password), 403 (No perm), 409 (Already disabled / Last admin) |
| `POST /api/v1/admin/users/{id}/force-logout` | `SESSION_REVOKE` | Có (Mật khẩu hoặc Token) | `FORCE_LOGOUT` | HTTP 400 (Self-action / Reason), 401 (Wrong password), 403 (No perm) |
| `POST /api/v1/admin/users/{id}/roles` | `USER_ROLE_MANAGE` | Có (Mật khẩu hoặc Token) | `CHANGE_USER_ROLE` | HTTP 400 (Self-action revoking ADMIN / Invalid role), 401 (Wrong password), 403 (No perm), 409 (Last admin) |
| `POST /api/v1/admin/auth/step-up` | `ROLE_ADMIN` | N/A (Endpoint cấp step-up) | Đăng ký theo actionCode | HTTP 401 (Sai mật khẩu) |
| `GET /api/v1/admin/dashboard/summary` | `ROLE_ADMIN` | Không | N/A | HTTP 403 Forbidden |

### 9.3 Quy tắc vòng đời tài khoản & Bảo vệ hệ thống
1. **Bảo vệ tự thao tác (Self-Action Guard):**
   - Quản trị viên không được tự khóa (`suspend`), tự vô hiệu hóa (`disable`), tự hủy vai trò `ADMIN` của chính mình, hoặc tự buộc đăng xuất (`force-logout`) qua endpoint quản trị. Các vi phạm bị chặn ngay với HTTP 400 `SELF_ACTION_NOT_ALLOWED`.
2. **Bảo vệ Quản trị viên cuối cùng (Last Admin Protection):**
   - Khi hệ thống chỉ còn 1 quản trị viên `ACTIVE`, cấm mọi thao tác khóa, vô hiệu hóa hoặc thu hồi quyền admin đối với tài khoản này. Hệ thống trả về HTTP 409 `LAST_ADMIN_PROTECTED`.
   - Cơ chế khóa bi quan `SELECT id FROM fitness.roles WHERE code = 'ADMIN' FOR UPDATE` tuần tự hóa các giao dịch cạnh tranh, ngăn chặn hoàn toàn race condition khi 2 admin đồng thời thao tác trên nhau.
3. **Tài khoản vô hiệu hóa không được khôi phục (Permanently Disabled):**
   - Tài khoản ở trạng thái `DISABLED` không thể khôi phục qua quy trình quản trị thông thường (`POST /restore` trả về HTTP 409 `INVALID_LIFECYCLE_TRANSITION`).
4. **Khôi phục không kích hoạt lại phiên cũ (No Session Resurrection):**
   - Khi tài khoản được chuyển từ `SUSPENDED` sang `ACTIVE`, các refresh token đã bị thu hồi trong quá khứ vẫn giữ nguyên `revoked_at` và không thể sử dụng lại. Người dùng buộc phải đăng nhập lại để nhận cặp token mới.
5. **Toàn vẹn giao dịch & Nhật ký kiểm toán (Transactional Audit Integrity):**
   - Mọi thao tác quản trị được bọc trong `@Transactional`. Nếu bước ghi nhật ký kiểm toán vào `fitness.audit_logs` gặp lỗi, toàn bộ giao dịch (thay đổi trạng thái người dùng, thu hồi token, ghi lịch sử) đều bị rollback tự động, giữ vững trạng thái cơ sở dữ liệu.
   - Mật khẩu và token không bao giờ được ghi vào log kiểm toán hoặc trả về client.

### 9.4 Phân tích hiệu lực phiên & Độ trễ thu hồi Token (Token Cutoff Analysis)
- **Cơ chế thu hồi phiên nghiêm ngặt (Strict Session Revocation Architecture):**
  - Tái sử dụng bảng bất biến `fitness.session_revocation_events` (đã có index `idx_session_revocation_target_time(target_user_id, occurred_at DESC)` từ migration V16) để lưu dấu mốc thu hồi phiên phạm vi `ALL_SESSIONS`.
  - Token JWT được bổ sung claim thời gian phát hành chính xác tới mili-giây: `iat_epoch_ms`.
  - `ActiveAccountJwtValidator` kết hợp với `UserRoleService.isAccountSessionValid` thực thi một câu truy vấn tối ưu duy nhất tới PostgreSQL trên mỗi request mang Bearer token:
    1. Kiểm tra trạng thái tài khoản: `status == 'ACTIVE'`.
    2. So khớp mốc thu hồi: Nếu có sự kiện thu hồi `last_revoked_at` và `tokenIssuedAt <= lastRevokedAt`, token bị từ chối ngay lập tức (**HTTP 401 Unauthorized**).
    3. Ngăn ngừa triệt để lỗi làm tròn giây (second truncation): Việc đối chiếu mili-giây đảm bảo các token cấp mới sau sự kiện thu hồi luôn được chấp nhận, trong khi mọi token phát hành tại hoặc trước thời điểm thu hồi đều bị chặn hoàn toàn.
- **Force Logout (Buộc đăng xuất toàn bộ phiên):**
  - Thu hồi toàn bộ refresh token trong bảng `fitness.refresh_tokens` (`revoked_at = now()`, reason `'ADMIN_FORCE_LOGOUT'`).
  - Ghi nhận sự kiện `session_revocation_events` với `occurred_at = now()`.
  - Toàn bộ access token hiện hữu bị từ chối ngay lập tức (**HTTP 401 Unauthorized**) trên mọi endpoint.
  - Người dùng được phép đăng nhập lại bình thường; token cấp mới sau mốc thu hồi hoạt động hoàn hảo.
- **Suspend & Restore Account (Đình chỉ và Khôi phục):**
  - Khi `suspend`: Trạng thái chuyển sang `SUSPENDED`, refresh token bị thu hồi, ghi nhận `session_revocation_events`. Access token bị từ chối 401 ngay lập tức.
  - Khi `restore`: Trạng thái chuyển lại `ACTIVE`. Đồng thời ghi nhận một mốc `session_revocation_events` mới tại thời điểm khôi phục.
  - **Không bao giờ hồi sinh phiên cũ (No Session Resurrection):** Mọi access token và refresh token được cấp trước thời điểm suspend hoặc restore đều bị từ chối vĩnh viễn (HTTP 401). Người dùng bắt buộc phải đăng nhập lại để nhận phiên làm việc mới.
- **Thu hồi vai trò ADMIN (Revoke ADMIN Role):**
  - Bảng `fitness.user_roles` cập nhật `revoked_at = now()`.
  - Toàn bộ refresh token của tài khoản bị thu hồi (`revoked_at = now()`, reason `'ADMIN_ROLE_REVOKED'`).
  - Ghi nhận `session_revocation_events` làm toàn bộ access token cũ chứa claim `ADMIN` bị vô hiệu hóa ngay lập tức (**HTTP 401 Unauthorized**).
  - `SecurityConfig` cấu hình `jwtAuthenticationConverter` truy vấn trực tiếp vai trò hoạt động từ `UserRoleQuery.getActiveRoles(userId)` (PostgreSQL system of record). Vì vậy, ngay cả khi người dùng đăng nhập lại hoặc sử dụng bất kỳ token nào không có quyền `ADMIN` trong DB, toàn bộ endpoint quản trị (kể cả endpoint chỉ có `@PreAuthorize("hasRole('ADMIN')")`) đều từ chối với **HTTP 403 Forbidden**.
  - Các endpoint thông thường (như `/users/me`) vẫn cho phép người dùng truy cập với vai trò hợp lệ còn lại.

### 9.5 Bằng chứng kiểm thử tự động (100% Pass)

1. **Backend Integration Tests:**
   - Lệnh: `.\services\backend\mvnw.cmd -f services/backend/pom.xml test -Dtest=AdminAccountAdministrationIntegrationTest`
   - Môi trường: PostgreSQL 18/pgvector Testcontainers độc lập, Flyway migrate 57 migrations.
   - Kết quả: **29/29 tests PASS (0 failures, 0 errors, 0 skipped, BUILD SUCCESS)**:
     - `testListUsersFilters`: Tìm kiếm, phân trang, lọc theo status, lọc theo role, kiểm tra 403 với non-admin.
     - `testGetUserDetail`: Trả về chi tiết người dùng, hồ sơ Student, hồ sơ Trainer, lịch sử trạng thái; 404 khi không tồn tại.
     - `testSuspendUserSuccess`: Khóa tài khoản ACTIVE -> SUSPENDED, thu hồi refresh token, ghi nhận `session_revocation_events`, `admin_actions` và `audit_logs`.
     - `testCannotSuspendSelf`: Chặn tự khóa bản thân (HTTP 400 `SELF_ACTION_NOT_ALLOWED`).
     - `testCannotSuspendLastAdmin`: Chặn khóa quản trị viên cuối cùng (HTTP 409 `LAST_ADMIN_PROTECTED`).
     - `testSuspendAlreadySuspendedReturns409`: Chặn khóa tài khoản đã bị khóa (HTTP 409 `INVALID_LIFECYCLE_TRANSITION`).
     - `testRestoreUserSuccess`: Khôi phục tài khoản SUSPENDED -> ACTIVE, không tái kích hoạt phiên cũ, ghi audit.
     - `testRestoreInvalidStatusReturns409`: Chặn khôi phục tài khoản đang ACTIVE hoặc DISABLED (HTTP 409).
     - `testDisableUserSuccess`: Vô hiệu hóa vĩnh viễn tài khoản sang DISABLED, thu hồi toàn bộ token.
     - `testForceLogoutSuccess`: Thu hồi toàn bộ refresh token của người dùng mà không đổi trạng thái tài khoản.
     - `testChangeUserRole`: Gán và thu hồi vai trò người dùng (ASSIGN / REVOKE).
     - `testStepUpEndpoint`: Cấp phiên Step-Up 15 phút khi mật khẩu đúng; từ chối HTTP 401 khi sai mật khẩu.
     - `testStepUpExpiredFails`: Từ chối xác thực khi Step-Up authentication ID đã hết hạn (HTTP 403).
     - `testStepUpWithWrongActionFails`: Từ chối Step-Up cấp cho hành động khác (HTTP 403).
     - `testDashboardSummary`: Thống kê tổng số người dùng, phân bố trạng thái (`ACTIVE`, `SUSPENDED`, `DISABLED`).
     - `testCannotForceLogoutSelf`: Chặn admin tự buộc đăng xuất bản thân qua endpoint admin (HTTP 400).
     - `testCannotDisableSelf`: Chặn admin tự vô hiệu hóa bản thân (HTTP 400).
     - `testCannotRevokeOwnAdminRole`: Chặn admin tự hủy vai trò ADMIN của mình (HTTP 400).
     - `testWhitespaceOnlyReasonRejected`: Chặn lý do chỉ chứa khoảng trắng hoặc < 5 ký tự (HTTP 400 `VALIDATION_FAILED`).
     - `testAuditFailureRollsBackStatusChange`: Kiểm tra rollback giao dịch khi ghi audit thất bại, bảo toàn dữ liệu database.
     - `testRevokedRefreshTokenCannotRefresh`: Xác minh sau khi Force Logout, access token bị từ chối 401 và refresh token bị từ chối 401.
     - `testSuspendAccountTokenAndLoginImpact`: Xác minh ngay sau khi suspend, access token bị từ chối 401 qua ActiveAccountJwtValidator và đăng nhập mới bị từ chối 403 ACCOUNT_UNAVAILABLE.
     - `testDisableAccountTokenAndLoginImpactAndCannotRestore`: Xác minh disable từ chối access token 401, refresh token 401, đăng nhập 403, và không thể restore (409).
     - `testConcurrentLastAdminProtection`: Kiểm tra tuần tự hóa đồng thời khi 2 luồng cùng khóa 2 admin cuối cùng: đúng 1 thành công (HTTP 200), 1 bị từ chối (HTTP 409), đúng 1 admin còn ACTIVE trong database.
     - `testForceLogoutAccessAndRefreshTokenInvalidationAndFreshLogin`: Kiểm chứng vòng đời đầy đủ của Force Logout: access token cũ thất bại (401), refresh token cũ thất bại (401), đăng nhập mới thành công và nhận cặp token mới hoạt động trơn tru.
     - `testSuspendThenRestoreTokensCannotResurrect`: Kiểm chứng Suspend rồi Restore trước khi token cũ hết hạn: access token cũ vẫn bị từ chối (401), refresh token cũ thất bại (401), đăng nhập mới thành công.
     - `testRevokeAdminRoleBlocksAllAdminEndpointsAndRevokesSessions`: Kiểm chứng thu hồi role ADMIN: access token cũ bị chặn ngay lập tức (401) trên cả API đọc và API nhạy cảm; đăng nhập mới nhận token không có quyền ADMIN bị chặn với 403 Forbidden; API người dùng thông thường vẫn hoạt động; admin hợp lệ khác không bị ảnh hưởng.
     - `testSameMillisecondAndLegacyTokenRevocationBehavior`: Kiểm chứng biên độ mili-giây và tương thích ngược: Token cấp cùng mili-giây với thời điểm thu hồi hoặc token legacy không có claim `iat_epoch_ms` bị từ chối dứt khoát với 401 Unauthorized (fail-safe); token mới cấp sau thời điểm thu hồi (+1ms) mang `iat_epoch_ms` được chấp nhận 200 OK.
     - `testConcurrentRefreshDuringForceLogout`: Kiểm chứng an toàn đồng thời giữa xoay vòng refresh token và Force Logout: phiên cũ bị khóa dòng `SELECT id FROM fitness.users WHERE id = :userId FOR NO KEY UPDATE` và thu hồi dứt điểm, không thể sinh phiên mới bất hợp pháp.

2. **Backend Regression Tests:**
   - `AdminAccountAdministrationIntegrationTest`: **29/29 tests PASS** (Toàn bộ kịch bản lifecycle, session invalidation, authority cutoff, edge cases và concurrency trên PostgreSQL Testcontainers).
   - `AuthSessionIntegrationTest`: **26/26 tests PASS** (Cập nhật assertion phân quyền hiệu lực của role `ADMIN` khớp với V57).
   - `CurrentUserIntegrationTest` & `ContentManagementIntegrationTest`: **27/27 tests PASS** (Bảo toàn kiểm tra phân quyền động).
   - `AccountErasureHttpIntegrationTest`: **14/14 tests PASS** (Bảo toàn quy trình xóa tài khoản GDPR).

3. **Admin Web Frontend Tests & Quality Checks:**
   - Vitest: `npm --prefix apps/admin-web test -- --run` -> **19 test files, 136/136 tests PASS** (Bao gồm 7 tests mới trong `UserPages.test.tsx` và 9 tests trong `userAdminApi.test.ts`).
   - TypeScript Typecheck: `npm --prefix apps/admin-web run typecheck` -> **0 errors (Exit code 0)**.
   - Linter: `npm --prefix apps/admin-web run lint` -> **0 warnings, 0 errors**.
   - Production Build: `npm --prefix apps/admin-web run build` -> **Build thành công (Vite v8.3.0)**.

4. **Hợp đồng & Định dạng:**
   - OpenAPI Validation: `redocly lint contracts/openapi/openapi.yaml` -> **Valid**.
   - Contract & Schema Tests: `npm run validate:contracts && npm run test:contracts` -> **21/21 tests PASS**.
   - Format & Whitespace Check: `git diff --check` -> **0 issues**.

### 9.6 Kết quả Nghiệm thu Trình duyệt Thực tế & Hồ sơ Bàn giao

Môi trường kiểm thử tự động (Vitest JSDOM và Spring Boot Testcontainers) đã kiểm chứng 29/29 kịch bản tích hợp, hợp đồng API, và các ràng buộc khóa dòng PostgreSQL trong phạm vi các ca kiểm thử đã thiết lập.

**Ghi nhận Xác nhận Nghiệm thu của Người dùng (2026-11-10):**
Người dùng đã trực tiếp đăng nhập bằng tài khoản Quản trị viên hợp lệ và thực hiện kiểm thử trên giao diện Admin Web thực tế (`http://localhost:5173`), xác nhận các chức năng đã kiểm tra hoạt động tốt trên môi trường thực tế.

> [!NOTE]
> Phân định phạm vi: Xác nhận nghiệm thu này ghi nhận kết quả kiểm thử chức năng trực quan của người dùng trên môi trường local dev; việc này tách biệt với quy trình human code review chính thức trên PR và các bước kiểm chứng staging/production sau này.

| STT | Kịch bản nghiệm thu | Mức độ kiểm thử tự động (Vitest / Testcontainers) | Kết quả Nghiệm thu Thực tế | Ghi chú kiểm chứng |
|---|---|---|---|---|
| 1 | **Đăng nhập Admin** | ĐÃ ĐẠT (MockMvc & JSDOM) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Đăng nhập tài khoản role `ADMIN` tại `/login`, chuyển hướng thành công vào Dashboard. |
| 2 | **Dashboard & Đường dẫn việc cần xử lý** | ĐÃ ĐẠT (`DashboardPage.test.tsx`) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Thẻ thống kê người dùng (Active, Suspended, Disabled) tải đủ số liệu và liên kết điều hướng sang `/users` hoạt động tốt. |
| 3 | **Danh sách người dùng** | ĐÃ ĐẠT (`UserPages.test.tsx`) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Tìm kiếm từ khóa, kết hợp lọc Status + Role, phân trang, và "Đặt lại bộ lọc" hoạt động chính xác. |
| 4 | **Chi tiết người dùng & Lịch sử trạng thái** | ĐÃ ĐẠT (`UserPages.test.tsx`) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Vào `/users/:id`, hiển thị Overview, Profiles (Student/Trainer), Bảng lịch sử thay đổi trạng thái kèm tên Admin & lý do. |
| 5 | **Tạm đình chỉ (Suspend) & Khôi phục (Restore)** | ĐÃ ĐẠT (Integration tests) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Suspend trên user `ACTIVE` cập nhật trạng thái `SUSPENDED` và thêm bản ghi lịch sử. Restore khôi phục về `ACTIVE`; phiên token cũ cấp trước suspend không tái sinh. |
| 6 | **Force Logout với phiên thứ hai** | ĐÃ ĐẠT (`testForceLogoutAccessAndRefreshTokenInvalidationAndFreshLogin`) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Buộc đăng xuất thu hồi tức thì toàn bộ refresh token và vô hiệu hóa access token cũ (401), yêu cầu đăng nhập mới. |
| 7 | **Vô hiệu hóa (Disable) tài khoản test** | ĐÃ ĐẠT (`testDisableUserSuccess`) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Hộp thoại cảnh báo mức cao, chuyển trạng thái `DISABLED`, vô hiệu hóa các thao tác tiếp theo, không thể khôi phục. |
| 8 | **Gán (Assign) & Thu hồi (Revoke) Role** | ĐÃ ĐẠT (`testChangeUserRole`) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Gán và thu hồi vai trò người dùng hoạt động chính xác, cắt quyền quản trị ngay lập tức khi thu hồi role `ADMIN`. |
| 9 | **Step-Up đúng / sai mật khẩu & lý do không hợp lệ** | ĐÃ ĐẠT (`testStepUpEndpoint`, `testWhitespaceOnlyReasonRejected`) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Sai mật khẩu báo lỗi 401 trên modal; lý do rỗng/<5 ký tự vô hiệu hóa nút xác nhận; mật khẩu đúng thực thi thành công. |
| 10 | **Self-Action Guard (Chặn tự tác động chính mình)** | ĐÃ ĐẠT (`testCannotSuspendSelf`, `testCannotForceLogoutSelf`, `testCannotDisableSelf`, `testCannotRevokeOwnAdminRole`) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Khi xem chính tài khoản admin đang đăng nhập: các nút thao tác nguy hiểm bị ẩn/vô hiệu hóa; backend chặn với HTTP 400 `SELF_ACTION_NOT_ALLOWED`. |
| 11 | **Last-Admin Guard (Chặn triệt tiêu Quản trị viên cuối cùng)** | ĐÃ ĐẠT (`testCannotSuspendLastAdmin`, `testConcurrentLastAdminProtection`) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Thao tác lên admin duy nhất còn lại bị backend chặn trả về HTTP 409 `LAST_ADMIN_PROTECTED`. |
| 12 | **Truy cập bằng tài khoản thiếu quyền** | ĐÃ ĐẠT (Controller 403 test) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Tài khoản `TRAINER` / `STUDENT` bị chặn truy cập API quản trị (403 `ACCESS_DENIED`) và ẩn các mục menu quản trị. |
| 13 | **Thông báo phản hồi & Chặn gửi lặp** | ĐÃ ĐẠT (`AdminActionDialog.test.tsx`) | **ĐÃ XÁC NHẬN BỞI NGƯỜI DÙNG** | Nút chuyển sang trạng thái "Đang xử lý..." kèm spinner ngăn chặn gửi lặp đúp; hoàn thành hiển thị thông báo thành công. |

### 9.7 Khắc phục CI Backend PR #151 — 10–11/11/2026

- **Bằng chứng CI:** [Backend job 129953156582](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/actions/runs/42851515349/job/129953156582), run `42851515349`, PR head `a039715`, merge ref `2f0beb6`. Lệnh `./mvnw -B test` ghi nhận 1.842 tests, 8 failures, 209 errors, 5 skipped. PR vẫn open/chưa merge khi kiểm tra.
- **Nguyên nhân 209 errors:** 14 lớp `@WebMvcTest` import `SecurityConfig` nhưng thiếu mock `UserRoleQuery`, dependency mới của `jwtAuthenticationConverter`; Spring không khởi tạo được test context. Đã khai báo `@MockitoBean UserRoleQuery` trong từng MVC slice, giữ nguyên kiểm tra authentication/authorization hiện có.
- **Nguyên nhân 8 failures:** fixture trong `ExerciseCatalogIntegrationTest` và `AdminExerciseCatalogIntegrationTest` tạo user và role claim trong JWT nhưng chưa tạo role assignment trong PostgreSQL. Sau A05/M8, authority lấy từ DB nên các request hợp lệ theo fixture cũ bị 403. Đã gán role tương ứng vào `fitness.user_roles` trong helper tạo bearer, chỉ trên DB Testcontainers riêng.
- **Phạm vi sửa:** test wiring và fixtures; không đổi production security, không fallback thêm về JWT role claim, không sửa migration V56/V57 hoặc dữ liệu dev/production. Giữ nguyên xác nhận nghiệm thu chức năng của người dùng ở mục 9.6.
- **Kiểm chứng local:** build sạch và chạy 16 suite bị ảnh hưởng đạt **228/228 tests**, 0 failures/errors/skipped (`BUILD SUCCESS`). Sau đó chạy toàn bộ Backend bằng `mvnw.cmd -B test`: **1.842 tests, 0 failures, 0 errors, 5 skipped, BUILD SUCCESS**, hoàn tất lúc `2026-11-11T00:05:35+07:00` (1.837 tests thực thi đạt; 5 tests được bỏ qua).
- **Cấu hình kiểm thử AI:** lượt chạy toàn bộ đầu tiên trên local phát hiện 2 failures của `AiKnowledgeQueryRealFastApiIntegrationTest` do FastAPI đọc `.env` chọn provider `openai` thay vì `simulated`; lượt này đã dừng. Lượt chạy đầy đủ đạt nêu trên đặt `F01_PROVIDER=simulated` và `OPENAI_API_URL=http://127.0.0.1:9` trong riêng tiến trình kiểm thử để dùng mô phỏng và ngăn gọi endpoint OpenAI thật. Không chỉnh sửa `.env`, khóa API hoặc cấu hình runtime của ứng dụng. Đây là khác biệt môi trường local, không phải 8 failures/209 errors của job CI gốc.
- **Giới hạn:** chưa commit/push bản sửa; trạng thái GitHub Actions sau sửa chỉ được xác nhận trên run mới chứa commit sửa. Xác nhận local và nghiệm thu người dùng không thay thế remote CI hoặc human code review.

---

## 10. Priority 5 — Support, Moderation & Audit Detailed Traceability (M8)

### 10.1 Phạm vi & Yêu cầu Nghiệp vụ
1. **Quản lý yêu cầu hỗ trợ (Support Cases):**
   - Hỗ trợ tạo phiếu, xem danh sách có bộ lọc (trạng thái, mức độ ưu tiên, phân loại, tìm kiếm) và phân trang.
   - Chi tiết phiếu hiển thị dòng sự kiện bất biến (append-only events).
   - Cho phép cập nhật trạng thái (`OPEN` → `IN_PROGRESS` → `RESOLVED` → `CLOSED`), phân công quản trị viên phụ trách, và thêm ghi chú nội bộ.
   - Chặn các chuyển trạng thái không hợp lệ (ví dụ phiếu đã `CLOSED` không được chỉnh sửa).
2. **Kiểm duyệt nội dung & vi phạm (Moderation):**
   - Quản lý các vụ việc kiểm duyệt liên quan đến người dùng, bài đăng hoặc bình luận vi phạm.
   - Ghi nhận lịch sử quyết định xử lý bất biến (append-only actions: `WARNING`, `SUSPENSION`, `DISABLE`, `CONTENT_REMOVAL`, `NO_ACTION`).
   - Bắt buộc cung cấp lý do cho mọi hành động chế tài; yêu cầu xác thực step-up đối với các hành động nhạy cảm (`SUSPENSION`, `DISABLE`).
3. **Quyền truy cập đặc quyền (Privileged Data Access):**
   - Mở phiên truy cập dữ liệu riêng tư có giới hạn thời gian (time-bound, mặc định 60 phút) gắn liền với mục đích và phiếu hỗ trợ cụ thể.
   - Tự động hiển thị thanh cảnh báo `PrivilegedAccessBanner` trên giao diện Quản trị khi có phiên đang hoạt động.
   - Cho phép thu hồi phiên bất cứ lúc nào với lý do rõ ràng.
4. **Hiệu chỉnh dữ liệu quản trị (Governed Data Correction):**
   - Hỗ trợ hiệu chỉnh số đo học viên (`MEASUREMENT`) và hồ sơ người dùng (`USER_PROFILE`).
   - Yêu cầu bắt buộc: quyền `DATA_CORRECTION_EXECUTE`, phiếu hỗ trợ chưa đóng, phiên truy cập đặc quyền `ACTIVE` của chính admin đang thực hiện, và xác thực step-up.
   - Thỏa mãn trigger bảo vệ cơ sở dữ liệu `validate_admin_action_authorization` và `protect_measurement_observation`.
   - Đối với số đo: bảo toàn lịch sử bằng cách tạo bản ghi số đo mới hợp lệ và đánh dấu số đo cũ là `SUPERSEDED`, tuyệt đối không ghi đè mất dấu.
   - Đảm bảo tính nhất quán transactional: nếu việc ghi audit log thất bại, toàn bộ thao tác hiệu chỉnh dữ liệu sẽ tự động rollback.
5. **Nhật ký kiểm toán (Audit Logs):**
   - Truy vấn toàn diện nhật ký kiểm toán với bộ lọc đa tiêu chí (người thực hiện, vai trò, hành động, loại đối tượng, ID đối tượng, mốc thời gian).
   - Bảo mật thông tin: che giấu tự động mật khẩu, token, và khóa bí mật (`[REDACTED]`).
   - Nghiêm cấm mọi thao tác sửa hoặc xóa nhật ký kiểm toán từ API và giao diện.

### 10.2 Bằng chứng Kiểm thử & Xác thực
- **Database Migration:** `V58__support_moderation_audit_governance.sql` áp dụng thành công qua `node tools/test-migrations.mjs` (58/58 migrations).
- **Backend Integration Tests:** `AdminSupportModerationAuditIntegrationTest` -> **14/14 tests PASS**:
  1. `testSupportCaseLifecycle`: Vòng đời tạo, lọc, ghi chú, cập nhật trạng thái và kiểm tra audit log.
  2. `testSupportCaseInvalidTransition`: Chặn chuyển trạng thái không hợp lệ trả về HTTP 409 Conflict.
  3. `testSupportCaseAssignmentValidation`: Phân công case yêu cầu role ADMIN hoạt động; từ chối user không có role ADMIN (HTTP 400).
  4. `testModerationLifecycle`: Vòng đời kiểm duyệt, ghi nhận hành động append-only và chặn thiếu lý do (HTTP 400).
  5. `testModerationSuspensionAndDisableBusinessImpact`: Xử lý chế tài kiểm duyệt với tác động nghiệp vụ thực tế (SUSPENSION cập nhật user status, thu hồi token; DISABLE cập nhật trạng thái tài khoản; WARNING ghi nhận security_events; chặn self-action của admin).
  6. `testPrivilegedDataAccess`: Tạo yêu cầu, kiểm tra phiên ACTIVE (HTTP 200), thu hồi quyền và kiểm tra không còn phiên hoạt động (HTTP 204).
  7. `testGovernedDataCorrection_Measurement`: Hiệu chỉnh số đo, liên kết phiếu hỗ trợ, thỏa mãn trigger DB, tạo trạng thái `SUPERSEDED` và ghi nhận audit.
  8. `testGovernedDataCorrection_UserProfile`: Hiệu chỉnh hồ sơ người dùng (display name, phone) an toàn với step-up password và privileged access session.
  9. `testGovernedDataCorrection_CrossUserPrivilegedAccessRejection`: Chặn admin dùng phiên privileged access của người dùng A để sửa dữ liệu người dùng B (HTTP 403 Forbidden).
  10. `testGovernedDataCorrection_ConcurrentConflict`: Chặn xung đột đồng thời khi hiệu chỉnh số đo đã bị superseded (HTTP 409).
  11. `testGovernedDataCorrection_AuditFailureRollback`: Rollback toàn bộ mutation khi ghi audit thất bại (tính nhất quán giao dịch).
  12. `testAuditLogSearch`: Tìm kiếm nhật ký kiểm toán và che giấu bí mật/mật khẩu.
  13. `testAuditLogDeepRecursiveRedaction`: Che giấu đệ quy sâu trong cấu trúc JSON lồng nhau và mảng (array) chứa token, password, apiKey, secrets.
  14. `testSecurityGuards`: Chặn truy cập không xác thực (401) và không đủ quyền admin (403).
- **Backend Regression Tests:**
  - `AdminAccountAdministrationIntegrationTest` -> **29/29 tests PASS**.
  - `MeasurementApiIntegrationTest` -> **22/22 tests PASS**.
- **OpenAPI & Contract Tests:** `contracts/openapi/openapi.yaml` -> **Valid** (Redocly); `npm run test:contracts` -> **21/21 tests PASS**; `npm run validate:contracts` -> **PASS**.
- **Admin Web Frontend Tests:** `npm --prefix apps/admin-web test -- --run` -> **21 test files, 148/148 tests PASS** (Bao gồm 6 tests trong `SupportModerationAuditPages.test.tsx` và 6 tests trong `supportModerationAuditApi.test.ts`).
- **Code Quality & Build:** `npm run lint:admin` -> **0 errors**; `npm run build:admin` -> **Build thành công**; `git diff --check` -> **Clean**.

### 10.3 Khắc phục các Nhận xét Review PR #152 (12/11/2026)

Sau khi PR #152 được merge vào `main` tại commit `7b8c61a`, toàn bộ 6 nhóm nhận xét review (P1.A, P1.B, P1.C, P2.A, P2.B, P2.C) đã được tái hiện, khắc phục triệt để và bổ sung kiểm thử tự động trên branch `fix/pr152-review-findings`:

1. **P1.A — Bắt buộc xác thực Step-Up khi cấp phiên Privileged Access:**
   - **Vấn đề:** Trước đây việc xác thực step-up trong `PrivilegedAccessService.createRequest()` bị bọc trong điều kiện `if (request.stepUpPassword() != null)`, dẫn tới việc nếu client không truyền mật khẩu thì bỏ qua hoàn toàn bước xác thực step-up mà vẫn cấp phiên `ACTIVE`. Đồng thời `stepUpAuthenticationId` gửi kèm không được xác minh.
   - **Khắc phục:** Bắt buộc xác minh step-up qua `adminStepUpService.resolveOrVerifyStepUp(adminUserId, "PRIVILEGED_ACCESS_REQUEST", password, stepUpAuthenticationId)` (hoặc fallback `PRIVILEGED_DATA_ACCESS`). Thiếu thông tin, sai mật khẩu, ID hết hạn hoặc ID của admin khác đều bị từ chối dứt khoát với HTTP 401 hoặc 403. Ghi nhận `stepUpAuthenticationId` vào audit log và domain event metadata.

2. **P1.B — Thực thi Resource, Purpose & Access Scope trên Data Correction:**
   - **Vấn đề:** `DataCorrectionService` chỉ kiểm tra phiên privileged access còn hạn nhưng chưa xác minh: `purpose == "DATA_CORRECTION"`, quyền hạn trên domain/targetType (`MEASUREMENT`, `USER_PROFILE`), và không kiểm tra target user/resource khi `targetUserId` là null.
   - **Khắc phục:** Thêm kiểm tra nghiêm ngặt `DATA_CORRECTION.equalsIgnoreCase(privAccess.purpose())`; kiểm tra cấu trúc JSON `accessScopeJson` cho phép `domains` hoặc `targetTypes` khớp với loại tài nguyên cần sửa; bổ sung fallback kiểm tra quyền sở hữu tài nguyên cụ thể đối với số đo hoặc profile nếu `targetUserId` của phiên không được chỉ định.

3. **P1.C — Tuân thủ ranh giới module (Clean Architecture):**
   - **Vấn đề:** `DataCorrectionService` (module `support.correction`) phụ thuộc trực tiếp vào persistence adapter `PrivilegedAccessPersistenceAdapter` và concrete service của module `administration`.
   - **Khắc phục:** Tách inbound port `PrivilegedAccessQuery` (`findById(UUID)`) trong module `administration.privileged.application.port.in`, để `PrivilegedAccessService` hiện thực port này. `DataCorrectionService` chỉ tiêm interface `PrivilegedAccessQuery` và `AdminStepUpUseCase`. Bổ sung test kiến trúc `SupportModuleArchitectureTest` kiểm soát việc không import persistence adapter của module khác.

4. **P2.A — Xác thực mã đơn vị đo lường và tính tương thích thứ nguyên khi hiệu chỉnh số đo:**
   - **Vấn đề:** Khi hiệu chỉnh số đo, nếu truyền unit code không tồn tại, hệ thống âm thầm fallback về đơn vị cũ mà không báo lỗi, hoặc chấp nhận đơn vị có thứ nguyên (dimension) không tương thích.
   - **Khắc phục:** Tra cứu `fitness.measurement_units` và `fitness.metric_definitions`. Báo lỗi HTTP 400 (`VALIDATION_FAILED`) ngay lập tức nếu mã đơn vị không tồn tại hoặc thứ nguyên của đơn vị hiệu chỉnh không khớp với thứ nguyên mặc định của chỉ số (ví dụ: gán đơn vị độ dài `cm` cho chỉ số khối lượng `WEIGHT`).

5. **P2.B — Serialize JSON chuẩn cho Metadata Audit & Domain Event:**
   - **Vấn đề:** Việc nối chuỗi thô `{"note\":\"" + note.replace("\"", "\\\"") + "\"}"` trong `SupportCaseService`, `ModerationService`, `PrivilegedAccessService` dễ sinh ra chuỗi JSON không hợp lệ khi `note`, `resolutionReason` chứa dấu xuống dòng (`\n`), ký tự gạch chéo ngược (`\`), ngoặc kép hoặc ký tự Unicode tiếng Việt.
   - **Khắc phục:** Sử dụng Jackson `ObjectMapper` tuần tự hóa an toàn toàn bộ payload metadata của audit và domain events.

6. **P2.C — Cơ chế sinh mã Support Case chống trùng lặp đồng thời (V59):**
   - **Vấn đề:** Mã phiếu `SUP-{timestamp % 10000000}` có nguy cơ đụng độ cao khi nhiều yêu cầu tạo phiếu diễn ra trong cùng một mili-giây, vi phạm ràng buộc `case_number UNIQUE`.
   - **Khắc phục:** Tạo forward migration `V59__support_case_number_sequence.sql` khai báo PostgreSQL sequence `fitness.support_case_number_seq START WITH 100001` và logic tự động tăng sequence vượt qua mã hiện có trong database. `SupportCasePersistenceAdapter` sử dụng sequence này để sinh mã duy nhất `SUP-{seq}` không đụng độ dưới tải đồng thời.

- **Bằng chứng Kiểm chứng Toàn diện:**
  - `AdminSupportModerationAuditIntegrationTest`: **23/23 tests PASS** (bổ sung các regression tests chuyên sâu: `testPrivilegedAccessStepUpEnforcement`, `testGovernedDataCorrection_AccessScopeAndPurposeValidation`, `testGovernedDataCorrection_UnitValidation`, `testAuditJsonEscapingAndUnicodeIntegrity`, `testSupportCase_ConcurrentCreationNoCollision` [10 luồng đồng thời qua CountDownLatch/ExecutorService], `testSupportCase_SequenceAdvancementAfterLegacyDataUpgrade` [tự động tiến vượt mã legacy V58], `testModerationWarning_SpecialCharactersInReasonProducesValidJson` [kiểm chứng JSON hợp lệ trong security_events.details], `testPrivilegedAccessRevocationValidation`).
  - `SupportModuleArchitectureTest`: **1/1 test PASS** (kiểm tra không vi phạm ranh giới module).
  - Hồi quy Quản trị người dùng & Số đo: `AdminAccountAdministrationIntegrationTest` (**29/29 tests PASS**), `MeasurementApiIntegrationTest` (**22/22 tests PASS**).
  - Flyway Migrations: **59/59 migrations PASS** (gồm cả V59).
  - Admin Web Frontend: **148/148 tests PASS**; Lint 0 errors; Production Build thành công.
  - Contract & Schema Tests: **21/21 tests PASS**; OpenAPI valid.
