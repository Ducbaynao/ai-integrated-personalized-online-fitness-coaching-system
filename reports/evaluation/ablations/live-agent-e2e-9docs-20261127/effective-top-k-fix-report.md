# Báo cáo Sửa Offline Policy `effective top_k` cho `search_knowledge` — 27/11/2026

**Phạm vi:** Agent `search_knowledge` (`services/ai-service/app/core/config.py`, `services/ai-service/app/agent/tools.py`, `services/ai-service/tests/test_agent_loop.py`). Public F01 (`/internal/v1/knowledge/query`) và API khác không thay đổi.  
**Trạng thái:** Chỉ sửa và kiểm thử offline (`0` paid provider calls); giữ nguyên artifacts gốc trong `reports/evaluation/ablations/live-agent-e2e-9docs-20261127/`.

---

## 1. Phân tách Contract & Runtime Parameters

| Tham số | Nơi xác định | Giá trị & Vai trò |
|---|---|---|
| `requested_limit` (`args.limit`) | `SearchKnowledgeArgs` (`ge=1, le=10, default=5`) | Giá trị model gửi trong tool call (hoặc mặc định `5` nếu bỏ qua). Giá trị `1..4` vẫn hợp lệ theo schema để không gây validation failure; `<1` hoặc `>10` bị từ chối với `ToolObservation(status="ERROR")`. |
| `effective_limit` (`effective_top_k`) | `ToolGateway._exec_search_knowledge` | `max(args.limit, min(settings.rag_search_min_top_k, 10))` với `settings.rag_search_min_top_k = 5`. Đảm bảo model không tự thu hẹp kết quả xuống `1` khi vẫn giữ nguyên `limit=6..10` nếu model yêu cầu nhiều hơn. |
| `candidate_limit` | `RetrievalPipeline.run` | `max(effective_limit, 15)` khi bật rerank/MMR, hoặc `effective_limit` (`5`) khi chạy `rrf_current` (không rerank/MMR). |
| Số chunks thực tế vào context | `pack_context_chunks(..., max_chars=8000)` | `<= effective_limit`, bị giới hạn tự nhiên bởi số chunk `ACTIVE` hợp lệ từ backend, lọc quyền và `rag_context_max_chars`. **Không** padding nếu có ít hơn `5` chunks. |

---

## 2. Thay đổi đã thực hiện

1. **`services/ai-service/app/core/config.py`:** Thêm `rag_search_min_top_k: int = 5` trong `Settings` (tái sử dụng cơ chế cấu hình hiện có, không hardcode rải rác).
2. **`services/ai-service/app/agent/tools.py`:**
   - Đồng bộ mô tả `limit` trong `TOOL_OPENAI_DEFINITIONS` (`search_knowledge`, giữ `strict: True` và `required: ["query", "limit"]`): nêu rõ mặc định và tối thiểu theo policy là `5`, tối đa `10`.
   - Trong `ToolGateway._exec_search_knowledge`: tính `effective_limit = max(args.limit, min_top_k)` trước khi gọi `self.retrieval_pipeline.run(..., limit=effective_limit)` và ghi `requestedLimit`, `effectiveLimit`, `returnedCount`, `candidateCount` vào `ToolObservation.data` (cả nhánh `OK` và `NOT_FOUND`).
   - Giữ nguyên `_authorize_search`, `AgentCitationValidator`, `AiAgentConversationService`, `RetrievalPipeline` và `pack_context_chunks`.

---

## 3. Rà soát finding `AG02-DEV:EN-02`

- Trong đợt live E2E (`live-agent-e2e-9docs-20261127`), `AG02-DEV:EN-02` (`PARTIAL_COVERAGE`, thiếu tài liệu `ISSN` trong corpus 9 docs) đã gọi `search_knowledge(limit=5)`. Ở nhánh `rrf_current` và `rerank`, model tự sinh thông tin protein ngoài corpus (`1.6–2.2 g/kg`) với `INFORMATION` không citation hợp lệ và đã bị `AiAgentConversationService` chặn đúng thành `BLOCKED / UNVERIFIED_CITATION`; nhánh `rerank_mmr` trả `INSUFFICIENT_DATA`.
- Không mở rộng corpus, không nới validator, và **không** mặc định việc sửa `limit=1` sẽ giải quyết hallucination khi thiếu nguồn tri thức.

---

## 4. Kết quả Regression Tests Offline (`services/ai-service/tests/test_agent_loop.py`)

- `test_agent_search_knowledge_limit_1_uses_effective_top_k_with_saved_rerank_fixture`: Tái hiện chính xác `AG02-DEV:VI-NO-DIACRITICS-01` qua `BoundedAgentRunner` + `OpenAiAgentModelAdapter` + `OfflineToolGateway` + `RetrievalPipeline` + `HttpRerankerAdapter` dùng **15 candidates & Cohere scores đã lưu**; xác nhận dù model gửi `limit=1`, `RetrievalPipeline` nhận `effectiveLimit=5` (`candidate_limit=15`), giữ `WHO-PA-SB-2020-VI-05#1` trong `obs.data["chunks"]` và ghi `requestedLimit=1, effectiveLimit=5, returnedCount=5, candidateCount=15`. *(Lưu ý: câu trả lời cuối trong test này là mock để kiểm tra chọn lọc context & nối ống, không thay thế đánh giá chất lượng sinh câu trả lời live).*
- `test_search_knowledge_limit_edge_cases_and_no_padding`: Kiểm chứng `limit` bị bỏ qua (`5`), `limit=7` (`7`), vượt trần `limit=11` / `limit=0` (`ERROR`), ít evidence `2` chunks (không padding), rỗng `0` chunks (`NOT_FOUND` + metadata), và `rag_context_max_chars=180` (cắt dưới `effectiveLimit`).

---

## 5. Kế hoạch Live Retest Tối thiểu (Khi được duyệt chạy)

- **Cấu hình & Ngân sách:** Giữ `gpt-4o-mini-2024-07-18`, `text-embedding-3-small`, `rerank-v3.5`, `max_completion_tokens=800`, `candidate_limit=15`, `rag_search_min_top_k=5`, `rag_context_max_chars=8000`, corpus 9 docs WHO/NIDDK; tiếp tục trong trần lũy kế OpenAI `$0.50` (đã dùng `$0.021551`) và Cohere `$0.10` (đã dùng `$0.038`).
- **Cases cần retest (tối đa 15 runs hoặc ưu tiên smoke + các ca đối chứng):**
  1. `WHO-PA-SB-2020:E01` *(smoke gate)* — kiểm tra `VI-05#1` vào context và trả lời đúng khi model gọi `limit=1` hoặc `5`.
  2. `AG02-DEV:VI-NO-DIACRITICS-01` — kiểm tra cải thiện rerank/MMR (`VI-05#1`) chuyển thành câu trả lời đúng với citation `VI-05#1`.
  3. `AG02-DEV:EN-02` — kiểm tra regression/guardrail `PARTIAL_COVERAGE` (không bịa phần `ISSN`).
  4. `AG02-DEV:EMPTY-01` — kiểm tra `INSUFFICIENT_DATA` không citation khi `effective_limit=5`.
  5. `AG02-DEV:POLICY-01` — kiểm tra `NOT_EXERCISED` + `BLOCKED / RULE_BLOCKED`.
