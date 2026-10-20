# Báo Cáo Nghiệm Thu Kỹ Thuật: A-26 — Tích Hợp Generation Adapter Thật (OpenAI) cho F01 Kiến Thức Chung

**Ngày cập nhật:** 2026-10-21  
**Đầu việc:** A-26 — Real Generation Adapter cho F01 General Fitness Knowledge Inquiry (`READ_INFORMATION`)  
**Quyết định phê duyệt cơ sở:** DEC-A13-02 (trong [f01-a13-provider-decision.md](./f01-a13-provider-decision.md)), phê duyệt bởi Chủ dự án ngày 19/10/2026  
**Branch thực hiện:** `feat/f01-real-generation-adapter`  
**Base commit:** `41b1e80bca649342d7abef9ff6f15e54b928e886` (sau khi merge PR #94–#97)  

---

## 1. Mục Tiêu và Phạm Vi Triển Khai

Triển khai adapter sinh câu trả lời thật (`OpenAiKnowledgeProvider`) kết nối với OpenAI Chat Completions API tại FastAPI AI service (`services/ai-service`), tích hợp vào luồng backend Spring Boot hiện có thông qua endpoint nội bộ `POST /internal/v1/knowledge/query` với context envelope được kiểm soát chặt chẽ.

### Bất biến kiến trúc & nghiệp vụ tuân thủ:
1. **Quyền hạn (`READ_INFORMATION`):** Chỉ cung cấp thông tin giải thích khoa học (`INFORMATION`, `INSUFFICIENT_DATA`, `BLOCKED`). Không tạo đề xuất giáo án (`No Apply`), không tạo mục tiêu dinh dưỡng hay thay đổi lịch tập.
2. **Bảo mật dữ liệu (Data Privacy):** Tham số bắt buộc `store: false` được gửi trong mọi request gọi OpenAI API, đảm bảo OpenAI không lưu trữ dữ liệu câu hỏi và ngữ cảnh trích dẫn vào application state. Không gửi dữ liệu định danh học viên (PII), lịch sử chat hay số đo cá nhân.
3. **Cơ chế Structured Outputs bắt buộc:** Sử dụng `response_format={"type": "json_schema", "json_schema": {"strict": True, ...}}` với schema cấu trúc độc lập (`f01_knowledge_generation`) và ánh xạ sang hợp đồng `F01KnowledgeQueryResult`.
4. **Bảo toàn nguồn trích dẫn & Chống ảo giác (Strict Citation Grounding):**
   - Chỉ cho phép mô hình tiếp cận các đoạn trích (`retrieved evidence chunks`) từ cơ chế tìm kiếm vector pgvector của Spring Boot.
   - Toàn bộ trích dẫn của mô hình được đối soát nghiêm ngặt với các chunk thực tế: `documentId`, `version`, `sourceId`, `sourcePath`, `sectionIndex` và `quoteOrExcerpt`.
   - Nếu mô hình sinh ra trích dẫn ngoài evidence hoặc trích dẫn đoạn văn không tồn tại trong chunk, adapter tự động từ chối với trạng thái `REJECTED_BY_VALIDATOR` và reason code `VALIDATION_FAILED` (`UNVERIFIED_CITATION`).
5. **Thời gian chờ và phân tầng Retry:**
   - FastAPI sở hữu hoàn toàn vòng đời gọi OpenAI: Connect timeout 5.0s, Read timeout 12.0s mỗi lần thử, tối đa 1 lần retry (2 lần thử tổng cộng) với Exponential Backoff & Random Jitter (0.1–0.5s), tôn trọng header `Retry-After`.
   - Toàn bộ cuộc gọi generation được bảo vệ bởi tổng deadline `asyncio.timeout(22.0)`.
   - Mã lỗi 429 Quota Exceeded (`insufficient_quota`) được nhận diện và xử lý ngay lập tức (không retry vô ích).

---

## 2. Chi Tiết Implementation

### 2.1. Cấu hình (`services/ai-service/app/core/config.py`)
Mở rộng lớp `Settings` với các trường cấu hình OpenAI theo DEC-A13-02:
- `f01_provider: str = "simulated"` (mặc định giữ `simulated` cho môi trường CI/CD deterministic 100%).
- `openai_api_key: str = ""` (nạp từ biến môi trường `OPENAI_API_KEY`).
- `openai_api_url: str = "https://api.openai.com/v1/chat/completions"`.
- `openai_model: str = "gpt-4o-mini-2024-07-18"` (snapshot cố định theo DEC-A13-02).
- `openai_store: bool = False` (rào chắn bảo vệ dữ liệu).
- `openai_connect_timeout_seconds: float = 5.0`.
- `openai_read_timeout_seconds: float = 12.0`.
- `openai_max_retries: int = 1`.
- `openai_overall_timeout_seconds: float = 22.0`.

### 2.2. Provider Routing (`services/ai-service/app/api/routes/internal_knowledge.py`)
Hàm phụ thuộc `get_f01_provider()` định tuyến tường minh:
- Nếu `settings.f01_provider.lower() == "openai"`: khởi tạo và trả về `OpenAiKnowledgeProvider()`.
- Ngược lại: trả về `SimulatedF01KnowledgeProvider()`.
- Tuyệt đối không fallback âm thầm sang `simulated` khi đang chạy ở chế độ `openai` mà gặp lỗi nhà cung cấp.

### 2.3. OpenAI Generation Adapter (`services/ai-service/app/integrations/openai_provider.py`)
Implement protocol `F01KnowledgeProvider`:
- **Tiền điều kiện:** Kiểm tra `allowedSourceIds` (trả về `INSUFFICIENT_DATA` nếu rỗng); gọi `KnowledgeRetriever.retrieve()` đến backend Spring Boot; báo `UPSTREAM_RETRIEVAL_FAILED` nếu backend không phản hồi; trả về `INSUFFICIENT_DATA` nếu không có chunk nào liên quan.
- **System Prompt chuẩn hóa:** Định hướng vai trò trợ lý khoa học thể hình, bắt buộc trả lời bằng tiếng Việt dựa trên evidence chunks, từ chối doping/chất cấm WADA và tư vấn y khoa chuyên sâu.
- **User Prompt:** Nạp câu hỏi, ngữ cảnh bài tập (nếu có) và danh sách chi tiết các evidence chunks (documentId, version, sourceId, locator, content).
- **Thực thi gọi mạng:** Dùng `httpx.AsyncClient` độc lập hoặc client được inject với timeout phân tầng, xử lý 429 quota, 429 rate limit (retry-after + jitter), 5xx retry, và timeout.
- **Xử lý phản hồi & Đối soát trích dẫn:**
  - Kiểm tra `finish_reason` (`content_filter` -> `PROVIDER_REFUSAL`, `length` -> `INCOMPLETE_OUTPUT`).
  - Phân tích JSON theo schema OpenAI Structured Outputs.
  - Xử lý các trạng thái: `ANSWERED` (đối soát trích dẫn từng chunk và nội dung quote), `INSUFFICIENT_EVIDENCE`, `REFUSED`.

---

## 3. Bảng Ánh Xạ 7 Nhánh Kết Quả & Lỗi Hợp Đồng F01

| Nhánh Hợp Đồng | Trạng thái / Dấu hiệu | `runStatus` | `resultType` | `reasonCode` | Mã Lỗi (`error.code`) |
|---|---|---|---|---|---|
| **1. Trả lời thành công có trích dẫn** | Model `status: ANSWERED`, trích dẫn khớp 100% evidence chunks | `SUCCEEDED` | `INFORMATION` | `NONE` | `null` |
| **2. Thiếu chứng cứ khoa học** | Không tìm thấy chunk hoặc model `status: INSUFFICIENT_EVIDENCE` | `SUCCEEDED` | `INSUFFICIENT_DATA` | `NO_RELEVANT_EVIDENCE` | `null` |
| **3. Vi phạm chính sách (Guardrail)** | Model `status: REFUSED` (doping/WADA, kê đơn y khoa) | `REJECTED_BY_VALIDATOR` | `BLOCKED` | `RULE_BLOCKED` | `POLICY_GUARDRAIL_TRIGGERED` |
| **4. Từ chối bởi nhà cung cấp** | OpenAI `finish_reason: content_filter` | `REJECTED_BY_VALIDATOR` | `BLOCKED` | `RULE_BLOCKED` | `PROVIDER_REFUSAL` |
| **5. Đầu ra bị cắt ngắn (Token limit)** | OpenAI `finish_reason: length` | `FAILED` | `null` | `PROCESSING_ERROR` | `INCOMPLETE_OUTPUT` |
| **6. Lỗi mạng / Provider 5xx** | HTTP 500/502/503/504 sau khi retry | `FAILED` | `null` | `PROVIDER_ERROR` | `PROVIDER_ERROR` |
| **7. Trích dẫn ảo giác / Không căn cứ** | Trích dẫn sai documentId, version, locator hoặc quote không có trong chunk | `REJECTED_BY_VALIDATOR` | `null` | `VALIDATION_FAILED` | `UNVERIFIED_CITATION` |
| **Bổ sung: Quota / Budget Exceeded** | HTTP 429 với `insufficient_quota` | `FAILED` | `null` | `PROVIDER_ERROR` | `QUOTA_EXCEEDED` |
| **Bổ sung: Quá thời gian chờ (Timeout)** | Vượt quá 12s/attempt hoặc 22s deadline | `FAILED` | `null` | `TIMEOUT` | `TIMEOUT` |

---

## 4. Kết Quả Kiểm Thử (100% PASS)

### 4.1. Bộ Kiểm Thử Python FastAPI (`services/ai-service/tests/`)
Chạy với pytest:
- `test_health.py`: 1/1 PASS.
- `test_internal_knowledge.py`: 10/10 PASS (bảo toàn toàn bộ test cases cho Simulated Provider).
- `test_openai_provider.py`: 22/22 PASS:
  - Định tuyến provider (`simulated` vs `openai`).
  - Kiểm tra rào chắn `allowedSourceIds` rỗng và lỗi retrieval backend.
  - Kiểm tra xử lý thiếu `OPENAI_API_KEY`.
  - Kiểm tra tạo thành công `INFORMATION` với đầy đủ ràng buộc `store: false`, `model: gpt-4o-mini-2024-07-18`, strict schema và metadata.
  - Kiểm tra chống ảo giác: phát hiện và từ chối citation không có trong evidence chunks, từ chối trích dẫn sai lệch nội dung (`quoteOrExcerpt`).
  - Kiểm tra xử lý trạng thái từ chối `REFUSED`, `content_filter`, và thiếu thông tin `INSUFFICIENT_EVIDENCE`.
  - Kiểm tra retry với HTTP 429 (thành công ở lần 2), không retry đối với `insufficient_quota`.
  - Kiểm tra retry với HTTP 500.
  - Kiểm tra timeout tổng (`overall deadline`).
  - Kiểm tra tích hợp toàn trình qua FastAPI `TestClient` với provider `openai`.
- **Tổng cộng: 33/33 tests PASS (0 failures, 0 errors).**

### 4.2. Bộ Kiểm Thử Java Spring Boot (`services/backend/src/test/`)
Chạy với Maven Surefire:
- `HttpF01AiServiceClientTest`: 5/5 PASS (xác thực gửi envelope, bắt 401/403, 422, 502, 504).
- `F01RuntimeOutputValidatorTest`: 8/8 PASS (xác thực các bất biến hợp đồng F01, từ chối trích dẫn sai scope, kiểm tra format).
- `AiRunF01PersistenceIntegrationTest`: 7/7 PASS (chạy trên PostgreSQL pgvector Testcontainers, kiểm tra toàn vẹn vòng đời và lưu trữ AI Run trong database).
- `AiKnowledgeQueryHttpEndToEndIntegrationTest`: 8/8 PASS (kiểm thử tích hợp giao thức HTTP wire giữa Spring Boot và FastAPI stub).
- **Tổng cộng: 28/28 tests PASS (0 failures, 0 errors).**

### 4.3. Kiểm Tra JSON Schema (`tools/schema-validator.mjs`)
- `node tools/schema-validator.mjs`: PASS (mọi schema JSON Draft 2020-12 hợp lệ và đồng bộ).

---

## 5. Hướng Dẫn Kích Hoạt và Thử Nghiệm Live (Khi Có API Key)

### 5.1. Rào chắn an toàn ngân sách (DEC-A13-03)
Trước khi chạy thử nghiệm với API OpenAI thật:
1. Đăng nhập vào dashboard OpenAI: [https://platform.openai.com/account/limits](https://platform.openai.com/account/limits).
2. Thiết lập **Hard Limit: $10.00 USD** (bật enforcement chặn gọi khi chạm mốc).
3. Thiết lập **Soft Alert: $5.00 USD** (nhận cảnh báo qua email).

### 5.2. Chạy thử nghiệm Local Dev
1. Thiết lập biến môi trường (không commit vào file `.env` theo dõi bởi git):
   ```powershell
   $env:OPENAI_API_KEY="sk-proj-..."
   $env:F01_PROVIDER="openai"
   ```
2. Khởi động AI Service:
   ```powershell
   cd services/ai-service
   .venv\Scripts\uvicorn app.main:app --port 8000
   ```
3. Khởi động Backend Spring Boot:
   ```powershell
   cd services/backend
   .\mvnw spring-boot:run
   ```
4. Gửi truy vấn thử nghiệm F01 qua API Gateway:
   ```bash
   curl -X POST http://localhost:8080/api/v1/ai/knowledge/query \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer <STUDENT_TOKEN>" \
     -d '{"question": "Lượng leucine tối thiểu mỗi bữa ăn là bao nhiêu để tối ưu hóa phát triển cơ bắp?", "language": "vi"}'
   ```
