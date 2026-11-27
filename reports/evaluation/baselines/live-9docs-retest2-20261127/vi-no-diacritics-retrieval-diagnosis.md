# Chẩn đoán & Sửa Offline Retrieval Tiếng Việt Không Dấu — 27/11/2026

**Phạm vi:** `KnowledgeRetrievalService` + forward migration `V68__knowledge_lexical_folded_search.sql`  
**Trạng thái:** **OFFLINE ONLY** (đã kiểm thử trên unit tests + Testcontainers `pgvector/pgvector:pg18`; chưa chạy live end-to-end với `text-embedding-3-small`).

---

## 1. Chẩn đoán từ Artifacts & Code

- **Xác minh ablation trước (`reports/evaluation/ablations/live-9docs-ag06-ag09-20261127/`):**
  - Cả `dense.json` (`HYBRID=false`) và `hybrid.json` (`HYBRID=true`) cho **cùng hệt** `stage_ranks` và `cosineSimilarity` trên `AG02-DEV:VI-NO-DIACRITICS-01` (`VI-01#1`, `VI-01#0`, `VI-05#0`, `VI-01#3`, `VI-06#0`) — tức nhánh lexical đã **trả về 0 candidates**, khiến `ReciprocalRankFusion.fuse(dense, [])` giữ nguyên kết quả `dense`.
- **Nguyên nhân đã xác nhận:**
  1. `V66__knowledge_lexical_search.sql` chỉ lập chỉ mục `search_vector` giữ nguyên dấu tiếng Việt (`to_tsvector('simple', ...)`). Truy vấn không dấu (`"khuyen"`, `"nghi"`, `"van"`, `"dong"`, `"phut"`) không khớp token có dấu (`"khuyến"`, `"nghị"`, `"vận"`, `"động"`, `"phút"`).
  2. `KnowledgeRetrievalService` dùng `websearch_to_tsquery('simple', ?)` nối mọi từ bằng toán tử `AND` (`&`), nên chỉ cần thiếu một từ hội thoại (`"bao"`, `"nhieu"`, `"truong"`, `"thanh"`) là toàn bộ nhánh lexical trả về 0 dòng (ngay cả với câu hỏi có dấu).
- **Vị trí mất `WHO-PA-SB-2020-VI-05#1`:** Mất ngay ở bước **candidate generation** (nhánh lexical trả `0` candidates; nhánh dense xếp `VI-05#1` ngoài Top-5 với `limit=5`).

---

## 2. Giải pháp Tối thiểu Đã Áp dụng

1. **Forward migration [`V68__knowledge_lexical_folded_search.sql`](../../../../services/backend/src/main/resources/db/migration/V68__knowledge_lexical_folded_search.sql):**
   - Tạo hàm `IMMUTABLE` `fitness.fold_vi_text(input text)` dùng chuẩn `normalize(..., NFD)` + bỏ dấu tổ hợp + `translate(..., 'đĐ', 'dD')` (**không** cần extension `unaccent`).
   - Thêm cột sinh tự động `search_vector_folded` (weight `'A'` cho `heading_path`, `'B'` cho `content`) và GIN index `idx_knowledge_chunks_lexical_folded`. Không sửa `content`/`heading_path` gốc và giữ nguyên `V66`.
2. **[`KnowledgeRetrievalService.java`](../../../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/content/application/service/KnowledgeRetrievalService.java):**
   - Thêm `buildFoldedLexicalTsQuery(query)` chuẩn hóa đồng nhất NFD + `đ/Đ -> d/D` + lowercase, tách token alphanumeric `>= 2` ký tự (tối đa 24 tokens), nối `|` (`to_tsquery('simple'::regconfig, ?)`), xếp hạng theo `ts_rank_cd(c.search_vector_folded, ...)`.
   - Giữ nguyên toàn bộ guards (`ACTIVE`, `TECHNICAL_VERIFICATION_PASSED`, `deleted_at IS NULL`, `source_id IN (...)`, `embedding_model_version_id`, `index_ready=true`) và `hybridEnabled=false` mặc định.

---

## 3. Kiểm thử Offline & Ảnh hưởng

- `KnowledgeRetrievalServiceTest`: **14/14 PASS** (bao phủ có dấu, không dấu, `đ/Đ`, Unicode NFD decomposed vs NFC, tiếng Anh, mixed-language `Squat`, chuỗi chỉ có dấu câu).
- `AiKnowledgeRetrievalIntegrationTest` (Testcontainers `pgvector/pgvector:pg18`, Flyway `V1–V68`): **16/16 PASS** (xác nhận truy vấn không dấu `"WHO 2020 khuyen nghi nguoi truong thanh tap van dong bao nhieu phut moi tuan?"` với `hybridEnabled=true` tìm trúng `FOLDED-WHO-VI-05` `sectionIndex=1` ngay cả khi vector cosine đối nghịch, đồng thời vẫn chặn `DRAFT`).
- *Giới hạn:* Đây là kiểm thử **offline** với `simulated` embeddings trên Testcontainers; hiệu quả end-to-end với `text-embedding-3-small` + `gpt-4o-mini-2024-07-18` cần xác minh live.

---

## 4. Kế hoạch Xác minh Live Tối thiểu (Chưa thực hiện)

- **Cấu hình:** `AI_RETRIEVAL_HYBRID_ENABLED=true`, `text-embedding-3-small` (`1536d`), `gpt-4o-mini-2024-07-18`, `max_completion_tokens=800`, `RAG_RERANK_ENABLED=false`.
- **Phạm vi đối chứng tối thiểu (4 ca retrieval + 2 ca `public-f01`):**
  - *Retrieval (`--mode=retrieval`, so sánh `HYBRID=false` vs `HYBRID=true`):* `AG02-DEV:VI-NO-DIACRITICS-01` (không dấu), `WHO-PA-SB-2020:E01` (có dấu tương đương), `AG02-DEV:MIXED-01` (mixed-language), `AG02-DEV:EMPTY-01` (đối chứng không liên quan).
  - *Public F01 (`--mode=public-f01`, chỉ khi `VI-05#1` vào Top-5 ở retrieval):* `AG02-DEV:VI-NO-DIACRITICS-01` + `AG02-DEV:EMPTY-01` (đối chứng không liên quan vẫn phải `INSUFFICIENT_DATA`).
- **Ước lượng chi phí:** Reindex 9 docs (`<= $0.00030`) + ~8 query embeddings (`< $0.00005`) + 2 LLM calls (~`$0.0012`) → **tổng `~$0.0016`** (tích lũy dự kiến `~$0.0136 / $0.50`).
