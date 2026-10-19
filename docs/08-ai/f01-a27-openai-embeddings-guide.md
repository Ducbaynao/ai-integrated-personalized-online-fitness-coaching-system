# F01 Knowledge Retrieval — Real OpenAI Embeddings Adapter Guide (A-27)

Tài liệu này hướng dẫn vận hành, cấu hình lựa chọn provider, kiểm thử offline, kiểm soát ngân sách và quy trình rollback an toàn cho module Embedding thật của F01 Kiến thức chung (`text-embedding-3-small`, 1536 chiều).

---

## 1. Bối cảnh và Quyết định Nền tảng (A-13)

Theo quyết định DEC-A13-01 trong [docs/08-ai/f01-a13-provider-decision.md](f01-a13-provider-decision.md):
- **Model được chọn**: `text-embedding-3-small` của OpenAI.
- **Không gian vector (model space)**: `openai/text-embedding-3-small/1536` với chiều dài vector cố định 1536 (L2 normalized cosine metric trên PostgreSQL pgvector).
- **Phân định Model ID và Model Version ID (Flyway V42)**:
  - **AI Model ID (`ai_models.id`)**: `e0000000-0000-0000-0000-000000000002` (`provider = 'openai'`, `model_code = 'text-embedding-3-small'`, `capability = 'EMBEDDING'`, `is_active = false` mặc định).
  - **AI Model Version ID (`ai_model_versions.id`)**: `e1000000-0000-0000-0000-000000000002` (`version_label = '1.0.0'`, `configuration = {"dimension": 1536, "metric": "COSINE", "model_space": "openai/text-embedding-3-small/1536"}`).
  - **Quan hệ khóa ngoại cơ sở dữ liệu**: Bảng `fitness.knowledge_embeddings` có ràng buộc khóa ngoại `embedding_model_version_id uuid NOT NULL REFERENCES fitness.ai_model_versions(id)`. Do đó, `EmbeddingModelPort.modelVersionId()` trả về chính xác ID của bảng `ai_model_versions` (`e1000000-0000-0000-0000-000000000002`), không nhầm lẫn với ID của bảng cha `ai_models`.
- **Ước tính chi phí lý thuyết và phân biệt Token Usage**:
  - **Quy tắc phân biệt token**: Số `tokenCount` trong bảng `fitness.knowledge_chunks` là **ước lượng heuristic nội bộ** (dựa trên thuật toán tách từ sơ bộ tiếng Việt trong `KnowledgeChunker.java`) dùng để phục vụ ngưỡng phân đoạn văn bản. Ngược lại, **token usage thực tế** tính cước do API của OpenAI xác định và trả về trong trường `usage.total_tokens` (dựa trên tokenizer BPE `cl100k_base`/`o200k_base`). Do đặc thù nguyên âm có dấu trong tiếng Việt, số token thực tế thường chênh lệch so với `tokenCount` ước lượng.
  - **Chi phí thử nghiệm WHO-01 (1 bài)**: WHO-01 gồm 5 chunks với ~622 heuristic tokens. Với đơn giá `$0.02 / 1M tokens` ($0.00000002 / token), chi phí vector hóa ước tính chỉ khoảng `622 * 0.00000002 = $0.00001244 USD` (~`$0.000012 USD`). Một lượt truy vấn retrieval (~20 tokens) tốn thêm khoảng `$0.00000040 USD`. Tổng chi phí thử nghiệm WHO-01 ước tính ~`$0.000013 USD`.
  - **Chi phí lý thuyết toàn bộ 9 bài derivative**: ~30,000 tokens ước lượng tương đương khoảng `~$0.0006 USD`. **Lưu ý**: Đây thuần túy là phép tính ước lượng số học lý thuyết, chi phí thực tế căn cứ vào trường `usage.total_tokens` từ phản hồi của OpenAI API.
- **Hiện trạng Corpus Derivative**: Toàn bộ corpus có 11 bài derivative, trong đó:
  - **9 bài đạt `TECHNICAL_VERIFICATION_PASSED`**: WHO 01, 02, 03, 05, 06; NIDDK 01, 02, 03, 04 (đủ điều kiện tham gia quy trình review xuất bản).
  - **2 bài giữ `HELD_AS_DRAFT`**: NIDDK 05 (thuốc và tăng cân) và NIDDK 06 (tình trạng sức khỏe & rối loạn ăn uống) bị giữ lại do yêu cầu guardrail an toàn y tế/biên tập.
  - **Tuyệt đối không mô tả cả 11 bài đều technical-ready.**

> [!IMPORTANT]
> **Cấm tự ý cập nhật Database dùng chung**:
> Không thực thi bất kỳ câu lệnh `UPDATE` hay `INSERT` trực tiếp nào trên cơ sở dữ liệu dùng chung hoặc môi trường production trong giai đoạn này. Các câu lệnh SQL trong tài liệu này chỉ mang tính định hướng quy trình kỹ thuật.

---

## 2. Kiểm soát Ngân sách và Giới hạn Chi tiêu (Đối chiếu OpenAI Documentation)

Đối chiếu tài liệu chính thức hiện hành của OpenAI về *Usage Limits*, *Budgets* và *Spend Limits* ([OpenAI Platform Documentation](https://platform.openai.com/docs/guides/production-best-practices#setting-up-usage-limits)):

1. **Phân biệt Cảnh báo (Alert) và Cơ chế Chặn Request Thực tế (Hard Limit)**:
   - **Soft email alert threshold** (đề xuất & đã duyệt: `$5.00 / tháng`): Chỉ là thông báo tự động qua email khi chi tiêu tích lũy chạm ngưỡng. **Cơ chế này hoàn toàn không chặn hay tạm dừng request**, lưu lượng API vẫn tiếp tục hoạt động bình thường.
   - **Hard spend limit** (đề xuất & đã duyệt: `$10.00 / tháng`): Là cấu hình giới hạn chi tiêu cứng cấp Organization hoặc Project. Khi kích hoạt và chi tiêu chạm trần, OpenAI sẽ thực sự từ chối các request tiếp theo với HTTP 429 (mã lỗi `insufficient_quota`, `organization_spend_limit_exceeded` hoặc `project_spend_limit_exceeded`).
   - **Lưu ý về Project Budget**: Trong giao diện OpenAI Project, việc chỉ thiết lập budget hàng tháng mà không bật tính năng Hard Limit tương ứng thì mặc định chỉ hoạt động như một email alert, không có khả năng ngăn chặn phát sinh chi phí vượt mức.
   - **Độ trễ thực thi (Enforcement Latency)**: Tài liệu chính thức của OpenAI nêu rõ việc ghi nhận mức sử dụng và áp dụng lệnh chặn có độ trễ tính bằng phút (*"can take several minutes to reflect in limits"*). Do đó, một lượng request gửi đồng thời với tốc độ cao có thể gây vượt nhẹ ngân sách (minor overage) trước khi hệ thống OpenAI ngắt toàn diện.
2. **Tình trạng Phê duyệt và Khoảng trống Xác minh**:
   - **Hạn mức đã phê duyệt**: Quyết định A-13 (**DEC-A13-03**) đã chính thức phê duyệt hạn mức thử nghiệm `$10.00 / tháng` (Hard Limit) và cảnh báo `$5.00 / tháng` (Email Alert). (DEC-A13-05 là mã quyết định về chiến lược dữ liệu reindex/rollback).
   - **Khoảng trống thực tế (UNVERIFIED)**: Tài khoản OpenAI của dự án hiện **chưa có bằng chứng xác nhận** đã hoàn tất cấu hình Hard Limit $10 và Email Alert $5 trên bảng điều khiển quản trị (`platform.openai.com/settings/organization/limits`).
   - **Điều kiện An toàn**: Khi cấu hình và cơ chế enforcement thực tế trên dashboard chưa được xác minh, **bước gọi API thật bên ngoài phải giữ ở trạng thái CHƯA THỰC HIỆN (PENDING PREREQUISITES)**. Không được tự ý nạp tiền hay chạy thử nghiệm live khi cơ chế chặn cứng chưa được bảo đảm.

---

## 3. Kiến trúc và Thành phần Adapter

### 3.1 Cổng Trừu tượng `EmbeddingModelPort`

Module Content giao tiếp với hệ thống embedding thông qua port:
[`com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/content/application/port/out/EmbeddingModelPort.java)

Hai adapter triển khai port:
1. `DeterministicEmbeddingGenerator`: Adapter giả lập ngoại tuyến (deterministic hashing, 1536 chiều), dùng mặc định cho môi trường phát triển cục bộ và CI/CD.
2. `OpenAiEmbeddingAdapter`: Adapter kết nối trực tiếp đến endpoint `POST /v1/embeddings` của OpenAI.

### 3.2 Cấu hình Lựa chọn Provider (`EmbeddingConfiguration`)

Bean `EmbeddingModelPort` được quản lý có điều kiện dựa trên thuộc tính `ai.embedding.provider`:
- Khi `ai.embedding.provider=simulated` (hoặc không cấu hình): Spring Boot nạp `DeterministicEmbeddingGenerator`.
- Khi `ai.embedding.provider=openai`: Spring Boot nạp `OpenAiEmbeddingAdapter`.
- **Nguyên tắc an toàn (Fail-fast)**:
  - Nếu cấu hình provider không được hỗ trợ (ví dụ typo `ai.embedding.provider=xyz`): `EmbeddingProperties` ngắt khởi động ngay với `IllegalStateException: Unsupported ai.embedding.provider: 'xyz'. Supported values are 'simulated' and 'openai'.`
  - Nếu chọn `openai` nhưng thiếu API key (`AI_EMBEDDING_API_KEY` hoặc `OPENAI_API_KEY`): ngắt khởi động ngay với `IllegalStateException: OpenAI embedding API key is required when ai.embedding.provider=openai.`

---

## 4. Biến môi trường và Cấu hình Chi tiết

Trong `services/backend/src/main/resources/application.yml`:

```yaml
ai:
  embedding:
    provider: ${AI_EMBEDDING_PROVIDER:simulated} # 'simulated' hoặc 'openai'
    openai:
      api-key: ${AI_EMBEDDING_API_KEY:${OPENAI_API_KEY:}}
      base-url: ${AI_EMBEDDING_BASE_URL:https://api.openai.com}
      model: ${AI_EMBEDDING_MODEL:text-embedding-3-small}
      model-space: ${AI_EMBEDDING_MODEL_SPACE:openai/text-embedding-3-small/1536}
      dimension: ${AI_EMBEDDING_DIMENSION:1536}
      connect-timeout-ms: ${AI_EMBEDDING_CONNECT_TIMEOUT_MS:5000}
      read-timeout-ms: ${AI_EMBEDDING_READ_TIMEOUT_MS:10000}
      max-attempts: ${AI_EMBEDDING_MAX_ATTEMPTS:2}
      overall-deadline-ms: ${AI_EMBEDDING_OVERALL_DEADLINE_MS:15000}
```

### Bảng tham số:

| Thuộc tính | Biến môi trường | Mặc định | Ý nghĩa & Quy tắc |
| :--- | :--- | :--- | :--- |
| `ai.embedding.provider` | `AI_EMBEDDING_PROVIDER` | `simulated` | Chọn provider: `simulated` hoặc `openai`. Giá trị khác sẽ bị từ chối khi khởi động. |
| `ai.embedding.openai.api-key` | `AI_EMBEDDING_API_KEY` / `OPENAI_API_KEY` | *(trống)* | API key OpenAI bí mật. Bắt buộc khi provider là `openai`. Không commit vào repo. |
| `ai.embedding.openai.base-url` | `AI_EMBEDDING_BASE_URL` | `https://api.openai.com` | Base URL của API OpenAI (hoặc mock gateway). Tự động chuẩn hóa: nếu cấu hình có hậu tố `/v1` hoặc trailing slash, adapter tự bóc tách để khi ghép với path `/v1/embeddings` không bao giờ bị lặp thành `/v1/v1/embeddings` (tránh lỗi HTTP 404). |
| `ai.embedding.openai.model` | `AI_EMBEDDING_MODEL` | `text-embedding-3-small` | Tên model OpenAI (`text-embedding-3-small`). |
| `ai.embedding.openai.model-space` | `AI_EMBEDDING_MODEL_SPACE` | `openai/text-embedding-3-small/1536` | Chuỗi định danh model space. |
| `ai.embedding.openai.dimension` | `AI_EMBEDDING_DIMENSION` | `1536` | Số chiều vector bắt buộc (1536). |
| `ai.embedding.openai.connect-timeout-ms` | `AI_EMBEDDING_CONNECT_TIMEOUT_MS` | `5000` | Socket connect timeout (5 giây). |
| `ai.embedding.openai.read-timeout-ms` | `AI_EMBEDDING_READ_TIMEOUT_MS` | `10000` | HTTP read timeout (10 giây). |
| `ai.embedding.openai.max-attempts` | `AI_EMBEDDING_MAX_ATTEMPTS` | `2` | Số lần thử tối đa cho lỗi tạm thời (1 attempt ban đầu + tối đa 1 retry). |
| `ai.embedding.openai.overall-deadline-ms` | `AI_EMBEDDING_OVERALL_DEADLINE_MS` | `15000` | Hạn chót tối đa cho toàn bộ batch (15 giây). Bao trùm cả connect, read, backoff và retries. |

---

## 5. Cơ chế Phòng vệ, Timeout/Retry và Tính Bất biến

1. **Thứ tự Vector (Index Mapping)**:
   OpenAI Embeddings API có thể trả về mảng `data` không theo thứ tự ban đầu khi xử lý batch. `OpenAiEmbeddingAdapter` bóc tách từng phần tử theo trường `index` và ánh xạ chính xác về đúng vị trí input.
2. **Kiểm tra Dữ liệu Đầu ra Nghiêm ngặt (`AiOutputValidationException`)**:
   - Từ chối phản hồi nếu body rỗng hoặc thiếu mảng `data`.
   - Từ chối nếu số lượng vector trả về khác số lượng input.
   - Từ chối nếu có phần tử `null`, `index` trùng lặp, âm hoặc vượt ngoài khoảng `[0, size - 1]`.
   - Kiểm tra mọi vector phải đúng 1536 chiều.
   - Quét từng giá trị float: từ chối nếu chứa `NaN` hoặc `Infinity`.
3. **Quản lý Deadline Tổng thể và Retry có Kiểm soát (Ranh giới Chứng minh của Code & Test)**:
   - **Deadline tổng thể (Overall Deadline 15s)**: Bao trùm toàn bộ: connect timeout (5s), read timeout (10s), thời gian sleep backoff và các lần thử lại. Cơ chế này được kiểm chứng qua `OpenAiEmbeddingAdapterTest` bằng việc tính toán hạn chót trước mỗi lần gọi và trước khi sleep backoff.
   - **Kiểm tra Deadline trước Retry**: Nếu thời gian còn lại trước deadline `<= 200ms`, adapter ngắt ngay với `AiTimeoutException` mà không retry vô ích.
   - **Xử lý header `Retry-After`**: Nếu OpenAI trả về header `Retry-After` với thời gian lớn hơn hoặc bằng thời gian còn lại của deadline, adapter ngắt ngay với `AiTimeoutException` thay vì chờ đợi vượt deadline.
   - **Ranh giới kỹ thuật**: Cơ chế deadline được kiểm soát ở tầng ứng dụng (Java Adapter level). Adapter không thể bảo đảm thời gian ngắt tức thì nếu tầng transport socket bên dưới bị treo ở cấp hệ điều hành vượt quá read timeout cấp TCP.
   - **Phân loại Lỗi**:
     - *Không thử lại (Non-retryable - Fail Fast)*: HTTP 401 / 403 (sai key, hết quyền), HTTP 400 / 422 (payload sai), và **HTTP 429 Quota Exceeded** (`insufficient_quota` / `spend_limit_exceeded`). **Tuyệt đối không retry khi hết ngân sách**.
     - *Được thử lại (Retryable)*: HTTP 429 rate limit thông thường (requests/tokens limit) với exponential backoff kèm full jitter, HTTP 5xx (500, 502, 503, 504), và timeout mạng (tối đa 2 attempts).
4. **Không Fallback Ngầm (No Silent Fallback)**:
   Nếu adapter OpenAI gặp sự cố mạng hoặc lỗi provider, hệ thống ném ngoại lệ rõ ràng (`AiProviderException` / `AiTimeoutException`), **tuyệt đối không âm thầm quay về vector simulated**. Việc trộn lẫn vector không gian simulated với OpenAI sẽ làm sai lệch hoàn toàn kết quả cosine distance trong pgvector.
5. **Cách ly I/O ngoài Database Transaction**:
   Cả `KnowledgeIndexingOutboxWorker` (Boundary 2) và `KnowledgeRetrievalService` đều thực thi lệnh sinh embedding với `PROPAGATION_NOT_SUPPORTED`, đảm bảo hoàn toàn không giữ DB transaction hay lock trong quá trình chờ HTTP response từ OpenAI.
6. **Bảo mật Bí mật và Thông tin Nhạy cảm**:
   - Headers chứa API Key (`Authorization: Bearer ...`) được cấu hình bảo mật ở cấp độ Client, không bao giờ in ra log.
   - Nội dung văn bản input không bị ghi lộ trong log lỗi.
   - Thông điệp log chỉ ghi nhận kích thước batch, tên model, mã trạng thái HTTP và thông báo lỗi kỹ thuật đã được lọc.

---

## 6. Kiểm thử Ngoại tuyến (Offline Test Suite)

Tất cả kiểm thử đơn vị và tích hợp của adapter đều chạy hoàn toàn ngoại tuyến, không yêu cầu API key thật và không gửi request ra internet:

### 6.1 Unit Tests (`OpenAiEmbeddingAdapterTest`)
Bao phủ các kịch bản chi tiết sử dụng `MockRestServiceServer`:
- Metadata khớp với Model Registry V42.
- Gọi single text và batch text thành công.
- Sắp xếp vector đúng index khi OpenAI trả kết quả đảo thứ tự.
- Bắt lỗi khi response body null, missing data array, null item, duplicate index, out of bounds index, kích thước không khớp, sai số chiều vector, chứa NaN/Infinity.
- Xử lý fail-fast đối với HTTP 401, 403, 400.
- Nhận diện và fail-fast đối với HTTP 429 `insufficient_quota` (không retry).
- Retry thành công đối với HTTP 429 rate limit thông thường và HTTP 500 server error.
- Dừng thử lại sau 2 attempts khi lỗi 503 kéo dài.
- Xử lý header `Retry-After` vượt deadline: ném `AiTimeoutException` ngay lập tức.
- Xác nhận không có fallback ngầm.
- Fail-fast khi khởi tạo adapter mà thiếu API key.

Lệnh chạy:
```bash
cd services/backend
./mvnw.cmd test -Dtest=OpenAiEmbeddingAdapterTest
```

### 6.2 Provider Selection Integration Tests (`EmbeddingProviderSelectionIntegrationTest`)
Bao phủ các kịch bản kiểm tra ngữ cảnh Spring Boot:
- Mặc định nạp `DeterministicEmbeddingGenerator` (`simulated`).
- Chọn `simulated` tường minh.
- Chọn `openai` kèm API key -> nạp `OpenAiEmbeddingAdapter`.
- Chọn `openai` nhưng thiếu API key -> Context khởi động thất bại ngay lập tức (`IllegalStateException`).
- Chọn provider không hợp lệ (`xyz`) -> Fail-fast ngay lập tức (`IllegalStateException`).

Lệnh chạy:
```bash
cd services/backend
./mvnw.cmd test -Dtest=EmbeddingProviderSelectionIntegrationTest
```

### 6.3 Spring Context Offline Regression (`OpenAiEmbeddingContextRegressionTest`)
Kiểm chứng ngữ cảnh Spring Boot production khởi động thành công với `ai.embedding.provider=openai` mà không cần gọi mạng bên ngoài:
- Xác nhận bean `RestClient.Builder` được cung cấp tự động bởi `@ConditionalOnMissingBean` trong `EmbeddingConfiguration`.
- Nạp đầy đủ cấu hình adapter `OpenAiEmbeddingAdapter`, timeouts và endpoint.
- Hoàn toàn offline (dùng H2 in-memory của test profile, dummy API key).

Lệnh chạy:
```bash
cd services/backend
./mvnw.cmd test -Dtest=OpenAiEmbeddingContextRegressionTest
```

### 6.4 WHO-01 Offline Chunking Verification (`Who01KnowledgeChunkingOfflineTest`)
Kiểm thử thuần túy cấp đơn vị (POJO unit test, không cần Spring Boot Context, không kết nối database hay gọi API):
- Chạy tự động trong mọi lần build Maven mặc định (`./mvnw test`) và trên GitHub CI.
- Xác thực tính toàn vẹn SHA-256 của file nguồn `WHO-PA-SB-2020-VI-01` (`5d191d97...339840`).
- Kiểm chứng thuật toán tách đoạn `KnowledgeChunker` tạo ra chính xác 5 chunks, cấu trúc heading paths, token count và tính tất định (determinism).

Lệnh chạy:
```bash
cd services/backend
./mvnw.cmd test -Dtest=Who01KnowledgeChunkingOfflineTest
```

### 6.5 Controlled Local Testbed Integration (`Who01RealEmbeddingTestbedIntegrationTest`)
Kiểm thử tích hợp đầu-cuối trên testbed local độc lập:
- **Cơ chế cô lập CI/CD:** Sử dụng `@EnabledIfEnvironmentVariable(named = "RUN_LIVE_OPENAI_TESTS", matches = "(?i)true")` ở cấp độ Class. Khi chạy trong CI hoặc khi không bật cờ, JUnit 5 bỏ qua toàn bộ test class trước khi Spring Boot TestContext được khởi tạo, tuyệt đối không cố kết nối tới `localhost:5433` và không gây lỗi runner.
- **Yêu cầu môi trường khi chủ động opt-in:**
  - Database testbed: PostgreSQL 18.6 với pgvector đang chạy tại `localhost:5433` (container `digital-fitness-postgres`).
  - Biến môi trường: `RUN_LIVE_OPENAI_TESTS=true` và `OPENAI_API_KEY` (hoặc `AI_EMBEDDING_API_KEY`).
  - Tuyệt đối không lưu khóa API thật vào file mã nguồn hoặc cấu hình mặc định.

Lệnh PowerShell để chạy thử nghiệm testbed cục bộ có kiểm soát:
```powershell
cd services/backend
$env:RUN_LIVE_OPENAI_TESTS="true"
$env:OPENAI_API_KEY="sk-proj-..."
./mvnw.cmd test -Dtest=Who01RealEmbeddingTestbedIntegrationTest
# Sau khi hoàn tất, luôn dọn dẹp biến môi trường trong phiên PowerShell:
Remove-Item Env:\RUN_LIVE_OPENAI_TESTS
Remove-Item Env:\OPENAI_API_KEY
```

---

## 7. Hướng dẫn Thử nghiệm Live (Khi Điều kiện Tiên quyết Đã Được Phê duyệt)

Khi chủ dự án đã xác nhận hạn mức $10/$5 trên OpenAI dashboard và phê duyệt chạy thử nghiệm live:

### Bước 1: Xác nhận hạn mức chi tiêu trên OpenAI Dashboard (Bắt buộc)
1. Đăng nhập trang quản trị OpenAI: `https://platform.openai.com/settings/organization/limits`.
2. Kiểm tra **Monthly spend limit**: tối đa `$10.00 / tháng`.
3. Kiểm tra **Email notification threshold**: `$5.00 / tháng`.
4. Đảm bảo đã bật cơ chế enforcement trên Project/Org.

### Bước 2: Thiết lập Biến Môi trường
Cung cấp API key qua biến môi trường của tiến trình (không ghi vào file mã nguồn):
```bash
export AI_EMBEDDING_PROVIDER=openai
export AI_EMBEDDING_API_KEY="sk-proj-xxxx..."
```

### Bước 3: Kích hoạt Model trong Database Registry
Trong PostgreSQL (chỉ thực hiện khi có phê duyệt):
```sql
-- Kích hoạt Model cha
UPDATE fitness.ai_models
SET is_active = true
WHERE id = 'e0000000-0000-0000-0000-000000000002';

-- Kích hoạt Model Version
UPDATE fitness.ai_model_versions
SET activated_at = now()
WHERE id = 'e1000000-0000-0000-0000-000000000002';
```

### Bước 4: Chạy Reindex Outbox cho Corpus
Khi chuyển sang OpenAI, worker outbox cần tính toán lại vector cho các document ACTIVE:
- Vector cũ của version (nếu có) sẽ được xóa sạch trong Boundary 3 transaction.
- Worker chia batch và gửi yêu cầu embedding đến OpenAI API.

### Bước 5: Chạy Đánh giá Retrieval (A-49 Evaluation Runner)
```bash
# Đánh giá tầng retrieval với internal service key
npm run eval:f01 -- --mode=retrieval --endpoint=http://localhost:8080/internal/v1/knowledge/retrieval --key=your-internal-key --limit=10
```

---

## 8. Quy trình Rollback An Toàn về Simulated Provider

> [!CAUTION]
> **Hiện trạng Kiểm chứng Rollback**:
> - **Nguyên lý kiến trúc & Quy trình**: Đã được thiết kế và đối chiếu với cơ chế xóa vector theo version của `KnowledgeIndexingOutboxWorker`.
> - **Thực hành Diễn tập Rollback Thực tế (Live Rollback Drill)**: Hiện ở trạng thái **CHƯA ĐƯỢC XÁC MINH (UNVERIFIED)** trên môi trường live/testbed có chứa vector OpenAI thật, do chưa có phiên kích hoạt và reindex live nào với OpenAI.
> - **Lưu ý Bắt buộc về Rollback**:
> Do `KnowledgeIndexingOutboxWorker` xóa vector của version cũ khi thực hiện reindex, việc chỉ thay đổi cấu hình `AI_EMBEDDING_PROVIDER=simulated` **sẽ không tự động khôi phục** lại vector simulated cho các văn bản đã reindex bằng OpenAI.
> **Bắt buộc phải kích hoạt Outbox Reindex lại toàn bộ corpus ACTIVE** sau khi chuyển cấu hình về `simulated`.

### Các bước thực hiện Rollback:

1. **Khôi phục Cấu hình Môi trường**:
   ```bash
   export AI_EMBEDDING_PROVIDER=simulated
   unset AI_EMBEDDING_API_KEY
   ```
2. **Cập nhật Database Registry**:
   ```sql
   -- Đánh dấu OpenAI model là inactive
   UPDATE fitness.ai_models
   SET is_active = false
   WHERE id = 'e0000000-0000-0000-0000-000000000002';

   -- Kích hoạt lại Simulated model
   UPDATE fitness.ai_models
   SET is_active = true
   WHERE id = 'e0000000-0000-0000-0000-000000000001';
   ```
3. **Kích hoạt Reindex Outbox lại Toàn bộ Corpus ACTIVE**:
   Tạo các bản ghi outbox reindex cho toàn bộ các version kiến thức ACTIVE để worker sinh lại vector `simulated/deterministic-1536/1.0.0`.
4. **Kiểm tra Tính Sẵn sàng của Index**:
   Xác nhận trường `index_ready = true` trong `knowledge_document_versions` trước khi cho phép người dùng hoặc hệ thống RAG truy vấn lại.
