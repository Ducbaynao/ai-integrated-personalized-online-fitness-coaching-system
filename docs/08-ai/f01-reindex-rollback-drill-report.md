# Báo Cáo Nghiệm Thu Diễn Tập Reindex / Rollback F01 (DEC-A13-05)

> **Mã công việc:** F01 Reindex & Rollback Runtime Verification Drill
> **Cơ sở quyết định:** [docs/08-ai/f01-a13-provider-decision.md](f01-a13-provider-decision.md) (Quyết định DEC-A13-05)
> **Tài liệu vận hành kèm theo:** [docs/08-ai/f01-reindex-rollback-runbook.md](f01-reindex-rollback-runbook.md)
> **Công cụ tự động hóa:** [`tools/f01-reindex-rollback-drill.ps1`](../../tools/f01-reindex-rollback-drill.ps1)
> **Test suite kiểm chứng:** [`F01ReindexRollbackDrillIntegrationTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/content/adapter/out/embedding/F01ReindexRollbackDrillIntegrationTest.java)
> **Thời điểm thực hiện lịch sử:** 2026-10-21 (Đã merge qua PR #95 tại commit `3a2d484518c6ddf6e64da65e26df1140be015e0d`)
> **Trạng thái:** **PASSED (5/5 Kịch bản được kiểm chứng thành công bằng runtime evidence)**
> **Lưu ý hồ sơ:** Đây là báo cáo nghiệm thu mốc lịch sử ban đầu tại thời điểm PR #95. Các cải tiến rào chắn an toàn, xử lý trùng tên tài nguyên, kiểm tra cấu trúc JDBC, khôi phục biến môi trường và đo lường thời gian động được bổ sung tiếp nối trên nhánh fix/f01-rollback-drill-safety.

---

## 1. Thông Tin Baseline Repository và Quản Lý Nhánh

- **Repository:** `ai-integrated-personalized-online-fitness-coaching-system`.
- **Remote `origin`:** `https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system.git`.
- **Base commit tham chiếu lịch sử:** `1f01da75bd83de3f37a6987b04264f805be75a20` (Merge PR #94 trên `main`).
- **Nhánh làm việc riêng:** `chore/f01-reindex-rollback-drill` (tạo trực tiếp từ `main` commit `1f01da75bd83de3f37a6987b04264f805be75a20`).
- **Trạng thái Working Tree khi nghiệm thu mốc PR #95:** Đã được kiểm chứng và đóng gói đầy đủ.
- **Bảo toàn Testbed gốc và Baseline gốc:**
  - Container testbed gốc `digital-fitness-postgres` (port 5433) được giữ nguyên vẹn (ở trạng thái Exited/không tác động trong suốt quá trình diễn tập).
  - Bộ artifact baseline gốc tại `reports/evaluation/baselines/baseline-run-20261020-064323-684/` không bị sửa đổi hồi tố.
  - Corpus 9 tài liệu đủ điều kiện, 29 chunks/vectors và guardrail `HELD_AS_DRAFT` (NIDDK 05/06) được bảo toàn tuyệt đối.

---

## 2. Môi Trường Cô Lập và Cấu Hình Diễn Tập

Diễn tập được thực hiện độc lập qua 2 tầng môi trường cô lập:

### 2.1. Tầng Container Độc Lập Chuyên Biệt (Port 5435)
- **Tên container:** `digital-fitness-postgres-drill`
- **Docker network riêng:** `digital-fitness-drill-net`
- **Port mapping:** `5435:5432` (hoàn toàn tách biệt với port 5432 và port 5433).
- **Database / Schema:** `digital_fitness_drill` / `fitness` (search_path: `fitness, public`).
- **Database user:** `fitness_drill_app`
- **Flyway Migrations:** 44/44 migrations (V1 đến V44) được áp dụng thành công bằng container `flyway/flyway:11`.
- **Kiểm tra Pre-flight Target:** Script tự động truy vấn `SELECT current_database()` và xác nhận chính xác `digital_fitness_drill` trên port 5435 trước khi ghi bất kỳ dữ liệu nào.

### 2.2. Cấu Hình Tắt Scheduling và Cách Ly Ứng Dụng
- **Biến môi trường:**
  - `APP_SCHEDULING_ENABLED=false` (vô hiệu hóa hoàn toàn `@Scheduled` background tasks).
  - `ATTENTION_REVIEW_CRON='-'` (vô hiệu hóa attention review cron).
  - `ai.embedding.provider=simulated` (không gọi API trả phí, bảo toàn ngân sách).

### 2.3. Tầng Tự Động Hóa CI (Testcontainers)
- Test suite `F01ReindexRollbackDrillIntegrationTest` đồng thời hỗ trợ chế độ tự khởi tạo container pgvector ngẫu nhiên qua Testcontainers khi chạy trong pipeline CI chuẩn mà không phụ thuộc vào daemon port tĩnh.

---

## 3. Ma Trận Kịch Bản và Kết Quả Kiểm Chứng Thực Tế

| Kịch bản | Mục tiêu kiểm chứng | Trạng thái ban đầu & Fault Injection | Cách phát hiện & Khôi phục | Kết quả Expected vs Actual | Trạng thái |
| :---: | :--- | :--- | :--- | :--- | :---: |
| **A** | **Reindex thành công (Idempotent Reindex)** | Phiên bản WHO-01 ACTIVE, fixture 2 chunks, 2 vectors, `index_ready=true` *(Lưu ý: Fixture drill 2 chunks đại diện tối thiểu, phân biệt với WHO-01 testbed gốc gồm 5 chunks)*. Reindex với nội dung không đổi. | Outbox worker claim và commit qua Boundary 1->2->3. | **Expected:** Giữ nguyên chunk UUIDs, không trùng lặp vector, retrieval trước và sau cho điểm tương đồng bằng nhau.<br>**Actual:** Giữ nguyên 2 chunk UUIDs, 2 vectors, điểm tương đồng 0.949 khớp tuyệt đối. **Retrieval trước/sau đạt; downtime liên tục: NOT_MEASURED**. | **PASS** |
| **B1** | **Lỗi giữa chừng tại Boundary 2 (Embedding Provider Fault Injection)** | Phiên bản WHO-01 ACTIVE với vector cũ. Tiêm lỗi ném `RuntimeException("Simulated 503 Gateway Timeout")` trong `generateBatch` *(phân biệt exception giả lập ở embedding adapter với ngắt kết nối mạng HTTP thật)*. | Boundary 3 không được mở. Worker bắt exception, chuyển job `knowledge_processing_runs` sang `FAILED` *(phân biệt lifecycle job outbox với AI Run)*. Xóa lỗi và worker retry. | **Expected:** Vector cũ không bị xóa; index cũ tiếp tục phục vụ retrieval; dữ liệu lỗi không được coi là index-ready.<br>**Actual:** 1 vector cũ nguyên vẹn; truy vấn retrieval vẫn hoạt động; job FAILED có error message rõ ràng; retry thành công. **Retrieval trước/sau đạt; downtime liên tục: NOT_MEASURED**. | **PASS** |
| **B2** | **Lỗi giữa chừng tại Boundary 3 (Claim Fencing Rejection)** | Enqueue run, hook can thiệp cập nhật `claim_token` trong DB khi đang ở Boundary 2 *(mô phỏng mất lease / fencing collision; phân biệt với lỗi mạng)*. | Fencing check tại `markSuccess` từ chối commit. Spring transaction rollback toàn bộ. | **Expected:** Toàn bộ chunk/embedding dở dang bị rollback; không có bản ghi rác được commit.<br>**Actual:** 0 chunks và 0 vectors của run bị lỗi được lưu vào DB; trạng thái RUNNING thuộc về worker mới. **Retrieval trước/sau đạt; downtime liên tục: NOT_MEASURED**. | **PASS** |
| **C** | **Rollback sau khi vector cũ bị thay thế (DEC-A13-05)** | Vector Model A (`simulated`) bị xóa sạch và thay bằng vector Model B (`openai`). *(Lưu ý: Model B sử dụng test double `DeterministicEmbeddingGenerator`, không gọi API OpenAI thật)*. Cấu hình ứng dụng chuyển về Model A. | 1. Chứng minh retrieval Model A trả về 0 chunks khi chỉ đổi config.<br>2. Khôi phục qua Outbox Reindex về Model A.<br>3. Khôi phục qua Table-level Restore trong môi trường drill. | **Expected:** Đổi config đơn thuần KHÔNG khôi phục được retrieval; Reindex Outbox hoặc Snapshot restore khôi phục 100% retrieval.<br>**Actual:** Retrieval Model A trả về rỗng khi chưa reindex; sau Outbox Reindex (~0.12s) hoặc Table-level Restore (~0.03s), retrieval phục hồi điểm số ban đầu. **Mất khả dụng truy vấn Model A từ lúc thay thế đến khi reindex/restore hoàn tất; downtime liên tục: NOT_MEASURED**. | **PASS** |
| **D** | **Citation History & Provenance Integrity** | Tạo AI Run thực tế (`fitness.ai_runs`) và liên kết trích dẫn (`fitness.ai_run_knowledge_chunks`) với Chunk 0 và Chunk 2. Rút gọn nội dung tài liệu còn 1 chunk. | 1. Thử sửa content chunk đã trích dẫn.<br>2. Reindex rút ngắn nội dung.<br>3. Submit tài liệu `HELD_AS_DRAFT` *(phân biệt fixture draft với 2 tài liệu NIDDK 05/06 gốc)*. | **Expected:** Sửa chunk trích dẫn bị chặn (`ContentConflictException`); chunk 2 được giữ lại không vector (`isChunkEligible=false`); draft bị loại bỏ khỏi retrieval.<br>**Actual:** Ném `ContentConflictException`, bảo toàn tuyệt đối sourceId/version/hash/locator; Chunk 2 giữ nguyên trong `knowledge_chunks` nhưng không có vector; draft bị từ chối index. | **PASS** |

---

## 4. Kiểm Kê Runtime Trước và Sau (Inventory Snapshot)

Đối chiếu dữ liệu runtime trong database cô lập giữa các giai đoạn diễn tập:

| Hạng mục kiểm kê | Trạng thái Ban đầu (Initial) | Sau Reindex Thường (Scenario A) | Sau Thay thế Model B (Scenario C) | Sau Rollback về Model A (Scenario C) | Sau Rút gọn Nội dung (Scenario D) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Document Code** | `WHO-DRILL-SCENARIO-A` | `WHO-DRILL-SCENARIO-A` | `WHO-DRILL-SCENARIO-C` | `WHO-DRILL-SCENARIO-C` | `WHO-DRILL-SCENARIO-D` |
| **Document Status** | `ACTIVE` | `ACTIVE` | `ACTIVE` | `ACTIVE` | `ACTIVE` |
| **Technical Readiness** | `TECHNICAL_VERIFICATION_PASSED` | `TECHNICAL_VERIFICATION_PASSED` | `TECHNICAL_VERIFICATION_PASSED` | `TECHNICAL_VERIFICATION_PASSED` | `TECHNICAL_VERIFICATION_PASSED` |
| **Active Chunk Count** | 2 | 2 (Không đổi) | 1 | 1 | 1 (Đã rút ngắn từ 3) |
| **Preserved Historical Chunks** | 0 | 0 | 0 | 0 | 1 (Chunk 2 được giữ cho AI Run) |
| **Active Vector Count** | 2 | 2 (Chống trùng lặp) | 1 (Model B) | 1 (Model A) | 1 (Chỉ chunk 0 có vector) |
| **Không gian Vector (Model Space)** | `simulated/deterministic-1536/1.0.0` | `simulated/deterministic-1536/1.0.0` | `openai/text-embedding-3-small/1536` | `simulated/deterministic-1536/1.0.0` | `simulated/deterministic-1536/1.0.0` |
| **Vector Model Version ID** | `e1000000-...0001` | `e1000000-...0001` | `e1000000-...0002` | `e1000000-...0001` | `e1000000-...0001` |
| **Trạng thái Index Readiness** | `index_ready = true` | `index_ready = true` | `index_ready = true` | `index_ready = true` | `index_ready = true` |
| **Trạng thái Job Outbox** | `SUCCEEDED` (1 attempt) | `SUCCEEDED` (1 attempt) | `SUCCEEDED` (1 attempt) | `SUCCEEDED` (1 attempt) | `SUCCEEDED` (1 attempt) |
| **Retrieval Chunk Count** | 2 | 2 | 1 (under Model B) / 0 (under Model A) | 1 (under Model A) | 1 (Chunk 2 không còn trong retrieval) |

---

## 5. Đo Lường Thời Gian Phục Hồi và Khoảng Gián Đoạn (Downtime Observation)

### 5.1. Kết Quả Đo Đạc Thực Tế Trong Môi Trường Cô Lập
- **Thời gian chạy toàn bộ diễn tập tự động:** **25.03 giây** (bao gồm khởi tạo container, wait ready, Flyway migrate 44 scripts, chạy 5 integration tests, sinh artifacts và teardown).
- **Thời gian chạy riêng biệt bộ 5 bài test Spring Boot:** **16.08 giây**.
- **Đánh giá Downtime và Khả năng Phục hồi:**
  - **Tuyên bố chuẩn hóa:** **“Retrieval trước/sau đạt; downtime liên tục: NOT_MEASURED”**.
  - **Lý do:** Diễn tập kiểm chứng trạng thái nhất quán của dữ liệu retrieval trước và ngay sau thao tác (point-in-time check); chưa triển khai probe liên tục gửi tải mô phỏng liên tục trong suốt từng phần nghìn giây diễn ra giao dịch để đo lường continuous availability.
  - **Phân biệt thời gian thao tác recovery với thời gian mất khả dụng retrieval:**
    - Trong Scenario C, khi vector Model A bị xóa để nạp Model B, không gian vector Model A mất khả dụng hoàn toàn đối với truy vấn cấu hình Model A cho đến khi hoàn thành khôi phục.
    - **Thời gian thao tác phục hồi qua Outbox Reindex:** **~0.12 giây** (tính toán lại vector Model A bằng test double trong testbed cục bộ).
    - **Thời gian thao tác phục hồi qua Table-level Restore:** **~0.03 giây** (nạp lại snapshot vector vào database).
    - **Phạm vi số đo:** Các giá trị ~0.03s và ~0.12s chỉ phản ánh tốc độ xử lý I/O cục bộ trên container diễn tập đơn luồng với 1 fixture tài liệu. **Tuyệt đối không suy rộng các số đo test double này sang provider thật ngoài Internet hay môi trường production có dung lượng lớn và độ trễ mạng**.
  - **Độ trễ và Downtime khi gọi Live Provider:**
    - **Trạng thái:** **`NOT_MEASURED`** (chưa chạy live OpenAI API, 0 network requests gửi ra ngoài).

### 5.2. Bản Chất của Cơ Chế Backup / Restore Được Kiểm Chứng
- **Đối tượng restore:** Thực hiện **“table-level restore trong môi trường drill”** đối với 2 bảng:
  1. `fitness.knowledge_embeddings`: khôi phục chính xác danh sách vector 1536 chiều của version mục tiêu.
  2. `fitness.knowledge_processing_runs`: khôi phục bản ghi run thành công chứa `statistics.index_ready = true` và `embedding_space`.
- **Tính nhất quán sau restore:** Kiểm tra và xác nhận vectors, model registry (`is_active`), index readiness (`index_ready = true`) và quan hệ ràng buộc khóa ngoại tới `knowledge_chunks` hoàn toàn khớp 100%.
- **Giới hạn kiểm chứng (Scope Boundary):**
  - Đây là khôi phục mức bảng (table-level restore) trong database đang hoạt động.
  - **Không gọi đây là diễn tập disaster recovery hay full database restore**.
  - **Phần backup/restore toàn database (full instance restore từ pg_dump / WAL archiving / Point-in-time recovery) vẫn chưa được kiểm chứng trong phạm vi F01 này**.

---

## 6. Hạch Toán Chi Phí và Giới Hạn Nghiệm Thu

### 6.1. Chi Phí Thực Tế (Usage & Spend)
- **Chi phí phát sinh thực tế trong lượt diễn tập:** **$0.00 USD** (0 tokens billed trên OpenAI dashboard).
- **Lý do:** Tuân thủ quyết định DEC-A13-04 và giới hạn phạm vi, toàn bộ 5 kịch bản được thực thi hoàn toàn ngoại tuyến thông qua adapter test double và Testcontainers.
- **Dự toán chi phí lý thuyết (DEC-A13-03):** Nếu thực hiện live reindex lại bài WHO-01 với model text-embedding-3-small, chi phí ước tính là 622 tokens * $0.00000002 = $0.00001244 USD.

### 6.2. Các Giới Hạn và Đầu Việc Kế Tiếp
1. **Chưa đo đạc live network latency:** Thời gian reindex với OpenAI provider thật trên môi trường mạng ngoài cần được đo lường khi có sự phê duyệt opt-in live test có credentials.
2. **Không tuyên bố hoàn thành toàn bộ Phase 2:** Báo cáo này chỉ nghiệm thu kỹ thuật cơ chế Reindex & Rollback theo quyết định DEC-A13-05. Tính năng F01 còn cần tích hợp generation provider thật (A-26) và đánh giá chất lượng mô hình (A-52).

---

## 7. Kết Luận Nghiệm Thu

1. **Chứng minh thành công DEC-A13-05:** Hệ thống chứng minh cơ chế thay thế vector hiện tại hoạt động an toàn, có khả năng rollback và khôi phục retrieval đầy đủ sau khi vector bị thay thế hoặc gặp lỗi dở dang.
2. **Bảo toàn provenance tuyệt đối:** Tính năng chống sửa đổi (`ContentConflictException`) bảo vệ 100% tính toàn vẹn của lịch sử trích dẫn, ngăn chặn nguy cơ làm sai lệch trích dẫn của các câu trả lời AI đã phát sinh trong quá khứ.
3. **Bộ công cụ tái chạy được (Reproducible Tooling):** Đã bổ sung script PowerShell `tools/f01-reindex-rollback-drill.ps1`, test suite `F01ReindexRollbackDrillIntegrationTest.java` và Runbook `docs/08-ai/f01-reindex-rollback-runbook.md`, cho phép tái diễn tập định kỳ hoàn toàn tự động.
