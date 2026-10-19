# Báo Cáo Nghiệm Thu Thử Nghiệm WHO-01 với OpenAI Embeddings Thật (A-27)

> **Mã công việc:** A-27 (Knowledge Retrieval & Real Embedding Integration)  
> **Tài liệu kiểm chứng:** `WHO-PA-SB-2020-VI-01` (WHO 2020 Guidelines on Physical Activity and Sedentary Behaviour - Bản tóm lược tiếng Việt)  
> **Thời điểm hoàn tất thử nghiệm live:** `17:39:03 ngày 19/10/2026 (GMT+07:00)`  
> **Branch thực thi:** `chore/phase2-a27-live-testbed`  
> **Môi trường thực thi:** Local testbed cô lập, PostgreSQL 18.6 + pgvector trên Docker container `digital-fitness-postgres`

---

## 1. Mục Đích và Giới Hạn Phạm Vi

### 1.1. Mục đích
- Nghiệm thu thực tế chu trình tích hợp đầu-cuối (end-to-end integration) của adapter OpenAI Embedding (`text-embedding-3-small`, 1536 chiều) trên đúng 01 tài liệu đã được phê duyệt chuyên gia: `WHO-PA-SB-2020-VI-01`.
- Kiểm chứng khả năng xử lý bất đồng bộ qua outbox pattern (`KnowledgeIndexingOutboxWorker`), lưu trữ vector vào bảng `fitness.knowledge_embeddings` (PostgreSQL/pgvector), và truy vấn ngữ nghĩa (`VectorKnowledgeSearchAdapter`).
- Xác minh tính tương thích của Spring Boot 4.1.1 với `RestClient.Builder` và chuẩn hóa endpoint URL (`https://api.openai.com/v1/embeddings`).

### 1.2. Giới hạn phạm vi (Boundary & Non-Goals)
- **Chỉ áp dụng cho 01 tài liệu:** Thử nghiệm giới hạn nghiêm ngặt ở `WHO-PA-SB-2020-VI-01` (5 chunks). Không tự động reindex toàn bộ 9 tài liệu derivative còn lại trong corpus.
- **Trạng thái Corpus:** 9 tài liệu đã đạt trạng thái `SPECIALIST_REVIEW_PASSED`; 2 tài liệu NIDDK vẫn được giữ nguyên trạng thái `HELD_AS_DRAFT` (chưa duyệt).
- **Chưa có Evaluation toàn diện:** Chưa chạy bộ đo benchmark A-49 trên toàn bộ corpus.
- **Chưa có LLM Generation thật:** Phân hệ tạo câu trả lời (generation) vẫn hoạt động ở chế độ simulated/mock theo lộ trình Phase 2.
- **Chất lượng truy vấn:** Điểm cosine similarity (`~0.6491`) chỉ chứng minh kết nối kỹ thuật và tính toán khoảng cách vector thành công trong pgvector; không thay thế cho đánh giá chuyên gia về độ chính xác thông tin.

---

## 2. Kết Quả Thực Thi Testbed (Surefire Evidence)

Bộ kiểm thử tích hợp `Who01RealEmbeddingTestbedIntegrationTest` được thực thi và hoàn tất thành công lúc **17:39:03 ngày 19/10/2026**:

```
[INFO] Running com.fitnesscoaching.platform.modules.content.adapter.out.embedding.Who01RealEmbeddingTestbedIntegrationTest
...
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 20.324 s -- in WHO-01 Real Embedding Testbed Integration Test
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

### Chi tiết các kịch bản kiểm thử:
1. **`testWho01OfflineChunkingAndReadiness` (0.63s - PASS):**
   - Xác minh file nguồn WHO-01: kích thước `3,429` bytes, SHA-256 hash: `5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840`.
   - Cơ chế chia đoạn tạo ra chính xác **5 chunks**, heading path và số lượng token ước tính phù hợp cấu trúc tài liệu.
2. **`testWho01LiveIndexingAndRetrievalWithOpenAi` (5.18s - PASS):**
   - **Xác thực môi trường an toàn:** Kết nối đúng database local `digital_fitness` (schema `fitness`); xác minh không có worker tự động (`ScheduledTasks` không kích hoạt outbox worker ngầm).
   - **Bảo toàn trạng thái Outbox:** Phát hiện job `44d538f4-d37d-4249-b895-d017485ac87a` ở trạng thái `QUEUED` (attempt 1 thất bại do lỗi 404 URL trước đó). Hệ thống tiếp tục xử lý (resume) đúng job này ở **attempt 2**, không xóa dữ liệu và không tạo job trùng lặp.
   - **Gọi API OpenAI thật:** Gửi batch 5 chunks qua model `text-embedding-3-small`, nhận về 5 vector 1536 chiều ngay trong lần gọi đầu tiên (`attempt 1/2`).
   - **Lưu trữ vector:** Cập nhật bảng `fitness.knowledge_embeddings` và đánh dấu job `SUCCEEDED` (thời gian index: `3,732ms`).
   - **Kiểm chứng Retrieval:**
     - Query: *"Khuyến nghị hoạt động thể lực người trưởng thành WHO 2020"*
     - Trả về: **5 chunks**, top similarity: `0.6490594460098009` (~0.6491), thời gian truy vấn `876ms`.
     - Chunk liên quan nhất: Heading path *"Phạm vi và cách sử dụng WHO 2020 > Nội dung"*.
   - **Kiểm chứng Negative Source Scope:**
     - Query kèm ràng buộc loại trừ nguồn (`NIH-ODS-EXERCISE-SUPPLEMENTS`).
     - Kết quả: Trả về **0 chunks** (chính xác tuyệt đối, không rò rỉ kiến thức ngoài phạm vi cho phép).

---

## 3. Đối Chiếu Dữ Liệu Trong Database (Read-Only Audit)

Truy vấn trực tiếp trên container `digital-fitness-postgres` (PostgreSQL 18.6, port 5433) ghi nhận các thông số sau:

### 3.1. Tài liệu và Phiên bản
- **Document (`fitness.knowledge_documents`):**
  - `id`: `b71dd65c-a48f-43b8-9cb5-4b9006e27a7e`
  - `document_code`: `WHO-PA-SB-2020-VI-01`
  - `title`: *Phạm vi và cách sử dụng WHO 2020*
  - `source_id`: `WHO-PA-SB-2020`
  - `language`: `vi`
- **Version (`fitness.knowledge_versions`):**
  - `id`: `c0a1f8b2-8932-49e3-a786-db2836706e55`
  - `version_number`: `1`
  - `status`: `ACTIVE`
  - `content_hash`: `5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840`
  - `technical_readiness`: `TECHNICAL_VERIFICATION_PASSED`

### 3.2. Processing Run và Outbox Worker
- **Run (`fitness.knowledge_processing_runs`):**
  - `id`: `44d538f4-d37d-4249-b895-d017485ac87a`
  - `run_type`: `REINDEX`
  - `status`: `SUCCEEDED`
  - `attempt_count`: `2` (tối đa `3`)
  - `created_at`: `2026-10-19 10:15:41.529463+00`
  - `completed_at`: `2026-10-19 10:39:01.63424+00`
  - `statistics`:
    ```json
    {
      "mode": "REAL_RETRIEVAL_INDEXING",
      "dimension": 1536,
      "processor": "KnowledgeIndexingOutboxWorker",
      "index_ready": true,
      "completed_at": "2026-10-19T10:38:58.045990400Z",
      "versionLabel": "1.1.0",
      "embedding_mode": "REAL_MODEL",
      "indexed_chunks": 5,
      "embedding_model": "text-embedding-3-small",
      "embedding_space": "openai/text-embedding-3-small/1536",
      "real_embeddings": true,
      "embedding_version": "1.0.0",
      "embedding_provider": "openai",
      "embedding_model_version_id": "e1000000-0000-0000-0000-000000000002"
    }
    ```
- **Hàng đợi (Backlog):** Hiện không còn bất kỳ job nào ở trạng thái `QUEUED` hoặc `RUNNING` trong hệ thống.

### 3.3. Dữ liệu Chunks và Vector Embeddings
- **Số lượng Chunks (`fitness.knowledge_chunks`):** `5`
- **Số lượng Embeddings (`fitness.knowledge_embeddings`):** `5`
- **Chiều vector (Dimension):** `1536`
- **Model Version ID:** `e1000000-0000-0000-0000-000000000002` (khớp chính xác với cấu hình V42 Flyway migration cho `openai/text-embedding-3-small/1536`).

---

## 4. Hạch Toán Chi Phí & Ngân Sách (DEC-A13-03)

- **Số lượng vector sinh:** 5 vectors (indexing) + 2 vectors (retrieval queries) = 7 vectors.
- **Tổng số token ước tính (heuristic):** ~622 tokens (index) + ~40 tokens (queries) ≈ 662 tokens.
- **Chi phí ước tính:** ~$0.000013 USD (tính theo đơn giá chính thức $0.02 / 1,000,000 tokens của OpenAI cho `text-embedding-3-small`).
- **Kiểm soát ngân sách:**
  - Token thực tế được tính căn cứ theo `usage.total_tokens` trả về từ API response của OpenAI.
  - Tổng chi phí thực nghiệm hoàn toàn nằm dưới mức ngưỡng an toàn ($10 budget cap, $5 alert threshold theo quyết định DEC-A13-03).

---

## 5. Kết Luận Nghiệm Thu

1. **Adapter và Cấu hình:** Đã giải quyết triệt để lỗi thiếu bean `RestClient.Builder` trong production context của Spring Boot 4.1.1 thông qua `@ConditionalOnMissingBean RestClient.Builder`. Cơ chế chuẩn hóa URL (`normalizeBaseUrl`) đảm bảo endpoint luôn là `https://api.openai.com/v1/embeddings` bất kể cấu hình base URL có hay không có suffix `/v1`.
2. **Quy trình Outbox & Fencing:** Job retry/recovery hoạt động chính xác theo thiết kế; bảo toàn dữ liệu lịch sử, không tạo job dư thừa, tuân thủ nghiêm ngặt transaction và lease boundaries.
3. **Mức độ sẵn sàng:** Phân hệ embedding và indexing với OpenAI thực tế đã sẵn sàng ở cấp độ kỹ thuật cho bước tiếp theo (chuẩn bị mở rộng reindex có kiểm soát và đo benchmark A-49 khi được kích hoạt).
