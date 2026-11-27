# Báo cáo Live Dev Evaluation (9-doc WHO/NIDDK) — 27/11/2026

**Nhánh:** `feat/fitness-agent-upgrade` (HEAD `57e27f8`, working tree `DIRTY`)  
**Cấu hình đã duyệt (phạm vi thử nghiệm dev):**
- **Embedding:** `openai / text-embedding-3-small / 1536` (`e1000000-0000-0000-0000-000000000002`)
- **LLM:** `openai / gpt-4o-mini-2024-07-18` (`max_completion_tokens=800`, `max_retries=1`, `store=false`)
- **Reranker / Proposal:** `RAG_RERANK_ENABLED=false`, `RAG_MMR_ENABLED=false`, `AI_AGENT_PROPOSALS_ENABLED=false`
- **Dataset:** `data/evaluation/ag02-dev-benchmark.json` (16 cases, nhãn `PROVISIONAL`, SHA-256: `8deeaefe89e462092c57e43f64a3c802958692f1eab9a10336ef5352348a15c6`). Loại `AG02-DEV-015/016` khỏi mẫu số `public-f01` (thuộc Spring authorization integration tests).
- **Coverage snapshot:** [coverage-snapshot-isolated-live-9docs.json](coverage-snapshot-isolated-live-9docs.json) (`9` `ACTIVE` docs WHO/NIDDK, `29` chunks, `29` vectors `1536d`, `index_ready=true`).

---

## 1. Chi phí & Usage (`$0.50` cap)

- **Embedding probe (1 request trước khi enqueue):** `4` prompt tokens (`HTTP 200`, `text-embedding-3-small`, `1536d`).
- **LLM (`gpt-4o-mini-2024-07-18`, đo từ `fitness.ai_runs`, 14 runs = 1 smoke + 13 `public-f01`):**
  - `38,066` input tokens + `3,141` output tokens = `41,207` total tokens.
  - **Chi phí LLM đo được:** **`$0.007594`** (`$0.15/1M` input + `$0.60/1M` output).
- **Embedding (ước tính cận trên cho 29 chunks + ~60 queries + 4 probe tokens):** `<= 20,000` tokens → **ước tính cận trên `$0.000400`** (`$0.02/1M` input).
- **Tổng chi phí ước tính:** **`$0.007994 / $0.500000`** (`within_budget=true`, `0` request `UNKNOWN`). Chi tiết trong [usage-and-cost-summary.json](usage-and-cost-summary.json).

---

## 2. Kết quả Retrieval Baseline (`--mode=retrieval`, 16 cases → `evaluable=7`)

- `document_hit_at_k`: **`0.8571`** (`6/7`, so với `0.5714` simulated)
- `document_recall_at_k`: **`0.7857`** (`5.5/7`, so với `0.5000` simulated)
- `mean_reciprocal_rank_at_k`: **`0.5714`** (`7`, so với `0.3619` simulated)
- `infra_errors`: `0`, `exec_failures`: `0`, `schema_violations`: `0`
- `EXPLAIN (ANALYZE, BUFFERS)` trên 29 chunks (`explain-analyze-live-9docs.txt`): `0.721 ms`.

---

## 3. Kết quả Public F01 (`14` cases = `1` smoke + `13` còn lại; loại `AG02-DEV-015/016`)

- **Smoke (`WHO-PA-SB-2020:E01`, `run-20261126203334-2f37b7`):** `2,299` input / `265` output tokens (`5,824 ms`), model sinh citation `WHO-PA-SB-2020-VI-04` không nằm trong evidence chunks → Backend validator từ chối hợp lệ (`REJECTED_BY_VALIDATOR` / `UNVERIFIED_CITATION`, HTTP `502`).
- **13 ca còn lại (`ag02-dev-13cases.json`):**
  - `schema_validity_rate`: `1.00` (`11/11` response hợp lệ schema)
  - `citation_document_match_rate`: `0.25` (`1/4`, `WHO-PA-SB-2020:E02` trả `INFORMATION` đúng citation)
  - `false_refusal_rate`: `0.50` (`1/2`, `AG02-DEV:VI-NO-DIACRITICS-01` trả `INSUFFICIENT_DATA`)
  - `correct_abstention_rate`: `1.00` (`2/2` trên `MIXED-02` và `EMPTY-01`)
  - `citation_validity_when_present`: `1.00` (`1/1`)
  - **2 ca bị cắt cụt do giới hạn `max_completion_tokens=800` (`INCOMPLETE_OUTPUT`, HTTP 500):** `NIDDK-WEIGHT-FACTORS-2023:E01` (`800` output tokens) và `AG02-DEV:EN-01` (`800` output tokens).
  - **2 ca `REJECTED_BY_VALIDATOR / RULE_BLOCKED`:** `AG02-DEV:POLICY-01` (chặn đúng policy guardrail) và `AG02-DEV:EMPTY-02`.
- **Latency (`14` cases):** `min=109 ms`, `p50=2,853.5 ms`, `p95=10,754 ms`, `max=11,060 ms`.

---

## 4. Giới hạn

- Kết quả dùng tập con 9 tài liệu WHO/NIDDK và nhãn dev `PROVISIONAL`; chưa có `chunk_relevance`, chưa mở `holdout`, chưa có specialist sign-off.
- `max_completion_tokens=800` gây cắt cụt JSON trên 2/14 ca (cần cân nhắc tăng giới hạn output token riêng cho câu trả lời JSON có trích dẫn nguyên văn ở vòng tuning tiếp theo nếu được duyệt).
