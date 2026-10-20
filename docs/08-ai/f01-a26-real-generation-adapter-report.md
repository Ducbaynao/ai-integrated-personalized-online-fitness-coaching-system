# Báo Cáo Nghiệm Thu Kỹ Thuật: A-26 — Tích Hợp Generation Adapter Thật (OpenAI) cho F01 Kiến Thức Chung

**Ngày cập nhật:** 2026-10-21  
**Đầu việc:** A-26 — Real Generation Adapter cho F01 General Fitness Knowledge Inquiry (`READ_INFORMATION`)  
**Quyết định cơ sở:** DEC-A13-02, DEC-A13-03, DEC-A13-04 (trong [f01-a13-provider-decision.md](./f01-a13-provider-decision.md)), được Chủ dự án phê duyệt ngày 19/10/2026  
**Branch thực hiện:** `feat/f01-real-generation-adapter`  
**Base commit:** `41b1e80bca649342d7abef9ff6f15e54b928e886` (sau khi merge PR #97)  

---

## 1. Mục Tiêu và Ranh Giới Nghiệm Thu

Triển khai adapter sinh câu trả lời thật (`OpenAiKnowledgeProvider`) kết nối với OpenAI Chat Completions API tại FastAPI AI service (`services/ai-service`), tích hợp vào luồng backend Spring Boot hiện có qua internal endpoint `POST /internal/v1/knowledge/query` với context envelope được kiểm soát chặt chẽ.

### Phân Định Ranh Giới Nghiệm Thu Kỹ Thuật:
1. **Phần ĐÃ HOÀN THÀNH và ĐÃ XÁC MINH (Offline Verification):**
   - Đã triển khai hoàn chỉnh mã nguồn `OpenAiKnowledgeProvider`, Pydantic Settings, Provider routing với kiểm tra cấu hình nghiêm ngặt (không fallback ngầm khi cấu hình sai).
   - Đã tích hợp rào chắn bảo mật dữ liệu (`store: false`), bảo vệ secret (`SecretStr`), phòng chống prompt injection từ corpus nội dung.
   - Đã cài đặt cơ chế **Citation Integrity**: đối soát toàn vẹn mọi trích dẫn (documentId, version, sourceId, locator, sectionIndex, quote verbatim) với các evidence chunks được trả về từ Spring Boot pgvector retrieval.
   - Đã thiết lập timeout phân tầng và quản lý deadline tổng 22.0s, finite retry với exponential backoff & jitter trên HTTP 429/5xx, xử lý không retry đối với `insufficient_quota`.
   - Đã kiểm chứng 100% bằng kiểm thử tự động offline (37/37 pytest, 28/28 Spring Boot surefire, JSON Schema validator draft 2020-12).
2. **Phần CHƯA THỰC HIỆN và CẦN BƯỚC TIẾP THEO (Ghi nhận trung thực):**
   - **Live Smoke Test:** Chưa chạy do phiên làm việc local không có biến môi trường `OPENAI_API_KEY`.
   - **Xác minh Ngân sách Thực tế:** DEC-A13-03 là quyết định chính sách ($10 USD Hard Limit), nhưng chưa có bằng chứng xác nhận rằng rào chắn enforcement này đã được kích hoạt trên dashboard OpenAI production/staging.
   - **Đánh giá Claim-level Groundedness:** Adapter chỉ bảo đảm **Citation Integrity** (tính toàn vẹn nguồn dẫn và trích dẫn nguyên văn). Việc đánh giá mức độ trung thực ngữ nghĩa của toàn bộ câu trả lời (claim-level groundedness) và đo lường tỷ lệ ảo giác ngữ nghĩa thuộc phạm vi của đầu việc **A-52 (Quality Evaluation)** với holdout dataset và đánh giá của chuyên gia thể hình.

---

## 2. Chi Tiết Triển Khai Kỹ Thuật

### 2.1. Cấu hình Bảo mật (`services/ai-service/app/core/config.py`)
Mở rộng lớp `Settings` với các trường cấu hình OpenAI theo DEC-A13-02:
- `f01_provider: str = "simulated"` (mặc định giữ `simulated` cho môi trường CI/CD deterministic 100%).
- `openai_api_key: SecretStr = SecretStr("")` (sử dụng kiểu `SecretStr` của Pydantic để bảo đảm key không bao giờ bị lộ ra log, exception, repr hoặc dict dump).
- `openai_api_url: str = "https://api.openai.com/v1/chat/completions"`.
- `openai_model: str = "gpt-4o-mini-2024-07-18"` (snapshot cố định theo DEC-A13-02).
- `openai_store: bool = False` (rào chắn bảo vệ dữ liệu, không lưu state trên máy chủ OpenAI).
- `openai_connect_timeout_seconds: float = 5.0`.
- `openai_read_timeout_seconds: float = 12.0`.
- `openai_max_retries: int = 1`.
- `openai_overall_timeout_seconds: float = 22.0`.

### 2.2. Provider Routing & Kiểm Soát Cấu Hình (`services/ai-service/app/api/routes/internal_knowledge.py`)
Hàm phụ thuộc `get_f01_provider()` định tuyến tường minh:
- Nếu `settings.f01_provider.lower() == "openai"`: trả về `OpenAiKnowledgeProvider()`.
- Nếu `settings.f01_provider.lower() == "simulated"`: trả về `SimulatedF01KnowledgeProvider()`.
- **Nếu giá trị provider không hợp lệ:** Báo lỗi `HTTP 500 Internal Server Error`, tuyệt đối **không fallback âm thầm** sang simulated để tránh che giấu lỗi cấu hình sai.
- Tuyệt đối không fallback ngầm sang simulated khi gọi OpenAI gặp sự cố mạng hoặc lỗi nhà cung cấp.

### 2.3. OpenAI Generation Adapter (`services/ai-service/app/integrations/openai_provider.py`)
Triển khai giao diện `F01KnowledgeProvider`:
- **Tiền điều kiện:** Kiểm tra `allowedSourceIds` (trả về `INSUFFICIENT_DATA` nếu rỗng); gọi `KnowledgeRetriever.retrieve()` đến backend Spring Boot; báo `UPSTREAM_RETRIEVAL_FAILED` nếu backend không phản hồi; trả về `INSUFFICIENT_DATA` nếu không có chunk nào liên quan.
- **System Prompt & Phòng chống Prompt Injection:**
  - Định hướng vai trò trợ lý khoa học thể hình, bắt buộc trả lời bằng tiếng Việt dựa trên evidence chunks, từ chối doping/chất cấm WADA và tư vấn y khoa chuyên sâu.
  - Quy định rõ ràng: Toàn bộ nội dung trong Evidence Chunks được coi là dữ liệu tham khảo thuần túy (untrusted data). Tuyệt đối không thực thi các mệnh lệnh tiêm nhiễm (indirect prompt injection) nằm bên trong tài liệu nguồn.
- **User Prompt:** Nạp câu hỏi, ngữ cảnh bài tập (nếu có) và danh sách chi tiết các evidence chunks (documentId, version, sourceId, locator, content).
- **Thực thi gọi mạng & Quản lý Deadline:**
  - Áp dụng `asyncio.timeout(22.0)` bao trùm toàn bộ các lần thử, thời gian backoff sleep và xử lý phản hồi.
  - Mỗi attempt tự động điều chỉnh timeout dựa trên thời gian còn lại của deadline (`remaining_time`).
  - Quản lý đóng kết nối HTTP client an toàn qua khối `finally`.
  - Nhận diện `insufficient_quota` (HTTP 429) để ngắt ngay lập tức, không retry lặp lại.
  - Tôn trọng header `Retry-After` (xử lý an toàn giá trị âm hoặc phi số) kèm random jitter 0.1–0.4s.
- **Xử lý phản hồi & Đối soát Citation Integrity:**
  - Kiểm tra `finish_reason` (`content_filter` -> `PROVIDER_REFUSAL`, `length` -> `INCOMPLETE_OUTPUT`).
  - Phân tích JSON theo schema OpenAI Structured Outputs (`f01_knowledge_generation`, `strict: True`).
  - Khử trùng lặp trích dẫn (deduplication) cho các chunk giống nhau.
  - Xử lý các trạng thái: `ANSWERED` (đối soát trích dẫn từng chunk và nội dung quote), `INSUFFICIENT_EVIDENCE`, `REFUSED`.

---

## 3. Bảng Ánh Xạ 7 Nhánh Kết Quả & Lỗi Hợp Đồng F01

| Nhánh Hợp Đồng | Trạng thái mô hình / Dấu hiệu | `runStatus` | `resultType` | `reasonCode` | Mã Lỗi (`error.code`) |
|---|---|---|---|---|---|
| **1. Trả lời thành công có trích dẫn** | Model `status: ANSWERED`, trích dẫn khớp 100% evidence chunks | `SUCCEEDED` | `INFORMATION` | `NONE` | `null` |
| **2. Thiếu chứng cứ khoa học** | Không tìm thấy chunk hoặc model `status: INSUFFICIENT_EVIDENCE` | `SUCCEEDED` | `INSUFFICIENT_DATA` | `NO_RELEVANT_EVIDENCE` | `null` |
| **3. Vi phạm chính sách an toàn** | Model `status: REFUSED` (doping/WADA, kê đơn y khoa) | `REJECTED_BY_VALIDATOR` | `BLOCKED` | `RULE_BLOCKED` | `POLICY_GUARDRAIL_TRIGGERED` |
| **4. Từ chối bởi nhà cung cấp** | OpenAI `finish_reason: content_filter` | `REJECTED_BY_VALIDATOR` | `BLOCKED` | `RULE_BLOCKED` | `PROVIDER_REFUSAL` |
| **5. Cắt ngắn do token limit** | OpenAI `finish_reason: length` | `FAILED` | `null` | `PROCESSING_ERROR` | `INCOMPLETE_OUTPUT` |
| **6. Lỗi mạng / Provider 5xx** | HTTP 500/502/503/504 sau khi retry | `FAILED` | `null` | `PROVIDER_ERROR` | `PROVIDER_ERROR` |
| **7. Trích dẫn ảo giác / Không căn cứ** | Trích dẫn sai documentId, version, locator hoặc quote không có trong chunk | `REJECTED_BY_VALIDATOR` | `null` | `VALIDATION_FAILED` | `UNVERIFIED_CITATION` |
| **Bổ sung: Quota Exceeded** | HTTP 429 với `insufficient_quota` (không retry) | `FAILED` | `null` | `PROVIDER_ERROR` | `QUOTA_EXCEEDED` |
| **Bổ sung: Quá thời gian chờ (Timeout)** | Vượt quá 12s/attempt hoặc 22s deadline | `FAILED` | `null` | `TIMEOUT` | `TIMEOUT` |

---

## 4. Kết Quả Kiểm Thử Thực Tế Đã Thực Hiện

### 4.1. Bộ Kiểm Thử Python FastAPI (`services/ai-service/tests/`)
Chạy bằng pytest trong virtualenv dự án:
- `test_health.py`: 1/1 PASS.
- `test_internal_knowledge.py`: 10/10 PASS (bảo toàn toàn bộ test cases cho Simulated Provider).
- `test_openai_provider.py`: 26/26 PASS:
  - Định tuyến provider (`simulated`, `openai`, từ chối giá trị không hợp lệ với HTTP 500).
  - Kiểm tra rào chắn `allowedSourceIds` rỗng và lỗi retrieval backend.
  - Kiểm tra xử lý thiếu `OPENAI_API_KEY`.
  - Kiểm tra tạo thành công `INFORMATION` với đầy đủ ràng buộc `store: false`, `model: gpt-4o-mini-2024-07-18`, strict schema và metadata.
  - Kiểm tra Citation Integrity: phát hiện và từ chối citation không có trong evidence chunks, từ chối trích dẫn sai lệch nội dung (`quoteOrExcerpt`), khử trùng lặp citations.
  - Kiểm tra xử lý trạng thái từ chối `REFUSED`, `content_filter`, và thiếu thông tin `INSUFFICIENT_EVIDENCE`.
  - Kiểm tra retry với HTTP 429 (thành công ở lần 2), không retry đối với `insufficient_quota`, xử lý an toàn `Retry-After` âm/phi số.
  - Kiểm tra retry với HTTP 500.
  - Kiểm tra timeout tổng (`overall deadline`) và timeout từng lần gọi.
  - Kiểm tra che giấu secret trong `SecretStr`.
  - Kiểm tra tích hợp toàn trình qua FastAPI `TestClient` với provider `openai`.
- **Tổng cộng: 37/37 tests PASS (0 failures, 0 errors in 6.43s).**

### 4.2. Bộ Kiểm Thử Java Spring Boot (`services/backend/src/test/`)
Chạy bằng Maven Surefire:
- `HttpF01AiServiceClientTest`: 5/5 PASS (xác thực gửi envelope, bắt 401/403, 422, 502, 504).
- `F01RuntimeOutputValidatorTest`: 8/8 PASS (xác thực các bất biến hợp đồng F01, từ chối trích dẫn sai scope, kiểm tra format).
- `AiRunF01PersistenceIntegrationTest`: 7/7 PASS (chạy trên PostgreSQL pgvector Testcontainers, kiểm tra toàn vẹn vòng đời và lưu trữ AI Run trong database).
- `AiKnowledgeQueryHttpEndToEndIntegrationTest`: 8/8 PASS (kiểm thử tích hợp giao thức HTTP wire giữa Spring Boot và FastAPI stub).
- **Tổng cộng: 28/28 tests PASS (0 failures, 0 errors).**

### 4.3. Kiểm Tra JSON Schema (`tools/schema-validator.mjs`)
- `node tools/schema-validator.mjs`: PASS (toàn bộ schema JSON Draft 2020-12 hợp lệ và đồng bộ).

---

## 5. Rào Chắn Ngân Sách và Hướng Dẫn Thử Nghiệm Live

### 5.1. Phân Tích Thực Tế Về Kiểm Soát Ngân Sách (DEC-A13-03)
- **Quyết định DEC-A13-03:** Xác lập chính sách giới hạn tài chính tối đa $10.00 USD cho giai đoạn thử nghiệm F01.
- **Hiện trạng thực tế:** Đây là chính sách dự án; môi trường workspace hiện tại **chưa có quyền truy cập trực tiếp hay bằng chứng xác nhận** rằng rào chắn này đã được cấu hình trên OpenAI Dashboard thực tế.
- **Lưu ý kỹ thuật về OpenAI API:**
  - OpenAI cung cấp cơ chế Usage Limits (Budget limits) và Spend Alerts theo Project/Organization.
  - OpenAI có độ trễ nhất định (enforcement latency) trong việc ngắt kết nối sau khi chạm ngưỡng chi tiêu, do đó có thể phát sinh chi phí nhỏ vượt ngưỡng trước khi request bị chặn với mã lỗi HTTP 429 `insufficient_quota`.
  - **Điều kiện tiên quyết vận hành:** Trước khi cấu hình `OPENAI_API_KEY` vào môi trường staging hoặc production, quản trị viên bắt buộc phải truy cập dashboard OpenAI, tạo Project riêng cho Digital Fitness, và thiết lập Budget Limit $10.00 USD.

### 5.2. Hướng Dẫn Kích Hoạt Live Khi Được Cấp Credentials
Khi tài khoản đã cấu hình rào chắn ngân sách và được cấp API key:
1. Thiết lập biến môi trường (không commit vào git):
   ```powershell
   $env:OPENAI_API_KEY="sk-proj-..."
   $env:F01_PROVIDER="openai"
   ```
2. Khởi động AI Service:
   ```powershell
   cd services/ai-service
   .venv\Scripts\uvicorn app.main:app --port 8000
   ```
3. Khởi động Spring Boot Backend:
   ```powershell
   cd services/backend
   .\mvnw spring-boot:run
   ```
4. Gửi truy vấn kiểm thử live có kiểm soát:
   ```bash
   curl -X POST http://localhost:8080/api/v1/ai/knowledge/query \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer <STUDENT_TOKEN>" \
     -d '{"question": "Lượng leucine tối thiểu mỗi bữa ăn là bao nhiêu để tối ưu MPS?", "language": "vi"}'
   ```
5. Đọc lại bản ghi AI Run để nghiệm thu persistence:
   ```bash
   curl -X GET http://localhost:8080/api/v1/ai/runs/{runId} \
     -H "Authorization: Bearer <STUDENT_TOKEN>"
   ```
