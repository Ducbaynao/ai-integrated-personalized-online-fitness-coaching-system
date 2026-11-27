# Báo Cáo Đánh Giá F01 Evaluation Runner (A-49)

> [!NOTE]
> **SIMULATED EVALUATION HARNESS — CHƯA PHẢI BENCHMARK PRODUCTION**<br>
> Báo cáo này được sinh bởi runner tối thiểu A-49 trên tập dữ liệu ứng viên phát triển (candidate development dataset).<br>
> Không gian embedding và sinh văn bản được đánh dấu theo bằng chứng thực tế: **openai/text-embedding-3-small/1536** (DECLARED).<br>
> Đánh giá chất lượng mô hình thực tế, semantic retrieval và groundedness chuyên gia thuộc về **Milestone A-52**.

---

## 1. Thông Tin Phiên Chạy (Run Metadata)

| Trường thông tin | Giá trị ghi nhận |
| :--- | :--- |
| **Run ID** | `run-20261126203431-6761fa` |
| **Thời điểm chạy** | `2026-11-26T20:34:31.104Z` |
| **Chế độ chạy (Mode)** | `public-f01` |
| **Git Commit SHA** | `57e27f8f40ed385c931979e5b35cff660b9f9fa8` |
| **Trạng thái Git** | `DIRTY (DIRTY)` |
| **Dataset Version** | `0.1.0-dev-provisional` |
| **Dataset Hash (SHA-256)** | `0bdb6c6ebb24b4fc50c2104dc2315309cb7eb667e40e09639b46f40bec84b970` |
| **Manifest Verified** | `VERIFIED_MATCH` |
| **Holdout Status** | `SEPARATE_FROM_HOLDOUT` (Candidate development data) |
| **Top-k Đánh giá** | `5` |
| **Endpoint thực thi** | `http://127.0.0.1:18080/api/v1/ai/knowledge/query` |
| **Không gian Embedding** | `openai/text-embedding-3-small/1536` (`DECLARED`) |
| **Bằng chứng Coverage** | `SNAPSHOT_PROVIDED` (`ISOLATED_POSTGRESQL_OPENAI_OUTBOX`) |

---

## 2. Báo Cáo Độ Phủ Tri Thức Runtime (Corpus Coverage Report)

*Độ phủ tri thức được tách biệt hoàn toàn khỏi chất lượng thuật toán retrieval để tránh ngộ nhận.*

- **Tổng số case:** `13`
- **Case có Full Coverage (đầy đủ tài liệu & version active):** `4`
- **Case có Partial Coverage (thiếu một phần tài liệu active):** `2`
- **Case No Coverage (toàn bộ tài liệu kỳ vọng chưa active):** `3`
- **Case Coverage Unknown (chưa có bằng chứng runtime snapshot):** `0`
- **Case không kỳ vọng tài liệu (refusal/guardrail):** `4`

---

## 3. Bảng Tổng Hợp Chỉ Số (Metrics Summary)

| Chỉ số (Metric) | Kết quả | Trạng thái | Mẫu số | Giải thích & Quy tắc loại trừ |
| :--- | :---: | :---: | :---: | :--- |
| **Document Hit@5** | **N/A** | `NOT_EVALUATED` | 0 | Retrieval chunks not exposed by public F01 endpoint |
| **Document Recall@5** | **N/A** | `NOT_EVALUATED` | 0 | Retrieval chunks not exposed by public F01 endpoint |
| **MRR@5** | **N/A** | `NOT_EVALUATED` | 0 | Retrieval chunks not exposed by public F01 endpoint |
| **Citation Document Match** | **0.25** | `CALCULATED` | 4 | Tỷ lệ câu trả lời public trích dẫn đúng tài liệu kỳ vọng |
| **Chunk Hit@5** | **N/A** | `NOT_EVALUATED` | 0 | *Chunk-level ground truth labels not present in candidate dataset (labels exist at document level only)* |
| **Chunk Recall@5** | **N/A** | `NOT_EVALUATED` | 0 | *Chunk-level ground truth labels not present in candidate dataset (labels exist at document level only)* |
| **False Refusal Rate** | **0.5** | `CALCULATED` | 2 | Từ chối sai trên case có đầy đủ coverage |
| **Correct Abstention Rate** | **1** | `CALCULATED` | 2 | Từ chối hoặc chặn đúng trên case ngoài phạm vi / guardrail |
| **Citation Contract Validity** | **1** | `CALCULATED` | 9 | Trích dẫn tuân thủ schema và nằm trong allowlist nguồn |
| **Citation Support / Faithfulness** | **N/A** | `NOT_EVALUATED` | 0 | *Automated claim-level groundedness requires specialist rubric review (Milestone A-52); automated keyword heuristics prohibited* |


---

## 4. Thống Kê Phân Loại & Loại Trừ Case

- **Tổng số case khảo sát:** `13`
- **Số case đủ điều kiện chấm Retrieval (Verified):** `0`
- **Số case đủ điều kiện chấm Public F01:** `11`
- **Lỗi hạ tầng (Timeout / Upstream 5xx / Auth / Network):** `2`
- **Lỗi thực thi mô hình / hệ thống (Run FAILED):** `2`
- **Lỗi vi phạm JSON Schema phản hồi:** `0`
- **Loại trừ do chưa xác minh được Coverage (COVERAGE_UNKNOWN):** `0`
- **Loại trừ do chỉ có coverage DECLARED (không tính vào quality metric chính):** `0`
- **Loại trừ do thiếu Corpus Coverage trong DB verified:** `0`
- **Loại trừ do thiếu Corpus Coverage trong danh sách DECLARED:** `0`
- **Loại trừ do không có expected documents:** `0`

---

## 5. Danh Sách Lỗi & Cảnh Báo Nếu Có

- **`NIDDK-WEIGHT-FACTORS-2023:E01`**: [INFRASTRUCTURE] HTTP 500: Upstream backend error: {"runId":"0a2bf1a5-2b9d-4974-82d4-86f5a2671139","correlationId":"0d2732e1-5380-40e2-9d82-66fb151e1cc3","runStatus":"FAILED","reasonCode":"PROCESSING_ERROR","asOf":"2026-11-26T20:34:44Z","usage":{"promptTokens":3584,"completionTokens":800,"totalTokens":4384,"latencyMs":10969},"error":{"code":"INCOMPLETE_OUTPUT","message":"Model generation was truncated due to token limit."},"generationMetadata":{"schemaVersion":"1.0.0","promptVersion":"v1.0","ruleVersion":"f01-rule-v1","provider":"openai","model":"gpt-4o-mini-2024-07-18"}}
- **`AG02-DEV:VI-NO-DIACRITICS-01`**: [FALSE_REFUSAL] Result was INSUFFICIENT_DATA on direct knowledge inquiry with verified full coverage
- **`AG02-DEV:EN-01`**: [INFRASTRUCTURE] HTTP 500: Upstream backend error: {"runId":"2b59b108-bad6-4d62-bcea-2b1698d211c3","correlationId":"861ae617-1183-4bc0-afce-c6054fbb9883","runStatus":"FAILED","reasonCode":"PROCESSING_ERROR","asOf":"2026-11-26T20:35:04Z","usage":{"promptTokens":2733,"completionTokens":800,"totalTokens":3533,"latencyMs":10671},"error":{"code":"INCOMPLETE_OUTPUT","message":"Model generation was truncated due to token limit."},"generationMetadata":{"schemaVersion":"1.0.0","promptVersion":"v1.0","ruleVersion":"f01-rule-v1","provider":"openai","model":"gpt-4o-mini-2024-07-18"}}
- **`AG02-DEV:EMPTY-02`**: [EXECUTION_FAILURE] Run status: REJECTED_BY_VALIDATOR, reason: RULE_BLOCKED
- **`AG02-DEV:POLICY-01`**: [EXECUTION_FAILURE] Run status: REJECTED_BY_VALIDATOR, reason: RULE_BLOCKED
