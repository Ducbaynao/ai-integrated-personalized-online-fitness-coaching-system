# Báo cáo Live Retest Tối thiểu & So sánh Trước/Sau — 27/11/2026

**Phạm vi:** `reports/evaluation/baselines/live-9docs-retest-20261127/` (tách biệt hoàn toàn khỏi `live-9docs-20261127/`)  
**Cấu hình:** `openai/text-embedding-3-small/1536`, `openai/gpt-4o-mini-2024-07-18`, `max_completion_tokens=800`, `max_retries=1`, `RAG_RERANK_ENABLED=false`, `AI_AGENT_PROPOSALS_ENABLED=false`, bật `--smoke-gate`.  
**Corpus:** Reindex mới trên stack cô lập (`ag02-retest-pg:15433`, Flyway V1–V67 PASS): `9` `ACTIVE` docs WHO/NIDDK, `29` chunks, `29` vectors `1536d` ([coverage-snapshot-isolated-retest-9docs.json](coverage-snapshot-isolated-retest-9docs.json)).

---

## 1. Kết quả Smoke Gate (`WHO-PA-SB-2020:E01`) & So sánh Trước/Sau

Do `--smoke-gate` kích hoạt dừng batch ngay khi `WHO-PA-SB-2020:E01` thất bại (`HTTP 502` / `REJECTED_BY_VALIDATOR`), **4 ca còn lại (`NIDDK-WEIGHT-FACTORS-2023:E01`, `AG02-DEV:EN-01`, `AG02-DEV:VI-NO-DIACRITICS-01`, `AG02-DEV:POLICY-01`) không được gọi**, đúng quy trình dừng paid calls.

| Case ID | Lượt chạy | Evidence | Outcome / `finish_reason` | Citation Validity | Latency | Usage (in / out) |
|---|---|---|---|---|---|---|
| `WHO-PA-SB-2020:E01` | **Trước** (`live-9docs-20261127`) | `VI-01#1`, `VI-06#3`, `VI-05#0`, `VI-05#1`, `VI-06#0` | `HTTP 502` · `REJECTED_BY_VALIDATOR` / `UNVERIFIED_CITATION` | Sai `documentId`: tự bịa `WHO-PA-SB-2020-VI-04` | `5,824 ms` (server) / `5,955 ms` (E2E) | `2,299` / `265` |
| `WHO-PA-SB-2020:E01` | **Sau** (`live-9docs-retest-20261127`) | Cùng 9-doc corpus `text-embedding-3-small` | `HTTP 502` · `REJECTED_BY_VALIDATOR` / `UNVERIFIED_CITATION` | **Hết bịa `VI-04`**, nhưng ghép chéo `documentId=WHO-PA-SB-2020-VI-05#0` với `quoteOrExcerpt` thuộc `WHO-PA-SB-2020-VI-01` → validator chặn đúng | `5,758 ms` (server) / `5,857 ms` (E2E) | `2,378` / `318` |
| `NIDDK-WEIGHT-FACTORS-2023:E01` | **Sau** | - | **NOT_RUN** (dừng tại smoke gate) | - | - | - |
| `AG02-DEV:EN-01` | **Sau** | - | **NOT_RUN** (dừng tại smoke gate) | - | - | - |
| `AG02-DEV:VI-NO-DIACRITICS-01` | **Sau** | - | **NOT_RUN** (dừng tại smoke gate) | - | - | - |
| `AG02-DEV:POLICY-01` | **Sau** | - | **NOT_RUN** (dừng tại smoke gate) | - | - | - |

> [!NOTE]
> Kết quả trên mới dựa trên **một lượt chạy** (`WHO-PA-SB-2020:E01`); chỉ xác nhận được rằng (a) `--smoke-gate` hoạt động đúng và dừng batch trước 4 ca còn lại, (b) prompt mới đã loại bỏ lỗi tự bịa `VI-04`, nhưng model vẫn có thể ghép nhầm metadata của chunk này với `quoteOrExcerpt` của chunk khác.

---

## 2. Chi phí & Usage tích lũy (`$0.50` cap)

- **Trước đó (`live-9docs-20261127`):** `$0.007594` (LLM đo được) + `$0.000400` (embedding ước tính cận trên) = **`$0.007994`**.
- **Đợt retest này (`live-9docs-retest-20261127`):**
  - Reindex 9 docs (29 chunks) + 1 query embedding (ước tính cận trên `<= 16,000` tokens): **`$0.000320`**.
  - `1` LLM call (`WHO-PA-SB-2020:E01`, `2,378` input + `318` output = `2,696` tokens): **`$0.000548`**.
- **Tổng tích lũy:** **`$0.008861 / $0.500000`** (`within_budget=true`, `0` request `UNKNOWN`). Chi tiết: [usage-and-cost-summary.json](usage-and-cost-summary.json).

---

## 3. Chẩn đoán Offline & Đầu việc tiếp theo

- **Nguyên nhân lỗi mới trên `WHO-PA-SB-2020:E01`:** Model chọn `documentId`/`sectionIndex` từ `CHUNK #3` (`WHO-PA-SB-2020-VI-05#0` — vốn chỉ chứa tiêu đề/metadata) nhưng lấy `quoteOrExcerpt` từ `CHUNK #1` (`WHO-PA-SB-2020-VI-01`: `"WHO 2020 đưa ra khuyến nghị sức khỏe cộng đồng..."`).
- **Đầu việc tiếp theo (offline trước khi retest lại):**
  1. Bổ sung vào prompt/schema quy tắc: `quoteOrExcerpt` **phải trích nguyên văn từ trường `content` của CHÍNH `CHUNK #i` đã chọn** (cùng `documentId` và `sectionIndex`), không lấy `quoteOrExcerpt` từ chunk khác.
  2. (Tùy chọn xem xét) Trong `KnowledgeChunker` hoặc prompt, lưu ý `sectionIndex=0` của các bài derivative chứa header/metadata (`# Title\nDocument ID: ...`), nên hướng dẫn model ưu tiên chọn chunk có nội dung khuyến nghị thực tế (ví dụ `sectionIndex >= 1`) khi trích dẫn.
  3. Bổ sung regression test offline cho trường hợp ghép chéo chunk (`VI-05#0` + quote của `VI-01#1`) vẫn bị validator chặn, rồi mới xin phép chạy lại smoke gate.
