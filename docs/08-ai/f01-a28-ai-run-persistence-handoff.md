# A-28: Lớp nghiệp vụ và Lưu trữ AI Run (F01 General Knowledge) — Handoff & Progress Report

**Ngày cập nhật:** 14/10/2026  
**Branch:** `feat/p2-a28-ai-run-persistence`  
**Chủ sở hữu nhiệm vụ:** Developer A (Content & AI F01 Pipeline)  
**Phạm vi:** Triển khai application service, domain logic, persistence layer, REST query API, và integration tests cho AI Run F01 kiến thức chung (INFORMATION).

---

## 1. Tóm tắt kết quả hoàn thành của A-28

### 1.1. Kiến trúc Domain & Enums
- **`AiRunStatus`:** Ánh xạ 1:1 với PostgreSQL enum `fitness.ai_run_status` (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `REJECTED_BY_VALIDATOR`, `CANCELLED`). Cung cấp phương thức `isTerminal()` để bảo vệ trạng thái cuối.
- **`ResultType`:** Phân định rõ loại kết quả hợp đồng (`INFORMATION`, `INSUFFICIENT_DATA`, `BLOCKED`). Tách bạch hoàn toàn khỏi trạng thái vận hành của Run.
- **`ReasonCode`:** Mã lý do chuẩn hóa theo hợp đồng (`NONE`, `MISSING_REQUIRED_CONTEXT`, `NO_RELEVANT_EVIDENCE`, `RULE_BLOCKED`, `VALIDATION_FAILED`, `MODEL_ERROR`, `PROVIDER_ERROR`, `TIMEOUT`, `PROCESSING_ERROR`).
- **Domain Records:**
  - `AiRun`: Biểu diễn đầy đủ một AI Run (ID, purpose, request_type, status, requested_by, correlation_id, evaluation_only, context_snapshot_id, result_type, reason_code, model_output, error_message, raw_tokens, inference_time_ms, created_at, started_at, completed_at).
  - `AiContextSnapshot`: Lưu trữ envelope context đầu vào cùng SHA-256 content hash.
  - `AiRunInputReference`: Liên kết các thực thể tham chiếu (e.g. `EXERCISE_CATALOG_DETAIL`).
  - `AiRunKnowledgeChunk`: Lưu trữ trích dẫn tài liệu tri thức (chunk id, document ID, version ID, similarity score, citation text).
  - `AiValidationResult`: Lưu kết quả kiểm định schema/domain trước khi trả kết quả.

### 1.2. Application & Persistence Layer (Ports & Adapters)
- **Cổng ra & Triển khai JDBC:**
  - `AiRunRepository` & `JdbcAiRunRepository`:
    - Tái sử dụng schema nền `fitness.ai_runs` (không sửa đổi hay thêm migration thừa).
    - Hỗ trợ lưu mới (`save`) với transaction boundary xác định.
    - Cập nhật trạng thái an toàn: chuyển sang `RUNNING` bằng optimistic update (`WHERE id = ? AND status = 'QUEUED'`), hoàn tất terminal run bằng `markCompleted(...)` với `FOR UPDATE` lock và kiểm tra chặn ghi đè trạng thái terminal (`AiRunLifecycleConflictException`).
  - `AiProvenanceRepository` & `JdbcAiProvenanceRepository`:
    - Lưu snapshot context envelope vào `fitness.ai_context_snapshots` kèm hash SHA-256.
    - Lưu các thực thể tham chiếu vào `fitness.ai_run_input_references`.
    - Lưu trích dẫn tri thức đã thẩm định vào `fitness.ai_run_knowledge_chunks`.
    - Lưu kết quả thẩm định vào `fitness.ai_validation_results`.
- **Nghiệp vụ Use Case `AiRunF01Service`:**
  - **Xác thực chủ sở hữu (Ownership):** `requestedBy` được trích xuất trực tiếp từ JWT authenticated identity (`actorId`), cấm client tự gửi hoặc giả mạo.
  - **Kiểm soát quyền truy cập:**
    - Người gọi phải ở trạng thái `ACTIVE` (`accountStatusQuery`).
    - Phải có năng lực `STUDENT` hoặc `TRAINER` (`userRoleQuery`).
    - Quyền `ADMIN` cơ bản đơn lẻ bị từ chối với `AiRunAccessDeniedException` (403 Forbidden).
  - **Kiểm định dữ liệu đầu vào:**
    - Câu hỏi có độ dài từ 5 đến 1000 unicode codepoints, cấm chuỗi chỉ chứa khoảng trắng.
    - Ngôn ngữ hợp lệ: `vi`, `en`.
    - Nếu có `exerciseId`, kiểm tra sự tồn tại và trạng thái `ACTIVE` qua `ExerciseCatalogRepository`. Nếu không tìm thấy, ném `ExerciseNotFoundException` (404).
  - **Sanitization lỗi:**
    - Lọc bỏ JDBC credentials, database URL, password, token, và internal Java stack traces (`Exception:`, `at com.`, `.java:`) trước khi lưu vào `error_message` hoặc trả cho client.

### 1.3. REST Web Adapter & Exception Handling
- **`AiRunQueryController`:** `GET /api/v1/ai/runs/{runId}`
  - Kiểm tra quyền sở hữu đối với người gọi (`requestedBy == authenticatedUserId`). Người khác gọi vào bị từ chối 403 `AI_FORBIDDEN`.
  - Trả về DTO `AiRunResponse` theo chuẩn hợp đồng, không làm lộ entity persistence.
- **`GlobalExceptionHandler`:**
  - `AiRunNotFoundException` $\rightarrow$ 404 `AI_RUN_NOT_FOUND`
  - `AiRunAccessDeniedException` $\rightarrow$ 403 `AI_FORBIDDEN`
  - `AiRunLifecycleConflictException` $\rightarrow$ 409 `AI_RUN_LIFECYCLE_CONFLICT`
  - `AiRunValidationException` $\rightarrow$ 400 `VALIDATION_FAILED`

---

## 2. Kết quả kiểm chứng tự động (Test Results)

1. **Backend Integration Test (`AiRunF01PersistenceIntegrationTest`):**
   - Chạy trên PostgreSQL container thực tế (`pgvector/pgvector:pg18`) với toàn bộ 38 bản migration Flyway.
   - **7/7 tests PASS (0 failures, 0 errors, 0 skipped):**
     - `createAndReadF01AiRunOwnership()`: Tạo và đọc Run hợp lệ cho cả Student và Trainer; kiểm tra trường DTO.
     - `unauthorizedAccessRejection()`: Chặn đọc chéo giữa hai Student (403 `AI_FORBIDDEN`); chặn Admin cơ bản tạo Run (403).
     - `inputValidationBounds()`: Chặn câu hỏi quá ngắn (<5 ký tự), chỉ có khoảng trắng, ngôn ngữ không hỗ trợ, hoặc exerciseId không tồn tại.
     - `provenancePersistence()`: Lưu và đọc chính xác context snapshot, input references, chunk citations, và validation results.
     - `lifecycleTransitionsAndTerminalProtection()`: Kiểm tra chuyển đổi trạng thái `QUEUED` $\rightarrow$ `RUNNING` $\rightarrow$ `SUCCEEDED`; chặn tuyệt đối việc ghi đè trạng thái terminal bằng `markCompleted` lần 2 (409 Conflict).
     - `failedRunLifecycleAndReasonSeparation()`: Run thất bại lưu đúng `status = FAILED`, `resultType = INSUFFICIENT_DATA`, `reasonCode = MODEL_ERROR`.
     - `errorMessageSanitization()`: Bóc tách và che chắn chuỗi mật khẩu kết nối JDBC, bearer token và stack trace Java.

2. **Hợp đồng Executable Contract (`npm run test:contracts`):**
   - **21/21 contract tests PASS (0 failures).**
   - Đảm bảo F01 contract, authority boundary (không có Apply/Recommendation trong INFORMATION), RFC 3339 bounds và schema OpenAPI được tuân thủ nghiêm ngặt.

3. **Kiểm tra cú pháp Git (`git diff --check`):**
   - Không có conflict markers hay whitespace errors.

---

## 3. Ranh giới & Các phần còn lại (Gaps cho A-22 / A-23)

Các hạng mục **chưa triển khai** theo đúng phạm vi giới hạn của A-28:
- **FastAPI AI Service Mock / Client (A-22):** Chưa có HTTP client gọi sang FastAPI `POST /internal/ai/runs/f01/general-knowledge`.
- **Spring/FastAPI Orchestration (A-23):** Chưa có background orchestrator nhận `QUEUED` run, gọi context builder, gọi FastAPI, kiểm định schema/domain và cập nhật hoàn tất run.
- **Vector Retrieval / Indexing thật:** Chưa bật pgvector embedding và chưa đưa Knowledge vào retrieval pipeline thực tế.
- **Model / Provider thật:** Chưa cấu hình LLM provider production.
- **Recommendation & Food Confirmation:** F01 chỉ trả về `INFORMATION`, không hỗ trợ và không liên quan đến Proposal Apply hay Food Confirmation.
