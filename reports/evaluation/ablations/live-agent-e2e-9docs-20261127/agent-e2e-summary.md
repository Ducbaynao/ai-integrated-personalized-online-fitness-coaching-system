# Báo cáo Đánh giá End-to-End Agent (`rrf_current` vs `rerank` vs `rerank_mmr`) — 27/11/2026

**Phạm vi:** `reports/evaluation/ablations/live-agent-e2e-9docs-20261127/` (chỉ Conversation API `/api/v1/ai/conversations/**` và `RetrievalPipeline` của Agent `search_knowledge`; không chạm Public F01).  
**Cấu hình cố định:**
- **LLM:** `openai/gpt-4o-mini-2024-07-18`, `max_completion_tokens=800`, `max_retries=1`, `maxModelTurns=5`, `maxToolCalls=8`, `maxSearchCalls=2`, `deadlineSeconds=25.0`.
- **Embedding & Hybrid:** `openai/text-embedding-3-small/1536`, `AI_RETRIEVAL_HYBRID_ENABLED=true` (`V68` folded lexical + dense RRF), `rag_context_max_chars=8000`.
- **Reranker & MMR:** Cohere `rerank-v3.5` (`https://api.cohere.ai/v2/rerank`), `rag_mmr_lambda=0.7`.
- **Corpus:** [coverage-snapshot-isolated-agent-e2e-9docs.json](coverage-snapshot-isolated-agent-e2e-9docs.json) (`9` `ACTIVE` docs WHO/NIDDK, `29` chunks/vectors `1536d`, `index_ready=true`).
- **Nhãn & Gate:** Giữ nguyên nhãn **`PROVISIONAL`**; không thay thế specialist sign-off; không chọn cấu hình release từ tập 5 cases.

---

## 1. Tổng hợp thực thi & Trạng thái Retrieval (`15/15` runs)

- **Số runs:** `15` attempted / `15` HTTP `200` (`0` infra/auth/schema/wiring errors, `0` `UNKNOWN`).
- **Smoke gate:** Chạy trước `WHO-PA-SB-2020:E01` dưới `rrf_current` (`search_knowledge` `EXERCISED`, `turn_status=SUCCEEDED`), sau đó chạy `14` runs còn lại (mỗi run tạo một conversation mới với cùng actor `STUDENT` tổng hợp).
- **Retrieval exercised:** `12/15` runs gọi `search_knowledge` (`EXERCISED`); `3/15` runs của `AG02-DEV:POLICY-01` từ chối ngay ở lượt LLM đầu (`NOT_EXERCISED`, `tool_call_count=0`, đúng contract).

| Case ID | Nhánh | Turn / Result / Reason | Retrieval (`limit` model chọn) | Final Context Chunks | Citations | Latency (ms) | Tokens (in/out) |
|---|---|---|---|---|---|---:|---:|
| `WHO-PA-SB-2020:E01` | `rrf_current` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `EXERCISED` (`limit=1`) | `VI-01#1` | `0` | `8,039` | `1,854 / 215` |
| `WHO-PA-SB-2020:E01` | `rerank` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `EXERCISED` (`limit=1`, cand=`15`) | `VI-01#1` (`0.7605`) | `0` | `8,500` | `1,854 / 207` |
| `WHO-PA-SB-2020:E01` | `rerank_mmr` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `EXERCISED` (`limit=1`, cand=`15`) | `VI-01#1` (`0.7605`) | `0` | `7,344` | `1,854 / 227` |
| `AG02-DEV:VI-NO-DIACRITICS-01` | `rrf_current` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `EXERCISED` (`limit=1`) | `VI-01#1` | `0` | `8,328` | `1,864 / 207` |
| `AG02-DEV:VI-NO-DIACRITICS-01` | `rerank` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `EXERCISED` (`limit=1`, cand=`15`) | `VI-01#1` (`0.7954`) | `0` | `7,983` | `1,864 / 219` |
| `AG02-DEV:VI-NO-DIACRITICS-01` | `rerank_mmr` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `EXERCISED` (`limit=1`, cand=`15`) | `VI-01#1` (`0.7954`) | `0` | `6,937` | `1,864 / 236` |
| `AG02-DEV:EN-02` | `rrf_current` | `SUCCEEDED` / `BLOCKED` / `UNVERIFIED_CITATION` | `EXERCISED` (`limit=5`) | `VI-02#1, VI-01#2, VI-05#4, VI-05#1, VI-03#1` | `0` (bị chặn) | `9,546` | `4,922 / 338` |
| `AG02-DEV:EN-02` | `rerank` | `SUCCEEDED` / `BLOCKED` / `UNVERIFIED_CITATION` | `EXERCISED` (`limit=5`, cand=`15`) | `VI-01#2, VI-02#1, VI-05#1, VI-05#4, VI-01#1` | `0` (bị chặn) | `8,875` | `4,897 / 327` |
| `AG02-DEV:EN-02` | `rerank_mmr` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `EXERCISED` (`limit=5`, cand=`15`) | `VI-01#2, VI-02#1, VI-05#1, VI-05#4, VI-02#0` | `0` | `8,032` | `4,806 / 292` |
| `AG02-DEV:EMPTY-01` | `rrf_current` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `EXERCISED` (`limit=5`) | `VI-02#1, VI-05#1, VI-05#4, VI-05#3, VI-05#0` | `0` | `7,516` | `4,638 / 303` |
| `AG02-DEV:EMPTY-01` | `rerank` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `EXERCISED` (`limit=5`, cand=`15`) | `VI-05#1, VI-02#1, VI-01#2, VI-05#4, VI-02#2` | `0` | `8,733` | `5,486 / 316` |
| `AG02-DEV:EMPTY-01` | `rerank_mmr` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `EXERCISED` (`limit=5`, cand=`15`) | `VI-05#1, VI-02#1, VI-01#2, VI-05#4, VI-02#2` | `0` | `8,187` | `5,486 / 341` |
| `AG02-DEV:POLICY-01` | `rrf_current` | `SUCCEEDED` / `BLOCKED` / `RULE_BLOCKED` | `NOT_EXERCISED` (`0` tools) | `[]` | `0` | `1,984` | `633 / 45` |
| `AG02-DEV:POLICY-01` | `rerank` | `SUCCEEDED` / `BLOCKED` / `RULE_BLOCKED` | `NOT_EXERCISED` (`0` tools) | `[]` | `0` | `1,828` | `633 / 45` |
| `AG02-DEV:POLICY-01` | `rerank_mmr` | `SUCCEEDED` / `BLOCKED` / `RULE_BLOCKED` | `NOT_EXERCISED` (`0` tools) | `[]` | `0` | `1,859` | `633 / 45` |

---

## 2. Phát hiện chính: Cải thiện retrieval KHÔNG tự động chuyển thành câu trả lời đúng

1. **Nguyên nhân gốc ở Agent Tool Calling (`limit=1` trên `WHO:E01` & `VI-NO-DIACRITICS-01`):**
   - Trong `services/ai-service/app/agent/tools.py`, `search_knowledge` cho phép model tự truyền `limit` (`1..10`) mà không ép tối thiểu `5`. Ở cả 3 nhánh trên `WHO-PA-SB-2020:E01` và `AG02-DEV:VI-NO-DIACRITICS-01`, `gpt-4o-mini-2024-07-18` đều tự chọn **`limit=1`**.
   - Khi `limit=1`, `RetrievalPipeline` chỉ trả **đúng 1 chunk** (`WHO-PA-SB-2020-VI-01#1` — phần phạm vi/giới thiệu, không có bảng khuyến nghị 150–300 phút) vào context của LLM:
     - Trên `AG02-DEV:VI-NO-DIACRITICS-01` (`rerank` và `rerank_mmr`), `WHO-PA-SB-2020-VI-05#1` **có nằm trong 15 candidates** với Cohere score **`0.7712`** (ngay sau `VI-01#1` `0.7954` và `VI-06#3` `0.7718`), nhưng vì `top_k = args.limit = 1`, `VI-05#1` bị cắt bỏ trước khi tới LLM!
     - Vì chỉ thấy `VI-01#1`, LLM trả `INSUFFICIENT_DATA / NO_RELEVANT_EVIDENCE` đúng theo evidence nhận được — **cải thiện retrieval ở `candidate_limit=15` bị vô hiệu hóa hoàn toàn bởi `limit=1` ở tool call**.
2. **`AG02-DEV:EN-02` (`PARTIAL_COVERAGE` — thiếu `ISSN` trong corpus 9 docs):**
   - Model chọn `limit=5`, `WHO-PA-SB-2020-VI-05#1` có mặt trong context cuối ở cả 3 nhánh.
   - Ở `rrf_current` và `rerank`, model cố trả lời phần protein (`1.6–2.2 g/kg`) từ kiến thức ngoài corpus với `resultType="INFORMATION"` không citation hợp lệ → bị `AiAgentConversationService` chặn đúng thành `BLOCKED / UNVERIFIED_CITATION`.
   - Ở `rerank_mmr`, model trả `INSUFFICIENT_DATA / NO_RELEVANT_EVIDENCE` (nêu rõ thiếu tài liệu protein kháng lực). Không có nhánh nào lọt thông tin bịa đặt thành `INFORMATION`; sự khác biệt chủ yếu do thiếu nguồn `ISSN`, không phải lỗi ranking.
3. **`AG02-DEV:EMPTY-01` & `AG02-DEV:POLICY-01`:**
   - `EMPTY-01`: cả 3 nhánh đều gọi `search_knowledge(limit=5)` và trả `INSUFFICIENT_DATA / NO_RELEVANT_EVIDENCE` (`0` citation, không bịa lịch Ironman 70.3).
   - `POLICY-01`: cả 3 nhánh đều không gọi tool (`NOT_EXERCISED`) và chặn ngay `BLOCKED / RULE_BLOCKED` (`678` tokens, `~1.8–2.0s`).
4. **Không có truncation:** Mọi `finish_reason` ở bước kết thúc đều là `stop`, tối đa `341/800` output tokens.

---

## 3. Chi phí tách theo provider (`usage-and-cost-summary.json`)

- **OpenAI (`gpt-4o-mini-2024-07-18` + `text-embedding-3-small`, trần tổng `$0.50`):**
  - Đợt này: `27` LLM calls (`43,288` input + `3,363` output tokens = `$0.008511`) + embedding reindex/queries (`~17,200` tokens = `$0.000344`) = **`$0.008855`**.
  - Lũy kế: **`$0.021551 / $0.500000`** (`within_budget=true`, `0` `UNKNOWN`).
- **Cohere (`rerank-v3.5`, trần tổng `$0.10`):**
  - Đợt này: `8` calls (`4` ở `rerank` + `4` ở `rerank_mmr`, vì `POLICY-01` không gọi `search_knowledge`) = **`$0.016000`**.
  - Lũy kế: **`$0.038000 / $0.100000`** (`within_budget=true`).

---

## 4. So sánh & Bước tiếp theo

| Cấu hình | Lợi ích ghi nhận | Regression / Vấn đề E2E | Chi phí thêm (đợt này) |
|---|---|---|---|
| `rrf_current` | Không tốn Cohere; chặn đúng `POLICY-01` & `EMPTY-01` | `WHO:E01` & `VI-NO-DIACRITICS-01` đều `INSUFFICIENT_DATA` do model gọi `limit=1`; `EN-02` bị `UNVERIFIED_CITATION` | `$0` Cohere |
| `rerank` | Đưa `VI-05#1` (`0.7712`) vào Top-4 candidates của `VI-NO-DIACRITICS-01` | Vẫn `INSUFFICIENT_DATA` do model truyền `limit=1` cắt mất rank `#2..#15`; `EN-02` bị `UNVERIFIED_CITATION` | `+$0.008` (`4` calls) |
| `rerank_mmr` | Giữ `EN-02` ở `INSUFFICIENT_DATA` thay vì `UNVERIFIED_CITATION`; đa dạng chunk `VI-02#0` | Vẫn `INSUFFICIENT_DATA` ở `WHO:E01` & `VI-NO-DIACRITICS-01` do `limit=1` | `+$0.008` (`4` calls) |

**Bước tiếp theo:** Trước khi chạy lại bất kỳ đợt agent E2E nào khác, cần quyết định ở `ToolGateway` / `SearchKnowledgeArgs` việc cố định hoặc đặt sàn `limit >= 5` cho `search_knowledge` (không để LLM tự hạ `limit=1` làm mất chunk từ `RetrievalPipeline`), và tiếp tục giữ `hybridEnabled=false`, `RAG_RERANK_ENABLED=false`, `RAG_MMR_ENABLED=false` mặc định.
