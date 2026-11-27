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
| **Run ID** | `run-20261126193538-96732e` |
| **Thời điểm chạy** | `2026-11-26T19:35:38.102Z` |
| **Chế độ chạy (Mode)** | `retrieval` |
| **Git Commit SHA** | `57e27f8f40ed385c931979e5b35cff660b9f9fa8` |
| **Trạng thái Git** | `DIRTY (DIRTY)` |
| **Dataset Version** | `0.1.0-dev-provisional` |
| **Dataset Hash (SHA-256)** | `0bdb6c6ebb24b4fc50c2104dc2315309cb7eb667e40e09639b46f40bec84b970` |
| **Manifest Verified** | `VERIFIED_MATCH` |
| **Holdout Status** | `SEPARATE_FROM_HOLDOUT` (Candidate development data) |
| **Top-k Đánh giá** | `5` |
| **Endpoint thực thi** | `http://127.0.0.1:18080/internal/v1/knowledge/retrieval` |
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
| **Document Hit@5** | **0.5714** | `CALCULATED` | 7 | Tỷ lệ tìm thấy ít nhất 1 tài liệu kỳ vọng |
| **Document Recall@5** | **0.5** | `CALCULATED` | 7 | Tỷ lệ tài liệu kỳ vọng được thu hồi (không bỏ tài liệu khó) |
| **MRR@5** | **0.3619** | `CALCULATED` | 7 | Thứ hạng nghịch đảo trung bình của tài liệu đúng đầu tiên |
| **Citation Document Match** | **N/A** | `NOT_EVALUATED` | 0 | Applicable only in public-f01 mode |
| **Chunk Hit@5** | **N/A** | `NOT_EVALUATED` | 0 | *Chunk-level ground truth labels not present in candidate dataset (labels exist at document level only)* |
| **Chunk Recall@5** | **N/A** | `NOT_EVALUATED` | 0 | *Chunk-level ground truth labels not present in candidate dataset (labels exist at document level only)* |
| **False Refusal Rate** | **N/A** | `NOT_EVALUATED` | 0 | Coverage not verified: declared coverage cannot produce verified false refusal metric (see exploratory_declared_metrics) |
| **Correct Abstention Rate** | **N/A** | `NOT_EVALUATED` | 0 | No refusal/guardrail cases |
| **Citation Contract Validity** | **N/A** | `NOT_EVALUATED` | 0 | No evaluated responses with citations |
| **Citation Support / Faithfulness** | **N/A** | `NOT_EVALUATED` | 0 | *Automated claim-level groundedness requires specialist rubric review (Milestone A-52); automated keyword heuristics prohibited* |


---

## 4. Thống Kê Phân Loại & Loại Trừ Case

- **Tổng số case khảo sát:** `16`
- **Số case đủ điều kiện chấm Retrieval (Verified):** `7`
- **Số case đủ điều kiện chấm Public F01:** `0`
- **Lỗi hạ tầng (Timeout / Upstream 5xx / Auth / Network):** `0`
- **Lỗi thực thi mô hình / hệ thống (Run FAILED):** `0`
- **Lỗi vi phạm JSON Schema phản hồi:** `0`
- **Loại trừ do chưa xác minh được Coverage (COVERAGE_UNKNOWN):** `0`
- **Loại trừ do chỉ có coverage DECLARED (không tính vào quality metric chính):** `0`
- **Loại trừ do thiếu Corpus Coverage trong DB verified:** `3`
- **Loại trừ do thiếu Corpus Coverage trong danh sách DECLARED:** `0`
- **Loại trừ do không có expected documents:** `6`

---

## 5. Danh Sách Lỗi & Cảnh Báo Nếu Có

*Không có lỗi hoặc cảnh báo nào trong phiên chạy.*
