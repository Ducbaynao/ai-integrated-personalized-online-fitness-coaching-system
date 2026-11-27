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
| **Run ID** | `run-20261126203334-2f37b7` |
| **Thời điểm chạy** | `2026-11-26T20:33:34.149Z` |
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

- **Tổng số case:** `1`
- **Case có Full Coverage (đầy đủ tài liệu & version active):** `1`
- **Case có Partial Coverage (thiếu một phần tài liệu active):** `0`
- **Case No Coverage (toàn bộ tài liệu kỳ vọng chưa active):** `0`
- **Case Coverage Unknown (chưa có bằng chứng runtime snapshot):** `0`
- **Case không kỳ vọng tài liệu (refusal/guardrail):** `0`

---

## 3. Bảng Tổng Hợp Chỉ Số (Metrics Summary)

| Chỉ số (Metric) | Kết quả | Trạng thái | Mẫu số | Giải thích & Quy tắc loại trừ |
| :--- | :---: | :---: | :---: | :--- |
| **Document Hit@5** | **N/A** | `NOT_EVALUATED` | 0 | Retrieval chunks not exposed by public F01 endpoint |
| **Document Recall@5** | **N/A** | `NOT_EVALUATED` | 0 | Retrieval chunks not exposed by public F01 endpoint |
| **MRR@5** | **N/A** | `NOT_EVALUATED` | 0 | Retrieval chunks not exposed by public F01 endpoint |
| **Citation Document Match** | **N/A** | `NOT_EVALUATED` | 0 | No evaluable cases expecting citations |
| **Chunk Hit@5** | **N/A** | `NOT_EVALUATED` | 0 | *Chunk-level ground truth labels not present in candidate dataset (labels exist at document level only)* |
| **Chunk Recall@5** | **N/A** | `NOT_EVALUATED` | 0 | *Chunk-level ground truth labels not present in candidate dataset (labels exist at document level only)* |
| **False Refusal Rate** | **N/A** | `NOT_EVALUATED` | 0 | Coverage not verified: declared coverage cannot produce verified false refusal metric (see exploratory_declared_metrics) |
| **Correct Abstention Rate** | **N/A** | `NOT_EVALUATED` | 0 | No refusal/guardrail cases |
| **Citation Contract Validity** | **N/A** | `NOT_EVALUATED` | 0 | No schema-valid evaluable public responses |
| **Citation Support / Faithfulness** | **N/A** | `NOT_EVALUATED` | 0 | *Automated claim-level groundedness requires specialist rubric review (Milestone A-52); automated keyword heuristics prohibited* |


---

## 4. Thống Kê Phân Loại & Loại Trừ Case

- **Tổng số case khảo sát:** `1`
- **Số case đủ điều kiện chấm Retrieval (Verified):** `0`
- **Số case đủ điều kiện chấm Public F01:** `0`
- **Lỗi hạ tầng (Timeout / Upstream 5xx / Auth / Network):** `1`
- **Lỗi thực thi mô hình / hệ thống (Run FAILED):** `0`
- **Lỗi vi phạm JSON Schema phản hồi:** `0`
- **Loại trừ do chưa xác minh được Coverage (COVERAGE_UNKNOWN):** `0`
- **Loại trừ do chỉ có coverage DECLARED (không tính vào quality metric chính):** `0`
- **Loại trừ do thiếu Corpus Coverage trong DB verified:** `0`
- **Loại trừ do thiếu Corpus Coverage trong danh sách DECLARED:** `0`
- **Loại trừ do không có expected documents:** `0`

---

## 5. Danh Sách Lỗi & Cảnh Báo Nếu Có

- **`WHO-PA-SB-2020:E01`**: [INFRASTRUCTURE] HTTP 502: Upstream backend error: {"runId":"ce999cd1-4407-4dc5-9114-bb502ba10a32","correlationId":"6cb782a4-869f-4234-aca9-a7e8a81f06bd","runStatus":"REJECTED_BY_VALIDATOR","reasonCode":"VALIDATION_FAILED","asOf":"2026-11-26T20:33:34Z","usage":{"promptTokens":2299,"completionTokens":265,"totalTokens":2564,"latencyMs":5781},"error":{"code":"UNVERIFIED_CITATION","message":"Model generated citation (WHO-PA-SB-2020-VI-04 v1.1.0) not present in retrieved evidence chunks."},"generationMetadata":{"schemaVersion":"1.0.0","promptVersion":"v1.0","ruleVersion":"f01-rule-v1","provider":"openai","model":"gpt-4o-mini-2024-07-18"}}
