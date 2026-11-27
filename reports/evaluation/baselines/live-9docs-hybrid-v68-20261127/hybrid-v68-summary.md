# Báo cáo Xác minh Live Hybrid V68 (4 Ca Retrieval Đối chứng) — 27/11/2026

**Phạm vi:** `reports/evaluation/baselines/live-9docs-hybrid-v68-20261127/` (tách biệt khỏi các lượt trước)  
**Cấu hình:** `openai/text-embedding-3-small/1536`, `top_k=5`, `candidateLimit=30` (khi `HYBRID=true`), `RAG_RERANK_ENABLED=false`, `AI_AGENT_PROPOSALS_ENABLED=false`.  
**Nâng cấp schema:** Đã xác minh nâng cấp `V67 → V68` trên DB cô lập có chunk sẵn (`0.026s`, backfill `search_vector_folded` + GIN index `idx_knowledge_chunks_lexical_folded`).  
**Corpus:** `9` `ACTIVE` docs WHO/NIDDK, `29` chunks, `29` vectors `1536d`, `index_ready=true` ([coverage-snapshot-isolated-hybrid-v68-9docs.json](coverage-snapshot-isolated-hybrid-v68-9docs.json)).

---

## 1. So sánh từng case `HYBRID=false` (Dense) vs `HYBRID=true` (Hybrid V68)

| Case ID | `HYBRID=false` (Dense Top-5) | Lexical V68 (`search_vector_folded` Top-10) | `HYBRID=true` (Fused Top-5) | Đánh giá (`VI-05#1` & độ nhiễu `OR`) |
|---|---|---|---|---|
| `AG02-DEV:VI-NO-DIACRITICS-01` | `VI-01#1` (`0.4458`), `VI-01#0`, `VI-05#0`, `VI-01#3`, `VI-06#0` | `VI-05#1` ở **#8** (`lex_rank=6.8`); đứng đầu là `VI-02#2` (`11.6`), `VI-05#4` (`10.4`), `VI-01#1` (`10.4`) | `VI-01#1`, `VI-06#3`, `VI-01#0`, `VI-06#0`, `VI-05#0` | **Chưa đạt Top-5:** `V68` đưa được `VI-05#1` vào lexical (#8), nhưng `OR` + `ts_rank_cd` ưu tiên đoạn văn dài (`VI-02#2`, `VI-05#4`) hơn bảng ngắn `VI-05#1`, nên `VI-05#1` vẫn ngoài Top-5 sau RRF. |
| `WHO-PA-SB-2020:E01` | `VI-01#1` (`0.5584`), `VI-06#3`, `VI-05#0`, **`VI-05#1` (#4)**, `VI-06#0` | `VI-02#2` (#1, `10.4`), ..., `VI-05#1` (#8, `7.0`) | `VI-01#1`, `VI-06#3`, `VI-02#2` (#3), `VI-05#4` (#4), **`VI-05#1` (#5)** | **Regression nhẹ (-1 hạng):** `OR` matching kéo `VI-02#2` (glossary) vào #3, đẩy `VI-05#1` từ #4 xuống #5. |
| `AG02-DEV:MIXED-01` | `VI-05#1`, `VI-05#4`, `VI-01#1`, `VI-01#2`, `VI-02#1` | `VI-05#4` (#1, `10.0`), `VI-02#2` (#2, `9.4`) | `VI-05#4`, `VI-05#1`, `VI-02#1`, `VI-01#1`, `VI-03#1` | Giữ `VI-05#1` & `VI-05#4` ở Top-2; vẫn thiếu `ISSN` (chưa active). |
| `AG02-DEV:EMPTY-01` | `VI-02#1`, `VI-05#1`, `VI-05#4`, `VI-05#2`, `VI-02#2` | `VI-05#4` (#1), `VI-02#2` (#2) | `VI-02#1`, `VI-05#4`, `VI-02#2`, `VI-05#1`, `VI-01#2` | Cả dense & hybrid đều trả 5 đoạn ít liên quan (`Ironman 70.3` không có trong corpus); bước quyết định `INSUFFICIENT_DATA` thuộc LLM/validator. |

---

## 2. Quyết định dừng trước LLM & Chi phí

- **Dừng trước `public-f01`:** Vì `VI-05#1` chưa vào Top-5 cho `AG02-DEV:VI-NO-DIACRITICS-01` dưới `HYBRID=true`, hệ thống **dừng trước LLM** (`0` LLM calls trong lượt này).
- **Chi phí đợt này:** `$0.000000` LLM + `<= $0.000360` embedding ước tính cận trên (reindex `29` chunks + `8` query embeddings).
- **Chi phí tích lũy toàn đợt:** **`$0.012376 / $0.500000`** (`within_budget=true`, `0` request `UNKNOWN`, [usage-and-cost-summary.json](usage-and-cost-summary.json)).

---

## 3. Chẩn đoán & Bước tiếp theo có căn cứ

- **Vì sao `OR` + `ts_rank_cd` gây nhiễu:** `ts_rank_cd` không chuẩn hóa theo độ dài chunk và không có IDF (tất cả từ `who`, `2020`, `van`, `dong`, `khuyen`, `nghi` lặp lại nhiều lần trong đoạn văn dài `VI-02#2` được chấm cao hơn bảng súc tích `VI-05#1` chỉ có 1–2 lần xuất hiện của `150`, `300`, `phut`).
- **Bước tiếp theo (offline trước khi thử live lại):**
  1. Giữ `ai.retrieval.hybrid-enabled=false` mặc định.
  2. Chuyển xếp hạng lexical từ `ts_rank_cd` thuần sang **IDF-aware / length-normalized** (ví dụ `ts_rank_cd(search_vector_folded, query, 32)` chia cho độ dài hoặc ưu tiên số token **khác nhau** khớp + bỏ stop-words hội thoại tiếng Việt như `bao`, `nhieu`, `moi`, `nguoi`, `la`, `hop`, `ly`), hoặc đánh giá **reranker thật (`AD-04`)** trên tập candidate `30` (nơi `VI-05#1` đã có mặt ở lexical #8!).
