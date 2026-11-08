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
| **PRI-02** | **Fitness Goals (M2)** | Create first goal, edit via Goal Version, transition journeys via Goal Transition, proposal accept/reject with conflict handling, trainer proposal creation for scoped student. Note: Independent from virus scanner. | Mobile `CurrentGoalScreen`, `GoalProposalDetailScreen`, `CreateGoalModal`, `CreateGoalVersionModal`, `CreateGoalTransitionModal`, `TrainerStudentGoalScreen`, `CreateGoalProposalModal`, route `(app)/coaching/[relationshipId]/goal` | `POST /api/v1/fitness-goals`, `POST /fitness-goals/{id}/versions`, `POST /fitness-goals/{id}/transitions`, `POST /fitness-goal-proposals/{id}/decisions`, `GET /api/v1/students/{studentId}/fitness-goals/current`, `GET /api/v1/students/{studentId}/fitness-goal-proposals`, `POST /api/v1/fitness-goals/{goalId}/proposals` | Student ownership, Trainer coaching scope, `FITNESS_GOAL` data sharing check, version conflict 409 | Backend: 130 integration tests + 34 unit tests PASS; Mobile: 91 tests PASS (`CurrentGoalScreen`, `fitnessGoalApi`, `GoalProposalDetailScreen`, `TrainerStudentGoalScreen`); Typecheck: 0 errors | **IMPLEMENTED (Component & Integration Tests Passing; E2E Device Pending Infrastructure)** |
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
| **F-01** | AI Lockdown bypassed if client injected | Moved test mode checks outside `if client is None:` and installed transport-level interceptor in `conftest.py`. Added 5 regression tests. | `test_ai_lockdown.py` (82 tests PASS in suite, 6 tests re-verified in self-review) |
| **F-02** | Missing document upload & admin document review | Added `POST /trainer-applications/documents` endpoint, Admin document list & download endpoints with audit logging, Mobile document picker & list, Admin Web document/certificate tables & download button. | Unit & integration tests pass across backend, mobile, admin-web. |
| **F-03** | Proposal endpoint naming clarification | Corrected documentation: Trainer creates proposals for a scoped student's active goal via `POST /api/v1/fitness-goals/{goalId}/proposals`, while `GET /api/v1/students/{studentId}/fitness-goal-proposals` lists proposals for that student. | `FitnessGoalProposalIntegrationTest` (41 tests PASS), `FitnessGoalProposalControllerUnitTest` (8 tests PASS). |
| **F-04** | MediaQueryPort semantics | Clarified `allMediaExistAndOwnedBy` (existence & ownership) vs `allMediaExistOwnedAndClean` (enforcing `scan_status = 'CLEAN'`). | `MediaPersistenceAdapterUnitTest` (4 tests PASS). |
| **F-05** | Disentangle blockers for Goals vs Trainer Verification | Clarified that Goals does NOT depend on the ClamAV scanner daemon; only Trainer Verification requires live scanner daemon / real media storage. Both still await manual UI acceptance testing on physical device / AVD. | Documented below in Section 7 & 8. |
| **F-NEW-01** | Documentation endpoint alias misstatement | Đính chính sai sót trong báo cáo tự rà soát trước đó về "alias POST theo studentId": code và contract chỉ hỗ trợ POST theo goalId, không có alias POST theo studentId. Không sửa code/contract. | Tài liệu đã đồng bộ hoàn toàn với controller và OpenAPI. |

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
- **S3-compatible Storage (MinIO local):** `curl -s http://localhost:9000/minio/health/live` -> HTTP 200. (Lớp lưu trữ `TrainerDocumentStorageService` có fallback cache bộ nhớ `localCache` khi MinIO chưa khởi chạy).
- **ClamAV daemon & Cơ chế Fail-Closed:**
  - Kiểm tra kết nối TCP tới daemon: Mở socket TCP tới `localhost:3310` gửi `PING\0`, nhận phản hồi `PONG`.
  - Cơ chế fail-closed của backend: Khi `CHAT_CLAMD_INTERNAL_HOST` được cấu hình nhưng daemon ClamAV tắt hoặc không kết nối được, `TrainerDocumentStorageService.java#L122` ném `ApplicationValidationException` ("Antivirus scanner is currently unavailable"). `GlobalExceptionHandler.java#L794-L804` bắt exception này và trả về **HTTP 400 Bad Request** với thân JSON:
    ```json
    {
      "errorCode": "VALIDATION_FAILED",
      "message": "Antivirus scanner is currently unavailable",
      "fieldErrors": [
        {
          "field": "file",
          "code": "SCANNER_UNAVAILABLE",
          "message": "Antivirus scanner is currently unavailable"
        }
      ]
    }
    ```
    Tệp nhị phân không được lưu vào storage và hồ sơ không được tạo. (Lưu ý: HTTP 503 chỉ trả về trong module chat qua `ClamdAttachmentScanner`, còn upload tài liệu xác thực trả về HTTP 400 `VALIDATION_FAILED` kèm field error `SCANNER_UNAVAILABLE`).

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
