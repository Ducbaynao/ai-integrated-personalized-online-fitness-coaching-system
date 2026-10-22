# Báo Cáo Đánh Giá Chất Lượng F01: A-52 — Quality Evaluation & Issue Remediation

> **Mã đầu việc:** A-52 — Đánh giá chất lượng F01 trong phạm vi corpus ACTIVE, điều tra và khắc phục các vấn đề đã ghi nhận<br>
> **Trạng thái:** HOÀN THÀNH ĐIỀU TRA & KHẮC PHỤC KỸ THUẬT (SẴN SÀNG REVIEW)<br>
> **Branch làm việc:** `feat/f01-a52-quality-evaluation`<br>
> **Commit gốc (Base commit):** `5d265ede7b172ec43f11c83dde07f034518d0ba1` (HEAD của `origin/main` sau khi merge PR #99)<br>
> **Ngày thực hiện:** 2026-10-22

---

## 1. Bối Cảnh, Phạm Vi & Trạng Thái Hệ Thống

### 1.1. Phạm vi thực tế & Bằng chứng trực tiếp
- **Môi trường:** Local testbed cô lập, kết nối PostgreSQL container (`localhost:5433` - `digital-fitness-postgres`).
- **Kho tri thức ACTIVE thực tế:**
  - Tổng số tài liệu: **9 tài liệu ACTIVE** (5 tài liệu thuộc nguồn `WHO-PA-SB-2020` và 4 tài liệu thuộc nguồn `NIDDK-WEIGHT-FACTORS-2023`).
  - Danh mục document ID đang ACTIVE:
    - WHO: `WHO-PA-SB-2020-VI-01`, `WHO-PA-SB-2020-VI-02`, `WHO-PA-SB-2020-VI-03`, `WHO-PA-SB-2020-VI-05`, `WHO-PA-SB-2020-VI-06`.
    - NIDDK: `NIDDK-WEIGHT-FACTORS-2023-VI-01`, `NIDDK-WEIGHT-FACTORS-2023-VI-02`, `NIDDK-WEIGHT-FACTORS-2023-VI-03`, `NIDDK-WEIGHT-FACTORS-2023-VI-04`.
  - Phân mảnh & Vector: **29 chunks** và **29 vectors** được lưu trữ trong bảng `fitness.knowledge_chunks` và `fitness.knowledge_embeddings`.
  - Không gian vector: `openai/text-embedding-3-small/1536` (Model Version ID: `e1000000-0000-0000-0000-000000000002`).
  - Tài liệu lưu giữ dạng nháp: `NIDDK-WEIGHT-FACTORS-2023-VI-05` và `VI-06` vẫn giữ trạng thái `HELD_AS_DRAFT` (tuân thủ nghiêm ngặt quy tắc quản trị corpus, không mở khóa trái phép).
  - Tài liệu chưa có trong runtime: Nguồn `ISSN-PROTEIN-EXERCISE-2017` và `NIH-ODS-EXERCISE-SUPPLEMENTS` chưa được kích hoạt trong runtime.

### 1.2. Phân định nguồn gốc bằng chứng
1. **Bằng chứng kiểm tra trực tiếp trong lượt này:**
   - Trạng thái Git: Working tree hiện đang chứa đúng 6 file modified và 1 file untracked (`f01-a52-quality-evaluation-report.md`) thuộc gói A-52; chưa tạo commit, không có thay đổi dở dang ngoài phạm vi.
   - Trạng thái DB và số lượng document/chunk/vector qua truy vấn PostgreSQL `digital-fitness-postgres`.
   - Lịch sử runs và chi tiết payload trong các bảng `fitness.ai_runs`, `fitness.ai_context_snapshots`, `fitness.ai_validation_results`.
   - Kết quả test suites thực tế:
     - Backend selected unit tests: 15/15 PASS (gồm 8 test `F01RuntimeOutputValidatorTest` và 7 test `HttpF01AiServiceClientTest`).
     - AI Service pytest: 44/44 PASS (`services/ai-service`).
     - AI Service Ruff check: `ruff check .` PASS (All checks passed).
     - Runner offline test suite: 24/24 PASS (`node --test tools/run-f01-evaluation.test.mjs`).
     - Schema validation test: PASS (`node tools/schema-validator.mjs`).
2. **Bằng chứng lịch sử kế thừa:**
   - Development retrieval baseline ID: `run-20261019234346-dcc3c0` (chạy trên 42 ca ứng viên WHO và NIDDK).
   - Smoke test đơn lẻ trên commit `015b0e5` với citation `WHO-PA-SB-2020-VI-05`.
3. **Giới hạn chưa xác minh (Pending):**
   - **Hành vi model sau sửa:** Cấu hình prompt/schema đã sửa và vượt qua offline unit tests; tuy nhiên hành vi sinh nội dung thực tế của model chưa được xác minh bằng live API call trong phiên này (do tuân thủ ranh giới ngân sách và quyền truy cập API).
   - **Chưa có Independent Holdout:** Đánh giá chất lượng toàn diện (Generation Quality & Claim-level Groundedness) trên tập Holdout độc lập chưa được thực hiện. Tập Holdout 60 ca và thành phần reviewer hiện giữ ở trạng thái đề xuất, cần được chủ dự án phê duyệt trước khi thu thập và gán nhãn.

---

## 2. Đối Chiếu Coverage & Mẫu Số Metrics

### 2.1. Phân loại Coverage trên Toàn Bộ Tập Ứng Viên (114 Cases)
Bộ dữ liệu ứng viên [`data/evaluation/f01-evaluation-manifest.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/data/evaluation/f01-evaluation-manifest.json) gồm **114 ca** thuộc nhóm `development_candidate`. Khi đối chiếu với kho tri thức 9 tài liệu ACTIVE thông qua thuật toán chuẩn `evaluateCaseCoverage` trong [`tools/evaluation-metrics.mjs`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/tools/evaluation-metrics.mjs), kết quả phân loại chuẩn xác như sau:

| Nguồn tri thức (Source ID) | Tổng số ca | Full Coverage (100% tài liệu kỳ vọng ACTIVE) | Partial Coverage (Có $\ge 1$ tài liệu ACTIVE) | No Coverage (0 tài liệu kỳ vọng ACTIVE) |
| :--- | :---: | :---: | :---: | :---: |
| `WHO-PA-SB-2020` | 24 | 11 | 5 | 8 |
| `NIDDK-WEIGHT-FACTORS-2023` | 18 | 10 | 0 | 8 |
| `ISSN-PROTEIN-EXERCISE-2017` | 30 | 0 | 0 | 30 |
| `NIH-ODS-EXERCISE-SUPPLEMENTS` | 42 | 0 | 0 | 42 |
| **TỔNG CỘNG** | **114** | **21** | **5** | **88** |

### 2.2. Giải thích chênh lệch số liệu và Mẫu số Metrics
- **Tại sao Baseline lịch sử ghi 26 ca có coverage?**
  - Trong run baseline `run-20261019234346-dcc3c0`, phạm vi chạy được lọc chỉ gồm **42 ca ứng viên** thuộc 2 nguồn WHO (24 ca) và NIDDK (18 ca).
  - Trong 42 ca này: có đúng **21 ca Full Coverage** + **5 ca Partial Coverage** = **26 ca có coverage khả dụng** (`evaluable_retrieval_cases: 26`).
  - 16 ca còn lại (8 WHO + 8 NIDDK) bị loại trừ khỏi phép đo với lý do `VERIFIED_CORPUS_COVERAGE_MISSING`.
- **Tại sao toàn bộ tập 114 ca có 88 ca No Coverage?**
  - 88 ca No Coverage gồm: 16 ca từ WHO và NIDDK (do tài liệu chưa publish hoặc HELD_AS_DRAFT) + 30 ca ISSN + 42 ca NIH-ODS (hai nguồn này chưa ingest vào runtime).
  - Tổng số ca có độ bao phủ khả dụng trong toàn bộ manifest 114 ca vẫn giữ nguyên là **26 ca** (21 Full + 5 Partial).
- **Đính chính số liệu trong bản thảo báo cáo trước:**
  - Bản thảo trước ghi nhầm "10 ca full coverage, 15 ca partial coverage, 89 ca no coverage". Đây là lỗi nhầm lẫn số học (10 ca full của riêng NIDDK bị gán thành toàn bộ, và 15 ca bị ghi nhận sai).
  - Tỷ lệ chính xác tuyệt đối: **21 Full Coverage, 5 Partial Coverage, 88 No Coverage (Tổng 114)**.

### 2.3. Bảng Hiệu Năng Retrieval Baseline (run-20261019234346-dcc3c0)

| Chỉ số Retrieval | Bộ đánh giá cơ sở (A-49 Baseline)<br>*(Full + Partial, N=26)* | Phân nhóm Full Coverage<br>*(Đủ 100% tài liệu, N=21)* | Phân nhóm Partial Coverage<br>*(Thiếu một phần tài liệu, N=5)* |
| :--- | :---: | :---: | :---: |
| **Trạng thái** | `CALCULATED` | `CALCULATED` | `CALCULATED` |
| **Mẫu số (Denominator)** | **26** | **21** | **5** |
| **Document Hit@5** | **0.8846** (23 / 26) | **0.9524** (20 / 21) | **0.6000** (3 / 5) |
| **Document Recall@5** | **0.8077** (21.0 / 26) | **0.9286** (19.5 / 21) | **0.3000** (1.5 / 5) |
| **MRR@5** | **0.6744** | **0.7857** | **0.2067** |
| **Chunk Hit@5** | **NOT_EVALUATED** | **NOT_EVALUATED** | **NOT_EVALUATED** |
| *Lý do Chunk Hit* | *Chưa đánh giá do dataset ứng viên chưa có nhãn ground truth cấp chunk (chỉ có cấp document)* | | |

> [!NOTE]
> **Đính chính Chunk Hit@5:** Bản thảo báo cáo trước từng ghi "Chunk Hit@5: 9/10". Qua kiểm tra trực tiếp file [`summary.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/reports/evaluation/baselines/baseline-run-20261020-064323-684/run-20261019234346-dcc3c0/summary.json), `chunk_hit_at_k` mang giá trị `null` (`status: NOT_EVALUATED`). Hiện tại chưa có nhãn ground truth cấp chunk nào được chuyên gia phê duyệt. Báo cáo này đính chính và loại bỏ con số không có căn cứ trên.

---

## 3. Xác Minh Phân Tích Retrieval Misses (E15, E19, E24)

Trích xuất trực tiếp từ artifact [`results.jsonl`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/reports/evaluation/baselines/baseline-run-20261020-064323-684/run-20261019234346-dcc3c0/results.jsonl) của run baseline `run-20261019234346-dcc3c0`:

### 3.1. WHO-PA-SB-2020:E15 (Full Coverage Miss)
- **Câu hỏi trong Dataset:** *"Trên 300 phút vừa bị WHO cấm?"*
- **Tài liệu kỳ vọng:** `WHO-PA-SB-2020-VI-05` (Vận động và tăng cường cơ ở tuổi 18–64, v1.1.0).
- **Kết quả Top 5 trả về thực tế:**
  1. Rank 1: `WHO-PA-SB-2020-VI-01` (chunk `1443be82...`, title "Phạm vi và cách sử dụng WHO 2020", score: 0.5056)
  2. Rank 2: `WHO-PA-SB-2020-VI-06` (chunk `a9a7e143...`, title "Hành vi ít vận động ở tuổi 18–64 > Giới hạn", score: 0.4689)
  3. Rank 3: `WHO-PA-SB-2020-VI-01` (chunk `c0bb10df...`, title "Phạm vi và cách sử dụng WHO 2020 > Nội dung", score: 0.4438)
  4. Rank 4: `WHO-PA-SB-2020-VI-03` (chunk `0e7a6708...`, title "MET và cường độ vận động", score: 0.4275)
  5. Rank 5: `WHO-PA-SB-2020-VI-06` (chunk `d41d1ac4...`, title "Hành vi ít vận động ở tuổi 18–64 > Truy vết nguồn", score: 0.4122)
- **Xác minh nguyên nhân & Đính chính:**
  - *Đính chính:* Bản thảo trước nêu nhầm các chunk cạnh tranh là `VI-04` và `VI-08`. Trên thực tế, testbed 9 tài liệu không chứa `VI-04` và `VI-08`. Các tài liệu cạnh tranh thực sự là **`VI-01`** và **`VI-06`**.
  - *Quan sát thứ hạng:* Tài liệu kỳ vọng `VI-05` bị đẩy xuống ngoài Top 5, dẫn tới Hit = 0.
  - *Giả thuyết giải thích:* Câu hỏi chứa sắc thái phủ định/nghi vấn ("bị WHO cấm?") khiến embedding ngữ nghĩa gần hơn với các đoạn trích bàn về *"Giới hạn"* (`VI-06`) và *"Phạm vi áp dụng"* (`VI-01`) thay vì đoạn trích khuyến nghị số phút aerobic (`VI-05`).
  - *Hướng xử lý:* Việc nghiên cứu thêm phương án reranking (hybrid search / cross-encoder) có thể được đánh giá trong tương lai nếu có bằng chứng so sánh chứng minh cải thiện thứ hạng; không triển khai trong phạm vi A-52.

### 3.2. WHO-PA-SB-2020:E19 (Partial Coverage Miss)
- **Câu hỏi trong Dataset:** *"Hãy thay giáo án của tôi theo khuyến nghị WHO."*
- **Tài liệu kỳ vọng:** `WHO-PA-SB-2020-VI-05` (ACTIVE) và `WHO-PA-SB-2020-VI-16` (Trẻ em dưới 5 tuổi, chưa publish).
- **Kết quả Top 5 trả về thực tế:**
  - Rank 1, 2, 4 thuộc về `WHO-PA-SB-2020-VI-01` (score từ 0.4240 đến 0.4909).
  - Rank 3: `WHO-PA-SB-2020-VI-03`, Rank 5: `WHO-PA-SB-2020-VI-06`.
- **Nguyên nhân xác minh:** Câu hỏi mang tính chất yêu cầu thay đổi giáo án cá nhân. Khái niệm này tương đồng ngữ nghĩa cao với tuyên bố ranh giới trong `VI-01`: *"Các khuyến nghị cần được điều chỉnh theo bối cảnh... không phải giáo án cá nhân"*, khiến `VI-01` chiếm trọn Top 5 và làm mất dấu `VI-05`.

### 3.3. WHO-PA-SB-2020:E24 (Partial Coverage Miss)
- **Câu hỏi trong Dataset:** *"Dùng dữ liệu nghiên cứu giảm 28% để dự báo nguy cơ ngã của tôi?"*
- **Tài liệu kỳ vọng:** `WHO-PA-SB-2020-VI-01` (ACTIVE) và `WHO-PA-SB-2020-VI-12` (Bệnh mạn tính, chưa publish).
- **Kết quả Top 5 trả về thực tế:**
  - Rank 1, 3, 4 thuộc về `WHO-PA-SB-2020-VI-05` (score: 0.3889 – 0.4406, đoạn *"Lợi ích và căn cứ"*).
  - Rank 2: `WHO-PA-SB-2020-VI-03`, Rank 5: `WHO-PA-SB-2020-VI-02`.
- **Nguyên nhân xác minh:** Dữ liệu nghiên cứu định lượng giảm rủi ro trong câu hỏi kéo semantic vector về phần bằng chứng dịch tễ của `VI-05` thay vì điều khoản giới hạn phạm vi của `VI-01`.

### 3.4. Bảng Đính Chính Lỗi Sao Chép Nhãn Câu Hỏi Lịch Sử
File tài liệu lịch sử [`full-coverage-supplementary-report.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/reports/evaluation/baselines/baseline-run-20261020-064323-684/run-20261019234346-dcc3c0/full-coverage-supplementary-report.md) đã sao chép nhầm nhãn câu hỏi cho 5 ca partial coverage. Bảng dưới đây đối chiếu và đính chính dựa trên nguồn chân lý gốc [`data/evaluation/f01-evaluation-manifest.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/data/evaluation/f01-evaluation-manifest.json):

| Case Key | Nhãn ghi nhầm trong báo cáo lịch sử cũ | Câu hỏi chuẩn xác trong Manifest gốc (`question_vi`) | Tài liệu kỳ vọng chuẩn |
| :--- | :--- | :--- | :--- |
| `WHO-PA-SB-2020:E12` | *Thời gian sedentary có trần số giờ ngồi cụ thể?* | **"Ngồi xe lăn có luôn là sedentary không?"** | `VI-02`, `VI-11` |
| `WHO-PA-SB-2020:E16` | *Bằng chứng sedentary cho người khuyết tật?* | **"Người bệnh thận nào cũng áp dụng phần bệnh mạn tính này?"** | `VI-01`, `VI-09` |
| `WHO-PA-SB-2020:E19` | *Trẻ em dưới 5 tuổi áp dụng khuyến nghị 18–64?* | **"Hãy thay giáo án của tôi theo khuyến nghị WHO."** | `VI-05`, `VI-16` |
| `WHO-PA-SB-2020:E23` | *Phụ nữ mang thai áp dụng khuyến nghị 18–64?* | **"Tôi chưa đạt lượng vận động WHO, hãy tự tạo plan tuần mới."** | `VI-05`, `VI-16` |
| `WHO-PA-SB-2020:E24` | *Bệnh nhân tăng huyết áp có tài liệu riêng?* | **"Dùng dữ liệu nghiên cứu giảm 28% để dự báo nguy cơ ngã của tôi?"** | `VI-01`, `VI-12` |

---

## 4. Điều Tra & Khắc Phục Lỗi Citation Rejections

### 4.1. Dữ liệu Run thực tế trong PostgreSQL
Truy vấn trực tiếp từ bảng `fitness.ai_runs` và `fitness.ai_context_snapshots`:
- **Run `30802098-d6e2-483a-9940-55c7ddaad0c9`:**
  - *Câu hỏi:* `"Theo WHO, người trưởng thành nên vận động kháng lực cường độ vừa bao nhiêu phút mỗi tuần?"`
  - *Kết quả:* `runStatus = REJECTED_BY_VALIDATOR`, `reasonCode = VALIDATION_FAILED`.
  - *Lỗi:* `UNVERIFIED_CITATION: Citation quoteOrExcerpt is not supported by the retrieved chunk content.` (Model gọi OpenAI sinh 548 completion tokens nhưng bị validator chặn).
- **Run `183d951c-5318-4d6f-a180-8f6170e8711e`:**
  - *Câu hỏi:* `"giấc ngủ quan trọng thế nào trong tập luyện"`
  - *Kết quả:* `runStatus = REJECTED_BY_VALIDATOR`, `reasonCode = VALIDATION_FAILED`.
  - *Lỗi:* `UNVERIFIED_CITATION: Citation quoteOrExcerpt is not supported by the retrieved chunk content.` (Model gọi OpenAI sinh 372 completion tokens nhưng bị validator chặn).

### 4.2. Phân tích nguyên nhân gốc
1. **Thiếu thông tin trong tài liệu nguồn:**
   - Trong `WHO-PA-SB-2020-VI-05`, khuyến nghị A03 chỉ nêu: *"Tăng cường cơ ở cường độ vừa hoặc cao hơn... ít nhất 2 ngày/tuần"*. WHO **không** quy định số phút mỗi tuần cho tập kháng lực.
   - Trong toàn bộ tài liệu WHO, giấc ngủ chỉ xuất hiện dưới dạng loại trừ định nghĩa trong `VI-06`: *"Ngủ không thuộc sedentary behaviour"*. Nguồn hoàn toàn không có hướng dẫn về cơ chế giấc ngủ cho vận động viên.
2. **Hành vi sinh nội dung của mô hình:**
   - Mô hình OpenAI đã cố trả lời ở chế độ `ANSWERED` thay vì từ chối bằng `INSUFFICIENT_EVIDENCE`.
   - Trong quá trình sinh trích dẫn, mô hình đã diễn giải lại (paraphrase) chuỗi trích dẫn. Khi chuỗi này đi qua hàm chuẩn hóa `normalize_text_for_comparison`:
     `norm_quote not in norm_content`
     Validator trong [`openai_provider.py`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/ai-service/app/integrations/openai_provider.py) lập tức phát hiện chuỗi trích dẫn không khớp chính xác từng từ trong chunk và kích hoạt rào chắn từ chối `UNVERIFIED_CITATION`.

### 4.3. Biện pháp khắc phục kỹ thuật
- **Bảo toàn bất biến kiểm soát:** Tuyệt đối **không nới lỏng** validator `UNVERIFIED_CITATION`. Rào chắn này là then chốt bảo vệ tính toàn vẹn và chống bịa đặt trích dẫn (citation integrity).
- **Hoàn thiện cấu hình Prompt & Schema (`services/ai-service/app/integrations/openai_provider.py`):**
  1. Trong JSON Schema: Cập nhật mô tả `quoteOrExcerpt`:
     > *"Exact continuous verbatim substring excerpted directly from the chunk content that directly supports this claim. Do not alter words or paraphrase."*
     (Loại bỏ cụm từ "MUST NOT be truncated" để tránh mô hình hiểu lầm là không được trích một đoạn ngắn hợp lệ).
  2. Trong System Prompt Rule 5: Khái quát hóa chỉ dẫn xử lý thiếu thông tin theo chuẩn nghiệp vụ (loại bỏ việc hardcode các câu hỏi thử nghiệm cụ thể):
     > *"5. Xử lý thiếu dữ liệu hoặc câu hỏi nằm ngoài phạm vi tài liệu: Nếu các Evidence Chunks không cung cấp đủ bằng chứng để trả lời câu hỏi, hoặc câu hỏi yêu cầu các biến số định lượng/cơ chế cụ thể mà tài liệu không nêu, bạn PHẢI đặt status là 'INSUFFICIENT_EVIDENCE' và nêu rõ tại missingData/limitations. Tuyệt đối không tự suy diễn hoặc dùng kiến thức ngoài nguồn để bổ sung số liệu."*
- **Kiểm chứng:**
  - Bổ sung unit test regression trong [`services/ai-service/tests/test_openai_provider.py`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/ai-service/tests/test_openai_provider.py#L1074-L1089).
  - Kết quả kiểm thử: **44/44 tests pytest PASS**.
  - *Lưu ý về phạm vi kiểm chứng:* Test prompt/schema chứng minh cấu hình payload gửi đi nhà cung cấp; test validator chứng minh rào chắn chặn output vi phạm. Các test này chưa chứng minh hành vi của model thật đã hoàn toàn loại bỏ ảo giác. Trong phiên làm việc này chưa thực hiện gọi live OpenAI mới (chưa có quyền chi tiêu/ngân sách batch được xác nhận trong phiên); việc chạy live regression kiểm chứng mô hình thuộc bước tiếp theo khi có ngân sách.

---

## 5. Điều Tra & Khắc Phục Lỗi Timeout & Deadline Inversion

### 5.1. Phân tích chuỗi Timeout toàn hệ thống

```
[Mobile Client (Đề xuất: 30s)]
       │
       ▼
[Spring Boot Backend] ── (Read Timeout: 25.0s / Connect: 5.0s)
       │
       ▼
[FastAPI AI Service] ── (Overall Deadline: 22.0s / Connect: 5.0s / Read: 12.0s)
       │
       ▼
[OpenAI API / Vector DB]
```

- **Lỗ hổng nghịch đảo thời gian chờ (Deadline Inversion):**
  - Cấu hình cũ: Spring Boot `ai.service.read-timeout-ms = 10000` (10 giây).
  - FastAPI: `openai_overall_timeout_seconds = 22.0` (22 giây) với cơ chế thử lại có giãn cách (retry with backoff).
  - Khi OpenAI cần 12–15 giây để phản hồi, FastAPI vẫn đang xử lý bình thường trong hạn mức 22 giây, nhưng Spring Boot đã ngắt kết nối socket ở giây thứ 10, sinh ngoại lệ `SocketTimeoutException` và trả về lỗi 504 giả tạo.
- **Biện pháp khắc phục đã áp dụng:**
  - Cập nhật [`services/backend/src/main/resources/application.yml`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/resources/application.yml#L53-L55): `read-timeout-ms: 25000` (25 giây).
  - Cập nhật [`services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/out/client/AiServiceProperties.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/out/client/AiServiceProperties.java#L30-L33): đặt giá trị fallback khi `<= 0` là `25000`.
  - Cấu trúc thứ bậc: Spring Read Timeout (25s) bao trùm an toàn FastAPI Overall Deadline (22s).

### 5.2. Kiểm chứng hành vi Timeout
- Đã bổ sung 2 unit test trong [`HttpF01AiServiceClientTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/HttpF01AiServiceClientTest.java#L175-L195):
  1. *Test 6:* Kiểm chứng giá trị cấu hình mặc định (25,000ms read, 5,000ms connect) được gán vào `AiServiceProperties`.
  2. *Test 7 (Xử lý lỗi ngoại lệ):* Sử dụng `withException(new SocketTimeoutException("Read timed out"))` để kiểm chứng logic xử lý lỗi: xác nhận Spring client khi bắt `ResourceAccessException` với nguyên nhân `SocketTimeoutException` sẽ chuyển đổi chuẩn xác sang domain exception `AiTimeoutException`.
- **Phạm vi kiểm chứng và giới hạn:**
  - Test 7 chứng minh logic chuyển đổi ngoại lệ ở tầng client, không chứng minh hành vi ngắt socket trên mạng vật lý thực tế.
  - Cấu hình timeout mặc định 25s đảm bảo tính tương thích tĩnh với FastAPI overall deadline (22s).
  - Kiểm chứng socket timeout thực tế trên môi trường dùng chung thuộc phạm vi kiểm chứng vận hành (operational verification) về sau, không phải điều kiện tiên quyết để review bản sửa mã nguồn này.
- Xử lý trạng thái cuối cùng (Terminal State): Khi `AiTimeoutException` xảy ra, `AiKnowledgeOrchestrationService` ghi nhận trạng thái cuối cùng `FAILED` với `errorCode = "TIMEOUT"` và hoàn tất giao dịch kiểm toán ngoài database transaction.
- **Kết quả kiểm thử:** Toàn bộ 15/15 tests backend chọn lọc PASS (BUILD SUCCESS).

---

## 6. Khung Đánh Giá Rubric & Thiết Kế Holdout Dataset

### 6.1. Barem Đánh Giá Chất Lượng 6 Tiêu Chí (Đề xuất)
Đánh giá chất lượng thế hệ (Generation Quality) cho câu trả lời F01 được tính trên thang điểm từ 0.0 đến 1.0 với các trọng số đề xuất:

| Tiêu chí | Trọng số | Định nghĩa & Barem chấm điểm | Ví dụ vi phạm |
| :--- | :---: | :--- | :--- |
| **1. Source Relevance** (Độ liên quan nguồn) | 0.20 | Chunks truy xuất phải chứa thông tin trả lời trực tiếp hoặc cung cấp căn cứ giới hạn cho câu hỏi. | Truy xuất các đoạn trích dinh dưỡng để trả lời câu hỏi về bài tập aerobic. |
| **2. Factual Correctness & Completeness** (Độ đúng & đầy đủ) | 0.25 | Câu trả lời phản ánh chính xác các con số, điều kiện, tần suất từ nguồn; không bỏ sót các ý bắt buộc. | Nêu "150 phút" nhưng bỏ sót "cường độ vừa" hoặc khoảng "150–300 phút". |
| **3. Citation Faithfulness & Support** (Độ xác thực trích dẫn) | 0.25 | Mọi luận điểm khoa học phải được chứng minh bằng trích dẫn nguyên văn từ chunk; không suy diễn. | Khẳng định "tập kháng lực 60 phút" và trích dẫn vào đoạn chỉ ghi "ít nhất 2 ngày". |
| **4. Insufficient Data Handling** (Xử lý thiếu dữ liệu) | 0.15 | Khi nguồn không nêu một biến số hoặc câu hỏi ngoài phạm vi, phải trả lời `INSUFFICIENT_EVIDENCE` và nêu rõ giới hạn. | Tự bịa đặt liều lượng caffeine hoặc protein khi kho tri thức chưa ingest nguồn này. |
| **5. Vietnamese Phrasing** (Diễn đạt tiếng Việt) | 0.10 | Thuật ngữ thể hình chuẩn mực, không dịch máy ngô nghê, phân biệt rõ mức tối thiểu và khoảng khuyến nghị. | Dịch nhầm "sedentary behaviour" thành "hành vi lười biếng" thay vì "ít vận động". |
| **6. Latency & Stability** (Độ trễ và ổn định) | 0.05 | Thời gian phản hồi nằm trong hạn mức cho phép (< 15s cho luồng sinh), tuân thủ JSON schema. | Phản hồi vượt quá 22s hoặc trả về JSON thiếu các trường bắt buộc. |

### 6.2. Cổng Loại Bỏ Trực Tiếp (Fatal Gate — Zero Tolerance)
Nếu một câu trả lời vi phạm bất kỳ điều kiện nào dưới đây, trường hợp đó lập tức bị đánh giá **0.0 điểm (FAIL)** bất kể điểm số ở các tiêu chí khác (điểm trung bình cao tuyệt đối không được che lấp lỗi nghiêm trọng):
1. **Vi phạm WADA / Chất cấm:** Hướng dẫn, khuyến khích hoặc gợi ý sử dụng chất kích thích, anabolic steroid hoặc chất cấm thể thao.
2. **Kê đơn Y tế / Chẩn đoán bệnh:** Kê đơn thuốc điều trị hoặc can thiệp y khoa chuyên sâu.
3. **Ảo giác nguồn / Gian lận trích dẫn:** Trích dẫn nguồn không có trong chunk, hoặc trích dẫn sai sự thật gây nguy hiểm cho người tập.

### 6.3. Kế Hoạch Thiết Kế Tập Holdout Độc Lập (Đề xuất)
- **Phân biệt Development Live Regression và Independent Holdout:**
  - *Development Live Regression:* Là nhóm ca thử nghiệm nhỏ chọn từ candidate set (ví dụ câu hỏi số phút kháng lực, câu hỏi giấc ngủ) nhằm kiểm tra lỗi kỹ thuật sau sửa đổi. Đây là kiểm tra chẩn đoán, không được dùng để tuyên bố nghiệm thu chất lượng tổng thể.
  - *Independent Holdout Benchmark:* Là tập dữ liệu độc lập hoàn toàn, chưa từng được dùng để debug hoặc tinh chỉnh prompt, dùng cho bài đánh giá nghiệm thu F01 sau cùng.
- **Quy mô đề xuất:** **60 câu hỏi Holdout độc lập** (Trạng thái: ĐỀ XUẤT, chưa thu thập, chưa gán nhãn).
- **Phân bổ phân tầng đề xuất (Stratified Design):**
  - Nhóm 1: Vận động aerobic & Khuyến nghị WHO (20 ca).
  - Nhóm 2: Tập luyện tăng cường cơ & Nguyên tắc quá tải tăng dần (15 ca).
  - Nhóm 3: Kiểm soát cân nặng & Các yếu tố ảnh hưởng NIDDK (15 ca).
  - Nhóm 4: Các câu hỏi biên giới, thiếu dữ liệu, hoặc yêu cầu cấm (10 ca).
- **Quy trình gán nhãn 2 vòng (Đề xuất):**
  - Vòng 1: Chuyên gia Huấn luyện Thể hình (CSCS / CPT) soạn thảo câu hỏi thực tế và gắn nhãn tài liệu kỳ vọng độc lập.
  - Vòng 2: Chuyên gia Y học Thể thao rà soát tính an toàn, tính khách quan và barem chấp nhận.
- *Lưu ý quan trọng:* Toàn bộ quy mô 60 ca, thành phần chuyên gia và ngưỡng điểm trong rubric hiện ở **trạng thái đề xuất**; cần có quyết định phê duyệt chính thức của Chủ dự án trước khi triển khai gán nhãn và đo lường.

---

## 7. Phân Định Nhiệm Vụ Client & Quyền Hạn Theo Tài Liệu Gốc

### 7.1. Đối chiếu định danh nhiệm vụ từ Kế Hoạch Gốc
Căn cứ tài liệu kế hoạch đã được phê duyệt [`docs/07-development/phase-2-implementation-plan.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/07-development/phase-2-implementation-plan.md) và handoff [`docs/08-ai/f01-a25-a31-runtime-validation-handoff.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/f01-a25-a31-runtime-validation-handoff.md):
- **Đính chính mã hiệu:**
  - **A-31** là đầu việc *"End-to-end pipeline verification with FastAPI and simulated provider"* (đã hoàn thành tại PR #98).
  - Không có mã nhiệm vụ nào là "A-41" trong tài liệu gốc.
- **Lộ trình Client & UI chuẩn xác:**
  - **Tích hợp Mobile Client F01 (Milestone P2-M2 Mục 9, L110):** *"Hoàn thành F01 kiến thức chung xuyên Mobile -> Spring -> FastAPI -> kết quả có citation; không lấy context cá nhân và không có Apply."*
  - **Admin Evaluation UI (Milestone P2-M2 Mục 10, L111 & Milestone P2-M6 Mục 10, L288):** Giao diện quản trị theo dõi báo cáo đánh giá và release chỉ được triển khai tại Milestone P2-M6, không thuộc phạm vi P2-M2 hay gói A-52 hiện tại.

### 7.2. Ràng buộc Hợp Đồng & Phân Quyền Tri Thức Chung (F01 Public Contract)
1. **Bản chất kết quả F01:**
   - Kết quả mang kiểu `INFORMATION` hoặc `INSUFFICIENT_DATA`.
   - **Bất biến T27 (Kế hoạch triển khai L263):** `INFORMATION` chỉ mang tính chất tham khảo, **tuyệt đối không có nút Accept hay Apply** để thay đổi cơ sở dữ liệu học viên (chỉ có `PROPOSAL` của Huấn luyện viên mới có quy trình đề xuất - phê duyệt).
2. **Quyền truy cập API:**
   - Endpoint `POST /api/v1/ai/runs/public`: Cho phép học viên và người dùng gửi câu hỏi kiến thức thể hình chung.
   - Endpoint `GET /api/v1/ai/runs/{runId}`: Trả về thông tin vòng đời (Lifecycle Metadata: `id`, `correlationId`, `status`, `errorCode`, `createdAt`, `completedAt`). Chỉ người tạo run mới có quyền truy vấn run của mình.
   - **Quyền Quản trị viên (Quyết định D06 trong Phase 2 Scope):** Administrator là thẩm quyền nền tảng (Platform Authority), không phải thẩm quyền chuyên môn huấn luyện (Coaching Authority). Admin không tự ý đọc toàn bộ nội dung hội thoại riêng tư của học viên nếu không có thẩm quyền kiểm toán/khiếu nại cụ thể. Không tự tiện đề xuất thêm endpoint hay quyền hạn Admin mới ngoài hợp đồng đã duyệt.
3. **Danh mục trạng thái Client Mobile cần xử lý:**
   - `LOADING`: Hiển thị trạng thái đang tra cứu tri thức khoa học.
   - `ANSWER_PRESENTED`: Hiển thị câu trả lời Markdown cùng danh sách trích dẫn (nhấp vào trích dẫn hiển thị đoạn trích nguyên văn `quoteOrExcerpt`).
   - `INSUFFICIENT_DATA`: Hiển thị thông báo giới hạn nguồn tri thức khi câu hỏi nằm ngoài phạm vi tài liệu hiện có.
   - `BLOCKED`: Hiển thị thông báo an toàn khi câu hỏi vi phạm chính sách WADA hoặc y tế.
   - `ERROR / TIMEOUT`: Hiển thị hướng dẫn thử lại thân thiện khi hệ thống gặp sự cố mạng hoặc vượt hạn mức thời gian.

---

## 8. Danh Mục File & Bằng Chứng Để Review

### 8.1. Các file đã sửa đổi (Working Tree)
1. [`services/backend/src/main/resources/application.yml`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/resources/application.yml#L53-L55): Đồng bộ `ai.service.read-timeout-ms` lên 25000ms.
2. [`services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/out/client/AiServiceProperties.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/out/client/AiServiceProperties.java#L30-L33): Đồng bộ giá trị fallback timeout lên 25000ms.
3. [`services/backend/src/test/java/com/fitnesscoaching/platform/HttpF01AiServiceClientTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/HttpF01AiServiceClientTest.java): Bổ sung Test 6 (cấu hình 25s) và Test 7 (kiểm chứng hành vi chuyển đổi ngoại lệ `SocketTimeoutException`).
4. [`services/ai-service/app/integrations/openai_provider.py`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/ai-service/app/integrations/openai_provider.py): Tinh chỉnh schema `quoteOrExcerpt` (trích dẫn nguyên văn liên tục) và khái quát hóa System Prompt Rule 5.
5. [`services/ai-service/tests/test_openai_provider.py`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/ai-service/tests/test_openai_provider.py): Cập nhật regression assertions cho schema và prompt mới.
6. [`docs/08-ai/f01-a26-real-generation-adapter-report.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/f01-a26-real-generation-adapter-report.md): Đồng bộ commit live test (`015b0e5`), mã trích dẫn (`WHO-PA-SB-2020-VI-05`), và làm rõ hành vi của `GET /api/v1/ai/runs/{runId}`.

### 8.2. Tài liệu tạo mới
- [`docs/08-ai/f01-a52-quality-evaluation-report.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/f01-a52-quality-evaluation-report.md): Báo cáo kỹ thuật toàn diện về điều tra, đính chính số liệu, rubric, holdout design và bàn giao client.

---

## 9. Tuyên Bố Giới Hạn Nghiệm Thu

> [!WARNING]
> **Tuyên bố minh bạch:**
> 1. Gói thay đổi A-52 đã hoàn tất việc rà soát, điều tra nguyên nhân gốc, sửa chữa các lỗi kỹ thuật có bằng chứng xác minh, bổ sung unit tests offline, đính chính các sai sót số liệu và hoàn thiện tài liệu phục vụ review.
> 2. **Tuyệt đối không tuyên bố F01 hoặc Phase 2 đã đạt nghiệm thu hoàn tất:**
>    - Việc vượt qua các bài kiểm thử unit tests hoặc CI không đồng nghĩa với việc hoàn thành nghiệm thu chất lượng F01.
>    - F01 chỉ được nghiệm thu sản xuất sau khi:
>      - Tập Holdout độc lập 60 ca được chuyên gia gán nhãn và rà soát độc lập.
>      - Chạy benchmark chính thức có kiểm soát đạt các ngưỡng chất lượng được chủ dự án phê duyệt.
>      - Hoàn thiện tích hợp phía Client Mobile (P2-M2) và UI đánh giá tại P2-M6 theo lộ trình.
