# Báo cáo Đánh giá Live Cohere `rerank-v3.5` trên `RetrievalPipeline` — 27/11/2026

**Phạm vi:** `reports/evaluation/ablations/live-rerank-9docs-20261127/` (tách biệt hoàn toàn khỏi các lượt trước)  
**Cấu hình:**
- **Retrieval:** `AI_RETRIEVAL_HYBRID_ENABLED=true` (`V68` folded lexical + `openai/text-embedding-3-small/1536`), `candidate_limit=15`, `top_k=5`, `rag_context_max_chars=8000`
- **Reranker:** Cohere `rerank-v3.5` (`https://api.cohere.ai/v2/rerank`), `degraded=0/10`, latency `719–905 ms`
- **MMR:** `apply_mmr(..., lambda_param=0.7)` tái sử dụng trực tiếp `rerank_scores` từ nhánh `rerank` (không gọi lại Cohere)
- **Dataset:** [rerank-benchmark-9docs.json](../rerank-prep-9docs-20261127/rerank-benchmark-9docs.json) (`10` dev cases: `7` evaluable + `3` `EMPTY_EVIDENCE`; toàn bộ nhãn giữ `PROVISIONAL`)
- **Corpus:** [coverage-snapshot-isolated-rerank-9docs.json](coverage-snapshot-isolated-rerank-9docs.json) (`9` `ACTIVE` docs WHO/NIDDK, `29` chunks/vectors `1536d`, `index_ready=true`).

---

## 1. Kết quả tổng hợp (`candidate_limit=15`, `top_k=5`, `max_chars=8000`)

| Nhánh đối chứng | Evaluable | `Document Hit@5` | `Document Recall@5` | `MRR@5` | `VI-NO-DIACRITICS-01` (`VI-05#1` rank) | `WHO:E01` (`VI-05#1` rank) | `EN-01` (`VI-05#1` rank) |
|---|---:|---:|---:|---:|---|---|---|
| **1. `rrf_current`** | 7 | `1.0000` | `0.8571` | **`0.6952`** | `#10` (**ngoài Top-5**) | `#5` | `#5` |
| **2. `rerank`** (`rerank-v3.5`) | 7 | `1.0000` | `0.8571` | `0.6429` | **`#3`** (`0.5499`) | **`#3`** (`0.5390`) | **`#2`** (`0.7235`) |
| **3. `rerank_mmr`** (tái sử dụng scores) | 7 | `1.0000` | `0.8571` | `0.6667` | **`#3`** (`0.5499`) | **`#2`** (`0.5390`) | **`#2`** (`0.7235`) |

- **Kiểm tra `candidate_limit=15`:** Trên `AG02-DEV:VI-NO-DIACRITICS-01`, `WHO-PA-SB-2020-VI-05#1` nằm ở **fused rank `#10`** (trong Top-15), nên `candidate_limit=15` đã đủ đưa `VI-05#1` vào tập reranker mà không cần mở rộng lên `30`.

---

## 2. Phân tích Chunk-level, Regression & `EMPTY_EVIDENCE`

- **Cải thiện rõ trên chunk mục tiêu (`PROVISIONAL`):**
  - `AG02-DEV:VI-NO-DIACRITICS-01`: `VI-05#1` từ **`#10` (ngoài Top-5)** lên **`#3`** (`0.5499`).
  - `WHO-PA-SB-2020:E01`: `VI-05#1` từ `#5` lên `#3` (`rerank`) và `#2` (`rerank_mmr`).
  - `AG02-DEV:EN-01`: `VI-05#1` từ `#5` lên **`#2`** (`0.7235`).
  - `NIDDK-WEIGHT-FACTORS-2023:E01`: 4 tài liệu NIDDK được gom sạch vào Top-4 (`0.64–0.93`), đẩy chunk `WHO` nhiễu xuống `#5` (`0.0822`).
- **Regression ghi nhận (`MRR@5` `0.6952 → 0.6429/0.6667`):**
  - Xảy ra trên `AG02-DEV:EN-02` (`PARTIAL_COVERAGE`, câu hỏi tiếng Anh về protein kháng lực — thiếu tài liệu `ISSN` trong corpus 9 docs): `WHO-PA-SB-2020-VI-05#1` tụt từ `#1` (`rrf_current`) xuống `#3` (`rerank`, `score=0.0776` vì thực chất các chunk `WHO` đều không chứa nội dung protein kháng lực).
- **`EMPTY_EVIDENCE` (`MIXED-02`, `EMPTY-01`, `EMPTY-02`):**
  - Điểm relevance cao nhất từ Cohere trên 3 ca ngoài corpus là `0.0974` (`MIXED-02`), `0.0502` (`EMPTY-01`), `0.1855` (`EMPTY-02`), tách biệt rõ với ngưỡng `0.55–0.93` của các ca `FULL_COVERAGE` (chưa suy ra answer quality vì chưa gọi LLM).

---

## 3. Chi phí & Usage (`usage-and-cost-summary.json`)

- **Cohere `rerank-v3.5` (trần riêng `$0.10`):** `1` probe + `10` cases = **`11` calls** → **`$0.022000 / $0.100000`** (`within_budget=true`).
- **OpenAI `text-embedding-3-small` (trần cũ `$0.50`):** Reindex `29` chunks + `10` queries (ước tính cận trên `<= 16,000` tokens) = `$0.000320` → **tích lũy `$0.012696 / $0.500000`** (`within_budget=true`).

---

## 4. Giới hạn & Bước tiếp theo

- Tập con 9 docs WHO/NIDDK, nhãn `PROVISIONAL`, mới đánh giá `RetrievalPipeline` (Agent `search_knowledge`); Public F01 chưa nối `RetrievalPipeline`. Giữ `hybridEnabled=false` và `RAG_RERANK_ENABLED=false` mặc định.
- **Bước tiếp theo có căn cứ:**
  1. Nếu muốn xác minh end-to-end cho `AG02-DEV:VI-NO-DIACRITICS-01` trên Agent `search_knowledge` (hoặc đưa `RetrievalPipeline` vào Public F01), cần quyết định phạm vi tích hợp và chạy LLM evaluation riêng.
  2. Nghiệm thu nhãn `PROVISIONAL` với reviewer chuyên môn và mở rộng corpus (`ISSN`, `NIH ODS`) trước khi chọn cấu hình cho `AD-04` / `G2`.
