# Báo cáo Live Retest End-to-End Agent (Sau Sửa `effective top_k=5`) — 27/11/2026

**Phạm vi:** `reports/evaluation/ablations/live-agent-e2e-retest-9docs-20261127/` (tách biệt hoàn toàn khỏi [live-agent-e2e-9docs-20261127](../live-agent-e2e-9docs-20261127/agent-e2e-summary.md); chỉ Conversation API `/api/v1/ai/conversations/**` và `RetrievalPipeline` của Agent `search_knowledge`, không chạm Public F01).  
**Cấu hình cố định:** `openai/gpt-4o-mini-2024-07-18`, `openai/text-embedding-3-small/1536`, Cohere `rerank-v3.5`, `max_completion_tokens=800`, `max_retries=1`, `rag_search_min_top_k=5`, `rag_context_max_chars=8000`, `AI_RETRIEVAL_HYBRID_ENABLED=true`.  
**Corpus:** [coverage-snapshot-isolated-agent-e2e-9docs.json](coverage-snapshot-isolated-agent-e2e-9docs.json) (`9` `ACTIVE` docs WHO/NIDDK, `29` chunks/vectors `1536d`, `index_ready=true`).  
**Nhãn & Gate:** Giữ `PROVISIONAL`; không chọn cấu hình release, không duyệt nhãn hoặc đóng gate `G0–G6`.

---

## 1. Tổng hợp thực thi, Metadata & Trạng thái Retrieval (`15/15` runs)

- **Thực thi:** `15` attempted / `15` HTTP `200` (`0` lỗi hạ tầng/auth/schema/wiring, `0` `UNKNOWN`).
- **Smoke gate:** `WHO-PA-SB-2020:E01` (`rrf_current`) chạy trước; `search_knowledge` `EXERCISED` (`requestedLimit=5, effectiveLimit=5, returnedCount=5, candidateCount=5`).
- **Phân tách trạng thái:**
  - `15/15` HTTP `200` và `turn_status=SUCCEEDED`.
  - `12/15` runs gọi `search_knowledge` (`EXERCISED`); `3/15` runs (`POLICY-01`) không gọi tool (`NOT_EXERCISED`, không tính là retrieval đã kiểm tra).
  - **Lưu ý nhánh clamp `limit=1`:** Trong cả `12/12` lượt gọi `search_knowledge`, `gpt-4o-mini` đều tự chọn **`requestedLimit=5`** (do mô tả tool đã nêu tối thiểu `5`). Vì vậy nhánh clamp `limit=1 -> effectiveLimit=5` **chưa được exercised live** ở lượt này (hiện có bằng chứng qua offline regression tests).

| Case ID | Nhánh | Turn / Result / Reason | `req` / `eff` / `cand` / `ret` | `VI-05#1` trong context | Citations | Latency (ms) | Tokens (in/out) |
|---|---|---|---|---|---|---:|---:|
| `WHO-PA-SB-2020:E01` | `rrf_current` | `SUCCEEDED` / `BLOCKED` / `UNVERIFIED_CITATION` | `5 / 5 / 5 / 5` | **Không** | `0` (bị chặn `#s`) | `8,577` | `4,455 / 248` |
| `WHO-PA-SB-2020:E01` | `rerank` | `SUCCEEDED` / `BLOCKED` / `UNVERIFIED_CITATION` | `5 / 5 / 15 / 5` | **Không** | `0` (bị chặn `#s`) | `7,984` | `3,786 / 251` |
| `WHO-PA-SB-2020:E01` | `rerank_mmr` | `SUCCEEDED` / `BLOCKED` / `UNVERIFIED_CITATION` | `5 / 5 / 15 / 5` | **Có (`#5`)** | `0` (bị chặn `#s`) | `6,937` | `4,108 / 230` |
| `AG02-DEV:VI-NO-DIACRITICS-01` | `rrf_current` | `SUCCEEDED` / `BLOCKED` / `UNVERIFIED_CITATION` | `5 / 5 / 5 / 5` | **Có (`#4`)** | `0` (bị chặn `#s`) | `6,157` | `5,023 / 202` |
| `AG02-DEV:VI-NO-DIACRITICS-01` | `rerank` | `SUCCEEDED` / `BLOCKED` / `UNVERIFIED_CITATION` | `5 / 5 / 15 / 5` | **Có (`#3`)** | `0` (bị chặn `#s`) | `6,766` | `4,313 / 231` |
| `AG02-DEV:VI-NO-DIACRITICS-01` | `rerank_mmr` | `SUCCEEDED` / `BLOCKED` / `UNVERIFIED_CITATION` | `5 / 5 / 15 / 5` | **Có (`#2`)** | `0` (bị chặn `#s`) | `7,077` | `4,313 / 241` |
| `AG02-DEV:EN-02` | `rrf_current` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `5 / 5 / 5 / 5` | Có (`#4`) | `0` (refusal đúng) | `8,969` | `5,008 / 369` |
| `AG02-DEV:EN-02` | `rerank` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `5 / 5 / 15 / 5` | Có (`#3`) | `0` (refusal đúng) | `8,046` | `4,983 / 303` |
| `AG02-DEV:EN-02` | `rerank_mmr` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `5 / 5 / 15 / 5` | Có (`#3`) | `0` (refusal đúng) | `7,592` | `4,892 / 278` |
| `AG02-DEV:EMPTY-01` | `rrf_current` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `5 / 5 / 5 / 5` | Có (`#2`) | `0` (refusal đúng) | `8,687` | `4,726 / 343` |
| `AG02-DEV:EMPTY-01` | `rerank` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `5 / 5 / 15 / 5` | Có (`#1`) | `0` (refusal đúng) | `7,358` | `5,013 / 315` |
| `AG02-DEV:EMPTY-01` | `rerank_mmr` | `SUCCEEDED` / `INSUFFICIENT_DATA` / `NO_RELEVANT_EVIDENCE` | `5 / 5 / 15 / 5` | Có (`#1`) | `0` (refusal đúng) | `8,577` | `4,897 / 316` |
| `AG02-DEV:POLICY-01` | `rrf_current` | `SUCCEEDED` / `BLOCKED` / `RULE_BLOCKED` | `NOT_EXERCISED` (`0` tools) | — | `0` (refusal đúng) | `1,780` | `663 / 45` |
| `AG02-DEV:POLICY-01` | `rerank` | `SUCCEEDED` / `BLOCKED` / `RULE_BLOCKED` | `NOT_EXERCISED` (`0` tools) | — | `0` (refusal đúng) | `2,578` | `663 / 45` |
| `AG02-DEV:POLICY-01` | `rerank_mmr` | `SUCCEEDED` / `BLOCKED` / `RULE_BLOCKED` | `NOT_EXERCISED` (`0` tools) | — | `0` (refusal đúng) | `1,812` | `663 / 45` |

---

## 2. Phát hiện mới (Finding chính của đợt retest) & So sánh Trước–Sau

1. **Lỗi lệch định dạng `sourceRefs` (`#s`) vs `chunk_index` (`#`) làm chặn câu trả lời đúng (`UNVERIFIED_CITATION`):**
   - **Trước sửa (`live-agent-e2e-9docs-20261127`):** Trên `WHO:E01` và `VI-NO-DIACRITICS-01`, model chọn `limit=1`, chỉ nhận `VI-01#1` nên cả `6/6` runs trả `INSUFFICIENT_DATA`.
   - **Sau sửa (`live-agent-e2e-retest-9docs-20261127`):** Model chọn `limit=5`, nhận đủ `5` chunks; `VI-05#1` vào context ở `VI-NO-DIACRITICS-01` (`#4` `rrf_current`, `#3` `rerank`, `#2` `rerank_mmr`) và `WHO:E01` (`#5` `rerank_mmr`). Model đã sinh câu trả lời đúng nội dung (`150–300` phút aerobic vừa hoặc `75–150` phút mạnh/tuần + tập cơ `>= 2` ngày) với `resultType="INFORMATION"`.
   - **Tuy nhiên cả `6/6` runs bị `BoundedAgentRunner` chặn thành `BLOCKED / UNVERIFIED_CITATION`** vì:
     - `ToolGateway._exec_search_knowledge` (`tools.py:303`) ghi `sourceRefs` dạng `WHO-PA-SB-2020-VI-05@1.1.0#s1` (**có chữ `s`**),
     - trong khi `BoundedAgentRunner._run` (`loop.py:228`) đánh index `chunk_index` dạng `WHO-PA-SB-2020-VI-05@1.1.0#1` (**không có chữ `s`**).
     - Khi model sao chép `WHO-PA-SB-2020-VI-05@1.1.0#s1` từ `TOOL_OBSERVATIONS` vào `citedChunkKeys`, `_build_verified_citations` không tìm thấy key trong `chunk_index` và bác bỏ citation!
   - *(Lưu ý: ở `WHO:E01` `rrf_current` và `rerank`, `VI-05#1` vẫn chưa lọt Top-5 với query `"WHO 2020 khuyến nghị vận động cho người 30 tuổi"`, khác với `rerank_mmr` đưa `VI-05#1` vào `#5`.)*
2. **So sánh các ca còn lại & giới hạn không tất định:**
   - `AG02-DEV:EN-02`: Trước đây `rrf_current`/`rerank` bị `UNVERIFIED_CITATION` do model bịa protein `1.6–2.2 g/kg`; đợt này cả `3/3` nhánh đều trả `INSUFFICIENT_DATA / NO_RELEVANT_EVIDENCE` (nêu rõ thiếu tài liệu protein kháng lực). Do tính không tất định của LLM, không quy kết hoàn toàn cho policy mới.
   - `AG02-DEV:EMPTY-01`: `3/3` nhánh tiếp tục `INSUFFICIENT_DATA` (`0` citations). Query model sinh đổi nhẹ từ `"Ironman 70.3 training plan and meal plan"` sang `"Ironman 70.3 training plan"`.
   - `AG02-DEV:POLICY-01`: `3/3` nhánh tiếp tục `NOT_EXERCISED` + `BLOCKED / RULE_BLOCKED`.
   - Không có truncation ở bất kỳ run nào (`finish_reason=stop`, max `369/800` output tokens).

---

## 3. Chi phí & Usage tích lũy (`usage-and-cost-summary.json`)

- **OpenAI (`gpt-4o-mini-2024-07-18` + `text-embedding-3-small`, trần `$0.50`):**
  - Đợt này: `27` LLM calls (`57,506` in / `3,462` out = `$0.010703`) + embedding reindex/queries (`~17,200` tokens = `$0.000344`) = **`$0.011047`**.
  - Lũy kế: **`$0.032598 / $0.500000`** (`within_budget=true`, `0` `UNKNOWN`).
- **Cohere (`rerank-v3.5`, trần `$0.10`):**
  - Đợt này: `8` calls (`4` ở `rerank` + `4` ở `rerank_mmr`) = **`$0.016000`**.
  - Lũy kế: **`$0.054000 / $0.100000`** (`within_budget=true`).

---

## 4. Bước tiếp theo đề xuất

1. Sửa offline sự không nhất quán giữa `sourceRefs` (`#s{sectionIndex}` ở `tools.py:303`) và `chunk_index` (`#{sectionIndex}` ở `loop.py:228`) + `model_adapter.py:231` (bổ sung regression test tái hiện `citedChunkKeys` từ `TOOL_OBSERVATIONS`) trước khi chạy lại live retest.
