# Báo cáo Sửa Lỗi Nhất quán Citation Key (`ToolGateway` vs `BoundedAgentRunner`) — 27/11/2026

**Phạm vi:** Agent `search_knowledge` & `BoundedAgentRunner` (`services/ai-service/app/agent/schemas.py`, `tools.py`, `loop.py`, `tests/agent_http_fixture.py`, `tests/test_agent_loop.py`). Public F01 và các API khác không thay đổi.  
**Trạng thái:** Offline only (`0` paid provider calls); giữ nguyên toàn bộ artifacts cũ trong `reports/evaluation/ablations/live-agent-e2e-retest-9docs-20261127/`.

---

## 1. Xác nhận Nguyên nhân & Định dạng Chuẩn Được Chọn

- **Đối chiếu raw observation & model output (`live-agent-e2e-retest-9docs-20261127`):**
  - `ToolGateway._exec_search_knowledge` (`tools.py`) phát sinh `sourceRefs` dạng `WHO-PA-SB-2020-VI-05@1.1.0#s1` (có tiền tố `s`).
  - `BoundedAgentRunner._run` (`loop.py`) lập chỉ mục `chunk_index` dạng `WHO-PA-SB-2020-VI-05@1.1.0#1` (không có `s`).
  - `OpenAiAgentModelAdapter` (`model_adapter.py`) hướng dẫn `'documentCode@version#sectionIndex'`, nhưng model sao chép `sourceRefs` từ `TOOL_OBSERVATIONS` thành `...#s1` trong `citedChunkKeys`, khiến `_build_verified_citations` không tìm thấy key và bác bỏ thành `BLOCKED / UNVERIFIED_CITATION`.
  - `services/ai-service/tests/agent_http_fixture.py` trước đây tự ghép lại `f"{chunk['documentCode']}@{chunk['version']}#{chunk['sectionIndex']}"` thay vì đọc từ `sourceRefs`, nên test HTTP E2E cũ đã không phát hiện lỗi này.
- **Định dạng chuẩn được chọn:** **`{documentCode}@{version}#{sectionIndex}`** (không có `s`), vì:
  1. Khớp trực tiếp hướng dẫn prompt trong `model_adapter.py:231` và định danh `(documentCode, version, sectionIndex)` khớp khóa duy nhất `(knowledge_version_id, chunk_index)` trong PostgreSQL & `AgentCitationValidator`.
  2. Ràng buộc đủ 3 chiều `documentCode`, `version`, `sectionIndex` (tránh collision giữa các version hoặc section khác nhau của cùng tài liệu).
  3. Không thêm dual-format alias để giữ hợp đồng chặt chẽ.

---

## 2. Phạm vi Sửa Đổi

1. **`services/ai-service/app/agent/schemas.py`:** Thêm helper dùng chung `format_chunk_citation_key(document_code, version, section_index) -> f"{document_code}@{version}#{int(section_index)}"`.
2. **`services/ai-service/app/agent/tools.py`:** `ToolGateway._exec_search_knowledge` dùng `format_chunk_citation_key(...)` để tạo `sourceRefs`.
3. **`services/ai-service/app/agent/loop.py`:** `BoundedAgentRunner._run` dùng `format_chunk_citation_key(...)` để tạo khóa `chunk_index`.
4. **`services/ai-service/tests/agent_http_fixture.py`:** Sửa fixture để model giả lập sao chép trực tiếp `searches[0]["sourceRefs"][0]` thay vì tự ghép lại key.
5. **`services/ai-service/tests/test_agent_loop.py`:** Thêm `test_tool_gateway_source_refs_match_runner_chunk_index_and_reject_invalid_keys` kiểm chứng xuyên `ToolGateway` → `TOOL_OBSERVATIONS` → model fixture → `BoundedAgentRunner` (`sectionIndex=0`, `sectionIndex=1`, nhiều versions `1.1.0`/`1.0.0`, và từ chối `#s1`, sai version, sai section, ngoài run).

---

## 3. Rà soát Cách Báo Kết quả & Đối chiếu Claim vs Context (`live-agent-e2e-retest-9docs-20261127`)

- **Phân biệt `turn_status=SUCCEEDED` và `resultType=BLOCKED`:**
  - Trong `AiAgentConversationService.java`, `turn_status=SUCCEEDED` chỉ phản ánh lượt thực thi HTTP/conversation hoàn tất không lỗi hạ tầng/timeout (`ai_runs.status = REJECTED_BY_VALIDATOR` khi `resultType=BLOCKED`). `resultType=BLOCKED` (`UNVERIFIED_CITATION` hoặc `RULE_BLOCKED`) nghĩa là không có câu trả lời được phát hành, **không** phải thành công về chất lượng.
- **Đối chiếu từng claim với context thực sự ở đợt retest vừa qua:**
  - `WHO-PA-SB-2020:E01` dưới `rrf_current` và `rerank`: **`VI-05#1` KHÔNG có trong 5 chunks context** (`VI-01#1, VI-06#0/1/3, VI-02#2` hoặc `VI-05#0`), nhưng model vẫn trả `"150 phút... 75 phút... 2 ngày mỗi tuần"` và trích dẫn `VI-01#s1`, `VI-06#s0/1/3`. Đây là dấu hiệu claim từ kiến thức ngoài `VI-05#1` / suy diễn, **không** thể coi là grounded chỉ vì nội dung đúng.
  - `WHO-PA-SB-2020:E01` dưới `rerank_mmr` và `AG02-DEV:VI-NO-DIACRITICS-01` dưới cả 3 nhánh: **`VI-05#1` CÓ trong context** (`#5` ở `WHO:E01` `rerank_mmr`; `#4`/`#3`/`#2` ở `VI-NO-DIACRITICS-01`), và câu trả lời nêu đúng khoảng `"150 đến 300 phút... 75 đến 150 phút... >= 2 ngày mỗi tuần"` kèm `WHO-PA-SB-2020-VI-05@1.1.0#s1`.

---

## 4. Kiểm thử Offline & Kế hoạch Retest Nhỏ nhất

- **Tests offline:** `pytest` (`129/129`), `ruff`, `validate:contracts`, `test:contracts` (`22/22`), và `AiAgentCrossLayerHttpE2EIntegrationTest` (Spring + `Testcontainers pgvector:pg18` + `uvicorn agent_http_fixture:app` sao chép `sourceRefs[0]`). Các test offline này chứng minh tính nhất quán key và validator, **chưa** chứng minh chất lượng model live.
- **Kế hoạch retest nhỏ nhất khi được duyệt:**
  1. Trước tiên chạy lại `AiAgentCrossLayerHttpE2EIntegrationTest` (đã bao gồm trong đợt kiểm tra offline này).
  2. Khi mở live retest có ngân sách: chỉ chạy **1 smoke (`WHO-PA-SB-2020:E01` dưới `rerank_mmr`) + `AG02-DEV:VI-NO-DIACRITICS-01` (3 nhánh) + `AG02-DEV:POLICY-01` (1 nhánh)** (= tối đa 5 runs, `<= $0.004` OpenAI, `<= $0.008` Cohere) trước khi cân nhắc chạy lại toàn bộ 15 runs.
