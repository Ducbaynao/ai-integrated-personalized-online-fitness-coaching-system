# Báo cáo Bàn giao A-22 / A-23: Spring Endpoint, Orchestration & Internal Envelope Builder (F01 General Knowledge)

**Ngày cập nhật:** 14/10/2026  
**Branch:** `feat/p2-a22-a23-f01-orchestration`  
**Base commit:** `4170c56` (A-28 trên `feat/p2-a28-ai-run-persistence`)  
**Chủ sở hữu nhiệm vụ:** Developer A (Content & AI F01 Pipeline)  
**Trạng thái Git:** Toàn bộ thay đổi mã nguồn được giữ nguyên trong working tree chưa commit theo chỉ dẫn của người dùng để kiểm tra (`git status` uncommitted).

---

## 1. Nội dung đã triển khai (A-22 / A-23)

### 1.1. Public Spring Endpoint (`POST /api/v1/ai/knowledge/query`)
- **Controller:** [`AiKnowledgeQueryController`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/AiKnowledgeQueryController.java)
- **Xác thực & Danh tính:**
  - Lấy danh tính người dùng bắt buộc từ JWT (`jwt.getSubject()`), không tin tưởng ID do client tự khai báo.
  - Kiểm tra trạng thái tài khoản `ACTIVE` và quyền `STUDENT` hoặc `TRAINER`. Base `ADMIN` đơn lẻ bị từ chối với 403 `AI_FORBIDDEN`.
- **Kiểm định đầu vào:**
  - Câu hỏi từ 5 đến 1000 unicode codepoints, không chứa chuỗi toàn khoảng trắng.
  - Ngôn ngữ: `vi` (mặc định) hoặc `en`.
  - Exercise Catalog: Nếu có `exerciseId`, kiểm tra sự tồn tại và trạng thái `ACTIVE` qua `ExerciseCatalogRepository` (404 `EXERCISE_NOT_FOUND` nếu không khả dụng).
- **Ánh xạ HTTP Status chuẩn contract F01:**
  - `200 OK`: `SUCCEEDED` (`INFORMATION` hoặc `INSUFFICIENT_DATA`) hoặc `REJECTED_BY_VALIDATOR` (`BLOCKED`).
  - `502 Bad Gateway`: `FAILED` (`MODEL_ERROR`, `PROVIDER_ERROR`) hoặc `REJECTED_BY_VALIDATOR` (`VALIDATION_FAILED`).
  - `504 Gateway Timeout`: `FAILED` (`TIMEOUT`).
  - `500 Internal Server Error`: `FAILED` (`PROCESSING_ERROR`).
  - `400 Bad Request`: `AiRunValidationException` (`VALIDATION_FAILED`).
  - `401 Unauthorized`: Thiếu JWT hoặc token không hợp lệ.
  - `403 Forbidden`: `AiRunAccessDeniedException` (`AI_FORBIDDEN`).
  - `404 Not Found`: `ExerciseNotFoundException` (`EXERCISE_NOT_FOUND`).

### 1.2. Internal Envelope Builder (`F01InternalEnvelopeBuilder`)
- **Vị trí:** [`F01InternalEnvelopeBuilder.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F01InternalEnvelopeBuilder.java)
- **Mô hình:** [`F01InternalContextEnvelope.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/domain/F01InternalContextEnvelope.java)
- **Tuân thủ hợp đồng:**
  - `requestId`: Echo từ AI Run ID của Spring.
  - `requestType`: Bắt buộc `"F01_GENERAL_KNOWLEDGE"`.
  - `schemaVersion`: `"1.0.0"`.
  - `correlationId`: UUID truy vết (nếu client không gửi, Spring tự sinh).
  - `actor`: `{ "userId": UUID, "role": "STUDENT" | "TRAINER" }` (lấy từ kiểm tra quyền phía server).
  - `purpose`: Bắt buộc `"GENERAL_FITNESS_EXPLANATION"`.
  - `allowedActions`: Danh sách whitelist duy nhất `["READ_INFORMATION"]`.
  - `asOf`: Chuẩn RFC 3339 (giây 00..59, không chứa leap seconds).
  - `knowledgeScope`: `{ "status": "ACTIVE", "allowedSourceIds": ["ISSN-PROTEIN-EXERCISE-2017", "WHO-PA-SB-2020", "ODS-EXERCISE-SUPPLEMENTS-2024", "NIDDK-WEIGHT-FACTORS-2023"] }`.
  - `exerciseContext`: Tùy chọn, chỉ đính kèm metadata đã xác thực (`exerciseId`, `exerciseName`, `movementPattern`).
  - **Bảo vệ ranh giới:** Tuyệt đối không mở rộng sang dữ liệu cá nhân (hồ sơ, lịch sử tập luyện, dinh dưỡng, số đo) hay các hành động đề xuất thay đổi (`PROPOSAL`/`Apply`).

### 1.3. Điều phối (Orchestration) & Quản lý Transaction Boundary
- **Vị trí:** [`AiKnowledgeOrchestrationService.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiKnowledgeOrchestrationService.java)
- **Tách biệt Transaction với Network Call:**
  - **Transaction 1:** Tạo bản ghi `AiRun` (`QUEUED`), dựng envelope và lưu snapshot vào `fitness.ai_context_snapshots` kèm hash SHA-256 $\rightarrow$ Commit.
  - **Transaction 2:** Chuyển trạng thái Run sang `RUNNING` với optimistic locking và terminal protection $\rightarrow$ Commit.
  - **Outside Transaction (Network I/O):** Gọi cổng dịch vụ AI `F01AiServiceClient.queryKnowledge(envelope)` hoàn toàn **ngoài** transaction database, không giữ connection hay khóa dòng trong lúc chờ mô hình xử lý.
  - **Transaction 3:** Đánh giá kết quả từ dịch vụ AI theo các quy tắc bất biến của hợp đồng, ghi nhận kết quả thẩm định vào `fitness.ai_validation_results`, ghi nhận completion terminal (`SUCCEEDED`, `FAILED`, `REJECTED_BY_VALIDATOR`) vào `fitness.ai_runs` $\rightarrow$ Commit.
- **Kiểm định đầu ra (Output Validation Guard):**
  - Không bao giờ đánh dấu `SUCCEEDED` nếu kết quả không thỏa mãn hợp đồng (thiếu answer, thiếu citations trong INFORMATION, vi phạm định dạng locator, hoặc lẫn lộn trạng thái terminal).
  - Nếu kết quả không hợp lệ, chuyển sang `REJECTED_BY_VALIDATOR` với mã lỗi `VALIDATION_FAILED` và trả HTTP 502 đã được sanitize.

### 1.4. Outbound Port & Adapter Giả lập
- **Cổng giao tiếp:** [`F01AiServiceClient.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/port/out/F01AiServiceClient.java)
- **Adapter giả lập:** [`SimulatedF01AiServiceClient.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/out/client/SimulatedF01AiServiceClient.java)
  - Hỗ trợ mô phỏng các kịch bản: `INFORMATION` hợp lệ, `INSUFFICIENT_DATA`, `BLOCKED` (chính sách an toàn WADA/doping), timeout (504), provider error (502), và lỗi cấu trúc validation.

---

## 2. Kết quả kiểm chứng tự động

1. **Integration Test Suite Mới (`AiKnowledgeQueryOrchestrationIntegrationTest`):**
   - Chạy trên PostgreSQL Testcontainers (`pgvector/pgvector:pg18`) với toàn bộ 38 migration Flyway.
   - **8/8 tests PASS (0 failures, 0 errors, 0 skipped):**
     1. `successfulInformationQueryForStudent`: Trả 200 OK, kết quả INFORMATION, lưu snapshot envelope đúng schema, ghi nhận validation passed.
     2. `successfulQueryWithExerciseForTrainer`: Trả 200 OK, lưu exerciseContext trong envelope và exercise reference trong `ai_run_input_references`.
     3. `insufficientDataOutcome`: Trả 200 OK, `resultType = INSUFFICIENT_DATA`, `reasonCode = NO_RELEVANT_EVIDENCE`, không có answer, có missingData/limitations.
     4. `policyGuardrailBlocked`: Trả 200 OK, `runStatus = REJECTED_BY_VALIDATOR`, `resultType = BLOCKED`, `reasonCode = RULE_BLOCKED`.
     5. `providerTimeoutFailure`: Trả 504 Gateway Timeout, ghi nhận Run `FAILED` với `TIMEOUT`.
     6. `providerFailure`: Trả 502 Bad Gateway, ghi nhận Run `FAILED` với `MODEL_ERROR`.
     7. `outputValidationFailure`: Trả 502 Bad Gateway, ghi nhận Run `REJECTED_BY_VALIDATOR` với `VALIDATION_FAILED` (chặn tuyệt đối ghi nhận SUCCEEDED).
     8. `accessControlAndInputValidation`: Kiểm tra 401 (không token), 403 (`AI_FORBIDDEN` cho Admin cơ bản), 404 (`EXERCISE_NOT_FOUND` cho exerciseId không tồn tại/không active), 400 (`VALIDATION_FAILED` cho câu hỏi quá ngắn, chỉ có khoảng trắng, hoặc ngôn ngữ không hỗ trợ).

2. **Kiểm tra hồi quy Suite Persistence (`AiRunF01PersistenceIntegrationTest`):**
   - **7/7 tests PASS (0 failures)**: Các kiểm tra quyền sở hữu, lưu trữ, optimistic locking và terminal protection của A-28 tiếp tục hoạt động hoàn hảo.

3. **Kiểm tra Hợp đồng Executable Contract (`npm run test:contracts`):**
   - **21/21 tests PASS (0 failures)**: Tuân thủ đầy đủ schema F01, ranh giới thẩm quyền, và không có leap second.

4. **Kiểm tra cú pháp (`git diff --check`):**
   - Không có conflict markers hay lỗi khoảng trắng nào.

---

## 3. Ranh giới & Gap còn lại cho A-26 / A-25

- **A-26 (FastAPI Service & Endpoint thật):**
  - Cần triển khai endpoint `POST /internal/v1/knowledge/query` (hoặc `/internal/ai/runs/f01/general-knowledge`) trên FastAPI để tiếp nhận `F01InternalContextEnvelope`.
  - Cấu hình HTTP Client thật trên Spring Boot (thay thế hoặc bọc ngoài `SimulatedF01AiServiceClient`) để gọi sang FastAPI.
  - Tích hợp model LLM provider thật.
- **A-25 (Runtime Validation & Active-only RAG Retrieval):**
  - Cần triển khai bộ trích xuất vector embeddings thực tế cho các tài liệu tri thức khi được Publish sang `ACTIVE`.
  - Triển khai thuật toán similarity search pgvector cho các câu hỏi F01.
  - Hiện tại corpus vẫn đang ở trạng thái DRAFT (`ACTIVE = 0`), RAG retrieval thực tế chưa được bật.
