# Báo cáo Ablation RetrievalPipeline (AG-06 / AG-09) — 27/11/2026

**Dataset:** `data/evaluation/ag02-dev-benchmark.json` (16 cases, nhãn `PROVISIONAL`; `7` evaluable = `5` `FULL_COVERAGE` + `2` `PARTIAL_COVERAGE`)  
**Coverage Snapshot:** `reports/evaluation/baselines/sim-9docs-20261127/coverage-snapshot-isolated-sim-9docs.json` (tập con 9 tài liệu WHO/NIDDK, 29 chunks/vectors, `simulated/deterministic-1536/1.0.0`)  
**Harness:** `tools/run-agent-retrieval-ablation.py` (gọi trực tiếp `app.rag.pipeline.RetrievalPipeline` của FastAPI tới `/internal/v1/knowledge/retrieval`, `top_k=5`, `rag_context_max_chars=8000`)

---

## 1. Phân tách tác động Flag: Public F01 vs Agent Search

| Flag / Config | Tác động Public F01 (`/api/v1/ai/knowledge/query`) | Tác động Agent Search (`RetrievalPipeline`) |
|---|---|---|
| `AI_RETRIEVAL_HYBRID_ENABLED` | Có (`KnowledgeRetrievalService`: `false`=dense, `true`=dense+lexical RRF) | Có (`RetrievalPipeline.retriever` gọi `/internal/v1/knowledge/retrieval`) |
| `RAG_RERANK_ENABLED` | **Không** (Public F01 không qua `RetrievalPipeline`) | Có (`HttpRerankerAdapter` / `DisabledReranker`) |
| `RAG_MMR_ENABLED` | **Không** (Public F01 không qua `RetrievalPipeline`) | Có (`apply_mmr`, `lambda=0.7`) |
| `RAG_CONTEXT_MAX_CHARS` (`8000`) | **Không** | Có (`pack_context_chunks`) |

---

## 2. Kết quả Ablation trên 9-doc Simulated Corpus

| Cấu hình | Flags | Trạng thái | Evaluable | Document Hit@5 | Document Recall@5 | MRR@5 | Ghi chú & Artifact |
|---|---|---|---|---:|---:|---:|---|
| **1. `dense`** | `HYBRID=false`, `RERANK=false`, `MMR=false` | **COMPLETED** | 7 | `0.7143` | `0.5714` | `0.4095` | [dense.json](dense.json) |
| **2. `hybrid`** | `HYBRID=true`, `RERANK=false`, `MMR=false` | **COMPLETED** | 7 | `0.7143` | `0.5714` | `0.4095` | [hybrid.json](hybrid.json) |
| **3. `hybrid + rerank`** | `HYBRID=true`, `RERANK=true`, `MMR=false` | **NOT_RUN** | - | - | - | - | Chưa có reranker endpoint/model thật (`RAG_RERANK_URL` trống); không dùng mock score |
| **4. `hybrid + rerank + mmr`** | `HYBRID=true`, `RERANK=true`, `MMR=true` | **NOT_RUN** | - | - | - | - | Phụ thuộc bước rerank thật (`AD-04`) |

---

## 3. Giới hạn & Diễn giải bắt buộc

- Cả `dense` và `hybrid` trong lượt chạy này đều dùng **simulated embeddings** (`simulated/deterministic-1536/1.0.0`) trên **tập con 9 tài liệu WHO/NIDDK (29 chunks)**.
- Sự giống nhau giữa `dense` và `hybrid` trên 7 ca này chỉ chứng minh `RetrievalPipeline` + `KnowledgeRetrievalService` hoạt động ổn định cho cả hai nhánh; **không** dùng kết quả simulated này để kết luận `hybrid` không cải thiện hay để chọn cấu hình/model release.
- Các cấu hình `hybrid + rerank` và `hybrid + rerank + mmr` giữ `NOT_RUN` cho đến khi có quyết định `AD-04` (reranker thật, privacy/egress, timeout, chi phí).
