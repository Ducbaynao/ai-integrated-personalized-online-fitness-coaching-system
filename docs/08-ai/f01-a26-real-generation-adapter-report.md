# Báo Cáo Nghiệm Thu Kỹ Thuật: A-26 — Tích Hợp Generation Adapter Thật (OpenAI) cho F01 Kiến Thức Chung

**Ngày cập nhật:** 2026-10-21  
**Đầu việc:** A-26 — Real Generation Adapter cho F01 General Fitness Knowledge Inquiry (`READ_INFORMATION`)  
**Quyết định cơ sở:** DEC-A13-02, DEC-A13-03, DEC-A13-04 (trong [f01-a13-provider-decision.md](./f01-a13-provider-decision.md)), được Chủ dự án phê duyệt ngày 19/10/2026  
**Branch thực hiện:** `feat/f01-real-generation-adapter`  
**Base commit:** `41b1e80bca649342d7abef9ff6f15e54b928e886` (sau khi merge PR #97)  
**Commits trên branch:**
- `d56c3a1`: `feat(ai): implement real OpenAI generation adapter for F01 knowledge inquiry (DEC-A13-02)` (triển khai ban đầu).
- `d5d70b5`: `fix(ai): harden OpenAI provider routing, deadline safety, prompt injection defense, and citation deduplication` (bản vá review độc lập).
- `54f3a60`: `docs(ai): reconcile A-26 handoff report test metrics and smoke test corpus scope` (hiệu chỉnh số liệu và phạm vi corpus).
- Commit bổ sung: `fix(ai): handle hard spend limit error codes and reconcile report` (chuẩn hóa xử lý 3 mã lỗi hard spend limit theo tài liệu OpenAI và đồng bộ tài liệu nghiệm thu).

---

## 1. Ranh Giới Nghiệm Thu Kỹ Thuật

Triển khai adapter sinh câu trả lời thật (`OpenAiKnowledgeProvider`) kết nối với OpenAI Chat Completions API tại FastAPI AI service (`services/ai-service`), tích hợp vào luồng backend Spring Boot hiện có qua internal endpoint `POST /internal/v1/knowledge/query` với context envelope được kiểm soát chặt chẽ.

### Phân Định Ranh Giới Giữa Các Hạng Mục:
1. **Phần ĐÃ HOÀN THÀNH và ĐÃ XÁC MINH OFFLINE:**
   - Triển khai mã nguồn `OpenAiKnowledgeProvider`, Pydantic Settings, Provider routing với kiểm tra cấu hình nghiêm ngặt (trả về HTTP 500 khi cấu hình provider không hợp lệ, không fallback âm thầm).
   - Tích hợp rào chắn bảo mật dữ liệu (`store: false`), bảo vệ secret (`pydantic.SecretStr`), phòng chống prompt injection gián tiếp từ corpus nội dung.
   - Cài đặt cơ chế **Citation Integrity**: đối soát toàn diện mọi trích dẫn (documentId, version, sourceId, locator, sectionIndex, quote verbatim) với các evidence chunks được trả về từ Spring Boot pgvector retrieval. Khử trùng lặp trích dẫn trùng lặp chunk.
   - Thiết lập timeout phân tầng và quản lý deadline tổng 22.0s, finite retry với exponential backoff & jitter trên HTTP 429/5xx, ngắt ngay không retry khi gặp lỗi quota hoặc hard spend limit (`insufficient_quota`, `project_spend_limit_exceeded`, `organization_spend_limit_exceeded`).
   - Kiểm chứng tự động offline trên commit `d5d70b5` và các bản cập nhật hoàn thiện: 43/43 AI Service pytest PASS, 28/28 Spring Boot surefire PASS (qua 2 lệnh kiểm thử riêng biệt), và JSON Schema validator draft 2020-12 PASS.
2. **Phần CHƯA THỰC HIỆN và GIỚI HẠN HIỆN TẠI (Ghi nhận trung thực):**
   - **Live Smoke Test:** Chưa chạy do phiên làm việc local không có biến môi trường `OPENAI_API_KEY`. Toàn bộ kiểm thử hiện tại sử dụng mock transport và Testcontainers offline; chưa có dữ liệu gọi API OpenAI live.
   - **Xác minh Ngân sách Thực tế:** Quyết định DEC-A13-03 là chính sách nội bộ ($10 USD ceiling, $5 USD alert). Workspace hiện tại không có quyền truy cập bảng điều khiển OpenAI để xác nhận liệu rào chắn ngân sách này đã thực sự được cấu hình hay chưa.
   - **Đánh giá Claim-level Groundedness:** Adapter bảo đảm **Citation Integrity** (tính toàn vẹn nguồn dẫn và trích dẫn nguyên văn từ chunk). Việc đánh giá mức độ trung thực ngữ nghĩa của toàn bộ nội dung câu trả lời (claim-level groundedness) và đo lường tỷ lệ ảo giác ngữ nghĩa thuộc phạm vi của đầu việc **A-52 (Quality Evaluation)** với holdout dataset và đánh giá của chuyên gia thể hình.

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
  - Quy định rõ ràng: Toàn bộ nội dung trong Evidence Chunks là dữ liệu tham khảo thuần túy (untrusted data). Tuyệt đối không thực thi các mệnh lệnh tiêm nhiễm (indirect prompt injection) nằm bên trong tài liệu nguồn.
- **User Prompt:** Nạp câu hỏi, ngữ cảnh bài tập (nếu có) và danh sách chi tiết các evidence chunks (documentId, version, sourceId, locator, content).
- **Thực thi gọi mạng & Quản lý Deadline:**
  - Áp dụng `asyncio.timeout(22.0)` bao trùm toàn bộ các lần thử, thời gian backoff sleep và xử lý phản hồi.
  - Mỗi attempt tự động điều chỉnh timeout dựa trên thời gian còn lại của deadline (`remaining_time`).
  - Quản lý đóng kết nối HTTP client an toàn qua khối `finally`.
  - Nhận diện các mã lỗi hard spend limit và cạn kiệt quota (`insufficient_quota`, `project_spend_limit_exceeded`, `organization_spend_limit_exceeded` trên HTTP 429) để ngắt ngay lập tức, không retry lặp lại.
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
| **Bổ sung: Quota / Spend Limit Exceeded** | HTTP 429 với `insufficient_quota`, `project_spend_limit_exceeded`, `organization_spend_limit_exceeded` (không retry) | `FAILED` | `null` | `PROVIDER_ERROR` | `QUOTA_EXCEEDED` |
| **Bổ sung: Quá thời gian chờ (Timeout)** | Vượt quá 12s/attempt hoặc 22s deadline | `FAILED` | `null` | `TIMEOUT` | `TIMEOUT` |

---

## 4. Chi Tiết Kết Quả Kiểm Thử Thực Tế

Các con số dưới đây phản ánh kết quả thực thi kiểm thử trực tiếp trên commit `d5d70b5` và các bản cập nhật hoàn thiện (43 tests AI service, 28 tests backend qua 2 lệnh độc lập, schema validator PASS):

### 4.1. Bộ Kiểm Thử Python FastAPI (`services/ai-service/tests/`)
Lệnh thực thi:
```powershell
& services/ai-service/.venv/Scripts/python -m pytest services/ai-service/tests
```
Kết quả: **43/43 tests PASS** (0 failures, 0 errors, thời gian chạy: ~6.1s).
- `test_health.py`: 1/1 PASS.
- `test_internal_knowledge.py`: 10/10 PASS (kiểm tra xác thực service key, simulated provider các nhánh, cô lập unit test khi backend unreachable).
- `test_openai_provider.py`: 32/32 PASS:
  - Định tuyến provider (`simulated`, `openai`, từ chối giá trị không hợp lệ với HTTP 500).
  - Kiểm tra rào chắn `allowedSourceIds` rỗng và lỗi retrieval backend.
  - Kiểm tra xử lý thiếu `OPENAI_API_KEY`.
  - Kiểm tra tạo thành công `INFORMATION` với đầy đủ ràng buộc `store: false`, `model: gpt-4o-mini-2024-07-18`, strict schema và metadata.
  - Kiểm tra Citation Integrity: phát hiện và từ chối citation không có trong evidence chunks, từ chối trích dẫn sai lệch nội dung (`quoteOrExcerpt`), khử trùng lặp citations.
  - Kiểm tra xử lý trạng thái từ chối `REFUSED`, `content_filter`, trường `message.refusal` của Structured Outputs, và thiếu thông tin `INSUFFICIENT_EVIDENCE`.
  - Kiểm tra retry với HTTP 429 (thành công ở lần 2), không retry đối với cả 3 mã lỗi hard spend limit/quota (`insufficient_quota`, `project_spend_limit_exceeded`, `organization_spend_limit_exceeded`), xử lý an toàn `Retry-After` âm/phi số.
  - Kiểm tra retry với HTTP 500.
  - Kiểm tra timeout tổng (`overall deadline`) và timeout từng lần gọi.
  - Kiểm tra che giấu secret trong `SecretStr`.
  - Kiểm tra bảo toàn token usage rỗng/null (không tự sinh số 0 giả lập).
  - Kiểm tra bắt buộc `store: false` vô điều kiện bất kể biến môi trường.
  - Kiểm tra từ chối câu trả lời dài vượt quá 5000 ký tự với `VALIDATION_FAILED`.
  - Kiểm tra tích hợp router qua FastAPI `TestClient` với provider `openai`.

*Lưu ý:* Toàn bộ 43 tests trên chạy hoàn toàn offline sử dụng `httpx.MockTransport` và FastAPI `TestClient`, không thực hiện cuộc gọi mạng ra ngoài hay gọi API trả phí.

### 4.2. Bộ Kiểm Thử Java Spring Boot (`services/backend/`)
Được xác minh qua 2 lệnh Maven Surefire riêng biệt:

1. **Unit Tests (Không phụ thuộc Docker/Database):**
   ```powershell
   ./mvnw test -Dtest="HttpF01AiServiceClientTest,F01RuntimeOutputValidatorTest"
   ```
   - `HttpF01AiServiceClientTest`: 5/5 PASS (xác thực gửi envelope, bắt lỗi 401/403, 422, 502, 504).
   - `F01RuntimeOutputValidatorTest`: 8/8 PASS (xác thực các bất biến hợp đồng F01, từ chối trích dẫn sai scope, kiểm tra format).
   - **Tiểu kế hoạch:** **13/13 tests PASS** (thời gian chạy: 5.30s).

2. **Integration Tests (Khởi động Docker Testcontainers PostgreSQL pgvector):**
   ```powershell
   ./mvnw test -Dtest="AiRunF01PersistenceIntegrationTest,AiKnowledgeQueryHttpEndToEndIntegrationTest"
   ```
   - `AiKnowledgeQueryHttpEndToEndIntegrationTest`: 8/8 PASS (kiểm thử tích hợp giao thức HTTP wire stub giữa Spring Boot và FastAPI stub).
   - `AiRunF01PersistenceIntegrationTest`: 7/7 PASS (chạy trên PostgreSQL pgvector Testcontainers, kiểm tra toàn vẹn vòng đời và lưu trữ AI Run trong database).
   - **Tiểu kế hoạch:** **15/15 tests PASS** (thời gian chạy: 47.79s).

**Tổng cộng Backend:** **28/28 tests PASS** (13 unit tests + 15 integration tests, 0 failures, 0 errors).

### 4.3. Kiểm Tra JSON Schema (`tools/schema-validator.mjs`)
Lệnh thực thi:
```powershell
node tools/schema-validator.mjs
```
Kết quả: **PASS** (100% schema JSON Draft 2020-12 hợp lệ và đồng bộ).

---

## 5. Rào Chắn Ngân Sách và Cơ Chế Giới Hạn Chi Phí (DEC-A13-03)

### 5.1. Cơ Sở Lý Thuyết và Tài Liệu Nền Tảng Chính Thức của OpenAI
Dựa trên tài liệu chính thức của OpenAI ([OpenAI Spend Limits Documentation](https://developers.openai.com/api/docs/guides/spend-limits) và [OpenAI Usage Limits Guide](https://platform.openai.com/docs/guides/production-best-practices#set-usage-limits)):
1. **Thiết lập Giới hạn Chi tiêu (Monthly Spend Limits):** OpenAI cho phép đặt giới hạn ngân sách hàng tháng theo Project hoặc Organization. Để thực sự chặn các request khi vượt ngưỡng, tùy chọn **'Enforce a hard limit'** (áp dụng giới hạn cứng) bắt buộc phải được kích hoạt (toggled ON). Nếu chỉ nhập số tiền mà không bật enforce hard limit, request sẽ không bị chặn tự động.
2. **Các Mã Lỗi Trả Về (Rejection Error Codes):** Khi chạm ngưỡng hard limit hoặc cạn kiệt số dư trả trước, OpenAI trả về mã phản hồi HTTP 429 với một trong ba mã lỗi (`code` hoặc `type`):
   - `insufficient_quota`: Hết hạn mức / số dư trả trước hoặc quota chung.
   - `project_spend_limit_exceeded`: Đạt ngưỡng hard limit được thiết lập riêng cho Project.
   - `organization_spend_limit_exceeded`: Đạt ngưỡng hard limit được thiết lập cho toàn bộ Organization.
   Adapter xử lý cả 3 mã lỗi này dưới tập hợp `HARD_SPEND_LIMIT_CODES` và kết luận ngay `QUOTA_EXCEEDED` mà không thực hiện retry vô ích.
3. **Độ trễ Áp dụng (Enforcement Latency):** OpenAI chính thức tài liệu hóa rằng hệ thống theo dõi và tính toán chi phí có độ trễ nhất định (enforcement latency). Do đó, các request đang bay (in-flight) hoặc phát sinh ngay tại thời điểm chạm ngưỡng vẫn có thể được xử lý xong trước khi toàn bộ hệ thống kích hoạt rào chắn 429. Vì vậy, số tiền thực tế ghi nhận trên hóa đơn có thể vượt nhẹ so với con số hard limit đã đặt.
4. **Spend Alerts:** Các ngưỡng cảnh báo qua email (Spend Alerts) chỉ gửi email thông báo khi chi phí đạt tỷ lệ nhất định (ví dụ $5.00 USD), **hoàn toàn không dừng hoặc giới hạn lưu lượng truy cập API**.

### 5.2. Hiện Trạng Xác Minh Thực Tế Trong Dự Án
- **Quyết định DEC-A13-03:** Là quyết định chính sách nội bộ của dự án đặt ra trần ngân sách thử nghiệm $10.00 USD/tháng và cảnh báo ở mức $5.00 USD.
- **Trạng thái tài khoản OpenAI bên ngoài:** **CHƯA ĐƯỢC XÁC MINH (UNVERIFIED)**. Trong môi trường phát triển hiện tại, không có quyền truy cập bảng điều khiển OpenAI Dashboard để kiểm tra hoặc chứng minh rằng hard limit $10.00 USD và enforce toggle đã được thiết lập thực tế. Cần phân định rõ ràng: DEC-A13-03 là chính sách dự án đã duyệt, còn cấu hình trên tài khoản bên ngoài là trạng thái chưa được kiểm chứng trong mã nguồn local.
- **Cơ chế trong mã nguồn:** Đoạn code xử lý mã lỗi HTTP 429 (`insufficient_quota`, `project_spend_limit_exceeded`, `organization_spend_limit_exceeded`) trong `openai_provider.py` chỉ có chức năng nhận diện khi OpenAI từ chối request và ngăn chặn retry vô ích; đoạn code này **không tự tạo ra rào chắn ngân sách $10** nếu tài khoản chưa được thiết lập và bật enforce trên dashboard.
- **Điều kiện vận hành bắt buộc:** Trước khi cấu hình `OPENAI_API_KEY` vào bất kỳ môi trường thử nghiệm nào, quản trị viên bắt buộc phải truy cập OpenAI Dashboard (mục Settings > Limits), tạo Project riêng cho Digital Fitness, thiết lập Monthly Spend Limit $10.00 USD và kích hoạt tùy chọn **Enforce a hard limit**.

---

## 6. Hướng Dẫn Thử Nghiệm Live (Xuyên Suốt và Đúng Phạm Vi Corpus)

### 6.1. Đối Chiếu Corpus ACTIVE Trong Local Testbed
Theo báo cáo kiểm kê [f01-a27-expanded-corpus-inventory-and-baseline-report.md](./f01-a27-expanded-corpus-inventory-and-baseline-report.md), kho tri thức cục bộ ACTIVE và đã lập chỉ mục (index-ready) chỉ gồm **9 tài liệu phái sinh thuộc WHO và NIDDK**:
- `WHO-PA-SB-2020`: `VI-01`, `VI-02`, `VI-03`, `VI-05`, `VI-06`.
- `NIDDK-WEIGHT-FACTORS-2023`: `VI-01`, `VI-02`, `VI-03`, `VI-04`.
- *(Tài liệu NIDDK `VI-05` và `VI-06` giữ trạng thái `HELD_AS_DRAFT`, không nạp vào database).*
- *(Tài liệu `ISSN-PROTEIN-EXERCISE-2017` hiện **chưa có trong database runtime** của testbed, chỉ tồn tại trong các fixture/test double).*

Do đó, truy vấn thử nghiệm live **bắt buộc phải sử dụng tài liệu ACTIVE trong kho tri thức đã index**.

### 6.2. Câu Hỏi Thử Nghiệm và Document ID Dự Kiến
- **Tài liệu nguồn được chọn:** `WHO-PA-SB-2020-VI-01` (Phiên bản `1.1.0`, Source ID: `WHO-PA-SB-2020`).
- **Nội dung tài liệu:** Khuyến nghị vận động thể lực chung cho người trưởng thành (18–64 tuổi).
- **Câu hỏi tiếng Việt:**
  > *"Theo khuyến nghị của WHO cho người trưởng thành từ 18 đến 64 tuổi, cần duy trì thời lượng hoạt động thể lực aerobic cường độ vừa phải tối thiểu bao nhiêu phút mỗi tuần để có lợi cho sức khỏe?"*
- **Kỳ vọng trích dẫn (Expected Citation):**
  - `documentId`: `WHO-PA-SB-2020-VI-01`
  - `version`: `1.1.0`
  - `sourceId`: `WHO-PA-SB-2020`
  - `locator.sourcePath`: `data/knowledge/curated/who-2020/01-adults-general.vi.md`
  - `quoteOrExcerpt`: Phải trích nguyên văn từ nội dung chunk (ví dụ liên quan đến con số 150–300 phút/tuần).

### 6.3. Quy Trình Xác Minh Toàn Trình (End-to-End Verification Flow)
Khi môi trường đã được cấp `OPENAI_API_KEY` và thiết lập `F01_PROVIDER=openai`:

1. **Bước 1 — Tiền kiểm tra tính sẵn sàng của chunk trong Database (Pre-flight check):**
   ```sql
   SELECT c.document_code, c.section_index, pr.status, (pr.statistics->>'index_ready')::boolean AS index_ready
   FROM fitness.knowledge_chunks c
   JOIN fitness.knowledge_processing_runs pr ON pr.knowledge_version_id = c.knowledge_version_id
   WHERE c.document_code = 'WHO-PA-SB-2020-VI-01' AND pr.status = 'SUCCEEDED';
   ```
   *Yêu cầu:* Phải trả về ít nhất 1 chunk với `status = 'SUCCEEDED'` và `index_ready = true`.

2. **Bước 2 — Khởi động các dịch vụ:**
   ```powershell
   # Terminal 1: FastAPI AI Service
   cd services/ai-service
   $env:OPENAI_API_KEY="sk-proj-..."
   $env:F01_PROVIDER="openai"
   .venv\Scripts\uvicorn app.main:app --port 8000

   # Terminal 2: Spring Boot Backend
   cd services/backend
   .\mvnw spring-boot:run
   ```

3. **Bước 3 — Gửi yêu cầu qua Public API:**
   ```bash
   curl -X POST http://localhost:8080/api/v1/ai/knowledge/query \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer <STUDENT_TOKEN>" \
     -d '{
       "question": "Theo khuyến nghị của WHO cho người trưởng thành từ 18 đến 64 tuổi, cần duy trì thời lượng hoạt động thể lực aerobic cường độ vừa phải tối thiểu bao nhiêu phút mỗi tuần để có lợi cho sức khỏe?",
       "language": "vi"
     }'
   ```

4. **Bước 4 — Luồng xử lý xuyên suốt được kiểm chứng:**
   - Spring Boot tạo bản ghi `AiRun` (`QUEUED` -> `RUNNING`).
   - pgvector tìm kiếm và ghi nhận evidence chunks (`WHO-PA-SB-2020-VI-01`) vào `F01RunEvidenceTracker`.
   - FastAPI gọi OpenAI Chat Completions API với model `gpt-4o-mini-2024-07-18`, `store: false`.
   - `OpenAiKnowledgeProvider` đối soát Citation Integrity với các chunk được truyền vào.
   - Spring Boot `AiKnowledgeOrchestrationService` thẩm định phản hồi qua `F01RuntimeOutputValidator`, đối soát trích dẫn với `runEvidence` và kiểm tra `isChunkEligible`.
   - AI Run chuyển sang trạng thái kết thúc `SUCCEEDED`, lưu quan hệ trích dẫn vào bảng `fitness.ai_run_knowledge_chunks`.

5. **Bước 5 — Đọc lại bản ghi để nghiệm thu Persistence:**
   ```bash
   curl -X GET http://localhost:8080/api/v1/ai/runs/{runId} \
     -H "Authorization: Bearer <STUDENT_TOKEN>"
   ```
   *Xác nhận:* Trả về JSON chứa `runStatus = "SUCCEEDED"`, `resultType = "INFORMATION"`, `citations` trỏ đúng vào `WHO-PA-SB-2020-VI-01` và các số liệu token usage thực tế.

*Cảnh báo chất lượng:* Việc kiểm tra citation hợp lệ chỉ xác nhận tính toàn vẹn nguồn dẫn (Citation Integrity). Nó không thay thế cho đánh giá chuyên môn sâu về tính chân thực ngữ nghĩa của toàn bộ các câu trong câu trả lời (Claim-level Groundedness), vốn sẽ được đo đạc chính thức tại **A-52**.
