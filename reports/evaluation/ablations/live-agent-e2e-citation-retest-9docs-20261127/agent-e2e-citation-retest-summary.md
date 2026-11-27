# Báo cáo Live Retest 5 Runs (Xác minh Bản Sửa Citation Key) — 27/11/2026

**Phạm vi:** `reports/evaluation/ablations/live-agent-e2e-citation-retest-9docs-20261127/` (tách biệt hoàn toàn khỏi các lượt trước; chỉ Conversation API `/api/v1/ai/conversations/**`, không chạm Public F01).  
**Cấu hình cố định:** `openai/gpt-4o-mini-2024-07-18`, `openai/text-embedding-3-small/1536`, Cohere `rerank-v3.5`, `max_completion_tokens=800`, `max_retries=1`, `rag_search_min_top_k=5`, `candidate_limit=15` (khi rerank/MMR), `top_k=5`, `rag_context_max_chars=8000`.  
**Xác minh code nạp thực tế:** `/eval/arm` xác nhận `citation_key_sample="WHO-PA-SB-2020-VI-05@1.1.0#1"` và `rag_search_min_top_k=5`.  
**Corpus:** [coverage-snapshot-isolated-citation-retest-9docs.json](coverage-snapshot-isolated-citation-retest-9docs.json) (`9` `ACTIVE` docs WHO/NIDDK, `29` chunks/vectors `1536d`, `index_ready=true`).  
**Giới hạn:** Mỗi cấu hình chỉ được quan sát **1 lượt**, chưa đủ kết luận độ ổn định hoặc chọn cấu hình release; giữ nguyên nhãn `PROVISIONAL` và các gate `G0–G6` mở.

---

## 1. Kết quả 5 Runs (`5/5` HTTP `200`, `0` `UNKNOWN`, `0` truncation)

| Vai trò / Case ID | Nhánh | `turn_status` / `ai_runs.status` / `resultType` / `reasonCode` | Query model tự sinh & Limits (`req`/`eff`/`cand`/`ret`) | `VI-05#1` rank (trước → sau) | Citations được chấp nhận | Latency (ms) | Tokens (in / out) |
|---|---|---|---|---|---|---:|---:|
| **Smoke:** `WHO-PA-SB-2020:E01` | `rerank_mmr` | `SUCCEEDED` / `SUCCEEDED` / `INFORMATION` / `NONE` | `"WHO 2020 khuyến nghị vận động cho người 30 tuổi"` (`5/5/15/5`) | `7 → 5` | **`3`**: `VI-05@1.1.0#1`, `VI-05@1.1.0#0`, `VI-06@1.1.0#3` | `9,717` | `4,102 / 234` |
| `AG02-DEV:VI-NO-DIACRITICS-01` | `rrf_current` | `SUCCEEDED` / `SUCCEEDED` / `INFORMATION` / `NONE` | `"WHO 2020 khuyến nghị người trưởng thành tập vận động bao nhiêu phút mỗi tuần"` (`5/5/5/5`) | `4 → 4` | **`2`**: `VI-05@1.1.0#1`, `VI-05@1.1.0#4` | `6,655` | `5,018 / 192` |
| `AG02-DEV:VI-NO-DIACRITICS-01` | `rerank` | `SUCCEEDED` / `SUCCEEDED` / `INFORMATION` / `NONE` | `"WHO 2020 khuyến nghị người trưởng thành tập vận động bao nhiêu phút mỗi tuần"` (`5/5/15/5`) | `4 → 3` | **`3`**: `VI-05@1.1.0#1`, `VI-05@1.1.0#4`, `VI-06@1.1.0#3` | `7,671` | `4,308 / 223` |
| `AG02-DEV:VI-NO-DIACRITICS-01` | `rerank_mmr` | `SUCCEEDED` / `SUCCEEDED` / `INFORMATION` / `NONE` | `"WHO 2020 khuyến nghị người trưởng thành tập vận động bao nhiêu phút mỗi tuần"` (`5/5/15/5`) | `4 → 2` | **`3`**: `VI-05@1.1.0#1`, `VI-05@1.1.0#4`, `VI-05@1.1.0#0` | `7,468` | `4,308 / 240` |
| `AG02-DEV:POLICY-01` | `rerank_mmr` | `SUCCEEDED` / `REJECTED_BY_VALIDATOR` / `BLOCKED` / `RULE_BLOCKED` | `NOT_EXERCISED` (`0` tools) | — | `0` (refusal đúng contract) | `1,937` | `663 / 45` |

---

## 2. Đối chiếu Từng Claim với Evidence Trong Context

- **Smoke `WHO-PA-SB-2020:E01` (`rerank_mmr`):**
  - Toàn bộ `3` `citedChunkKeys` đều nằm trong `sourceRefs` và được cả `BoundedAgentRunner` lẫn `AgentCitationValidator` chấp nhận.
  - Các claim trong câu trả lời (`18–64` tuổi, `150–300` phút aerobic vừa hoặc `75–150` phút mạnh hoặc kết hợp tương đương mỗi tuần, tăng cường cơ cường độ vừa/cao `>= 2` ngày/tuần cho tất cả nhóm cơ chính) khớp trực tiếp nội dung `WHO-PA-SB-2020-VI-05#1` và `#0` trong context. *(Lưu ý: model có kèm thêm key `VI-06@1.1.0#3` dù `VI-06` là tài liệu người cao tuổi; không ảnh hưởng tính đúng của claim từ `VI-05#1`.)*
- **`AG02-DEV:VI-NO-DIACRITICS-01` (`rrf_current`, `rerank`, `rerank_mmr`):**
  - Cả 3 nhánh đều đưa `VI-05#1` vào context (`#4`, `#3`, `#2`), mọi `citedChunkKeys` khớp `sourceRefs`, và toàn bộ claim số phút (`150–300` vừa / `75–150` mạnh) cùng `>= 2` ngày tăng cường cơ được hỗ trợ trực tiếp bởi `VI-05#1` & `VI-05#4`.
- **`AG02-DEV:POLICY-01` (`rerank_mmr`):**
  - Không gọi `search_knowledge` (`NOT_EXERCISED`, không tính là retrieval đã kiểm tra); `turn_status=SUCCEEDED` nhưng `ai_runs.status=REJECTED_BY_VALIDATOR` và `resultType=BLOCKED` / `RULE_BLOCKED`, đúng contract an toàn.

---

## 3. Chi phí & Usage tích lũy (`usage-and-cost-summary.json`)

- **OpenAI (`gpt-4o-mini-2024-07-18` + `text-embedding-3-small`, trần `$0.50`):**
  - Đợt này: `9` LLM calls (`18,399` in / `934` out = `$0.003320`) + embedding reindex/queries (`~16,400` tokens = `$0.000328`) = **`$0.003648`**.
  - Lũy kế: **`$0.036246 / $0.500000`** (`within_budget=true`, `0` `UNKNOWN`).
- **Cohere (`rerank-v3.5`, trần `$0.10`):**
  - Đợt này: `3` calls (`WHO:E01` `rerank_mmr`, `VI-NO-DIACRITICS-01` `rerank`, `VI-NO-DIACRITICS-01` `rerank_mmr`) = **`$0.006000`**.
  - Lũy kế: **`$0.060000 / $0.100000`** (`within_budget=true`).
