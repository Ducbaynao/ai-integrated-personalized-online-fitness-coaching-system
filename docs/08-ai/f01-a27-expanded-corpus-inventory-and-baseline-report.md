# Báo Cáo Kiểm Kê Corpus, Sẵn Sàng Indexing A-27 và Baseline Đánh Giá A-49

> **Mã công việc:** A-27 (Knowledge Retrieval & Real Embedding Integration) & A-49 (Evaluation Runner Baseline)  
> **Branch làm việc:** `feat/p2-a27-corpus-retrieval-baseline`  
> **Base commit:** `9279057cbf45d21b3b6677f8f4a99a6785b8e6a8` (Merge PR #93 trên `main`)  
> **Thời điểm xác minh:** `2026-10-20`  
> **Môi trường:** Local testbed cô lập, PostgreSQL 18.6 + pgvector trên Docker container `digital-fitness-postgres` (port 5433)

---

## 1. Xác Minh Baseline Repository và Trạng Thái CI

### 1.1. Tình trạng Repository và Nhánh Làm Việc
- **Repository:** `ai-integrated-personalized-online-fitness-coaching-system`.
- **Remote `origin`:** `https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system.git`.
- **Trạng thái chuyển nhánh:**
  1. `git fetch origin` hoàn tất, working tree sạch trước khi thao tác.
  2. `git switch main` và `git pull --ff-only origin main` đồng bộ tại commit `9279057` (Merge PR #93).
  3. Tạo branch mới `feat/p2-a27-corpus-retrieval-baseline` từ `main` không tracking upstream (`git switch --no-track -c ...`).
  4. Xác nhận branch hiện tại: `feat/p2-a27-corpus-retrieval-baseline`, base commit: `9279057cbf45d21b3b6677f8f4a99a6785b8e6a8`.

### 1.2. Kết Quả Kiểm Tra CI trên GitHub Actions
Đối chiếu trực tiếp qua GitHub REST API cho các check runs:

1. **Trên merge commit 33f54d2 (PR #92 - Cách ly WHO-01 testbed khỏi Backend CI):**
   - `AI Service`: `completed / success` (2026-10-19T11:54:28Z)
   - `Contracts & Docs Validation`: `completed / success` (2026-10-19T11:54:26Z)
   - `Client Mobile`: `completed / success` (2026-10-19T11:55:06Z)
   - `Backend`: `completed / success` (2026-10-19T12:01:38Z) *(Đã hoàn thành PASS, không còn IN_PROGRESS)*
   - `Database Migrations Check`: `completed / success` (2026-10-19T11:54:39Z)
   - `Client Admin Web`: `completed / success` (2026-10-19T11:54:28Z)
   - **Kết luận:** 6/6 checks **PASSED**.

2. **Trên latest main commit 9279057 (PR #93 - Safe Area QA Fix):**
   - `AI Service`: `completed / success` (2026-10-19T17:14:06Z)
   - `Backend`: `completed / success` (2026-10-19T17:19:55Z)
   - `Client Mobile`: `completed / success` (2026-10-19T17:15:15Z)
   - `Contracts & Docs Validation`: `completed / success` (2026-10-19T17:14:00Z)
   - `Database Migrations Check`: `completed / success` (2026-10-19T17:14:09Z)
   - `Client Admin Web`: `completed / success` (2026-10-19T17:14:00Z)
   - **Kết luận:** 6/6 checks **PASSED**. Toàn bộ CI trên main xanh 100%.

---

## 2. Kiểm Kê Local Testbed và Đối Chiếu Dữ Liệu Thực Tế

### 2.1. Xác Thực Môi Trường Database Testbed
- **Container Docker:** `digital-fitness-postgres` (Image: `pgvector/pgvector:pg18`).
- **Host / Port:** `localhost:5433` (cô lập với port 5432 mặc định).
- **Database / Schema:** `digital_fitness` / `fitness` (search_path: `fitness, public`).
- **Flyway Migrations:** Đã áp dụng đầy đủ 44 migrations (V1 đến V44), tất cả đều có `success = true`.
- **Model Registry (`fitness.ai_models` & `fitness.ai_model_versions`):**
  - Simulated Test Double: `e0000000-0000-0000-0000-000000000001` (`simulated/deterministic-1536/1.0.0`, `is_active = true`).
  - OpenAI Model Reservation: `e0000000-0000-0000-0000-000000000002` (`text-embedding-3-small`, `is_active = true`).
  - OpenAI Version ID: `e1000000-0000-0000-0000-000000000002` (model space: `openai/text-embedding-3-small/1536`, dimension: 1536, metric: `COSINE`, `activated_at = 2026-10-19 10:15:41`).
- **Trạng thái Outbox Queue:** Hiện tại không có job nào ở trạng thái `QUEUED` hay `RUNNING`. Không có worker ngầm tự ý xử lý backlog.

### 2.2. Bảng Kiểm Kê Toàn Bộ 11 Tài Liệu Phái Sinh (Corpus vs Database Runtime Inventory)

| STT | Document Code | Source ID | Version | Content Hash (SHA-256) | Bytes | Specialist Review | Technical Readiness | Trạng thái Repo Staging | Trạng thái DB Runtime | Số Chunk (Đo đạc) | Số Vector DB | Không gian Vector | Điều kiện còn thiếu & Hành động dự kiến |
| :---: | :--- | :--- | :---: | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| **1** | `WHO-PA-SB-2020-VI-01` | `WHO-PA-SB-2020` | `1.1.0` | `5d191d97fbbc258aa38396cc23557fadc906c4958db106e2b40f956156339840` | 3,429 | `APPROVED` (2026-10-08) | `PASSED` | `DRAFT` | `ACTIVE` | 5 | 5 | `openai/text-embedding-3-small/1536` | **Đã nghiệm thu (Tái sử dụng)**. Version ID: `c0a1f8b2...`, Job ID: `44d538f4...` (SUCCEEDED, 2 attempts). Bảo toàn nguyên vẹn, không reindex. |
| **2** | `WHO-PA-SB-2020-VI-02` | `WHO-PA-SB-2020` | `1.1.0` | `47393a0e6335c702fe4c69bb2c45b7a848bab39a265a1532d7a2e4a389a2ad51` | 5,472 | `APPROVED` (2026-10-08) | `PASSED` | `DRAFT` | `ACTIVE` | 5 | 5 | `openai/text-embedding-3-small/1536` | **Pilot nghiệm thu thành công (20/10/2026 01:53:10)**. Version ID: `4984d35e...`, Job ID: `1f038a63...` (SUCCEEDED, 1 attempt). Bảo toàn nguyên vẹn, không reindex. |
| **3** | `WHO-PA-SB-2020-VI-03` | `WHO-PA-SB-2020` | `1.1.0` | `d56ad3c4473e8648e2b158c20e9cfa311a9fd37f282a0b517f1a9494c2099ebc` | 2,685 | `APPROVED` (2026-10-08) | `PASSED` | `DRAFT` | `ACTIVE` | 4 | 4 | `openai/text-embedding-3-small/1536` | **Live Indexing thành công (20/10/2026 02:03:33)**. Version ID: `de16472d...`, Job ID: `1cac334e...` (SUCCEEDED, 1 attempt). Sẵn sàng phục vụ retrieval. |
| **4** | `WHO-PA-SB-2020-VI-05` | `WHO-PA-SB-2020` | `1.1.0` | `db6f9dc1140fd582487422cc23596224bfda2e711ff009335a7da07227b6fbe5` | 4,202 | `APPROVED` (2026-10-08) | `PASSED` | `DRAFT` | `ACTIVE` | 6 | 6 | `openai/text-embedding-3-small/1536` | **Live Indexing thành công (20/10/2026 02:03:33)**. Version ID: `1e50bba8...`, Job ID: `0f23aac7...` (SUCCEEDED, 1 attempt). Sẵn sàng phục vụ retrieval. |
| **5** | `WHO-PA-SB-2020-VI-06` | `WHO-PA-SB-2020` | `1.1.0` | `630802d505124da0fa395cc939977ed4278ee2297dfcac9cdde7fce6eb49fdd0` | 2,986 | `APPROVED` (2026-10-08) | `PASSED` | `DRAFT` | `ACTIVE` | 5 | 5 | `openai/text-embedding-3-small/1536` | **Live Indexing thành công (20/10/2026 02:03:33)**. Version ID: `0ab7a6e5...`, Job ID: `7b175e1b...` (SUCCEEDED, 1 attempt). Sẵn sàng phục vụ retrieval. |
| **6** | `NIDDK-WEIGHT-FACTORS-2023-VI-01` | `NIDDK-WEIGHT-FACTORS-2023` | `1.1.0` | `49a628a6f06a20c0f85599542e8a4592ec3087a7d44fb64bb940d93de760cd90` | 1,678 | `APPROVED` (2026-10-08) | `PASSED` | `DRAFT` | `ACTIVE` | 1 | 1 | `openai/text-embedding-3-small/1536` | **Live Indexing thành công (20/10/2026 02:03:33)**. Version ID: `a2acab1c...`, Job ID: `9b927afb...` (SUCCEEDED, 1 attempt). Sẵn sàng phục vụ retrieval. |
| **7** | `NIDDK-WEIGHT-FACTORS-2023-VI-02` | `NIDDK-WEIGHT-FACTORS-2023` | `1.1.0` | `cd5f102f182bfd2eea2a7ebf4f6b9aa91e6ecc894c8f9197756d2f9a4aa7c3d5` | 1,952 | `APPROVED` (2026-10-08) | `PASSED` | `DRAFT` | `ACTIVE` | 1 | 1 | `openai/text-embedding-3-small/1536` | **Live Indexing thành công (20/10/2026 02:03:33)**. Version ID: `f6ee0da4...`, Job ID: `36a2c654...` (SUCCEEDED, 1 attempt). Sẵn sàng phục vụ retrieval. |
| **8** | `NIDDK-WEIGHT-FACTORS-2023-VI-03` | `NIDDK-WEIGHT-FACTORS-2023` | `1.1.0` | `cee3300127db2e339b2ceccf0e73a6c4e81172cb2db02942e332af3d0c9669ec` | 1,687 | `APPROVED` (2026-10-08) | `PASSED` | `DRAFT` | `ACTIVE` | 1 | 1 | `openai/text-embedding-3-small/1536` | **Live Indexing thành công (20/10/2026 02:03:33)**. Version ID: `e8a30907...`, Job ID: `dc8a419f...` (SUCCEEDED, 1 attempt). Sẵn sàng phục vụ retrieval. |
| **9** | `NIDDK-WEIGHT-FACTORS-2023-VI-04` | `NIDDK-WEIGHT-FACTORS-2023` | `1.1.0` | `87e4a99998a3fcea44d96bcda218b27214cba3dabc18e008fb1cd8bc618ef82f` | 1,951 | `APPROVED` (2026-10-08) | `PASSED` | `DRAFT` | `ACTIVE` | 1 | 1 | `openai/text-embedding-3-small/1536` | **Live Indexing thành công (20/10/2026 02:03:33)**. Version ID: `81c4984d...`, Job ID: `a4b44183...` (SUCCEEDED, 1 attempt). Sẵn sàng phục vụ retrieval. |
| **10** | `NIDDK-WEIGHT-FACTORS-2023-VI-05` | `NIDDK-WEIGHT-FACTORS-2023` | `1.1.0` | `322a120ac8e5470fbd9c0ced6c0967b89b38cd758f37120e193a0812e4edac15` | 2,107 | `APPROVED` (2026-10-08) | `HELD_AS_DRAFT` | `DRAFT` | *Không nạp* | — | 0 | — | **KHÔNG ĐỦ ĐIỀU KIỆN (Guardrail an toàn y tế)**. Giữ nguyên HELD_AS_DRAFT, cấm index. 0 dòng trong DB runtime. |
| **11** | `NIDDK-WEIGHT-FACTORS-2023-VI-06` | `NIDDK-WEIGHT-FACTORS-2023` | `1.1.0` | `40c179c3fe3a1b3c2d937d011f1f3c9b49c8c7c7648d87c2f45a54c31f63996c` | 1,854 | `APPROVED` (2026-10-08) | `HELD_AS_DRAFT` | `DRAFT` | *Không nạp* | — | 0 | — | **KHÔNG ĐỦ ĐIỀU KIỆN (Guardrail an toàn y tế)**. Giữ nguyên HELD_AS_DRAFT, cấm index. 0 dòng trong DB runtime. |

### 2.3. Bảng Đối Chiếu Job Indexing Thực Tế Trong Database Runtime (`fitness.knowledge_processing_runs`)

| STT | Document Code | Knowledge Version ID | Operation Key | Processing Run Job ID | Trạng Thái Cuối | Số Lần Thử (Attempts) | Thời Điểm Hoàn Thành (UTC) | Ghi Chú Nghiệm Thu |
| :---: | :--- | :--- | :--- | :--- | :---: | :---: | :--- | :--- |
| 1 | `WHO-PA-SB-2020-VI-01` | `c0a1f8b2-8932-49e3-a786-db2836706e55` | `INDEX:c0a1f8b2...` | `44d538f4-d37d-4249-b895-d017485ac87a` | `SUCCEEDED` | 2 | 2026-10-19 10:39:01 | Tái sử dụng nguyên vẹn; không reindex |
| 2 | `WHO-PA-SB-2020-VI-02` | `4984d35e-9c15-4be0-b3ad-a3b6bc7c3d45` | `INDEX:4984d35e...` | `1f038a63-e032-4be9-b1fc-7019fbc8efc1` | `SUCCEEDED` | 1 | 2026-10-19 18:53:09 | Pilot thành công; tái sử dụng |
| 3 | `WHO-PA-SB-2020-VI-03` | `de16472d-abbb-4714-bd04-d7261d3c3edd` | `INDEX:de16472d...` | `1cac334e-01f3-4255-b19f-56e7c23a1d2a` | `SUCCEEDED` | 1 | 2026-10-19 19:03:30 | Expanded index thành công |
| 4 | `WHO-PA-SB-2020-VI-05` | `1e50bba8-b1db-45f9-b444-ae60961f17e9` | `INDEX:1e50bba8...` | `0f23aac7-8e49-45a4-a93f-5efb7241f471` | `SUCCEEDED` | 1 | 2026-10-19 19:03:31 | Expanded index thành công |
| 5 | `WHO-PA-SB-2020-VI-06` | `0ab7a6e5-f1a8-44b4-aad2-cf7787ed69c4` | `INDEX:0ab7a6e5...` | `7b175e1b-2ba5-44dc-baa9-af8c46c94c71` | `SUCCEEDED` | 1 | 2026-10-19 19:03:31 | Expanded index thành công |
| 6 | `NIDDK-WEIGHT-FACTORS-2023-VI-01` | `a2acab1c-3295-4b2b-bca8-80a48967fec3` | `INDEX:a2acab1c...` | `9b927afb-afaf-4799-b075-a30115b165ed` | `SUCCEEDED` | 1 | 2026-10-19 19:03:32 | Expanded index thành công |
| 7 | `NIDDK-WEIGHT-FACTORS-2023-VI-02` | `f6ee0da4-34fd-473d-b531-fb162e656a13` | `INDEX:f6ee0da4...` | `36a2c654-b9a4-4668-a57e-f96a03b94fb6` | `SUCCEEDED` | 1 | 2026-10-19 19:03:32 | Expanded index thành công |
| 8 | `NIDDK-WEIGHT-FACTORS-2023-VI-03` | `e8a30907-ca61-4096-afa7-cb60fd50d12f` | `INDEX:e8a30907...` | `dc8a419f-ac1a-45b0-bc62-993a3c2a7d9f` | `SUCCEEDED` | 1 | 2026-10-19 19:03:32 | Expanded index thành công |
| 9 | `NIDDK-WEIGHT-FACTORS-2023-VI-04` | `81c4984d-df7c-45ed-9e58-0d0b395d8588` | `INDEX:81c4984d...` | `a4b44183-631e-4933-8f51-e6b8a57d9387` | `SUCCEEDED` | 1 | 2026-10-19 19:03:33 | Expanded index thành công |

- **Tổng hợp hàng đợi (Backlog):** `QUEUED = 0`, `RUNNING = 0`, `FAILED = 0`, `SUCCEEDED = 9`. Không có tác vụ treo.
- **Tổng hợp chunks & vectors:** Đúng **29 chunks** và **29 vectors** 1536 chiều, model space `openai/text-embedding-3-small/1536`.
- **Bảo toàn guardrail:** `NIDDK-WEIGHT-FACTORS-2023-VI-05` và `VI-06` có 0 dòng trong database, giữ nguyên `HELD_AS_DRAFT`.

---

## 3. Tính Toán Đo Đạc Chunks và Hạch Toán Chi Phí Dự Kiến (DEC-A13-03)

### 3.1. Kết Quả Đo Đạc Thực Tế từ `KnowledgeChunker` (Qua Kiểm Thử Tự Động)
Kết quả chạy từ `EligibleCorpusKnowledgeChunkingOfflineTest`:
- **WHO-01:** 5 chunks, 622 heuristic tokens *(Đã index xong trên testbed)*.
- **8 tài liệu đủ điều kiện còn lại:**
  - `WHO-02`: 5 chunks, 1,125 heuristic tokens
  - `WHO-03`: 4 chunks, 496 heuristic tokens
  - `WHO-05`: 6 chunks, 845 heuristic tokens
  - `WHO-06`: 5 chunks, 590 heuristic tokens
  - `NIDDK-01`: 1 chunk, 316 heuristic tokens
  - `NIDDK-02`: 1 chunk, 371 heuristic tokens
  - `NIDDK-03`: 1 chunk, 323 heuristic tokens
  - `NIDDK-04`: 1 chunk, 379 heuristic tokens
  - **Tiểu kế 8 tài liệu còn lại:** **24 chunks**, **4,445 heuristic tokens**.
- **Tổng toàn bộ 9 tài liệu đủ điều kiện:** **29 chunks**, **5,067 heuristic tokens**, tổng dung lượng văn bản: **26,041 bytes**.

### 3.2. Ước Tính Chi Phí API OpenAI (Đơn giá: $0.02 / 1,000,000 tokens)
- **Chi phí đã sử dụng cho WHO-01:** 5 vectors index + 2 queries ≈ 662 tokens ≈ **~$0.000013 USD**.
- **Chi phí ước tính vector hóa 8 tài liệu còn lại (24 chunks):**
  $$\text{Chi phí ước tính} = 4,445 \times \$0.00000002 \approx \mathbf{\$0.0000889\text{ USD}}\ (\sim\$0.00009\text{ USD})$$
- **Chi phí ước tính toàn bộ 29 chunks (nếu index lại từ đầu):**
  $$5,067 \times \$0.00000002 \approx \mathbf{\$0.00010134\text{ USD}}\ (\sim\$0.00010\text{ USD})$$
- **Phân biệt rành mạch:** Số liệu trên là **ước tính toán học heuristic** dựa trên bộ đếm từ tiếng Việt của Java Chunker. Số cước thực tế được tính căn cứ theo `usage.total_tokens` (tokenizer BPE `cl100k_base`) trả về trong response thật của OpenAI.
- **Tuân thủ ngân sách DEC-A13-03:** Chi phí ~$0.00009 USD hoàn toàn nằm dưới ngưỡng cảnh báo ($5.00) và ngưỡng chặn cứng ($10.00).

---

## 4. Kiểm Thử và Kiến Trúc Bảo Vệ

### 4.1. Bảng Tổng Hợp Kiểm Thử Đã Chạy Thành Công (100% PASS)

| Bộ Kiểm Thử | Công cụ / Lệnh | Số Test | Kết Quả | Ý Nghĩa / Mục Tiêu |
| :--- | :--- | :---: | :---: | :--- |
| **Knowledge Staging Validation** | `npm run validate:knowledge` | 1 suite | **PASSED** | 4 nguồn, 73 bài, 114 candidate cases; ACTIVE=0. 11 derivatives (9 PASSED, 2 HELD_AS_DRAFT). |
| **Knowledge Corpus Integrity** | `npm run test:knowledge` | 25 tests | **PASSED (25/25)** | Xác thực SHA-256 snapshots, autocrlf, tính bất biến DRAFT, không ingestion benchmark. |
| **OpenAPI & JSON Schemas** | `npm run validate:contracts` | 2 suites | **PASSED** | Linting OpenAPI (`openapi.yaml`) + Ajv 2020 trên 5 schemas và 20 contract fixtures. |
| **Contract Invariants Suite** | `npm run test:contracts` | 21 tests | **PASSED (21/21)** | Xác thực fail-closed, kiểm soát envelope, trích dẫn bắt buộc, cấm biến F01 thành proposal. |
| **A-49 Evaluation Runner Tests** | `npm run test:eval` | 22 tests | **PASSED (22/22)** | Xác thực composite key, schema Ajv fail-closed, timeout stream, strict denominators, phân lập lỗi. |
| **Backend Offline Embedding** | `mvn test -Dtest=Who01KnowledgeChunkingOfflineTest,OpenAiEmbeddingAdapterTest,EmbeddingProviderSelectionIntegrationTest,OpenAiEmbeddingContextRegressionTest` | 37 tests | **PASSED (37/37)** | Unit & Spring Context offline: provider selection, mock REST server, retry 429, deadline 15s. |
| **Eligible Corpus Chunking Offline (MỚI)** | `mvn test -Dtest=EligibleCorpusKnowledgeChunkingOfflineTest` | 3 tests | **PASSED (3/3)** | Xác minh SHA-256 cả 11 derivatives; guardrail NIDDK 05/06; chunking 9 bài (29 chunks, 5067 tokens). |
| **Eligible Corpus Testbed Isolation (MỚI)** | `mvn test -Dtest=EligibleCorpusRealEmbeddingTestbedIntegrationTest` | 1 test | **PASSED (SKIPPED)** | Cô lập CI: tự động bỏ qua khi không có `RUN_LIVE_OPENAI_TESTS=true`, bảo vệ 100% CI runner. |

### 4.2. Mã Nguồn Mới Được Bổ Sung
1. [`EligibleCorpusKnowledgeChunkingOfflineTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/content/adapter/out/embedding/EligibleCorpusKnowledgeChunkingOfflineTest.java): Kiểm thử đơn vị POJO chạy tự động trong CI, xác thực hash 11 bài, chốt 29 chunks cho 9 bài đủ điều kiện, khóa cứng NIDDK 05/06 ở `HELD_AS_DRAFT`.
2. [`EligibleCorpusRealEmbeddingTestbedIntegrationTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/content/adapter/out/embedding/EligibleCorpusRealEmbeddingTestbedIntegrationTest.java): Kiểm thử tích hợp testbed có kiểm soát, sẵn sàng thực thi nạp bundle qua `ImportKnowledgeCorpusUseCase`, xuất bản qua `ContentLifecycleService`, kích hoạt outbox worker từng bước và kiểm tra semantic retrieval cho cả 2 nguồn WHO và NIDDK khi có opt-in credentials.

---

## 5. Bằng Chứng Runtime Coverage Snapshot và Phân Bổ Ca Đánh Giá A-49

### 5.1. Runtime Verified Coverage Snapshot (`coverage-snapshot-testbed-9docs.json`)
Dựa trên kết quả truy vấn đọc trực tiếp từ PostgreSQL `localhost:5433` (schema `fitness`), tệp snapshot độ phủ xác minh đã được tạo tại [`data/evaluation/coverage-snapshot-testbed-9docs.json`](../../data/evaluation/coverage-snapshot-testbed-9docs.json):
- **Snapshot ID:** `snap-local-testbed-20261020-020333`
- **Thời điểm xác minh:** `2026-10-20T02:03:33+07:00`
- **Môi trường:** `local_testbed`
- **Cấp độ tin cậy (Tier):** `VERIFIED`
- **Nguồn bằng chứng:** `LOCAL_POSTGRESQL_RUNTIME_AUDIT`
- **Quy mô ngữ liệu active:** 9 tài liệu (`WHO-01, 02, 03, 05, 06` và `NIDDK-01, 02, 03, 04`), tất cả đều có `status = 'ACTIVE'` và `index_ready = true`.

### 5.2. Phân Tích Phân Bổ Độ Phủ 42 Test Cases Thuộc 2 Nguồn Đang Active
Tập candidate manifest gồm **114 cases**. Khi lọc `--source=WHO-PA-SB-2020,NIDDK-WEIGHT-FACTORS-2023`, có **42 test cases** được đưa vào đánh giá (72 cases còn lại thuộc ISSN [30] và NIH-ODS [42] chưa nạp vào testbed):

1. **Full Coverage (21 cases):**
   - 11 cases nguồn WHO: `WHO-PA-E01, E02, E05, E06, E07, E08, E14, E15, E17, E18, E21`.
   - 10 cases nguồn NIDDK: `NW-E01, NW-E02, NW-E03, NW-E04, NW-E05, NW-E06, NW-E07, NW-E08, NW-E09, NW-E10`.
   - *Đặc điểm:* Toàn bộ tài liệu kỳ vọng đều đang ACTIVE và index-ready trong database. Đây là mẫu số chính thức để tính các chỉ số chất lượng retrieval (`Hit@k`, `Recall@k`, `MRR@k`).
   - *Ghi chú đối chiếu:* Trong văn bản tóm tắt trước đó, danh sách WHO vô tình được ghi vắn tắt theo dải `E01..E11`. Tuy nhiên khi đối chiếu trực tiếp cấu trúc manifest, `E03, E04, E09, E10, E11` thực chất kỳ vọng các tài liệu chưa phái sinh (`VI-04, 07, 08, 09`) nên thuộc nhóm No Coverage; trong khi `E14, E15, E17, E18, E21` kỳ vọng các bài đã active (`VI-02, 03, 05, 06`) nên mới là Full Coverage. Tổng số case Full Coverage vẫn đúng tuyệt đối 21 cases.

2. **Partial Coverage (5 cases):**
   - 5 cases nguồn WHO: `WHO-PA-E12, E16, E19, E23, E24`.
   - *Đặc điểm:* Chỉ một phần tài liệu kỳ vọng đang active trong 9 bài đã index (ví dụ: `E12` kỳ vọng `VI-02` [active] và `VI-11` [chưa phái sinh]; `E16` kỳ vọng `VI-01` [active] và `VI-09` [chưa phái sinh]; `E19` và `E23` kỳ vọng `VI-05` [active] và `VI-16` [chưa phái sinh]; `E24` kỳ vọng `VI-01` [active] và `VI-12` [chưa phái sinh]). Được tách riêng, không tính vào mẫu số Full Coverage.

3. **Zero Coverage (16 cases):**
   - 8 cases nguồn NIDDK:
     - 6 cases guardrail y tế: `NW-E11, NW-E12, NW-E13, NW-E14, NW-E15, NW-E16` (kỳ vọng tài liệu VI-05/06 đang bị khóa cứng ở `HELD_AS_DRAFT`).
     - 2 cases phái sinh chưa xuất bản: `NW-E17, NW-E18` (kỳ vọng VI-07, VI-08).
   - 8 cases nguồn WHO: `WHO-PA-E03, E04, E09, E10, E11, E13, E20, E22` (kỳ vọng các bài VI-04, 07, 08, 09, 12, 15 chưa phái sinh).

---

## 6. Hướng Dẫn Vận Hành Development Baseline A-49

### 6.1. Trạng Thái Tiền Đề Nghiệm Thu (Prerequisites Status)
- [x] **A-27 Live Indexing:** 9/9 tài liệu đủ điều kiện đã index thành công vào PostgreSQL local với 29 vectors 1536 chiều thật.
- [x] **Database Audit:** 9 jobs `SUCCEEDED`, 0 queued, 0 running, 0 failed.
- [x] **Safety Guardrails:** NIDDK-05/06 giữ nguyên `HELD_AS_DRAFT`, 0 dòng trong DB.
- [x] **Verified Coverage Snapshot:** Đã sinh tệp `data/evaluation/coverage-snapshot-testbed-9docs.json` đạt tier `VERIFIED`.
- [x] **Runner Multi-Source Support:** `tools/run-f01-evaluation.mjs` đã hỗ trợ lọc `--source=WHO-PA-SB-2020,NIDDK-WEIGHT-FACTORS-2023`.

### 6.2. Script Thực Thi Chuẩn Hóa (`tools/run-f01-retrieval-baseline.ps1`)

Kịch bản PowerShell hoàn chỉnh đã được đóng gói và kiểm thử an toàn tại [`tools/run-f01-retrieval-baseline.ps1`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/tools/run-f01-retrieval-baseline.ps1). Script hỗ trợ hai chế độ vận hành:

1. **Chế độ Live Execution (Toàn trình):**
   - Kiểm tra branch bảo vệ `feat/p2-a27-corpus-retrieval-baseline`.
   - Kiểm tra xung đột cổng 8080 trước khi khởi động.
   - Nhập ẩn API key qua SecureString / BSTR (không lộ CLI, không log disk).
   - Thiết lập môi trường cô lập: `APP_SCHEDULING_ENABLED=false`, `ATTENTION_REVIEW_CRON='-'`, DB port 5433.
   - Khởi động backend ngầm, theo dõi liveness và HTTP readiness `/actuator/health`.
   - Thực hiện auth check (query rỗng) và smoke query kiểm chứng pipeline embedding/retrieval.
   - Tạo thư mục output riêng biệt `reports/evaluation/baselines/baseline-run-<timestamp>`.
   - Chạy runner A-49 trên 2 nguồn active (`WHO-PA-SB-2020,NIDDK-WEIGHT-FACTORS-2023`).
   - Tự động nghiệm thu artifacts và dọn dẹp an toàn trong khối `finally`.

2. **Chế độ Offline Audit & Verification (`-VerifyRunDir`):**
   - Cho phép audit, đối chiếu và in kết quả nghiệm thu từ một thư mục artifacts có sẵn mà không cần nhập API key, không khởi động backend, không chạy npm runner và không gọi OpenAI API.
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\tools\run-f01-retrieval-baseline.ps1 -VerifyRunDir "reports\evaluation\baselines\baseline-run-20261020-064323-684\run-20261019234346-dcc3c0"
   ```

---

## 7. Kết Quả Thực Thi Development Baseline A-49 (Nghiệm Thu Chính Thức)

### 7.1. Thông Tin Phiên Chạy và Nguồn Dữ Liệu
- **Run ID:** `run-20261019234346-dcc3c0`
- **Thời điểm thực thi:** `2026-10-19T23:43:46.639Z` (UTC) / `2026-10-20T06:43:46` (GMT+07)
- **Thư mục Artifacts:** `reports/evaluation/baselines/baseline-run-20261020-064323-684/run-20261019234346-dcc3c0`
- **Branch:** `feat/p2-a27-corpus-retrieval-baseline`
- **Git SHA:** `9279057cbf45d21b3b6677f8f4a99a6785b8e6a8`
- **Bộ dữ liệu đánh giá:** F01 General Fitness Knowledge Candidate Dataset v1.0.0-candidate (Hash: `0bdb6c6ebb24b4fc50c2104dc2315309cb7eb667e40e09639b46f40bec84b970`)
- **Phạm vi lọc:** `WHO-PA-SB-2020` (24 ca) + `NIDDK-WEIGHT-FACTORS-2023` (18 ca) = **42 ca candidate**
- **Trạng thái Holdout:** `NO_INDEPENDENT_HOLDOUT_YET` (Baseline phát triển sơ bộ trên candidate dataset; chưa áp dụng holdout độc lập)

### 7.2. Phân Bổ Ca Đánh Giá và Tỷ Lệ Bao Phủ (Coverage Breakdown)
Theo snapshot runtime xác minh (`data/evaluation/coverage-snapshot-testbed-9docs.json`), trong tổng số 42 ca kiểm thử:
- **Full Coverage:** **21 ca** (Toàn bộ tài liệu kỳ vọng đều đang ACTIVE trong testbed).
- **Partial Coverage:** **5 ca** (Ít nhất một tài liệu kỳ vọng đang ACTIVE trong testbed).
- **No Coverage:** **16 ca** (Không có tài liệu kỳ vọng nào ACTIVE; loại trừ hợp lệ với lý do `VERIFIED_CORPUS_COVERAGE_MISSING`).
- **Coverage Unknown:** **0 ca** (Snapshot runtime đạt tier `VERIFIED` 100%).
- **Mẫu số đánh giá retrieval (`evaluable_retrieval_cases`):** **26 ca** (Full Coverage 21 + Partial Coverage 5).

### 7.3. Kết Quả Chỉ Số Chất Lượng Retrieval (A-49 Standard vs. Full-Coverage Only)

| Chỉ Số Đánh Giá | Baseline Chính Thức A-49<br>*(Evaluable Cases: Full + Partial, N=26)* | Phân Tích Bổ Sung: Full Coverage Only<br>*(Đủ 100% tài liệu kỳ vọng, N=21)* | Nhóm Partial Coverage Only<br>*(Thiếu một phần tài liệu kỳ vọng, N=5)* |
|---|:---:|:---:|:---:|
| **Trạng thái Metric** | **`CALCULATED`** | **`CALCULATED`** | **`CALCULATED`** |
| **Mẫu số (Denominator)** | **26** | **21** | **5** |
| **Document Hit@5** | **0.8846** (23 / 26 hits) | **0.9524** (20 / 21 hits) | **0.6000** (3 / 5 hits) |
| **Document Recall@5** | **0.8077** (sum_recall = 21.0000 / 26) | **0.9286** (sum_recall = 19.5000 / 21) | **0.3000** (sum_recall = 1.5000 / 5) |
| **MRR@5** | **0.6744** (sum_mrr = 17.5333 / 26) | **0.7857** (sum_mrr = 16.5000 / 21) | **0.2067** (sum_mrr = 1.0333 / 5) |
| **Lỗi hạ tầng (Infrastructure Errors)** | **0** | **0** | **0** |
| **Lỗi thực thi (Execution Failures)** | **0** | **0** | **0** |
| **Vi phạm Schema (Schema Violations)** | **0** | **0** | **0** |

### 7.4. Phân Tích Chi Tiết 5 Ca Partial Coverage và Tính Toàn Vẹn Của Recall

Cơ chế tính toán A-49 bảo toàn tính trung thực của mẫu số Recall:
$$\text{Recall} = \frac{|\text{Exact Matched Expected Doc IDs}|}{|\text{All Expected Doc IDs}|}$$
Mẫu số của từng ca luôn là **toàn bộ số tài liệu kỳ vọng** của ca đó, không bị cắt giảm theo số tài liệu đang active. Do đó, các ca Partial Coverage bị phạt điểm trực tiếp vì tài liệu còn thiếu:
1. `WHO-PA-SB-2020:E12`: Kỳ vọng `[VI-02, VI-11]`. Chỉ `VI-02` active. Thu hồi `VI-02` tại Rank 3. Hit = 1, Recall = 0.5000 (1/2), MRR = 0.3333.
2. `WHO-PA-SB-2020:E16`: Kỳ vọng `[VI-01, VI-09]`. Chỉ `VI-01` active. Thu hồi `VI-01` tại Rank 5. Hit = 1, Recall = 0.5000 (1/2), MRR = 0.2000.
3. `WHO-PA-SB-2020:E19`: Kỳ vọng `[VI-05, VI-16]`. Chỉ `VI-05` active. Không thu hồi trúng `VI-05` trong top 5. Hit = 0, Recall = 0.0000 (0/2), MRR = 0.
4. `WHO-PA-SB-2020:E23`: Kỳ vọng `[VI-05, VI-16]`. Chỉ `VI-05` active. Thu hồi `VI-05` tại Rank 2. Hit = 1, Recall = 0.5000 (1/2), MRR = 0.5000.
5. `WHO-PA-SB-2020:E24`: Kỳ vọng `[VI-01, VI-12]`. Chỉ `VI-01` active. Không thu hồi trúng `VI-01` trong top 5. Hit = 0, Recall = 0.0000 (0/2), MRR = 0.

### 7.5. Phân Tích Ca Bỏ Lỡ Trong Nhóm Full Coverage (1/21 Ca)
Trong 21 ca Full Coverage, chỉ có đúng **1 ca** không đạt Hit@5:
- **Case Key:** `WHO-PA-SB-2020:E15` ("Trên 300 phút vừa bị WHO cấm?")
- **Tài liệu kỳ vọng:** `WHO-PA-SB-2020-VI-05` (Khuyến nghị vận động ở tuổi 18–64, v1.1.0).
- **Kết quả thu hồi thực tế:** Top 5 chunk trả về thuộc `WHO-PA-SB-2020-VI-01` (Rank 1, 3), `WHO-PA-SB-2020-VI-06` (Rank 2, 5) và `WHO-PA-SB-2020-VI-03` (Rank 4). Cụ thể nội dung VI-06 (hành vi ít vận động) và VI-01 (phạm vi khuyến nghị) có điểm tương đồng ngữ nghĩa cao hơn VI-05 đối với từ khóa câu hỏi này. Đây là hành vi retrieval thực tế của mô hình embedding, cần lưu ý khi thiết kế reranker hoặc query expansion trong các mốc tiếp theo.

### 7.6. Báo Cáo Minh Bạch Hạn Chế Metadata (Model Space Limitation)
- **Tình trạng:** Trong file `run-metadata.json`, trường `model_space.model_space` hiển thị giá trị `"UNKNOWN"`.
- **Nguyên nhân kỹ thuật:** Endpoint `POST /internal/v1/knowledge/retrieval` tại thời điểm chạy baseline không đính kèm header hoặc thuộc tính phản hồi `modelSpace`, đồng thời tham số CLI `--model-space` không được truyền tường minh vào lệnh thực thi npm.
- **Quy tắc toàn vẹn:** File artifact gốc `run-metadata.json` được bảo toàn nguyên trạng, không can thiệp hồi tố.
- **Bằng chứng đối chiếu:** Bằng chứng database PostgreSQL local đã được nghiệm thu độc lập tại Mục 2.1 và 2.2: toàn bộ 29 vector active trong bảng `fitness.knowledge_embeddings` đều thuộc phiên bản `e1000000-0000-0000-0000-000000000002` (`openai/text-embedding-3-small/1536`).
### 7.7. Bản Chất Của Baseline và Phạm Vi Giới Hạn Nghiệm Thu
1. **Phân loại Baseline:** Đây là **development retrieval baseline** được đo đạc nội bộ trên môi trường testbed cô lập với candidate dataset v1.0.0-candidate (42 cases).
2. **Không phải Independent Holdout:** Trạng thái trong metadata là NO_INDEPENDENT_HOLDOUT_YET. Bộ dữ liệu đánh giá chưa trải qua quá trình holdout độc lập; không được coi đây là chứng chỉ chất lượng cho môi trường sản xuất (production).
3. **Giới hạn phạm vi (Retrieval Only):** Baseline này chỉ nghiệm thu năng lực **truy hồi tài liệu (retrieval)**. Kết quả **hoàn toàn chưa đánh giá năng lực sinh ngôn ngữ (generation)**, tính trung thực câu chữ (faithfulness) hay mức độ tuân thủ trích dẫn của mô hình ngôn ngữ (LLM).
4. **Bảo toàn dữ liệu kiểm thử (Không tự ý đổi nhãn):** Ca bỏ lỡ WHO-PA-SB-2020:E15 được bảo toàn nguyên trạng và đánh giá trung thực là miss (Hit=0), không tự ý chỉnh sửa câu hỏi, nhãn ground-truth hay lược bỏ ca để làm đẹp kết quả báo cáo.

---

## 8. Đề Xuất Kế Hoạch Các Bước Tiếp Theo (Post-Handover Roadmap)

### 8.1. Diễn Tập Reindexing và Rollback Cô Lập (Quyết Định DEC-A13-05)
- **Cơ sở phê duyệt:** Theo quyết định kiến trúc đã duyệt **DEC-A13-05** về quản lý vòng đời vector embedding, khi thay đổi phiên bản tài liệu phái sinh hoặc cập nhật mô hình embedding, hệ thống cần thực hiện reindex kiểm soát hoặc rollback an toàn.
- **Kế hoạch diễn tập:** Thực hiện một kịch bản diễn tập reindex/rollback có kiểm soát trong container testbed riêng biệt, xác minh:
  * Không làm gián đoạn các vector đang phục vụ retrieval.
  * Giữ nguyên các trích dẫn lịch sử trong bảng kiểm toán AI Run.
  * Rollback về model version trước đó thành công khi có sự cố.

### 8.2. Triển Khai Adapter Sinh Nội Dung Thật A-26 (Theo Ranh Giới Kiến Trúc A-13)
- **Ranh giới trách nhiệm (Architecture Boundaries):**
  * **Spring Boot Monolith (Orchestrator):** Tiếp nhận yêu cầu từ Mobile/Admin, quản lý quyền hạn/xác thực, thực hiện retrieval ngữ liệu từ PostgreSQL, đóng gói context kèm danh sách chunks hợp lệ, điều phối gọi dịch vụ AI, xác thực schema phản hồi, kiểm toán AI Run và quản lý giao dịch dữ liệu.
  * **FastAPI Service (AI Worker):** Dịch vụ phụ trợ chuyên trách thực hiện giao tiếp với nhà cung cấp mô hình (LLM provider) để sinh câu trả lời theo đúng JSON schema và trích dẫn chuẩn hóa.
  * **Quy tắc tuyệt đối:** Mobile và Admin clients không bao giờ gọi trực tiếp đến FastAPI hay LLM provider. FastAPI và LLM không có thẩm quyền nghiệp vụ và không là nguồn chân lý.
- **Ranh giới thẩm quyền F01:**
  * Dịch vụ F01 chỉ trả về kết quả dạng INFORMATION (hoặc INSUFFICIENT_DATA, BLOCKED).
  * Tuyệt đối không sinh PROPOSAL và không cung cấp thao tác Apply.
  * Trích dẫn (citations) bắt buộc phải gắn với các chunk tài liệu đã được Spring Boot thu hồi và xác thực, không cho phép mô hình tự bịa nguồn.

