# Báo Cáo Kiểm Chứng Live & Bàn Giao Kỹ Thuật F01 / A-52

> **Mã đầu việc:** P2-M2 Mục 9 — Mobile Client F01 & A-52 Live Quality Verification  
> **Branch thực hiện:** `chore/f01-live-validation-20261023`  
> **Commit nền (Base commit):** `4591e91cee9c950337a454669040e2035d3af12f` (Merge PR #103 trên `origin/main`)  
> **Commit kiểm thử (Tested Commits):** `4591e91` (tích hợp PR #101 Mobile Client F01 và PR #100 A-52 fixes)  
> **Commit tài liệu (Doc-only commit):** `5f6ca84` (và commit hoàn thiện hiện tại)  
> **Ngày thực hiện:** 2026-10-23  
> **Trạng thái:** HOÀN THÀNH RÀ SOÁT & ĐỐI CHIẾU CHÍNH XÁC — PHÂN LOẠI MINH BẠCH

---

## 1. Bảng Tổng Hợp Phân Loại Trạng Thái Từng Ca Kiểm Chứng

| Nhóm kiểm chứng | Ca kiểm thử | Câu hỏi thực tế đã chạy | Kết quả phân loại | Lý do & Căn cứ kỹ thuật |
| :--- | :--- | :--- | :---: | :--- |
| **Mobile F01 End-to-End** | **Ca 1: WHO Hoạt động thể chất** | *"WHO khuyến nghị thời gian hoạt động thể chất hàng tuần cho người lớn là bao nhiêu?"* | **PASS** | Hoàn thành thông suốt toàn chuỗi Mobile -> Spring -> OpenAI -> pgvector -> FastAPI -> OpenAI -> Mobile. Cả hai nội dung aerobic và tăng cường cơ đều khớp chính xác tài liệu nguồn `WHO-PA-SB-2020-VI-05 v1.1.0`. Run ID: `ab86b5a5-916f-46f2-badf-0c420fdcb8e3`. |
| **A-52 Regression** | **Ca 2: Kháng lực (Số ngày mỗi tuần)** | *"Khuyến nghị tập tăng cường cơ bắp bao nhiêu ngày mỗi tuần?"* | **PARTIAL**<br>*(Kiểm chứng bổ sung: PASS)* | Ca kiểm chứng bổ sung (Positive Case) đạt kết quả `INFORMATION` chuẩn xác trích dẫn `WHO-PA-SB-2020-VI-05` (mục 1 & 4). **Ca gốc A-52 hỏi về số phút kháng lực CHƯA CHẠY** do hết hạn mức generation. Run ID: `3e515a7b-6743-46d3-a5d7-8e4c4462e495`. |
| **A-52 Regression** | **Ca 3: Giấc ngủ & Phục hồi cơ bắp** | *"Giấc ngủ đóng vai trò như thế nào trong phục hồi cơ bắp khi tập luyện?"* | **PARTIAL**<br>*(Đánh giá Coverage: PASS)* | Đã kiểm tra qua endpoint truy xuất nội bộ (`/internal/v1/knowledge/retrieval`), xác nhận tìm thấy tài liệu NIDDK về cân nặng nhưng không có dữ liệu về phục hồi cơ bắp. **Chưa kiểm chứng luồng F01 end-to-end; chưa chạy generation** do chủ động dừng để tuân thủ hạn mức. Không có bản ghi AI Run. |
| **A-52 Regression (Gốc)** | **Ca Kháng lực hỏi số phút** | *"Theo WHO, người trưởng thành nên vận động kháng lực cường độ vừa bao nhiêu phút mỗi tuần?"* | **NOT RUN** | Chưa chạy trong phiên này do giới hạn quota tối đa 2 generation requests. Cần phiên bổ sung có hạn mức riêng. |

---

## 2. Đối Chiếu Và Đính Chính Chi Tiết Từng Ca

### 2.1. Ca 1: WHO Hoạt Động Thể Chất Hàng Tuần (PASS)

- **Người dùng thực hiện:** Học viên `mobile.smoke@example.com` (Role: `STUDENT`).
- **Giao diện:** Pixel_8 Android Emulator (Expo Go).
- **Run ID:** `ab86b5a5-916f-46f2-badf-0c420fdcb8e3` | **Trạng thái AI Run:** `SUCCEEDED`.
- **Latency tổng thể:** `12,122 ms` (Spring Boot ghi nhận: `12,062 ms` tại AI service).
- **Token ghi nhận từ Provider:** `2,368` prompt tokens, `350` completion tokens (Tổng: `2,718` tokens).
- **Nội dung câu trả lời thực tế:**
  > *"WHO khuyến nghị người lớn nên thực hiện từ 150 đến 300 phút hoạt động aerobic cường độ vừa hoặc từ 75 đến 150 phút hoạt động aerobic cường độ mạnh mỗi tuần, hoặc kết hợp của cả hai. Ngoài ra, cũng nên thực hiện hoạt động tăng cường cơ ở cường độ vừa hoặc cao hơn ít nhất 2 ngày trong tuần."*
- **Đối chiếu toàn diện với tài liệu nguồn `WHO-PA-SB-2020-VI-05 v1.1.0` (Mục 1):**
  - Khuyến nghị Aerobic: *"Trong tuần: 150–300 phút aerobic cường độ vừa, hoặc 75–150 phút aerobic cường độ mạnh, hoặc kết hợp tương đương"* -> Trùng khớp 100% với trích dẫn nguyên văn (`quoteOrExcerpt`).
  - Khuyến nghị Tăng cường cơ: *"Tăng cường cơ ở cường độ vừa hoặc cao hơn, tác động tất cả nhóm cơ chính, ít nhất 2 ngày/tuần"* -> Mô hình tổng hợp chuẩn xác từ cùng chunk nguồn, không bịa đặt số phút.
- **Thẻ trích dẫn pháp lý hiển thị trên Mobile:**
  - `WHO-PA-SB-2020-VI-05 v1.1.0` (Mục: *Vận động và tăng cường cơ ở tuổi 18–64 > Khuyến nghị được chuẩn hóa*).
- **Phạm vi & Giới hạn:** Hiển thị 2 gạch đầu dòng giới hạn và lưu ý miễn trừ trách nhiệm y khoa.
- **Bằng chứng Artifacts:**
  - [Ảnh màn hình kết quả câu hỏi WHO](file:///C:/Users/Admin/.gemini/antigravity-cli/brain/8456b62b-ef1e-4186-95f0-a8bd9655289d/emulator_result_live_openai.png)
  - [Ảnh màn hình cuộn chi tiết trích dẫn và giới hạn](file:///C:/Users/Admin/.gemini/antigravity-cli/brain/8456b62b-ef1e-4186-95f0-a8bd9655289d/emulator_result_live_openai_scrolled.png)

### 2.2. Ca 2: Kháng Lực — Phân Định Giữa Ca Số Ngày Và Ca Số Phút

- **Câu hỏi thực tế đã chạy:** *"Khuyến nghị tập tăng cường cơ bắp bao nhiêu ngày mỗi tuần?"* (Chọn từ câu hỏi gợi ý 1 trên Mobile).
- **Run ID:** `3e515a7b-6743-46d3-a5d7-8e4c4462e495` | **Trạng thái AI Run:** `SUCCEEDED`.
- **Latency:** `8,185 ms` | **Token:** `2,437` prompt, `418` completion (Tổng: `2,855` tokens).
- **Trích dẫn:** `WHO-PA-SB-2020-VI-05 v1.1.0` (mục 1 và 4: "ít nhất 2 ngày/tuần").
- **Ảnh chụp màn hình:** [emulator_result_live_ca2.png](file:///C:/Users/Admin/.gemini/antigravity-cli/brain/8456b62b-ef1e-4186-95f0-a8bd9655289d/emulator_result_live_ca2.png).
- **Đối chiếu với ca lỗi gốc trong A-52:**
  - Theo tài liệu [`docs/08-ai/f01-a52-quality-evaluation-report.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/f01-a52-quality-evaluation-report.md#L139-L143) (Run lịch sử `30802098-d6e2-483a-9940-55c7ddaad0c9`), câu hỏi gốc của A-52 là:
    > *"Theo WHO, người trưởng thành nên vận động kháng lực cường độ vừa bao nhiêu phút mỗi tuần?"*
  - Khuyến nghị WHO **không quy định số phút** cho tập kháng lực. Ca gốc này nhằm kiểm tra việc mô hình có từ chối bịa đặt số phút hay không (trước đây mô hình bị `REJECTED_BY_VALIDATOR` do cố tình gán 150-300 phút của aerobic sang kháng lực).
  - **Đính chính phân loại:**
    - Ca hỏi **số ngày** đã chạy là **kiểm chứng bổ sung (Positive Validation: PASS)**.
    - Ca gốc A-52 hỏi **số phút** **CHƯA ĐƯỢC CHẠY (NOT RUN)** trong phiên này. Dự án không tự ý sửa câu hỏi gốc để tính là đã hoàn thành.

### 2.3. Ca 3: Giấc Ngủ — Phân Định Giữa Truy Xuất Nội Bộ Và Luồng F01 End-to-End

- **Thao tác thực tế:** Gọi trực tiếp endpoint truy xuất nội bộ `POST /internal/v1/knowledge/retrieval` trên Spring Boot với câu hỏi:
  *"Giấc ngủ đóng vai trò như thế nào trong phục hồi cơ bắp khi tập luyện?"*.
- **Các tầng đã đi qua:** Spring Boot Controller -> Service -> OpenAI Embedding Provider (`text-embedding-3-small`, request embedding #3/3) -> pgvector HNSW search.
- **Các tầng CHƯA đi qua:**
  - Chưa đi qua endpoint nghiệp vụ `/api/v1/ai/runs/general-knowledge`.
  - Chưa khởi tạo bản ghi `fitness.ai_runs` trong database.
  - Chưa gọi FastAPI AI Service `/internal/v1/knowledge/query`.
  - Chưa gọi OpenAI Chat Completions API.
- **Bằng chứng phản hồi từ hệ thống:**
  - Spring Boot trả về danh sách chunks khả dụng. Chunk có độ tương đồng cao nhất (`similarityScore: 0.4368`) là `b55f2e3e-958e-3f59-9826-2775c85b2092` thuộc tài liệu `NIDDK-WEIGHT-FACTORS-2023-VI-04` (*"Giấc ngủ và cân nặng ở người trưởng thành"*).
  - Nội dung chunk chỉ đề cập đến thời lượng ngủ 7–9 giờ và nguy cơ thừa cân, hoàn toàn không có nội dung về phục hồi cơ bắp sau tập luyện.
- **Đính chính phân loại chuẩn xác:**
  - Phân loại: **Đã kiểm tra retrieval và đánh giá coverage; chưa kiểm chứng kết quả F01 end-to-end**.
  - **Không ghi `SUCCEEDED` cho AI Run** vì không có bản ghi AI Run tương ứng được tạo trong cơ sở dữ liệu.
  - **Không tự suy ra hệ thống đã trả về `INSUFFICIENT_DATA`** trong phiên này, vì chưa có response/log thực tế từ toàn bộ pipeline F01 chứng minh điều đó.
  - **Làm rõ nguyên nhân dừng:** Quyết định dừng lại ở bước retrieval là **quyết định của người kiểm thử nhằm tuân thủ nghiêm ngặt hạn mức tối đa 2 generation requests**, KHÔNG PHẢI là quyết định từ chối do thiếu bằng chứng của ứng dụng.

---

## 3. Thống Kê Số Lần Gọi Provider, Token Và Chi Phí Ước Tính

### 3.1. Đối chiếu số lần gọi Provider (Bao gồm Retry)

| Loại Request | Endpoint Provider | Ca 1 (WHO) | Ca 2 (Cơ bắp) | Ca 3 (Giấc ngủ) | Retry tự động | Tổng số lần gọi thực tế | Hạn mức cho phép |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Embedding** | `https://api.openai.com/v1/embeddings` | 1 | 1 | 1 | 0 | **3** | **3** |
| **Generation** | `https://api.openai.com/v1/chat/completions` | 1 | 1 | 0 *(Dừng)* | 0 | **2** | **2** |

*(Cấu hình `ai.embedding.openai.max-attempts=1` và `OPENAI_MAX_RETRIES=1` đảm bảo không phát sinh retry ngầm).*

### 3.2. Bảng phân định Tokens & Chi phí ước tính

| Hạng mục kiểm chứng | Prompt Tokens (Provider) | Completion Tokens (Provider) | Embedding Tokens (Ước tính) | Đơn giá niêm yết OpenAI | Chi phí ước tính (USD) | Ghi chú nguồn dữ liệu |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **Ca 1 (WHO)** | 2,368 | 350 | ~15 | $0.15 / $0.60 / $0.02 / 1M | $0.0005655 | Ghi nhận từ DB `fitness.ai_runs` |
| **Ca 2 (Kháng lực)** | 2,437 | 418 | ~13 | $0.15 / $0.60 / $0.02 / 1M | $0.0006167 | Ghi nhận từ DB `fitness.ai_runs` |
| **Ca 3 (Giấc ngủ)** | 0 | 0 | ~18 | $0.02 / 1M | $0.0000004 | Chỉ nhúng truy xuất, dừng trước gen |
| **TỔNG CỘNG** | **4,805** | **768** | **~46** | — | **$0.0011826** | **Chi phí ước tính** |

> [!IMPORTANT]
> **Kỷ luật ngân sách & Hạn mức:**
> 1. Con số **0.001183 USD** là **chi phí ước tính từ usage**, chưa được đối chiếu với hóa đơn chi tiết trên OpenAI Billing Dashboard.
> 2. Mặc dù số tiền còn dư lớn so với trần 0.01 USD, **hạn mức request (3 embedding, 2 generation) đã cạn kiệt 100%**. Việc còn dư tiền không đồng nghĩa với quyền được gọi thêm request khi chưa được cấp hạn mức bổ sung.

---

## 4. Danh Mục Artifacts & Bằng Chứng Đã Khử Khuẩn Secret

Toàn bộ artifacts đã được rà soát, khử khuẩn tuyệt đối bí mật (không có API key, JWT token, mật khẩu DB hay thông tin nhạy cảm):

| Tên Artifact | Đường dẫn tuyệt đối | Gắn với Case / Run | Mô tả nội dung chứng minh |
| :--- | :--- | :--- | :--- |
| `emulator_result_live_openai.png` | `C:\Users\Admin\.gemini\antigravity-cli\brain\8456b62b-ef1e-4186-95f0-a8bd9655289d\emulator_result_live_openai.png` | Ca 1 (`ab86b5a5...`) | Màn hình Mobile hiển thị câu hỏi, badge INFORMATION, câu trả lời đầy đủ và trích dẫn WHO. |
| `emulator_result_live_openai_scrolled.png` | `C:\Users\Admin\.gemini\antigravity-cli\brain\8456b62b-ef1e-4186-95f0-a8bd9655289d\emulator_result_live_openai_scrolled.png` | Ca 1 (`ab86b5a5...`) | Màn hình cuộn chi tiết trích dẫn nguồn, trích dẫn nguyên văn, giới hạn áp dụng và miễn trừ y khoa. |
| `emulator_result_live_ca2.png` | `C:\Users\Admin\.gemini\antigravity-cli\brain\8456b62b-ef1e-4186-95f0-a8bd9655289d\emulator_result_live_ca2.png` | Ca 2 (`3e515a7b...`) | Màn hình Mobile hiển thị kết quả câu hỏi số ngày tập kháng lực với trích dẫn WHO. |
| `run_fastapi.ps1` | `C:\Users\Admin\.gemini\antigravity-cli\brain\8456b62b-ef1e-4186-95f0-a8bd9655289d\scratch\run_fastapi.ps1` | Tiến trình AI Service | Kịch bản khởi chạy nạp cấu hình an toàn từ `.env` vào memory, không in key ra log. |
| `run_backend.ps1` | `C:\Users\Admin\.gemini\antigravity-cli\brain\8456b62b-ef1e-4186-95f0-a8bd9655289d\scratch\run_backend.ps1` | Tiến trình Spring Boot | Kịch bản khởi chạy đồng bộ cấu hình môi trường cho backend, không lưu secret trong file. |

---

## 5. Rà Soát Khoảng Trống Kiến Trúc P2-M2

Báo cáo trung thực tình trạng hiện tại của hệ thống so với các mục tiêu kiến trúc Phase 2 Milestone 2:

1. **Revalidation trước khi xuất kết quả:**
   - *Hiện trạng:* Đã kiểm tra quyền JWT khi tiếp nhận và kiểm tra quyền sở hữu (`run.requestedBy().equals(actorId)`) khi lấy dữ liệu qua GET. Chưa có cơ chế revalidation trạng thái người dùng (ví dụ: tài khoản bị khóa hoặc phiên bị hủy trong khi OpenAI đang xử lý) ngay trước khi trả dữ liệu ra HTTP client.
2. **AI Kill Switch:**
   - *Hiện trạng:* Đã có cấu hình tĩnh (`ai.service.enabled=false` phía Spring Boot và `backend_internal_retrieval_enabled` phía FastAPI). Chưa có runtime flag động trong database được điều khiển qua giao diện Admin Web.
3. **Phục hồi bền vững (Durable Recovery):**
   - *Hiện trạng:* Xử lý lỗi đồng bộ hoạt động tốt (đánh dấu `FAILED` với `errorCode = "TIMEOUT"` hoặc `MODEL_ERROR`). Chưa có background sweeper định kỳ quét để thu hồi và chuyển trạng thái các AI Run bị mồ côi (orphaned runs) khi tiến trình backend bị crash đột ngột trong lúc run đang ở trạng thái `RUNNING`.
4. **Cơ chế `store: false`:**
   - *Hiện trạng:* Đã cấu hình cứng `"store": false` trong payload gửi sang OpenAI. Cơ chế này loại bỏ việc lưu dữ liệu vào Application Store của OpenAI theo cam kết quyền riêng tư, nhưng không can thiệp được chính sách lưu log bảo mật/giám sát lạm dụng tạm thời (abuse monitoring retention) theo điều khoản dịch vụ của OpenAI.

---

## 6. Phân Quyền Người Dùng Trong Kiểm Thử

- **Học viên (Student):** Tài khoản `mobile.smoke@example.com` đã kiểm chứng end-to-end thành công trên thiết bị thật/emulator.
- **Huấn luyện viên (Trainer):** Tài khoản `smoke.trainer@example.com` có role `TRAINER` trong database nhưng mật khẩu fixture không được cung cấp trong cấu hình môi trường. Tuân thủ tuyệt đối quy tắc `AGENTS.md` (*"Không chỉnh sửa trực tiếp mật khẩu hoặc phân quyền trong PostgreSQL"*), giao diện Trainer được ghi nhận trung thực là **chưa kiểm chứng live trên thiết bị**.

---

## 7. Đề Xuất Kế Hoạch Kiểm Thử Bổ Sung Tối Thiểu (CHƯA THỰC HIỆN)

Để hoàn tất các ca còn thiếu mà không gây lãng phí tài nguyên, đề xuất kế hoạch kiểm thử bổ sung khi được cấp hạn mức:

### 7.1. Ca 1: Kháng lực hỏi số phút (A-52 Case 1)
- **Câu hỏi chính xác:** `"Theo WHO, người trưởng thành nên vận động kháng lực cường độ vừa bao nhiêu phút mỗi tuần?"`
- **Kỳ vọng có căn cứ:**
  - Hệ thống truy xuất `WHO-PA-SB-2020-VI-05`.
  - Mô hình nhận diện WHO không quy định số phút cho bài tập kháng lực (chỉ quy định ít nhất 2 ngày/tuần).
  - Trả về câu trả lời nêu rõ giới hạn tài liệu hoặc trả về trạng thái `INSUFFICIENT_EVIDENCE` / `INSUFFICIENT_DATA`.
  - Tuyệt đối không gán nhầm 150–300 phút aerobic sang kháng lực, không bị rào chắn `UNVERIFIED_CITATION` từ chối.
- **Hạn mức cần:** 1 embedding, 1 generation.

### 7.2. Ca 2: Giấc ngủ qua toàn bộ luồng F01 (A-52 Case 2)
- **Câu hỏi chính xác:** `"Giấc ngủ đóng vai trò như thế nào trong phục hồi cơ bắp khi tập luyện?"`
- **Kỳ vọng có căn cứ:**
  - Luồng đi qua toàn bộ pipeline F01 từ Mobile / API nghiệp vụ.
  - Khởi tạo bản ghi `fitness.ai_runs` trong database.
  - Mô hình nhận diện tài liệu NIDDK trong kho ACTIVE chỉ bàn về cân nặng và thời lượng ngủ chung, không có cơ chế phục hồi cơ bắp.
  - Kết quả trả về `INSUFFICIENT_DATA` với `reasonCode = "NO_RELEVANT_EVIDENCE"`, citations rỗng, không bịa đặt nguồn dẫn.
- **Hạn mức cần:** 1 embedding, 1 generation.

### 7.3. Tổng mức ngân sách đề xuất cho phiên bổ sung
- **Số request tối đa (tính cả retry):** **2 embeddings** và **2 generations**.
- **Giới hạn tokens:** Tối đa 6,000 tokens (4,500 prompt, 1,500 completion).
- **Chi phí dự kiến:** `< 0.0015 USD`.
- **Cách ghi bằng chứng:** Chụp ảnh màn hình Mobile + Trích xuất bản ghi `fitness.ai_runs` (ID, status, resultType, reasonCode, payloads).
- **Cam kết:** Không thực hiện bất kỳ lệnh gọi API nào trước khi có sự phê duyệt hạn mức chính thức.
