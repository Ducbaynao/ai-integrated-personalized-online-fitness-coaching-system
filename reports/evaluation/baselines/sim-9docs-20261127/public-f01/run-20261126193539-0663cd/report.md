# Báo Cáo Đánh Giá F01 Evaluation Runner (A-49)

> [!NOTE]
> **SIMULATED EVALUATION HARNESS — CHƯA PHẢI BENCHMARK PRODUCTION**<br>
> Báo cáo này được sinh bởi runner tối thiểu A-49 trên tập dữ liệu ứng viên phát triển (candidate development dataset).<br>
> Không gian embedding và sinh văn bản được đánh dấu theo bằng chứng thực tế: **simulated/deterministic-1536/1.0.0** (DECLARED).<br>
> Đánh giá chất lượng mô hình thực tế, semantic retrieval và groundedness chuyên gia thuộc về **Milestone A-52**.

---

## 1. Thông Tin Phiên Chạy (Run Metadata)

| Trường thông tin | Giá trị ghi nhận |
| :--- | :--- |
| **Run ID** | `run-20261126193539-0663cd` |
| **Thời điểm chạy** | `2026-11-26T19:35:39.180Z` |
| **Chế độ chạy (Mode)** | `public-f01` |
| **Git Commit SHA** | `57e27f8f40ed385c931979e5b35cff660b9f9fa8` |
| **Trạng thái Git** | `DIRTY (DIRTY)` |
| **Dataset Version** | `0.1.0-dev-provisional` |
| **Dataset Hash (SHA-256)** | `0bdb6c6ebb24b4fc50c2104dc2315309cb7eb667e40e09639b46f40bec84b970` |
| **Manifest Verified** | `VERIFIED_MATCH` |
| **Holdout Status** | `SEPARATE_FROM_HOLDOUT` (Candidate development data) |
| **Top-k Đánh giá** | `5` |
| **Endpoint thực thi** | `http://127.0.0.1:18080/api/v1/ai/knowledge/query` |
| **Không gian Embedding** | `simulated/deterministic-1536/1.0.0` (`DECLARED`) |
| **Bằng chứng Coverage** | `SNAPSHOT_PROVIDED` (`ISOLATED_POSTGRESQL_SIMULATED_OUTBOX`) |

---

## 2. Báo Cáo Độ Phủ Tri Thức Runtime (Corpus Coverage Report)

*Độ phủ tri thức được tách biệt hoàn toàn khỏi chất lượng thuật toán retrieval để tránh ngộ nhận.*

- **Tổng số case:** `16`
- **Case có Full Coverage (đầy đủ tài liệu & version active):** `5`
- **Case có Partial Coverage (thiếu một phần tài liệu active):** `2`
- **Case No Coverage (toàn bộ tài liệu kỳ vọng chưa active):** `3`
- **Case Coverage Unknown (chưa có bằng chứng runtime snapshot):** `0`
- **Case không kỳ vọng tài liệu (refusal/guardrail):** `6`

---

## 3. Bảng Tổng Hợp Chỉ Số (Metrics Summary)

| Chỉ số (Metric) | Kết quả | Trạng thái | Mẫu số | Giải thích & Quy tắc loại trừ |
| :--- | :---: | :---: | :---: | :--- |
| **Document Hit@5** | **N/A** | `NOT_EVALUATED` | 0 | Retrieval chunks not exposed by public F01 endpoint |
| **Document Recall@5** | **N/A** | `NOT_EVALUATED` | 0 | Retrieval chunks not exposed by public F01 endpoint |
| **MRR@5** | **N/A** | `NOT_EVALUATED` | 0 | Retrieval chunks not exposed by public F01 endpoint |
| **Citation Document Match** | **0.2** | `CALCULATED` | 10 | Tỷ lệ câu trả lời public trích dẫn đúng tài liệu kỳ vọng |
| **Chunk Hit@5** | **N/A** | `NOT_EVALUATED` | 0 | *Chunk-level ground truth labels not present in candidate dataset (labels exist at document level only)* |
| **Chunk Recall@5** | **N/A** | `NOT_EVALUATED` | 0 | *Chunk-level ground truth labels not present in candidate dataset (labels exist at document level only)* |
| **False Refusal Rate** | **0** | `CALCULATED` | 5 | Từ chối sai trên case có đầy đủ coverage |
| **Correct Abstention Rate** | **0** | `CALCULATED` | 15 | Từ chối hoặc chặn đúng trên case ngoài phạm vi / guardrail |
| **Citation Contract Validity** | **1** | `CALCULATED` | 16 | Trích dẫn tuân thủ schema và nằm trong allowlist nguồn |
| **Citation Support / Faithfulness** | **N/A** | `NOT_EVALUATED` | 0 | *Automated claim-level groundedness requires specialist rubric review (Milestone A-52); automated keyword heuristics prohibited* |


---

## 4. Thống Kê Phân Loại & Loại Trừ Case

- **Tổng số case khảo sát:** `16`
- **Số case đủ điều kiện chấm Retrieval (Verified):** `0`
- **Số case đủ điều kiện chấm Public F01:** `16`
- **Lỗi hạ tầng (Timeout / Upstream 5xx / Auth / Network):** `0`
- **Lỗi thực thi mô hình / hệ thống (Run FAILED):** `0`
- **Lỗi vi phạm JSON Schema phản hồi:** `0`
- **Loại trừ do chưa xác minh được Coverage (COVERAGE_UNKNOWN):** `0`
- **Loại trừ do chỉ có coverage DECLARED (không tính vào quality metric chính):** `0`
- **Loại trừ do thiếu Corpus Coverage trong DB verified:** `0`
- **Loại trừ do thiếu Corpus Coverage trong danh sách DECLARED:** `0`
- **Loại trừ do không có expected documents:** `0`

---

## 5. Danh Sách Lỗi & Cảnh Báo Nếu Có

- **`WHO-PA-SB-2020:E01`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`WHO-PA-SB-2020:E02`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`ISSN-PROTEIN-EXERCISE-2017:E01`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`NIDDK-WEIGHT-FACTORS-2023:E01`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`NIH-ODS-EXERCISE-SUPPLEMENTS:E01`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`AG02-DEV:VI-NO-DIACRITICS-01`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`AG02-DEV:VI-NO-DIACRITICS-02`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`AG02-DEV:EN-01`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`AG02-DEV:EN-02`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`AG02-DEV:MIXED-01`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`AG02-DEV:MIXED-02`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`AG02-DEV:EMPTY-01`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`AG02-DEV:POLICY-01`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`AG02-DEV:PERM-CROSS-USER-01`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
- **`AG02-DEV:PERM-REVOKED-02`**: [FAILED_ABSTENTION] Expected refusal/blocked but got INFORMATION
