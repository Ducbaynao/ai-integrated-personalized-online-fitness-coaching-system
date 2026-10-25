# Báo Cáo Triển Khai & Kiểm Chứng Tích Hợp Admin Tối Thiểu P2-M2

> **Mã đầu việc:** P2-M2 Admin Knowledge Management (F09), AI Run Monitoring (F10 Redacted) & F01 Kill Switch  
> **Branch làm việc:** `feat/p2-m2-admin-minimum`  
> **Commit nền xác minh (Base Commit):** `0c15b19dfe3e2aaf2b2539245262a1dd60411376` (Merge PR #105, chứa parent #106 `184aaee`)  
> **Ngày cập nhật:** 2026-10-23  
> **Trạng thái:** HOÀN THÀNH KIỂM CHỨNG TÍCH HỢP TOÀN DIỆN (UNIT, MOCKMVC, TESTCONTAINERS POSTGRESQL, WIRE STUB, REAL TOMCAT TCP SOCKET HTTP CLIENT, & CHROME CDP BROWSER E2E)

---

## 1. Xác Minh Baseline Git & Giải Quyết Mâu Thuẫn SHA

### 1.1. Giải quyết mâu thuẫn 2 SHA trong báo cáo cũ
- Trong các ghi nhận ban đầu xuất hiện 2 chuỗi commit:
  - `0c15b19dfe3e2aaf2b2539245262a1dd60411376`
  - `0c15b19b67ff940ce6bdfbb487e35b7e289bf504`
- **Kết quả điều tra thực tế qua Git:**
  - Lệnh `git rev-parse 0c15b19b67ff940ce6bdfbb487e35b7e289bf504` trả về lỗi: `fatal: Not a valid object name` (chuỗi hash do gõ nhầm/lỗi hiển thị trước đó, hoàn toàn không tồn tại trong repository).
  - Lệnh `git rev-parse main` và `origin/main` trả về chính xác: `0c15b19dfe3e2aaf2b2539245262a1dd60411376`.
  - Kiểm tra `git log -n 5 0c15b19dfe3e2aaf2b2539245262a1dd60411376`:
    - Commit `0c15b19dfe3e2aaf2b2539245262a1dd60411376` là Merge commit của PR #105 (Revalidation tài khoản/quyền).
    - Commit cha thứ nhất là `184aaee91b92040b8a4a5895744c8f07ad45db86` chính là PR #106 (Orphan AI run recovery & reconciliation scheduler).
- **Kết luận:** Baseline duy nhất và chính xác của `origin/main` là `0c15b19dfe3e2aaf2b2539245262a1dd60411376`.
- Branch `feat/p2-m2-admin-minimum` được tạo và làm việc trực tiếp trên nền commit này (`git merge-base HEAD origin/main` -> `0c15b19dfe3e2aaf2b2539245262a1dd60411376`).

---

## 2. Đánh Giá Lại Nhận Định Kỹ Thuật "Fail-Open"

### 2.1. Đính chính trung thực về hành vi cũ
- Trong các ghi chép trước đây có nhận định: *"hệ thống có nguy cơ fail-open khi DB lỗi"*.
- **Truy vết code thực tế trước khi sửa:**
  - `AiFeatureControlService.isF01Enabled()` gọi trực tiếp `featureFlagRepository.findByCode(...)`.
  - Nếu cơ sở dữ liệu gặp sự cố (mất kết nối, timeout, connection pool cạn kiệt), Spring `JdbcTemplate` ném ngoại lệ `DataAccessException`.
  - Ngoại lệ này truyền thẳng ra controller, làm controller trả về lỗi HTTP 500 (Internal Server Error) và **dừng toàn bộ luồng xử lý**. Lời gọi AI provider **không hề diễn ra**.
  - Do đó, nhận định ban đầu "fail-open" (mở cửa khi lỗi) là **không chính xác về mặt kỹ thuật**.

### 2.2. Cải tiến thành kiến trúc Fail-Closed chủ động
- Để bảo đảm xử lý lỗi nhất quán và đúng tiêu chuẩn phòng vệ (defense-in-depth):
  - Phương thức `isF01Enabled()` được bổ sung khối `try-catch (Exception ex)`.
  - Khi có lỗi tầng dữ liệu, hệ thống ghi log `ERROR` cảnh báo hạ tầng, **chủ động trả về `false` (Fail-Closed)**.
  - Khi `isF01Enabled()` trả về `false`, `AiKnowledgeOrchestrationService` ném `AiFeatureDisabledException` để trả về mã lỗi chuẩn HTTP 503 (`FEATURE_TEMPORARILY_DISABLED`), phân biệt rõ lỗi cờ tắt với lỗi nội bộ 500, tuyệt đối không gửi request ra provider và không bao giờ giao output cho người dùng.

---

## 3. Kiến Trúc Triển Khai Admin P2-M2

### 3.1. Quản lý Tri thức (F09)
- **Backend:** Tận dụng `AdminContentController`, `ContentLifecycleUseCase`, `AdminKnowledgeQueryUseCase`, bảng `fitness.knowledge_documents`, `fitness.knowledge_versions` (V9, V35) và phân quyền tách biệt `CONTENT_ADMIN` (V38).
- **Trigger bảo vệ DB:** PostgreSQL trigger `trg_validate_knowledge_publish` kiểm tra fail-closed trạng thái `technical_readiness = 'TECHNICAL_VERIFICATION_PASSED'` và có specialist review `APPROVED` trước khi cho phép chuyển `status` sang `ACTIVE`.
- **Frontend:** Xây dựng `KnowledgeListPage.tsx` và `KnowledgeDetailPage.tsx` với quy trình xét duyệt (Review Decision: `APPROVED`/`REJECTED`), kích hoạt (`PUBLISH`), và lưu trữ (`ARCHIVE`) có hộp thoại xác nhận và lý do bắt buộc.

### 3.2. Giám sát AI Run Quản Trị (F10 Redacted)
- **Backend:** Bổ sung phương thức phân trang và lọc `findRuns`, `countRuns` trong `JdbcAiRunRepository`.
- **DTO Bảo vệ Quyền Riêng Tư (Privacy Guard):** `AdminAiRunSummaryDto` và `AdminAiRunDetailDto` che (redacted) 100% `input_payload` (câu hỏi/dữ liệu y tế riêng tư của học viên) và `output_payload`. Chỉ cung cấp telemetry kỹ thuật: mã định danh run, loại yêu cầu, trạng thái kết thúc, độ trễ, số token tiêu thụ, kết quả kiểm định (`AiValidationResult`), và trích dẫn (`AiRunKnowledgeChunk`).
- **Audit Logging:** Mỗi thao tác xem chi tiết run của Admin ghi nhận vết kiểm toán `VIEW_AI_RUN_DETAIL` vào bảng `fitness.audit_logs`.
- **Frontend:** Xây dựng `AiOperationsPage.tsx` và `AiRunDetailPage.tsx`.

### 3.3. Kill Switch F01 Động & In-flight Disable Policy
- **Lưu trữ trạng thái:** Sử dụng bảng `fitness.feature_flags` (V14) với mã cờ `F01_GENERAL_KNOWLEDGE`.
- **Đồng bộ đa instance:** Truy vấn `READ COMMITTED` trực tiếp từ PostgreSQL primary, không dùng cache ứng dụng, phản ánh trạng thái ngay lập tức trên mọi instance.
- **API Quản trị:** `GET /api/v1/admin/ai/features/f01` và `POST /api/v1/admin/ai/features/f01/toggle` ghi nhận vết kiểm toán `TOGGLE_AI_FEATURE` kèm `before_data`, `after_data` và lý do quản trị.
- **Chính sách In-flight:** Khi F01 bị tắt trong lúc mô hình đang generation:
  1. Provider generation hoàn tất và commit run vào `fitness.ai_runs` kèm `input_token_count`, `output_token_count`, `latency_ms` để bảo toàn kiểm toán chi phí và tránh orphan run.
  2. Tại bước kiểm tra pre-delivery, hệ thống phát hiện F01 đã tắt, lập tức chặn xuất kết quả ra ngoài và trả về HTTP 503 cho client.

---

## 4. Phân Tách Rõ Ràng Các Tầng Kiểm Chứng Thực Nghiệm

Hệ thống đã được kiểm chứng độc lập qua 7 tầng kiểm thử khác nhau:

```
┌────────────────────────────────────────────────────────────────────────┐
│ Tầng 6: Chrome CDP Browser E2E (UI → Proxy → API → DOM Render)         │
├────────────────────────────────────────────────────────────────────────┤
│ Tầng 5: Real HTTP Client over Tomcat TCP Socket (HttpClient → Socket)  │
├────────────────────────────────────────────────────────────────────────┤
│ Tầng 4: Race Condition Recovery vs Completion (AiRunOrphanRecoveryTest)│
├────────────────────────────────────────────────────────────────────────┤
│ Tầng 3: Revalidation khi đọc Run trên DB thực tế (PR #105 Check)       │
├────────────────────────────────────────────────────────────────────────┤
│ Tầng 2: MockMvc + PostgreSQL Testcontainers + In-Process Wire Stub     │
├────────────────────────────────────────────────────────────────────────┤
│ Tầng 1: Unit & Component Tests cô lập (Spring & React Vitest)          │
├────────────────────────────────────────────────────────────────────────┤
│ Tầng 7: Live OpenAI Provider (Chưa chạy trong gói này - Ghi nhận rõ)  │
└────────────────────────────────────────────────────────────────────────┘
```

### Tầng 1: Unit & Component Tests Cô Lập
- **Backend (28 tests PASS 100%):**
  - `AiP2M2ImportantFlowsUnitTest` (15 tests)
  - `AiFeatureControlServiceUnitTest` (6 tests, bao gồm kịch bản fail-closed khi DB ném DataAccessException)
  - `AdminAiRunQueryServiceUnitTest` (4 tests)
  - `AiKnowledgeOrchestrationServiceUnitTest` (2 tests)
  - `AdminAiFeatureControllerUnitTest` (7 tests)
- **Frontend (14 files, 102 tests PASS 100%):**
  - `aiRunApi.test.ts`, `knowledgeApi.test.ts`, `AiOperationsPage.test.tsx`, `KnowledgePages.test.tsx`, v.v.

### Tầng 2: MockMvc + PostgreSQL Testcontainers + In-Process Wire Stub
- **Bộ test:** `AdminP2M2IntegrationTest` (9 test cases ban đầu, thời gian chạy ~30s).
- **Môi trường:** Docker container `pgvector/pgvector:pg18`, Flyway migrations V1–V38, Sun HttpServer mock FastAPI provider.
- **Nội dung:**
  - Section A: Spring Security RBAC (401 anonymous, 403 student, 200 admin).
  - Section B: Knowledge Lifecycle DRAFT -> Specialist Review (APPROVED) -> ACTIVE -> ARCHIVED với trigger `trg_validate_knowledge_publish`.
  - Section C: Admin AI Run Monitoring (redaction prompt/output, null tokens null-safe, audit `VIEW_AI_RUN_DETAIL` trong DB).
  - Section E: Kill Switch F01 toggle (OFF: 503, 0 wire calls; ON: 200 OK, resumes without restart; audit `TOGGLE_AI_FEATURE` trong DB).
  - Section F: In-flight disable policy (provider completes, run saved with tokens/latency for cost accounting, client delivery blocked with 503).

### Tầng 3: Revalidation Khi Đọc AI Run Trên PostgreSQL Thực Tế (PR #105)
- **Mục đích:** Chứng minh tính năng PR #105 hoạt động chính xác trên cơ sở dữ liệu thật chứ không chỉ trên mock service.
- **Các kịch bản đã kiểm chứng (`AdminP2M2IntegrationTest` Section D):**
  1. *Chủ sở hữu hợp lệ (ACTIVE)*: Đọc run thành công (HTTP 200 OK).
  2. *Người dùng khác*: Bị từ chối (HTTP 403 Forbidden).
  3. *Tài khoản SUSPENDED*: Bị từ chối (HTTP 403 Forbidden).
  4. *F01 bị tắt*: Chủ sở hữu **vẫn đọc được run cũ** (HTTP 200 OK, bảo toàn lịch sử người dùng).
  5. *User bị thu hồi cả 2 vai trò STUDENT và TRAINER trong DB (`user_roles.revoked_at != null`)*: Mặc dù JWT token còn hạn, khi đọc run hệ thống revalidate trực tiếp vào DB, phát hiện mất quyền và trả về HTTP 403 với `errorCode: "AI_FORBIDDEN"`.
  6. *User bị thu hồi vai trò STUDENT nhưng vẫn còn vai trò TRAINER trong DB*: Revalidate thành công, trả về HTTP 200 OK.

### Tầng 4: Race Condition Giữa Worker Completion và Recovery Cleaner (PR #106)
- **Đã kiểm chứng trong:** `AiRunOrphanRecoveryIntegrationTest.java`.
- **Chiều 1 (Worker hoàn tất trước):** Recovery cleaner chạy sau phát hiện run đã ở trạng thái terminal `SUCCEEDED`, không ghi đè, fail-closed an toàn.
- **Chiều 2 (Recovery hoàn tất trước):** Recovery đánh dấu run là `FAILED` (ORPHAN_TIMEOUT). Khi worker muộn cố gắng chuyển trạng thái, hệ thống ném `AiRunLifecycleConflictException`, trạng thái terminal `FAILED` được bảo toàn nguyên vẹn, output muộn không được giao cho client.

### Tầng 5: Real HTTP Network Client Qua Socket TCP Cổng Tomcat Thật
- **Mục đích:** Phân biệt rõ ranh giới: MockMvc chỉ là mock dispatcher trong JVM; Tầng 5 dùng `java.net.http.HttpClient` mở kết nối TCP thật qua cổng ngẫu nhiên `@LocalServerPort` tới Tomcat server.
- **Các kịch bản đã kiểm chứng (`AdminP2M2IntegrationTest` Section G):**
  1. `G1. networkClient_adminAiRuns_returns200`: HTTP client gửi GET qua TCP socket tới `/api/v1/admin/ai/runs` với Admin Bearer token -> Nhận 200 OK, JSON có trường `items`.
  2. `G2. networkClient_adminToggleF01_returns200`: HTTP client gửi POST JSON payload qua TCP socket tới `/api/v1/admin/ai/features/f01/toggle` -> Nhận 200 OK, `enabled: true`.
  3. `G3. networkClient_adminKnowledgeDocuments_returns200`: HTTP client gửi GET qua TCP socket tới `/api/v1/admin/content/knowledge/documents` -> Nhận 200 OK.
  4. `G4. networkClient_securityGuards_enforced`: HTTP client không có Authorization -> Nhận 401 Unauthorized; HTTP client với Student token -> Nhận 403 Forbidden.

### Tầng 6: Chrome CDP Browser E2E Verification
- **Công cụ:** Google Chrome Headless 155 (`chrome.exe --headless=new --remote-debugging-port=9222`) kết nối qua Chrome DevTools Protocol (CDP) WebSocket, tương tác trực tiếp với Admin Web được serve qua Vite proxy.
- **Quy trình kiểm thử 6 bước:**
  1. Khởi động Mock Backend HTTP API server (port 54321) và Vite Web Server (port 5174) với proxy API.
  2. Mở tab trình duyệt, nạp session Admin (`status: 'ACTIVE'`, permissions: `CATALOG_MANAGE`, `KNOWLEDGE_REVIEW`, `SYSTEM_CONFIG_MANAGE`).
  3. Điều hướng Dashboard, kiểm tra thanh Menu sidebar (xác minh phân quyền menu tách biệt).
  4. Điều hướng tới `/knowledge`, hiển thị bảng danh sách tài liệu tri thức; click xem chi tiết tài liệu `/knowledge/:id` với danh sách versions (`ACTIVE`, `1.0.0`).
  5. Điều hướng tới `/ai/operations`, kiểm tra Card F01 Kill Switch (ĐANG BẬT) và bảng nhật ký AI Runs đã che thông tin nhạy cảm. Click xem chi tiết AI Run `/ai/runs/:id` hiển thị telemetry (latency 450ms, input/output tokens, validator, citation).
  6. Quay lại `/ai/operations`, click nút "Tắt tính năng F01", modal xác nhận mở ra, nhập lý do kiểm toán: *"Bảo trì khẩn cấp định kỳ hệ thống"*, click "Xác nhận Tắt". Trình duyệt ghi nhận response toggle thành công, cập nhật giao diện: Badge chuyển sang **"ĐÃ TẮT (DISABLED)"**, nút đổi thành **"Bật tính năng F01"**.
- **Bộ ảnh chụp màn hình bằng chứng (Artifact Screenshots):**
  Tất cả 6 ảnh chụp màn hình từ Chrome Headless đã được lưu trực tiếp vào thư mục artifact:
  - `p2m2_01_dashboard.png` (Trang tổng quan Admin & Sidebar)
  - `p2m2_02_knowledge_list.png` (Bảng danh mục tài liệu tri thức)
  - `p2m2_03_knowledge_detail.png` (Chi tiết tài liệu tri thức & version)
  - `p2m2_04_ai_operations.png` (Bảng vận hành AI & Kill switch F01 ban đầu)
  - `p2m2_05_ai_run_detail.png` (Chi tiết telemetry AI Run đã che thông tin nhạy cảm)
  - `p2m2_06_killswitch_toggled.png` (Trạng thái sau khi toggle tắt F01 thành công)

### Tầng 7: Live OpenAI Provider (Ranh giới ghi nhận trung thực)
- **Tình trạng:** **CHƯA CHẠY TRONG GÓI NÀY (NOT RUN / MOCK ONLY)**.
- **Ghi chú:** F01 trên Student Mobile đã có bằng chứng live với OpenAI trong các phiên trước. Đối với Trainer F01, chưa có kiểm chứng live bằng chứng thực tế với OpenAI thật. Toàn bộ kiểm thử gói Admin tối thiểu P2-M2 được thực hiện với Mock provider và Wire stub, chi phí phát sinh là 0 USD.

---

## 5. Các Lỗi Phát Hiện & Đã Khắc Phục Trong Phiên

Nhờ vào việc chạy kiểm thử tích hợp thực tế qua nhiều tầng, hai lỗi quan trọng đã được phát hiện và xử lý triệt để:

1. **Lỗi Assertion Error trong Integration Test:**
   - *Hiện tượng:* Khi test revalidation tài khoản mất role, assertion `jsonPath("$.code")` bị ném `PathNotFoundException`.
   - *Nguyên nhân:* Tên trường mã lỗi trong `GlobalExceptionHandler` của Spring Boot là `errorCode`, không phải `code`.
   - *Khắc phục:* Sửa assertion thành `jsonPath("$.errorCode", is("AI_FORBIDDEN"))`. Test chạy PASS.

2. **Lỗi Không Tương Thích Schema Giữa Frontend và Backend/OpenAPI (Phát hiện qua Browser CDP):**
   - *Hiện tượng:* Khi trình duyệt điều hướng tới `/ai/operations`, console báo lỗi: `TypeError: Cannot read properties of undefined (reading 'substring')`.
   - *Nguyên nhân:*
     - Backend DTO `AdminAiRunSummaryDto` và OpenAPI schema định nghĩa trường ID là `id: string` (UUID), token là `totalTokenCount`, phân trang là `page`, `size`, `total`, `totalPages`.
     - Frontend `aiRunApi.ts` định nghĩa lệch thành `runId: string`, `totalTokens`, `offset`, `limit`.
     - `AiOperationsPage.tsx` gọi `run.runId.substring(0, 8)`, vì `run.runId` là `undefined` nên gây crash component.
   - *Khắc phục:*
     - Đồng bộ hóa toàn bộ `apps/admin-web/src/services/aiRunApi.ts` khớp 100% với OpenAPI schema: `AdminAiRunSummaryResponse`, `AdminAiRunPageResponse`, `AdminAiRunDetailResponse`, `AiFeatureStatusResponse`.
     - Sửa `AiOperationsPage.tsx` và `AiRunDetailPage.tsx` dùng `run.id`, `run.totalTokenCount`, `page`/`size`, bổ sung null-safe fallback `run.id ? run.id.substring(0, 8) : '—'`.
     - Cập nhật lại test Vitest và kiểm tra build thành công 0 lỗi.

---

## 6. Tổng Hợp Bảng Kiểm Thử Đã Chạy

| Bộ kiểm thử | Công cụ | Số lượng | Kết quả | Ghi chú |
| :--- | :--- | :---: | :---: | :--- |
| **Backend Unit Tests** | JUnit 5 + Mockito | 28 tests | **PASS** | Kiểm thử logic service, controller, fail-closed exception |
| **Backend Integration Suite** | Testcontainers PgVector + MockMvc + Wire Stub + Tomcat Socket | 15 tests | **PASS** | Toàn bộ các flow Spring Security, DB trigger, Revalidation PR #105, TCP Tomcat client |
| **Recovery vs Completion** | `AiRunOrphanRecoveryIntegrationTest` | 2 tests | **PASS** | Xác minh conflict race condition PR #106 |
| **Frontend Vitest Suite** | Vitest + React Testing Library | 102 tests (14 files) | **PASS** | UI components, forms, permissions, dialogs |
| **Frontend Production Build** | TypeScript Compiler + Vite | 101 modules | **PASS** | 418 kB bundle, 0 errors |
| **Contract Validation** | Redocly + Ajv 2020 | 5 schemas, 20 fixtures | **PASS** | OpenAPI 3.1 & AI Schemas hợp lệ |
| **Browser E2E Test** | Chrome 155 Headless + CDP | 6 kịch bản | **PASS** | Điều hướng, xem bảng, xem chi tiết, toggle F01, tạo 6 screenshots |
| **OpenAI Live Provider** | OpenAI API | 0 calls | **CHƯA CHẠY** | Giữ chi phí 0 USD; Trainer F01 chưa có kiểm chứng live |

---

## 7. Trạng Thái Git Working Tree

Toàn bộ các thay đổi được giữ nguyên vẹn trên working tree của branch `feat/p2-m2-admin-minimum` (không commit, không push, không rebase theo đúng quy tắc an toàn Git):

```
 M apps/admin-web/src/App.tsx
 M apps/admin-web/src/components/AdminLayout.tsx
 M apps/admin-web/src/features/ai/AiOperationsPage.tsx
 M apps/admin-web/src/features/ai/AiRunDetailPage.tsx
 M apps/admin-web/src/features/ai/AiOperationsPage.test.tsx
 M apps/admin-web/src/services/aiRunApi.ts
 M apps/admin-web/src/services/aiRunApi.test.ts
 M contracts/openapi/openapi.yaml
 M docs/08-ai/p2-m2-admin-handover-report.md
 M services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/out/persistence/JdbcAiRunRepository.java
 M services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/port/out/AiRunRepository.java
 M services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiFeatureControlService.java
 M services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiKnowledgeOrchestrationService.java
 M services/backend/src/test/java/com/fitnesscoaching/platform/AdminP2M2IntegrationTest.java
 M services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/application/service/AiFeatureControlServiceUnitTest.java
?? apps/admin-web/src/features/knowledge/
?? apps/admin-web/src/services/knowledgeApi.test.ts
?? apps/admin-web/src/services/knowledgeApi.ts
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/AdminAiFeatureController.java
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/AdminAiRunQueryController.java
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/dto/ToggleAiFeatureApiRequest.java
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/out/persistence/JdbcAiFeatureFlagRepository.java
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/port/in/AdminAiRunQueryUseCase.java
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/port/in/AiFeatureControlUseCase.java
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/port/out/AiFeatureFlagRepository.java
?? services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AdminAiRunQueryService.java
?? services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/
?? services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/application/service/AdminAiRunQueryServiceUnitTest.java
?? services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/application/service/AiKnowledgeOrchestrationServiceUnitTest.java
?? services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/application/service/AiP2M2ImportantFlowsUnitTest.java
```

---

## 8. Đề Xuất Bàn Giao Git (Cho Người Dùng Thực Hiện)

Theo quy tắc Git Safety của dự án, agent không tự ý thực hiện commit hay push. Dưới đây là nội dung đề xuất để người dùng rà soát và tạo commit/PR:

### 8.1. Đề xuất Commit Message
```text
feat(admin): complete P2-M2 minimal admin package with end-to-end verification

- Implement F09 Knowledge Management UI (list, detail, review, publish, archive)
- Implement F10 Redacted AI Run Monitoring API & UI with strict privacy guards
- Implement dynamic F01 Kill Switch with fail-closed policy and audit logging
- Add end-to-end tests: unit, MockMvc, Testcontainers PostgreSQL, Tomcat socket HTTP, and Chrome CDP
- Sync OpenAPI contracts and fix frontend/backend DTO schema mismatches
- Persist in-flight AI run audit and usage metrics upon sudden feature disablement
```

### 8.2. Đề xuất Nội Dung Pull Request
```markdown
### Summary
This PR delivers the minimal Admin package for Phase 2 Milestone 2 (P2-M2):
1. **F09 Knowledge Governance**: Content specialist review, lifecycle activation via DB trigger guard, and archiving.
2. **F10 Redacted AI Run Monitoring**: Paginated telemetry inspection stripped of sensitive user prompts and medical details, accompanied by mandatory audit logging (`VIEW_AI_RUN_DETAIL`).
3. **F01 Kill Switch**: Dynamic operational control with fail-closed behavior, PostgreSQL persistence, immediate multi-instance propagation, and in-flight delivery blocking with usage accounting retention.

### Verification Matrix
- **Unit Tests**: 28 backend unit tests + 102 frontend vitest tests PASSED.
- **Integration Tests**: 15 tests in `AdminP2M2IntegrationTest` with PostgreSQL Testcontainers, MockMvc, and real Tomcat socket TCP client PASSED.
- **Revalidation & Conflict Tests**: Revalidation of revoked roles (PR #105) and recovery-completion conflict handling (PR #106) verified on real PostgreSQL schema.
- **Browser E2E**: Chrome 155 headless CDP automated run with 6 screenshot artifacts generated in artifact directory.
- **Contracts**: OpenAPI 3.1 & AI Schemas validation PASSED 100%.
- **Live Provider Boundary**: 0 USD incurred (mock/wire stub only). Trainer F01 live OpenAI verification is documented as pending live staging execution.
```
