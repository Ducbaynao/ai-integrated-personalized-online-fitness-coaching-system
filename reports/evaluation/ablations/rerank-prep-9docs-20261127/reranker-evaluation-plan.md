# Chuẩn bị Đánh giá Reranker Thực tế (AG-07 / AG-09 / AD-04) — 27/11/2026

**Trạng thái:** **PREPARED (OFFLINE ONLY)** — chưa bật `RAG_RERANK_ENABLED`, chưa gọi reranker ngoài, giữ `ai.retrieval.hybrid-enabled=false` mặc định.  
**Artifacts liên quan:**
- Benchmark manifest: [rerank-benchmark-9docs.json](rerank-benchmark-9docs.json) (`10` dev cases: `7` evaluable + `3` `EMPTY_EVIDENCE`, nhãn `PROVISIONAL`)
- Live V68 evidence: [hybrid-v68-summary.md](../../baselines/live-9docs-hybrid-v68-20261127/hybrid-v68-summary.md)
- Offline lexical ablation: [lexical-ranking-ablation-report.md](../../baselines/live-9docs-hybrid-v68-20261127/lexical-ranking-ablation-report.md)

---

## 1. Kiểm tra tính tương đương của Replay (`#3` ở replay vs `#5` ở live V68)

- **Kết luận:** Replay offline tại `lexical-ranking-ablation-report.md` **KHÔNG tương đương hoàn toàn** với lượt chạy live V68.
- **Giải thích chính xác khác biệt trên `WHO-PA-SB-2020:E01`:**
  1. Khi `AI_RETRIEVAL_HYBRID_ENABLED=true`, `KnowledgeRetrievalService` lấy `candidateLimit = max(limit, 30)` (`30` dense + `30` lexical).
  2. Artifact lưu từ `live-9docs-20261127/retrieval/` chỉ ghi **Top-5** dense (`limit=5`), không lưu dense ranks `#6–#30`.
  3. Ở lượt live V68, `WHO-PA-SB-2020-VI-02#2` (`cosine=0.3728`, lexical `#1`) và `WHO-PA-SB-2020-VI-05#4` (`cosine=0.4271`, lexical `#4`) đều nằm trong dense Top-30 nên được cộng **cả hai** thành phần RRF, đẩy chúng lên `#3` và `#4` và đẩy `VI-05#1` xuống `#5`. Trong replay chỉ có `dense_top5`, hai chunk này thiếu điểm dense nên tụt dưới `VI-05#1` (`#3`).
- **Hệ quả:** Không dùng replay `dense_top5` làm bằng chứng chọn cấu hình live; chỉ dùng như chẩn đoán xu hướng lexical.

---

## 2. Xác minh đường tích hợp (`Public F01` vs `Agent Search`)

- **Public F01 (`/api/v1/ai/knowledge/query`):** Gọi `OpenAiKnowledgeProvider` → `KnowledgeRetriever.retrieve(..., limit=5)` → `KnowledgeRetrievalService` (`/internal/v1/knowledge/retrieval`). **Không** đi qua `RetrievalPipeline`, `HttpRerankerAdapter`, `apply_mmr`, hay `pack_context_chunks`.
  - **Hệ quả quan trọng:** Bật `RAG_RERANK_ENABLED=true` **KHÔNG tác động** tới Public F01. Muốn đưa reranker vào Public F01 cần mở rộng phạm vi/kiến trúc riêng (`OpenAiKnowledgeProvider` gọi `RetrievalPipeline`), không tự mở trong đợt này.
- **Agent Search (`search_knowledge` trong `ToolGateway`):** Đi qua `RetrievalPipeline.run(..., limit=5)`:
  - Khi `RAG_RERANK_ENABLED=true` hoặc `RAG_MMR_ENABLED=true`, `RetrievalPipeline` gọi Spring với `candidate_limit = max(limit, 15)` (`15` candidates), sau đó đưa toàn bộ `15` candidates vào `HttpRerankerAdapter.rerank(query, candidates, top_n=5)` (hoặc `top_n=15` nếu bật MMR).
  - *Lưu ý:* Để chắc chắn `VI-05#1` (hiện ở lexical `#8`) lọt vào tập đưa vào reranker của `AG02-DEV:VI-NO-DIACRITICS-01`, cần kiểm tra `VI-05#1` có nằm trong **fused Top-15** của `HYBRID=true` hay không (nếu `candidate_limit=15` chưa đủ, có thể cần `candidate_limit=20..30` khi đánh giá `RetrievalPipeline`, giữ nguyên `limit=5` cuối cùng).

---

## 3. So sánh các lựa chọn Reranker phù hợp (`AD-04`)

`HttpRerankerAdapter` (`services/ai-service/app/rag/reranker.py`) đã hỗ trợ sẵn chuẩn JSON `{"model", "query", "documents", "top_n"}` → `{"results": [{"index", "relevance_score"}]}` (`max_documents=40`, `max_chars_per_doc=2000`, timeout `4.0s`, degrade an toàn khi lỗi).

| Tiêu chí | Phương án 1: **Cohere `rerank-v3.5`** *(hoặc `rerank-multilingual-v3.0`)* | Phương án 2: **Jina `jina-reranker-v2-base-multilingual`** | Phương án 3: **Local `BAAI/bge-reranker-v2-m3`** *(self-hosted)* |
|---|---|---|---|
| **Hỗ trợ tiếng Việt / đa ngôn ngữ** | Tốt (>100 ngôn ngữ, hỗ trợ tốt văn bản bán cấu trúc/bảng) | Tốt (đa ngôn ngữ, hỗ trợ tiếng Việt) | Tốt (chuẩn mở đa ngôn ngữ, hỗ trợ tiếng Việt) |
| **Tương thích `HttpRerankerAdapter`** | **Trực tiếp** (`/v2/rerank` hoặc `/v1/rerank`, Bearer token) | **Trực tiếp** (`https://api.jina.ai/v1/rerank`, Bearer token) | **Trực tiếp** nếu bọc qua TEI / vLLM / FastAPI `/rerank` nội bộ |
| **Giới hạn & Timeout** | Context `4,096` tokens/doc; khớp `max_chars_per_doc=2000`, `timeout=4.0s` | Giới hạn theo RPM/TPM (Free: 100 RPM / 100k TPM); `timeout=4.0s` | Phụ thuộc CPU/GPU local; không bị rate limit ngoài |
| **Chi phí ước tính (10 queries × 15–30 docs)** | `$2.00 / 1,000 queries` → 10 queries ≈ **`$0.02`** | `~$0.05 / 1M tokens` → 10 queries × ~10k tokens ≈ **`$0.005`** | `$0` API fee; tốn RAM/CPU/GPU local (~2.2 GB weights) |
| **Egress & Retention dữ liệu** | Gửi `query` + `chunk content` ra Cohere API (cần duyệt egress `AD-04`) | Gửi `query` + `chunk content` ra Jina API (cần duyệt egress `AD-04`) | **Không egress** dữ liệu ra ngoài |

---

## 4. Lệnh chạy dự kiến & Quyết định cần chốt trước khi chạy (`AD-04`)

- **Phạm vi đối chứng đề xuất:** Chạy `tools/run-agent-retrieval-ablation.py` (mở rộng tham số `rerank` / `rerank+mmr` trên `RetrievalPipeline`, `top_k=5`, `rag_context_max_chars=8000`, giữ `HYBRID=true` trên stack test cô lập) cho 3 nhánh: `rrf_current` (`RERANK=false, MMR=false`) vs `rerank` (`RERANK=true, MMR=false`) vs `rerank_mmr` (`RERANK=true, MMR=true`).
- **Ước lượng chi phí đợt chạy đầu (nếu dùng API ngoài):** `10` queries × `2` nhánh có rerank = `20` rerank calls → **`~$0.04`** (Cohere) hoặc **`~$0.01`** (Jina), cộng embedding `~$0.0003`.
- **Các mục BẮT BUỘC người dùng chốt trước khi gọi reranker (authorization `$0.50` trước đây chỉ gồm đợt không reranker):**
  1. Chọn phương án reranker (`Cohere rerank-v3.5`, `Jina jina-reranker-v2-base-multilingual`, hoặc `Local BAAI/bge-reranker-v2-m3`).
  2. Chốt phạm vi đánh giá đợt đầu chỉ trên `RetrievalPipeline` (Agent `search_knowledge`), chưa đưa vào Public F01.
  3. Phê duyệt quyền gửi `query` tổng hợp + `content` 9 tài liệu WHO/NIDDK ra endpoint đã chọn (nếu dùng API ngoài) và trần chi phí riêng cho reranker.
