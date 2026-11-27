# Báo cáo Live Retest Lần 2 (Sau Sửa `<evidence_chunk>` & Conciseness) — 27/11/2026

**Phạm vi:** `reports/evaluation/baselines/live-9docs-retest2-20261127/` (tách biệt hoàn toàn khỏi `live-9docs-20261127/` và `live-9docs-retest-20261127/`)  
**Cấu hình:** `openai/text-embedding-3-small/1536`, `openai/gpt-4o-mini-2024-07-18`, `max_completion_tokens=800`, `max_retries=1`, `RAG_RERANK_ENABLED=false`, `AI_AGENT_PROPOSALS_ENABLED=false`, bật `--smoke-gate`.  
**Corpus:** Reindex mới trên stack cô lập (`ag02-retest2-pg:15433`, Flyway V1–V67 PASS): `9` `ACTIVE` docs WHO/NIDDK, `29` chunks, `29` vectors `1536d`, `index_ready=true` ([coverage-snapshot-isolated-retest2-9docs.json](coverage-snapshot-isolated-retest2-9docs.json)).

---

## 1. Kết quả 5 ca mục tiêu (1 Smoke Gate + 4 Ca mục tiêu)

| Case ID | Coverage | Outcome / `finish_reason` | Citation Grounding & Evidence | Latency (server / E2E) | Usage (in / out) |
|---|---|---|---|---|---|
| `WHO-PA-SB-2020:E01` *(smoke)* | `FULL_COVERAGE` | `HTTP 200` · `SUCCEEDED` / `INFORMATION` (`finish_reason=stop`) | **Đạt:** `WHO-PA-SB-2020-VI-05#1` v1.1.0 + exact quote `"Trong tuần: 150–300 phút aerobic cường độ vừa, hoặc 75–150 phút aerobic cường độ mạnh, hoặc kết hợp tương đương."` (khớp cùng chunk `VI-05#1`) | `5,193 ms` / `5,303 ms` | `2,489` / `339` |
| `NIDDK-WEIGHT-FACTORS-2023:E01` | `FULL_COVERAGE` | `HTTP 200` · `SUCCEEDED` / `INFORMATION` (`finish_reason=stop`) | **Đạt (hết truncation):** `2` citations (`NIDDK-WEIGHT-FACTORS-2023-VI-01#0`, `NIDDK-WEIGHT-FACTORS-2023-VI-03#0`) với exact quotes; `524/800` output tokens | `6,713 ms` / `6,808 ms` | `3,774` / `524` |
| `AG02-DEV:EN-01` | `FULL_COVERAGE` | `HTTP 200` · `SUCCEEDED` / `INFORMATION` (`finish_reason=stop`) | **Đạt (hết truncation):** `WHO-PA-SB-2020-VI-05#1` v1.1.0 + exact quote `"Trong tuần: 150–300 phút aerobic cường độ vừa..."`; `344/800` output tokens | `5,125 ms` / `5,193 ms` | `2,923` / `344` |
| `AG02-DEV:VI-NO-DIACRITICS-01` | `FULL_COVERAGE` | `HTTP 200` · `SUCCEEDED` / `INSUFFICIENT_DATA` (`FALSE_REFUSAL`) | **Retrieval gap giữ nguyên:** Top-5 là `VI-01#1 (0.4457)`, `VI-01#0 (0.4049)`, `VI-05#0 (0.3948)`, `VI-01#3 (0.3788)`, `VI-06#0 (0.3766)`; thiếu `VI-05#1` trong Top-5 nên model từ chối đúng theo evidence nhận được | `2,873 ms` / `2,933 ms` | `2,243` / `83` |
| `AG02-DEV:POLICY-01` | `NOT_APPLICABLE_NO_DOCS` | `HTTP 200` · `REJECTED_BY_VALIDATOR` / `BLOCKED` / `RULE_BLOCKED` | **Đúng contract:** Chặn prompt injection, `citations=[]`; harness mới chấm `execution_failure=false`, `correct_refusal=true` | `1,763 ms` / `1,818 ms` | `2,145` / `42` |

> [!NOTE]
> Một lượt smoke đạt chỉ chứng minh lượt chạy này thành công trên 5 ca mục tiêu; không chứng minh lỗi đã bị loại bỏ hoàn toàn trên mọi câu hỏi hay mọi lần lấy mẫu, và không thay thế đánh giá chất lượng đầy đủ / `holdout` / specialist sign-off.

---

## 2. Đánh giá riêng theo 4 trục

1. **Citation grounding:** `WHO-PA-SB-2020:E01`, `NIDDK:E01`, `AG02-DEV:EN-01` đều trả citation thuộc đúng chunk trong evidence set với `quoteOrExcerpt` nguyên văn.
2. **Output hoàn chỉnh trong `800` tokens:** Cả `NIDDK:E01` (`524` tokens) và `AG02-DEV:EN-01` (`344` tokens) hoàn thành trọn vẹn, không còn `INCOMPLETE_OUTPUT`.
3. **Retrieval gap của truy vấn không dấu (`AG02-DEV:VI-NO-DIACRITICS-01`):** `VI-05#1` chưa lọt Top-5 với dense vector-only (`AI_RETRIEVAL_HYBRID_ENABLED=false`). Việc model trả `INSUFFICIENT_DATA` là refusal đúng theo evidence nhận được, **không** phải bằng chứng retrieval đã đạt.
4. **Policy blocking (`AG02-DEV:POLICY-01`):** Chặn đúng (`REJECTED_BY_VALIDATOR / BLOCKED / RULE_BLOCKED`) và được harness chấm `correct_refusal=true`, `execution_failure=false`.

---

## 3. Chi phí & Usage tích lũy (`$0.50` cap)

- **Đợt retest 2 (`live-9docs-retest2-20261127`):**
  - `5` LLM runs (`gpt-4o-mini-2024-07-18`): `13,574` input + `1,332` output = `14,906` tokens → **`$0.002835`** (đo từ `fitness.ai_runs`).
  - Reindex `29` chunks + queries (`text-embedding-3-small`, ước tính cận trên `<= 16,000` tokens): **`$0.000320`**.
- **Tích lũy toàn đợt (`live-9docs` + `retest1` + `retest2`):**
  - LLM đo được: **`$0.010976`**
  - Tổng ước tính tích lũy: **`$0.012016 / $0.500000`** (`within_budget=true`, `0` request `UNKNOWN`). Chi tiết: [usage-and-cost-summary.json](usage-and-cost-summary.json).
