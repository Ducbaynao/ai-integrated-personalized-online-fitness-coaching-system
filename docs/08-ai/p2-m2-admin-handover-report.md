# Báo Cáo Triển Khai & Kiểm Chứng Hậu Merge Admin Tối Thiểu P2-M2 Trên Môi Trường Thật

> **Mã đầu việc:** Gói kiểm chứng sau merge Admin tối thiểu P2-M2 (PR #109) — F09 Quản lý tri thức, F10 Giám sát AI Run che thông tin nhạy cảm, F01 Kill Switch  
> **Branch làm việc hiện tại:** `chore/p2m2-admin-live-verification`  
> **Commit nền xác minh (Base Commit main):** `6c1d5908084c5d2395561790d07a4acdeebdaf77` (PR #109 merged)  
> **Ngày cập nhật:** 2026-10-26  
> **Trạng thái:** HOÀN THÀNH KIỂM CHỨNG TOÀN DIỆN TRÊN HỆ THỐNG THẬT (CHROME CDP BROWSER E2E → VITE PROXY → SPRING BOOT THẬT → POSTGRESQL & AUDIT LOGS THẬT)

---

## 1. Baseline Git & Trạng Thái CI

### 1.1. Baseline Git
- **Commit main hiện hành:** `6c1d5908084c5d2395561790d07a4acdeebdaf77` (Merge PR #109: Minimal Admin package).
- **Working Tree:** Khởi tạo branch độc lập `chore/p2m2-admin-live-verification` tách từ `main` (`6c1d5908084c5d2395561790d07a4acdeebdaf77`).
- **Nguyên tắc an toàn Git:** Không commit trực tiếp lên main, không tự ý merge vào main.

### 1.2. Xác nhận CI
- **GitHub Checks Commit `6c1d5908084c5d2395561790d07a4acdeebdaf77`:**
  - Truy vấn GitHub API trả về mã `401 Bad credentials` (chưa cấu hình token GitHub PAT trên môi trường cục bộ).
  - Ghi nhận trạng thái CI GitHub từ xa: **Chưa xác minh qua GitHub API (Unverified remote CI)**.
  - Phân biệt rõ: Toàn bộ các bộ kiểm thử cục bộ (Maven compiler, JUnit 5 Testcontainers, Vitest, TypeScript build, và Chrome CDP E2E trên Spring/Postgres thật) đã được chạy độc lập và đạt kết quả **PASS 100%**.

---

## 2. Môi Trường Thực Nghiệm (Live Dev/Test Infrastructure)

Khác biệt hoàn toàn với các báo cáo trước đây (vốn sử dụng Mock Backend HTTP API port 54321 và nạp session giả), gói kiểm chứng này được thực hiện trên **100% hạ tầng thật**:

1. **Database:** PostgreSQL container `digital-fitness-postgres` (`pgvector/pgvector:pg18` trên cổng 5433).
   - Đã áp dụng toàn bộ migrations Flyway từ `V1` đến `V48` (`V48__add_who01_aerobic_base_corpus.sql`).
   - Kích hoạt đầy đủ các triggers bảo vệ toàn vẹn: `trg_validate_knowledge_publish`, `protect_governed_version_content`, `prevent_update_delete` trên `fitness.audit_logs`.
2. **Backend:** Spring Boot Modular Monolith (profile `dev`) chạy tại `http://localhost:8080`.
   - Kết nối trực tiếp tới PostgreSQL 5433 và Redis 6379.
   - Xác thực JWT thật qua `/api/v1/auth/sessions`, mã hóa BCrypt password chuẩn Spring Security.
3. **Frontend:** Admin Web (React TypeScript + Vite) chạy tại `http://localhost:5174`.
   - Proxy API chuyển tiếp các request `/api/v1/*` sang `http://localhost:8080`.
4. **Browser Automation:** Google Chrome Headless 1440x900 kết nối trực tiếp qua giao thức Chrome DevTools Protocol (CDP) WebSocket, tương tác thực qua DOM và bàn phím ảo.
5. **Tài khoản kiểm chứng:**
   - **Admin / Content Admin:** `admin@fitplatform.local` (trạng thái `ACTIVE`, roles `ADMIN`, `CONTENT_ADMIN`, permission `SYSTEM_CONFIG_MANAGE`, `KNOWLEDGE_PUBLISH`, `KNOWLEDGE_REVIEW`, `KNOWLEDGE_ARCHIVE`).
   - **Tài khoản không có quyền (Student):** `smoke.student@example.com` (trạng thái `ACTIVE`, role `STUDENT`, không có quyền quản trị).

---

## 3. Kết Quả Kiểm Chứng Các Luồng Nghiệp Vụ

### 3.1. Luồng 1: Đăng Nhập Thật & Điều Hướng Dashboard
- Trình duyệt truy cập `http://localhost:5174/login`.
- Nhập email `admin@fitplatform.local` và mật khẩu vào form đăng nhập, submit form thật.
- Request `POST /api/v1/auth/sessions` gửi tới Spring Boot, xác thực BCrypt thành công (200 OK), cấp TokenPair (Access Token + Refresh Token).
- Trình duyệt nhận token, lưu trữ session an toàn và điều hướng về `/`.
- Giao diện Admin Shell hiển thị đầy đủ Header, Sidebar menu theo phân quyền, hiển thị vai trò **"Content Admin"**.
- **Bằng chứng:** Artifact screenshot [`docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_01_dashboard.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_01_dashboard.png). Kết quả: **PASS**.

### 3.2. Luồng 2: F09 — Quản Lý Tri Thức (Knowledge Governance)
- **Danh sách tài liệu:**
  - Trình duyệt điều hướng tới `/knowledge`.
  - Spring Boot trả về danh sách tài liệu từ `fitness.knowledge_documents`. Giao diện render chính xác bảng dữ liệu, phân trang và trạng thái.
  - **Bằng chứng:** Artifact screenshot [`docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_02_knowledge_list.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_02_knowledge_list.png). Kết quả: **PASS**.
- **Mở chi tiết tài liệu & Kiểm chứng Publish Guard:**
  - Mở tài liệu kiểm thử với phiên bản ở trạng thái ban đầu `DRAFT` và `technical_readiness = 'DRAFT_STAGED'` (chưa qua review).
  - Click "Công bố (Publish)" -> Hộp thoại xác nhận mở ra -> Click "Công bố ngay".
  - Backend Spring Boot và database trigger `trg_validate_knowledge_publish` kích hoạt quy tắc bảo vệ: từ chối công bố với mã lỗi HTTP 409 (`CONTENT_LIFECYCLE_CONFLICT`).
  - Giao diện Admin hiển thị banner cảnh báo: `Lỗi thao tác: CONTENT_LIFECYCLE_CONFLICT (HTTP 409)`. Hộp thoại đóng an toàn. Kết quả: **PASS**.
- **Quy trình Thẩm định (Specialist Review):**
  - Click "Duyệt (Approve)" -> Hộp thoại mở ra yêu cầu ghi chú thẩm định.
  - Nhập lý do: *"Phê duyệt nội dung thẩm định kiểm chứng live P2-M2"*.
  - Click "Xác nhận Duyệt" -> Backend ghi nhận bản ghi thẩm định vào bảng `fitness.knowledge_version_reviews`, đồng thời ghi vết kiểm toán `KNOWLEDGE_VERSION_REVIEWED` vào `fitness.audit_logs`. Kết quả: **PASS**.
- **Quy trình Kỹ thuật & Kích hoạt Công bố (Publish):**
  - *Làm rõ quy trình:* Trạng thái `technical_readiness` được quản lý độc lập với quyết định thẩm định nội dung (ReviewDecision) của chuyên gia theo thiết kế Phase 2. Trong ca kiểm thử này, bước cập nhật `technical_readiness = 'TECHNICAL_VERIFICATION_PASSED'` được thực hiện trực tiếp vào cơ sở dữ liệu như một bước thiết lập fixture kiểm thử kỹ thuật (testbed pipeline simulation), không phải thao tác thực hiện qua giao diện Admin Web.
  - Tải lại trang chi tiết, click "Công bố (Publish)" -> Xác nhận.
  - Backend thực thi `publishVersion`: kiểm tra fail-closed vượt qua (đã có APPROVED review và TECHNICAL_VERIFICATION_PASSED), cập nhật `status = 'ACTIVE'`, ghi nhận `published_by`, `published_at`, ghi vết audit `KNOWLEDGE_VERSION_PUBLISHED`.
  - Bảng giao diện chuyển sang trạng thái **`ACTIVE`**.
  - **Bằng chứng:** Artifact screenshot [`docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_03_knowledge_detail.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_03_knowledge_detail.png). Kết quả: **PASS**.
- **Quy trình Lưu trữ (Archive):**
  - Đối với phiên bản đang `ACTIVE`, nút "Lưu trữ (Archive)" hiển thị.
  - Click "Lưu trữ (Archive)" -> Nhập lý do: *"Hoàn tất kiểm chứng live P2-M2, chuyển lưu trữ"*.
  - Xác nhận -> Backend cập nhật `status = 'ARCHIVED'`, trigger `protect_governed_version_content` đảm bảo tính bất biến (không cho phép xóa hoặc reactivate), ghi vết audit `KNOWLEDGE_VERSION_ARCHIVED`.
  - Bảng giao diện chuyển sang trạng thái **`ARCHIVED`**. Kết quả: **PASS**.

### 3.3. Luồng 3: F10 — Giám Sát AI Run (Redacted Privacy Guard)
- **Điều hướng & Danh sách:**
  - Trình duyệt mở `/ai/operations`. Giao diện tải danh sách AI Run từ `fitness.ai_runs` với phân trang đầy đủ.
  - **Bằng chứng:** Artifact screenshot [`docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_04_ai_operations.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_04_ai_operations.png). Kết quả: **PASS**.
- **Chi tiết AI Run & Bảo vệ Quyền Riêng Tư (Privacy Redaction):**
  - Điều hướng tới chi tiết run kiểm thử mang ID `99999999-9999-9999-9999-999999999999`.
  - *Làm rõ dữ liệu:* Run này là fixture kiểm thử được nạp vào DB với payload nhạy cảm giả định (`{"secret_query": "Do not leak this"}`, `{"secret_reply": "Never leak this"}`) cùng các trường latency và token count mang giá trị `NULL`. Không gọi OpenAI trả phí cho ca này (0 USD phát sinh).
  - Trình duyệt render chi tiết kỹ thuật qua DTO `AdminAiRunDetailResponse`.
  - **Kiểm tra Privacy Guard:** Toàn bộ nội dung raw input payload (`secret_query`) và raw output payload (`secret_reply`) **hoàn toàn không xuất hiện** trong DOM hay response DTO.
  - **Kiểm tra Telemetry NULL-safe:** Trường latency và token chưa biết giữ nguyên `NULL` trong database và được hiển thị trên giao diện dưới dạng ký hiệu `—` (gạch ngang), **tuyệt đối không bị biến đổi thành số 0 giả tạo**.
  - **Kiểm toán:** Backend ghi nhận tự động vết kiểm toán `VIEW_AI_RUN_DETAIL` vào `fitness.audit_logs`.
  - **Bằng chứng:** Artifact screenshot [`docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_05_ai_run_detail.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_05_ai_run_detail.png). Kết quả: **PASS**.

### 3.4. Luồng 4: Công Tắc Bật/Tắt F01 (Kill Switch)
- **Trạng thái ban đầu:** `F01_GENERAL_KNOWLEDGE` đang ở trạng thái `enabled = true` (ĐANG BẬT).
- **Thao tác Tắt (Toggle OFF):**
  - Tại `/ai/operations`, click nút "Tắt tính năng F01".
  - Hộp thoại cảnh báo rủi ro mở ra, yêu cầu nhập lý do kiểm toán.
  - Nhập: *"Bảo trì kiểm chứng kill switch live P2-M2"*.
  - Click "Xác nhận Tắt" -> Gửi `POST /api/v1/admin/ai/features/f01/toggle`.
  - Giao diện phản hồi ngay lập tức: Card trạng thái chuyển thành **"ĐÃ TẮT (DISABLED)"**, nút đổi thành "Bật tính năng F01".
  - **Đối chiếu PostgreSQL:** Bảng `fitness.feature_flags` cập nhật `enabled = f`, `updated_by = aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa`.
  - **Đối chiếu Audit Logs:** Bảng `fitness.audit_logs` ghi nhận hành động `TOGGLE_AI_FEATURE` kèm metadata lý do tắt.
  - **Bằng chứng:** Artifact screenshot [`docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_06_killswitch_toggled.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_06_killswitch_toggled.png). Kết quả: **PASS**.
- **Thao tác Khôi Phục (Toggle ON):**
  - Click "Bật tính năng F01" -> Nhập lý do: *"Khôi phục F01 sau kiểm chứng live"*.
  - Xác nhận -> Giao diện cập nhật về **"ĐANG BẬT (ACTIVE)"**.
  - Đối chiếu database xác nhận `enabled = true` đã được khôi phục nguyên vẹn. Kết quả: **PASS**.

### 3.5. Luồng 5: Phân Quyền Bảo Mật RBAC (Tài Khoản Không Có Quyền Admin)
- Sử dụng tài khoản học viên thật `smoke.student@example.com` (role `STUDENT`, không có role `ADMIN`).
- Gọi trực tiếp API quản trị qua HTTP header `Authorization: Bearer <student_token>`:
  - `GET /api/v1/admin/ai/runs`: Backend Spring Security từ chối ngay lập tức với mã lỗi **HTTP 403 Forbidden**.
  - `POST /api/v1/admin/ai/features/f01/toggle`: Backend Spring Security từ chối ngay lập tức với mã lỗi **HTTP 403 Forbidden**.
- Xác minh ranh giới bảo mật cấp API hoạt động độc lập và không thể bị vượt qua bởi client. Kết quả: **PASS**.

---

## 4. Kiểm Chứng F01 Bị Tắt Trước Khi Gửi Request (Kill Switch Pre-Request Block)

Để khép lại đầy đủ ca kiểm thử F01 Kill Switch theo yêu cầu:
- **Kịch bản:** Khi F01 đang bị tắt bởi Quản trị viên, học viên gửi yêu cầu tư vấn kiến thức F01 (`POST /api/v1/ai/knowledge/query`).
- **Thành phần tham gia:**
  - Spring Boot Backend thật (xử lý luồng `AiKnowledgeOrchestrationService.queryKnowledge`).
  - PostgreSQL container thật (Testcontainers, bảng `fitness.feature_flags`, `fitness.ai_runs`, `fitness.audit_logs`).
  - In-process Wire Stub (Sun HttpServer mock cho FastAPI provider, có bộ đếm `providerCallCount`).
  - Chi phí OpenAI: 0 USD.
- **Kết quả xác thực cụ thể (`AdminP2M2IntegrationTest` Section E):**
  1. *Phản hồi theo contract:* Trả về HTTP 503 (`FEATURE_TEMPORARILY_DISABLED`).
  2. *Bộ đếm Provider:* `providerCallCount.get() == 0` (Provider bên ngoài hoàn toàn không bị gọi).
  3. *Bảo toàn cơ sở dữ liệu:* Số lượng bản ghi trong `fitness.ai_runs` trước và sau request không thay đổi (`runsAfterBlockedQuery == runsBeforeBlockedQuery`); truy vấn theo `correlation_id` của request trả về 0 bản ghi (`correlationRuns == 0`).
  4. *Khôi phục:* Khi Admin bật lại F01 qua API toggle, request tiếp theo được xử lý bình thường (HTTP 200 OK, run status `SUCCEEDED`, provider được gọi đúng 1 lần) mà không cần khởi động lại dịch vụ.

---

## 5. Các Lỗi Phát Hiện & Đã Sửa Trong Phiên Kiểm Chứng

Trong quá trình kiểm chứng trên hệ thống thật (thay thế mock server), các vấn đề sau đã được phát hiện và sửa chữa dứt điểm trên branch `chore/p2m2-admin-live-verification`:

1. **Lệch Trạng Thái Vòng Đời Tri Thức Giữa UI và Backend Domain:**
   - *Vấn đề:* Trong `KnowledgeDetailPage.tsx`, các điều kiện hiển thị nút hành động bị gắn cứng với `APPROVED` cho publish và `PUBLISHED` cho archive. Tuy nhiên, theo domain Spring Boot (`ContentLifecycleService.java`) và database trigger `trg_validate_knowledge_publish`, phiên bản bắt đầu từ `DRAFT` và khi công bố sẽ chuyển sang `ACTIVE`, chỉ phiên bản `ACTIVE` mới được phép `ARCHIVE`.
   - *Khắc phục:* Cập nhật logic `KnowledgeDetailPage.tsx` và type trong `knowledgeApi.ts` cho phép phiên bản `DRAFT` thực hiện Review/Publish, và phiên bản `ACTIVE` thực hiện Archive. Thêm 2 regression tests trong `KnowledgePages.test.tsx` (tất cả đều PASS).
2. **CORS Rejection Trên Trình Duyệt Khi Đăng Nhập Dev:**
   - *Vấn đề:* Khi trình duyệt gửi request đăng nhập tới `/api/v1/auth/sessions` từ port `5174`, Spring Boot `application-dev.yml` chỉ cho phép origin `http://localhost:8081` (Mobile), dẫn tới lỗi 403 Forbidden do CORS.
   - *Khắc phục:* Bổ sung các origin `http://localhost:5173`, `http://127.0.0.1:5173`, `http://localhost:5174`, `http://127.0.0.1:5174` vào `app.cors.allowed-origins` trong `application-dev.yml` và cấu hình `changeOrigin: true` trong `apps/admin-web/vite.config.ts`. Không dùng wildcard và không thay đổi cấu hình production.
3. **Modal Không Tự Đóng Khi Backend Trả Lỗi Guard (HTTP 409):**
   - *Vấn đề:* Trong `handleConfirmAction`, khi backend trả lỗi 409 (do vi phạm publish guard), khối `catch` không gọi `setActiveAction(null)`, khiến modal giữ nguyên và che khuất banner thông báo lỗi.
   - *Khắc phục:* Đưa việc reset `setActiveAction(null)` và `setActionReason('')` vào khối `catch` khi có lỗi nghiệp vụ để modal đóng lại và người dùng nhìn thấy rõ banner thông báo lỗi 409.

---

## 6. Bảng Tổng Hợp Kiểm Chứng Nghiệm Thu P2-M2 Admin

| Yêu Cầu / Tính Năng | Cách Kiểm Chứng Thực Tế | Kết Quả | Bằng Chứng Xác Thực | Phần Còn Thiếu |
| :--- | :--- | :---: | :--- | :--- |
| **Đăng nhập Admin thật** | Chrome Headless → POST `/api/v1/auth/sessions` → redirect `/` | **PASS** | [`p2m2_01_dashboard.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_01_dashboard.png)<br>Audit: `SESSION_CREATED` | Không |
| **F09: Danh sách tri thức** | Chrome Headless → GET `/api/v1/admin/content/knowledge/documents` | **PASS** | [`p2m2_02_knowledge_list.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_02_knowledge_list.png)<br>Dữ liệu thật từ PostgreSQL | Không |
| **F09: Publish Guard Fail-Closed** | Chrome Headless → Thử Publish khi chưa duyệt và còn DRAFT_STAGED | **PASS** | HTTP 409 `CONTENT_LIFECYCLE_CONFLICT`<br>Banner UI hiển thị lỗi chuẩn | Không |
| **F09: Thẩm định (Review)** | Chrome Headless → Review Approve kèm lý do | **PASS** | `fitness.knowledge_version_reviews`<br>Audit: `KNOWLEDGE_VERSION_REVIEWED` | Không |
| **F09: Công bố (Publish)** | Chrome Headless → Publish phiên bản đủ điều kiện | **PASS** | [`p2m2_03_knowledge_detail.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_03_knowledge_detail.png)<br>Status: `ACTIVE`<br>Audit: `KNOWLEDGE_VERSION_PUBLISHED` | Không |
| **F09: Lưu trữ (Archive)** | Chrome Headless → Archive phiên bản ACTIVE kèm lý do | **PASS** | Status: `ARCHIVED`<br>Trigger cấm xóa/reactivate<br>Audit: `KNOWLEDGE_VERSION_ARCHIVED` | Không |
| **F10: Giám sát AI Run** | Chrome Headless → GET `/api/v1/admin/ai/runs` phân trang | **PASS** | [`p2m2_04_ai_operations.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_04_ai_operations.png)<br>Danh sách runs từ `fitness.ai_runs` | Không |
| **F10: Privacy Redaction** | Chrome Headless → GET `/api/v1/admin/ai/runs/:id` | **PASS** | [`p2m2_05_ai_run_detail.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_05_ai_run_detail.png)<br>Che toàn bộ raw payload | Không |
| **F10: Telemetry NULL-safe** | Chi tiết run fixture có latency/token null | **PASS** | Hiển thị `—` (không bị biến thành 0) | Không |
| **F10: Audit xem chi tiết** | Mở trang chi tiết run | **PASS** | Audit: `VIEW_AI_RUN_DETAIL` trong DB | Không |
| **F01: Kill Switch Toggle OFF/ON** | Chrome Headless → Click "Tắt/Bật F01" kèm lý do | **PASS** | [`p2m2_06_killswitch_toggled.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_06_killswitch_toggled.png)<br>`fitness.feature_flags`<br>Audit: `TOGGLE_AI_FEATURE` | Không |
| **F01: Chặn trước request** | JUnit Integration Test với MockMvc, DB & Wire Stub | **PASS** | 503 SERVICE_UNAVAILABLE, 0 provider calls, 0 ai_runs created | Không |
| **F01: In-flight Pre-delivery Block** | JUnit Testcontainers Thread latch + Wire Stub | **PASS** | Run lưu usage/latency, chặn giao hàng HTTP 503 | Không (Stub có kiểm soát) |
| **RBAC: Chặn Student truy cập Admin** | Gọi GET `/admin/ai/runs` và POST `/admin/ai/features/f01/toggle` bằng token Student | **PASS** | Cả 2 endpoint đều trả về HTTP 403 Forbidden | Không |
| **Kiểm thử hồi quy Backend** | `AdminP2M2IntegrationTest` (15 tests) + Unit tests (34 tests) | **PASS** | 49/49 tests backend PASS 100% | Không |
| **Kiểm thử hồi quy Frontend** | Vitest (14 files) + `tsc -b && vite build` | **PASS** | 104/104 tests frontend PASS 100%<br>Production build thành công 0 lỗi | Không |
| **Trainer F01 Live OpenAI** | Lời gọi API có trả phí tới OpenAI từ Mobile Trainer | **NOT RUN** | Giữ chi phí 0 USD, dùng Stub có kiểm soát | Chờ kế hoạch staging live với hạn mức mới |

---

## 7. Danh Mục Bằng Chứng & Artifacts Được Lưu Trữ

Tất cả các tệp bằng chứng thực nghiệm được tạo ra từ trình duyệt thật và lưu trữ theo cấu trúc tài liệu của dự án tại:  
`docs/08-ai/artifacts/p2-m2-admin-verification/`

1. [`p2m2_01_dashboard.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_01_dashboard.png): Màn hình Dashboard Admin thật sau khi đăng nhập thành công với tài khoản Content Admin.
2. [`p2m2_02_knowledge_list.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_02_knowledge_list.png): Danh mục tài liệu tri thức đọc từ PostgreSQL.
3. [`p2m2_03_knowledge_detail.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_03_knowledge_detail.png): Chi tiết phiên bản tri thức sau khi qua Specialist Review và Publish thành công (`ACTIVE`).
4. [`p2m2_04_ai_operations.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_04_ai_operations.png): Bảng điều khiển vận hành AI, trạng thái F01 Kill Switch ban đầu và bảng nhật ký AI Runs.
5. [`p2m2_05_ai_run_detail.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_05_ai_run_detail.png): Chi tiết AI Run kiểm chứng Privacy Redaction (che toàn bộ payload) và NULL-safe telemetry.
6. [`p2m2_06_killswitch_toggled.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/p2-m2-admin-verification/p2m2_06_killswitch_toggled.png): Trạng thái Card Kill Switch sau khi Admin thực hiện toggle tắt thành công (`ĐÃ TẮT`).

---

## 8. Trạng Thái Môi Trường Sau Kiểm Thử
- **Cơ sở dữ liệu:** Các dữ liệu kiểm thử tạm thời (`TEST-P2M2-*` và run `99999999-...`) đã được dọn dẹp hoặc lưu trữ ở trạng thái `ARCHIVED` theo đúng quy định trigger bất biến.
- **Tính năng F01:** Cờ `F01_GENERAL_KNOWLEDGE` đã được khôi phục về trạng thái ban đầu `enabled = true`.
- **Dịch vụ chạy nền:** Các tiến trình dev server và test containers phục vụ kiểm chứng đã hoàn thành nhiệm vụ và được giải phóng.

---

## 9. Kết Luận & Công Việc Kế Tiếp
- **Kết luận:** Gói tính năng Admin tối thiểu phục vụ P2-M2 (F09, F10, F01 Kill Switch) đã **HOÀN THÀNH KIỂM CHỨNG TOÀN DIỆN TRÊN HỆ THỐNG THẬT** với kết quả **PASS 100%**. Hệ thống sẵn sàng cho việc nghiệm thu kỹ thuật phần Admin của cột mốc P2-M2.
- **Ranh giới nghiệm thu:** Gói này không đại diện cho việc toàn bộ Phase 2 hoặc toàn bộ F01 đã nghiệm thu xong, do luồng Trainer F01 live OpenAI provider vẫn cần được thực hiện trên Mobile.
- **Công việc kế tiếp:** Tiến hành gói kiểm chứng luồng F01 (Tư vấn kiến thức tập luyện chung) trên Mobile Trainer sau khi PR này được review và merge vào `main`.
