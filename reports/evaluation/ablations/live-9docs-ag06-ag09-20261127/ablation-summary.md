# Báo cáo Ablation RetrievalPipeline Live (AG-06 / AG-09) — 27/11/2026

**Dataset:** `data/evaluation/ag02-dev-benchmark.json` (16 cases, nhãn `PROVISIONAL`, SHA-256: `8deeaefe89e462092c57e43f64a3c802958692f1eab9a10336ef5352348a15c6`; `7` evaluable = `5` `FULL_COVERAGE` + `2` `PARTIAL_COVERAGE`)  
**Coverage Snapshot:** `reports/evaluation/baselines/live-9docs-20261127/coverage-snapshot-isolated-live-9docs.json` (tập con 9 tài liệu WHO/NIDDK, 29 chunks/vectors, `openai/text-embedding-3-small/1536`)  
**Harness:** `tools/run-agent-retrieval-ablation.py` (`RetrievalPipeline`, `top_k=5`, `rag_context_max_chars=8000`)

---

## Kết quả Ablation trên 9-doc Live Embeddings Corpus

| Cấu hình | Flags | Trạng thái | Evaluable | Document Hit@5 | Document Recall@5 | MRR@5 | Ghi chú & Artifact |
|---|---|---|---|---:|---:|---:|---|
| **1. `dense`** | `HYBRID=false`, `RERANK=false`, `MMR=false` | **COMPLETED** | 7 | `1.0000` | `0.8571` | `0.7143` | [dense.json](dense.json) |
| **2. `hybrid`** | `HYBRID=true`, `RERANK=false`, `MMR=false` | **COMPLETED** | 7 | `1.0000` | `0.8571` | `0.7143` | [hybrid.json](hybrid.json) |
| **3. `hybrid + rerank`** | `HYBRID=true`, `RERANK=true`, `MMR=false` | **NOT_RUN** | - | - | - | - | Tắt reranker theo `AD-04` (`RAG_RERANK_ENABLED=false`) |
| **4. `hybrid + rerank + mmr`** | `HYBRID=true`, `RERANK=true`, `MMR=true` | **NOT_RUN** | - | - | - | - | Phụ thuộc bước rerank thật (`AD-04`) |

## Giới hạn

- Kết quả đo trên tập con 9 tài liệu WHO/NIDDK (29 chunks) và tập dev `PROVISIONAL` (`evaluable=7`), chưa có nhãn `chunk_relevance` và chưa có `holdout` / specialist sign-off.
