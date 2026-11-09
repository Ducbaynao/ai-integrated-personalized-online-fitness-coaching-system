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
