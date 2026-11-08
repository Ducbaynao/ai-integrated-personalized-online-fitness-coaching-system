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
  - `TrainerDocumentStorageService.java`: Implemented fail-closed verification document upload without in-memory RAM fallback; file signature magic byte validation (PDF, PNG, JPEG); ClamAV `zINSTREAM` stream scanning throwing `ScannerUnavailableException` (HTTP 503 `SCANNER_UNAVAILABLE`) if unconfigured or unreachable, or `ApplicationValidationException(MALWARE_DETECTED)` (HTTP 400) if infected; S3 storage operations throwing `StorageUnavailableException` (HTTP 503 `STORAGE_UNAVAILABLE`) if unconfigured or failed, and `TrainerDocumentNotFoundException` (HTTP 404 `TRAINER_DOCUMENT_NOT_FOUND`) if object not found; S3 orphan cleanup on database persistence failure.
  - Endpoints added:
    - `POST /api/v1/trainer-applications/documents` (multipart document upload for trainers)
    - `GET /api/v1/admin/trainer-applications/{applicationId}/documents` (admin document list)
    - `GET /api/v1/admin/trainer-applications/{applicationId}/documents/{mediaId}/download` (admin document download with `ADMIN_TRAINER_DOCUMENT_ACCESSED` audit)
    - `GET /api/v1/admin/trainer-applications/{applicationId}/certificates` (admin certificate list)
- **Trainer Applications:**
  - `TrainerApplicationService.java`: Enforced required rejection reasons, concurrency protection on admin decisions, verified status updates on trainer profile, S3 orphan cleanup on DB failure, and audit event publishing.
  - `AdminTrainerApplicationIntegrationTest.java`: 26 tests verifying admin queue, filtering, approve/reject flows, audit logging, and 409 conflict handling.
  - `TrainerApplicationIntegrationTest.java`: 17 tests verifying complete lifecycle including discovery filtering.
  - `TrainerDocumentStorageServiceTest.java`: 20 unit tests verifying fail-closed storage, fail-closed scanner, mock socket ClamAV stream protocol, magic byte validation, and EICAR handling.
  - `TrainerApplicationServiceUnitTest.java`: 27 unit tests for application transitions and upload error cleanup.
  - `AdminTrainerVerificationServiceUnitTest.java`: 22 unit tests for admin verification and download logic.
- **Fitness Goals & Proposals:**
  - `GetStudentCurrentGoalForTrainerUseCase.java` & `GetStudentGoalProposalsForTrainerUseCase.java`: Ports for trainer retrieval of scoped student goals and proposals.
  - `FitnessGoalProposalService.java`: Implemented trainer query methods enforcing `GoalTrainerAuthorityPort.verifyTrainerCanViewProposal` (validates relationship, active `HUMAN_COACH` period, and `FITNESS_GOAL:VIEW` permission).
  - `FitnessGoalProposalController.java`: Added endpoints:
    - `GET /api/v1/students/{studentId}/fitness-goals/current` (`@PreAuthorize("hasRole('TRAINER')")`)
    - `GET /api/v1/students/{studentId}/fitness-goal-proposals` (`@PreAuthorize("hasRole('TRAINER')")`)
    - `POST /api/v1/fitness-goals/{goalId}/proposals` (`@PreAuthorize("hasRole('TRAINER')")`)
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
  - `POST /api/v1/fitness-goals/{goalId}/proposals`

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

> [!NOTE]
> **Tuyên bố Bản quyền & Giới hạn Đánh giá:**  
> Đánh giá trạng thái này là kết quả **Self-Review** của chính agent thực hiện triển khai và sửa lỗi. Đây không phải là review độc lập từ bên thứ ba và đang **chờ reviewer khác xác nhận**.

### A. Trạng thái Mã nguồn & Đủ điều kiện Review (Ready for Review)
- Self-review chưa phát hiện finding chặn merge trong phạm vi đã kiểm tra.
- Chờ reviewer khác xác nhận độc lập.
- Không có lỗi biên dịch, không có lỗi type error, không có lỗi linter/OpenAPI schema.
- **Phân định kết quả kiểm thử:**
  - *Kiểm thử thực sự chạy lại trong phiên self-review hiện tại:* `test_ai_lockdown.py` (6 tests PASS), Admin Web TrainerApplication vitest (13 tests PASS), Mobile TrainerProfileScreen jest (17 tests PASS), Backend Media + Trainer unit tests (79 tests PASS), Contracts linting (0 errors).
  - *Kiểm thử kế thừa từ phiên monorepo trước đó:* Backend full suite (449 tests PASS), AI service full suite (82 tests PASS), Mobile full suite (720 tests / 89 suites PASS), Admin Web full suite (117 tests / 16 suites PASS).
  - *Lưu ý:* Việc các bài kiểm thử tự động vượt qua là điều kiện cần nhưng không đảm bảo tuyệt đối "không có hồi quy" trong mọi trường hợp biên runtime.

### B. Trạng thái Nghiệm thu UI E2E (Chưa hoàn tất nghiệm thu UI E2E — Pending Acceptance)
- Chưa khẳng định hoàn thành 100% tính năng khi chưa chạy nghiệm thu tương tác trực tiếp trên giao diện thực tế.
- Chưa khẳng định đã kiểm chứng storage/scanner thật khi mới chỉ kiểm chứng qua lớp mock/in-memory trong automated unit tests.
- **Blockers thực tế đã quan sát (Factual Observations):**
  - **Mục tiêu Thể chất (Goals - M2):** Không bị chặn bởi ClamAV daemon hay S3 storage (luồng Goals không upload binary). Chưa hoàn tất nghiệm thu UI E2E trên thiết bị di động thật hoặc Android Virtual Device (AVD) / iOS Simulator.
  - **Xác minh Huấn luyện viên (Trainer Verification - M1/M8):**
    - Thiếu hạ tầng daemon ClamAV thật (`clamd` port 3310) và lưu trữ tương thích S3 thật (ví dụ MinIO cục bộ) đang chạy dạng background service để xác thực quét malware nhị phân trực tiếp qua stream `zINSTREAM`.
    - Chưa chạy nghiệm thu thủ công các bước chọn file và tương tác UI trên thiết bị di động thật / AVD.

---

## 6. Review Checklist for Reviewers

- [ ] Verify that `RUN_LIVE_OPENAI_TESTS=true` remains false in CI/CD environments.
- [ ] Verify that Trainer Application rejection requires non-empty `rejectionReason` in Admin Web.
- [ ] Verify that `/api/v1/coaching/trainers` strictly excludes non-verified trainers.
- [ ] Verify that Trainer cannot access `/api/v1/students/{studentId}/fitness-goals/current` for students outside their coaching roster.
- [ ] Verify that concurrent goal proposal acceptance returns 409 and prompts user to reload state.
- [ ] Verify that Trainer creates proposals via `POST /api/v1/fitness-goals/{goalId}/proposals` and lists them via `GET /api/v1/students/{studentId}/fitness-goal-proposals`.

---

## 7. Hướng dẫn Nghiệm thu Thực tế Có thể Chạy Được (Runnable Acceptance Guide)

### 7.1 Lệnh khởi chạy các dịch vụ (Theo cấu hình thực tế của repository)

Build tool của backend được xác nhận là **Maven** (`pom.xml`, Java 21, Spring Boot 4.1.1); wrapper là `./mvnw` (Linux/macOS) và `mvnw.cmd` (Windows). Repository không sử dụng Gradle.

```bash
# 1. Khởi chạy Backend (Spring Boot)
# Working Directory: services/backend
cd services/backend
./mvnw spring-boot:run
# Trên Windows: .\mvnw.cmd spring-boot:run
# Hoặc từ repo root: .\services\backend\mvnw.cmd -f services/backend/pom.xml spring-boot:run
# Hoặc script: .\services\backend\run-dev.ps1

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
```

### 7.2 Biến cấu hình cần thiết (Mẫu không nhạy cảm)

Khớp chính xác với cấu hình `services/backend/src/main/resources/application.yml` và `docker-compose.yml`:

- **Backend (`application-dev.yml` hoặc biến môi trường):**
  - `DATABASE_URL=jdbc:postgresql://localhost:5433/digital_fitness` (Port 5433 theo docker-compose)
  - `DATABASE_USERNAME=fitness_app`
  - `DATABASE_PASSWORD=change-me`
  - `CHAT_S3_ENDPOINT=http://localhost:9000` (MinIO local)
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

### 7.3 Health check & Xác nhận dịch vụ sẵn sàng

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

### 7.4 Chuẩn bị tài khoản kiểm thử (Roles & Capabilities)

1. **Tài khoản HLV (Trainer):** User vai trò `TRAINER`, trạng thái `ACTIVE`, hồ sơ `NOT_SUBMITTED` hoặc `REJECTED`, có active coaching relationship với Học viên 1.
2. **Tài khoản Học viên (Student):** User vai trò `STUDENT`, trạng thái `ACTIVE`, có mục tiêu thể chất đang hoạt động.
3. **Tài khoản Quản trị viên (Admin):** User vai trò `ADMIN`, trạng thái `ACTIVE`.

### 7.5 Quy trình nghiệm thu từng luồng

#### Luồng 1: Mục tiêu Thể chất (Physical Fitness Goals) — Không phụ thuộc scanner/storage
1. **HLV tạo đề xuất:** Đăng nhập HLV trên Mobile -> Vào `RelationshipDetailScreen` của Học viên 1 -> Bấm nút điều hướng mục tiêu học viên (`(app)/coaching/[relationshipId]/goal`) -> Màn hình `TrainerStudentGoalScreen` tải mục tiêu và lịch sử đề xuất (`GET /api/v1/students/{studentId}/fitness-goals/current` & `GET /api/v1/students/{studentId}/fitness-goal-proposals`) -> Bấm "Đề xuất điều chỉnh mục tiêu" -> Điền lý do (bắt buộc), tiêu đề mới, thời gian -> Bấm gửi đề xuất (`POST /api/v1/fitness-goals/{goalId}/proposals`). Đề xuất mới hiển thị ở trạng thái `PENDING`.
2. **Học viên quyết định:** Đăng nhập Học viên trên Mobile -> Vào `CurrentGoalScreen` -> `GoalProposalDetailScreen` -> So sánh mục tiêu hiện tại vs đề xuất:
   - *Chấp nhận (Accept):* Bấm "Chấp nhận" (`POST /fitness-goal-proposals/{proposalId}/decisions` với `decision = 'ACCEPT'`). Mục tiêu chuyển version mới; đề xuất chuyển `ACCEPTED`.
   - *Từ chối (Reject):* Bấm "Từ chối" kèm lý do. Đề xuất chuyển `REJECTED`, mục tiêu cũ giữ nguyên.
3. **Kiểm tra tính nhất quán:** Tải lại màn hình để kiểm tra lịch sử mục tiêu không bị ghi đè.
4. **Bằng chứng cần lưu:** Ảnh chụp màn hình HLV gửi đề xuất, màn hình học viên đối chiếu quyết định, màn hình mục tiêu sau khi cập nhật version.

#### Luồng 2: Xác minh Huấn luyện viên (Trainer Verification) — Có scanner & storage tương thích S3
1. **HLV đính kèm tài liệu & nộp hồ sơ:** Đăng nhập HLV trên Mobile -> Vào `TrainerProfileScreen` -> Trạng thái `NOT_SUBMITTED` -> Bấm "+ Attach Document" -> Document Picker chọn file PDF/PNG/JPEG (< 10MB) -> Gọi `POST /api/v1/trainer-applications/documents` -> ClamAV quét virus `zINSTREAM`, lưu S3/MinIO bucket `fitness-media` với quyền `PRIVATE` -> Tệp hiển thị trong danh sách đính kèm với nhãn "Scanned & Clean". Nút "X" cho phép bỏ đính kèm nếu muốn -> Điền ghi chú ứng viên -> Bấm "Submit Application" (`POST /api/v1/trainer-applications` kèm `documentMediaIds`) -> Hồ sơ chuyển `PENDING`, hiển thị banner "APPLICATION UNDER REVIEW".
2. **Admin kiểm tra & tải tài liệu:** Đăng nhập Admin trên trình duyệt -> Mở `http://localhost:5173/trainer-applications` -> Danh sách hiển thị hồ sơ `PENDING` -> Bấm vào chi tiết hồ sơ (`TrainerApplicationDetailPage`) -> Hiển thị bảng chứng chỉ và bảng tài liệu đính kèm kèm badge `CLEAN` -> Bấm "Tải xuống" bên cạnh tài liệu (`GET /api/v1/admin/trainer-applications/{applicationId}/documents/{mediaId}/download`) -> File tải về máy; backend ghi vết audit `ADMIN_TRAINER_DOCUMENT_ACCESSED`. Response header có `Cache-Control: no-store` và `Content-Disposition: attachment`.
3. **Admin từ chối (Reject flow):** Bấm "Từ chối hồ sơ" -> Nhập lý do từ chối (bắt buộc) -> Xác nhận -> Hồ sơ chuyển `REJECTED`, ghi vết audit `TRAINER_APPLICATION_REJECTED`.
4. **HLV nộp lại (Resubmit flow):** HLV mở lại `TrainerProfileScreen` trên Mobile -> Hiển thị thông báo từ chối kèm lý do chính xác -> Đính kèm tài liệu mới bổ sung -> Bấm "Resubmit Application" -> Hồ sơ chuyển lại thành `PENDING`.
5. **Admin phê duyệt (Approve flow) & Tìm kiếm:** Admin mở lại hồ sơ trên Admin Web -> Bấm "Phê duyệt hồ sơ" -> Nhập ghi chú duyệt -> Xác nhận -> Hồ sơ và profile HLV chuyển `VERIFIED`, ghi vết audit `TRAINER_APPLICATION_APPROVED` -> HLV bật công tắc "Accepting Students" trên Mobile -> Gọi `GET /api/v1/coaching/trainers` -> HLV xuất hiện trong danh sách tìm kiếm công khai.
6. **Bằng chứng cần lưu:** Ảnh chụp tải tài liệu trên Mobile, bảng tài liệu trên Admin Web, tệp tải về máy, bản ghi audit trong `fitness.audit_logs`.
7. **Lưu ý nguyên tắc:** Không dùng cập nhật database trực tiếp để giả lập trạng thái scanner hay bỏ qua các bước giao diện. Nghiệp vụ chỉ yêu cầu Document Picker (không bắt buộc camera vật lý). Môi trường cục bộ tương thích S3 (như MinIO) là đáp ứng kiến trúc, không bắt buộc cloud AWS S3.
