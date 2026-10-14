# Handoff: A-26 — FastAPI Endpoint, Simulated Provider, và Spring HTTP Client cho F01 Kiến thức chung

**Ngày cập nhật:** 2026-10-14  
**Branch thực hiện:** `feat/p2-a26-f01-fastapi-mock` (tạo từ `feat/p2-a22-a23-f01-orchestration`, giữ nguyên toàn bộ thay đổi uncommitted của A-22/A-23 và commit `4170c56` của A-28)  
**Phạm vi:** F01 General Fitness Knowledge Inquiry (INFORMATION-only)  

---

## 1. Mục tiêu và Kết quả Triển khai A-26

Triển khai hoàn chỉnh kết nối giữa Spring Boot backend và FastAPI AI service thông qua internal endpoint có xác thực service-to-service, provider giả lập xác định (deterministic mock provider), và Spring HTTP client adapter.

### Các thành phần đã triển khai:

1. **FastAPI Schemas (`services/ai-service/app/schemas/f01_schemas.py`):**
   - Định nghĩa Pydantic models khớp 100% với JSON Schema contract:
     - `F01InternalContextEnvelope` (`contracts/ai-schemas/f01-internal-context-envelope.schema.json`): Kiểm tra cứng `GENERAL_FITNESS_EXPLANATION`, `READ_INFORMATION`, `status: ACTIVE`, actor role (`STUDENT` hoặc `TRAINER`).
     - `F01KnowledgeQueryResult` (`contracts/ai-schemas/f01-knowledge-query-result.schema.json`): Quản lý đầy đủ các trường `runId`, `runStatus`, `resultType`, `reasonCode`, `answer`, `citations`, `limitations`, `missingData`, `asOf`, `usage`, `error`, `generationMetadata`.

2. **FastAPI Security & Configuration (`services/ai-service/app/core/`):**
   - `config.py`: Quản lý cấu hình `internal_service_key`, `f01_mock_enabled`, `f01_prompt_version`, `f01_rule_version`, `f01_model`, `f01_provider` qua `pydantic-settings`.
   - `security.py`: Dependency `verify_internal_service_key` kiểm tra header `X-Internal-Service-Key` bằng `secrets.compare_digest` chống timing attacks. Trả về `401 Unauthorized` nếu thiếu header và `403 Forbidden` nếu key sai.

3. **FastAPI Internal Endpoint (`services/ai-service/app/api/routes/internal_knowledge.py`):**
   - Route `POST /internal/v1/knowledge/query` nhận envelope từ Spring Boot backend.
   - Không cho phép truy cập trực tiếp từ client bên ngoài (yêu cầu service auth key).

4. **Deterministic Simulated Provider (`services/ai-service/app/integrations/`):**
   - `provider_interface.py`: Interface `F01KnowledgeProvider` (async protocol).
   - `simulated_provider.py`: `SimulatedF01KnowledgeProvider` sinh kết quả xác định cho các tình huống:
     - `INFORMATION` (SUCCEEDED) kèm citation cấu trúc chuẩn ISSN 2017 (`ISSN-PROTEIN-EXERCISE-2017-VI-04`, version `1.0.0`).
     - `INSUFFICIENT_DATA` (SUCCEEDED) khi danh sách nguồn rỗng hoặc câu hỏi kích hoạt `trigger_insufficient_data`.
     - `BLOCKED` (REJECTED_BY_VALIDATOR, `RULE_BLOCKED`) khi phát hiện doping/steroid.
     - `TIMEOUT` simulation (`trigger_timeout`).
     - `MODEL_ERROR` simulation (`trigger_provider_error`).
     - `VALIDATION_FAILED` simulation (`trigger_validation_failure`).
     - Báo lỗi `503 Service Unavailable` nếu mock provider bị tắt (`f01_mock_enabled = False`).

5. **Spring HTTP Client Adapter (`services/backend/.../ai/adapter/out/client/`):**
   - `HttpF01AiServiceClient.java`: Sử dụng Spring `RestClient` với connection timeout (5s) và read timeout (10s), gửi header `X-Internal-Service-Key`.
   - Ánh xạ lỗi mạng / HTTP:
     - 401/403 -> `AiProviderException("FastAPI AI service authentication failed")`
     - 422 -> `AiOutputValidationException("FastAPI rejected envelope schema validation")`
     - 504 / Read Timeout -> `AiTimeoutException` (Spring orchestrator chuyển Run thành `FAILED` với reason `TIMEOUT`, trả HTTP 504 cho client).
     - 502/500 / Network down -> `AiProviderException` (Spring orchestrator chuyển Run thành `FAILED` với reason `MODEL_ERROR`, trả HTTP 502 cho client).
   - `AiServiceProperties.java`: Bind các thuộc tính `ai.service.*`.
   - `AiServiceClientConfiguration.java`: Lựa chọn tường minh giữa `HttpF01AiServiceClient` (`ai.service.mode=http`) và `SimulatedF01AiServiceClient` (`ai.service.mode=simulated`). Tuyệt đối không fallback âm thầm sang mock khi HTTP call thất bại.

---

## 2. Kết Quả Kiểm Chứng (Testing Verification)

1. **FastAPI Unit/Route Tests (`services/ai-service/tests/test_internal_knowledge.py`):**
   - 10/10 tests passed (100%):
     - `test_auth_missing_header`: Kiểm tra từ chối 401.
     - `test_auth_invalid_key`: Kiểm tra từ chối 403.
     - `test_query_knowledge_success_information`: Trả về `INFORMATION` hợp lệ kèm citation và metadata.
     - `test_query_knowledge_insufficient_data`: Trả về `INSUFFICIENT_DATA` khi không có allowed sources.
     - `test_query_knowledge_blocked`: Trả về `BLOCKED` và `RULE_BLOCKED` khi có từ khóa doping.
     - `test_query_knowledge_trigger_timeout`: Giả lập lỗi timeout.
     - `test_query_knowledge_trigger_provider_error`: Giả lập lỗi provider.
     - `test_query_knowledge_invalid_envelope_constraints`: Bắt buộc purpose, role, knowledgeScope status.
     - `test_disabled_mock_provider`: Trả về 503 khi mock bị vô hiệu hóa.
   - Linter `ruff check app tests`: Tất cả quy chuẩn kiểm tra đều sạch sẽ (`All checks passed!`).

2. **Spring HTTP Client Tests (`services/backend/.../HttpF01AiServiceClientTest.java`):**
   - 5/5 tests passed (100%):
     - Xác thực gửi request POST và header `X-Internal-Service-Key` chính xác.
     - Bắt và ánh xạ lỗi 401/403 thành `AiProviderException`.
     - Bắt và ánh xạ lỗi 422 thành `AiOutputValidationException`.
     - Bắt và ánh xạ lỗi 504 thành `AiTimeoutException`.
     - Bắt và ánh xạ lỗi 502 thành `AiProviderException`.

3. **Regression Tests (A-28 & A-22/A-23):**
   - `mvn test "-Dtest=AiKnowledgeQueryOrchestrationIntegrationTest,AiRunF01PersistenceIntegrationTest"`: 15/15 tests passed.
   - `npm run test:contracts`: 21/21 contract tests passed.
   - `git diff --check`: Không có lỗi định dạng hay trailing whitespace.

---

## 3. Ranh Giới và Giới Hạn Nghiệp Vụ (Boundaries & Invariants)

- **Không có Apply/Accept:** F01 là `INFORMATION`, tuyệt đối không tạo `PROPOSAL` hay thay đổi domain state.
- **Không có Vector Retrieval thật:** Corpus vẫn ở trạng thái DRAFT (`ACTIVE=0`), chưa tạo pgvector embedding hoặc chạy RAG thật (dành cho A-25).
- **Không có LLM Provider bên ngoài thật:** Sử dụng provider giả lập xác định để kiểm chứng pipeline end-to-end mà không tốn chi phí API hay phụ thuộc mạng ngoài.
- **Không giữ DB Transaction:** HTTP client được gọi hoàn toàn bên ngoài transaction boundary của database.
- **Git Safety:** Toàn bộ thay đổi của A-22/A-23 và A-26 được giữ nguyên trên working tree của branch `feat/p2-a26-f01-fastapi-mock`, không thực hiện bất kỳ lệnh `git commit`, `git push`, hay `git reset` nào.
