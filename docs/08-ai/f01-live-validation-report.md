# Báo Cáo Kiểm Chứng Live & Bàn Giao Kỹ Thuật F01 / A-52

> **Mã đầu việc:** P2-M2 Mục 9 — Mobile Client F01 & A-52 Live Quality Verification  
> **Branch thực hiện:** `chore/f01-live-validation-20261023`  
> **Commit nền (Base commit):** `4591e91cee9c950337a454669040e2035d3af12f` (Merge PR #103 trên `origin/main`)  
> **Commit kiểm thử (Tested Commits):** `4591e91` (tích hợp PR #101 Mobile Client F01 và PR #100 A-52 fixes)  
> **Commit tài liệu (Doc-only commits):** `5f6ca84`, `a9a7dc3` và commit nghiệm thu hiện tại  
> **Pull Request:** [Draft PR #104](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/pull/104)  
> **Ngày thực hiện:** 2026-10-23  
> **Trạng thái:** HOÀN THÀNH TOÀN DIỆN BỘ DEVELOPMENT LIVE REGRESSION A-52 TRÊN MOBILE

---

## 1. Bảng Tổng Hợp Trạng Thái Các Ca Kiểm Chứng Live (4 Ca Thực Tế)

| STT | Ca kiểm thử | Câu hỏi thực tế trên Mobile | Run ID trong PostgreSQL | runStatus | resultType | reasonCode | Trạng thái kỹ thuật |
| :---: | :--- | :--- | :--- | :---: | :---: | :---: | :---: |
| **1** | **WHO Hoạt động thể chất hàng tuần** | *"WHO khuyến nghị thời gian hoạt động thể chất hàng tuần cho người lớn là bao nhiêu?"* | `ab86b5a5-916f-46f2-badf-0c420fdcb8e3` | `SUCCEEDED` | `INFORMATION` | `NONE` | **PASS** |
| **2** | **Kháng lực (Số ngày mỗi tuần)** *(Kiểm chứng bổ sung)* | *"Khuyến nghị tập tăng cường cơ bắp bao nhiêu ngày mỗi tuần?"* | `3e515a7b-6743-46d3-a5d7-8e4c4462e495` | `SUCCEEDED` | `INFORMATION` | `NONE` | **PASS** *(Bổ sung)* |
| **3** | **Kháng lực (Số phút mỗi tuần)** *(A-52 Case 1 gốc)* | *"Theo WHO, người trưởng thành nên vận động kháng lực cường độ vừa bao nhiêu phút mỗi tuần?"* | `d2434996-3e21-4ad2-80e7-b4de1e00a38c` | `SUCCEEDED` | `INFORMATION` | `NONE` | **PASS** |
| **4** | **Giấc ngủ & Phục hồi cơ bắp** *(A-52 Case 2 gốc qua toàn bộ F01)* | *"Giấc ngủ đóng vai trò như thế nào trong phục hồi cơ bắp khi tập luyện?"* | `aac3a2a5-aed2-4b71-b61f-ffe6e322a094` | `SUCCEEDED` | `INSUFFICIENT_DATA` | `NO_RELEVANT_EVIDENCE` | **PASS** |

---

## 2. Chi Tiết Thực Thi & Bằng Chứng Từng Ca

### 2.1. Ca 1: WHO Hoạt Động Thể Chất Hàng Tuần (PASS)

- **Mục tiêu:** Kiểm chứng luồng tích hợp đầy đủ từ Mobile Client đến các tầng backend, RAG pgvector và nhà cung cấp mô hình.
- **Run ID:** `ab86b5a5-916f-46f2-badf-0c420fdcb8e3` | **Correlation ID:** `e22340f2-37f2-4a69-8acc-06b6f5df7f66`.
- **Latency:** `12,122 ms` (AI service latency: `12,062 ms`).
- **Token Provider:** `2,368` prompt, `350` completion (Tổng: `2,718` tokens).
- **Nội dung trả lời:**
  > *"WHO khuyến nghị người lớn nên thực hiện từ 150 đến 300 phút hoạt động aerobic cường độ vừa hoặc từ 75 đến 150 phút hoạt động aerobic cường độ mạnh mỗi tuần, hoặc kết hợp của cả hai. Ngoài ra, cũng nên thực hiện hoạt động tăng cường cơ ở cường độ vừa hoặc cao hơn ít nhất 2 ngày trong tuần."*
- **Đối chiếu nguồn:** Cả hai mệnh đề aerobic và tăng cường cơ đều được trích xuất chính xác từ chunk `WHO-PA-SB-2020-VI-05 v1.1.0` (Mục 1). Đoạn trích dẫn nguyên văn (`quoteOrExcerpt`): *“Trong tuần: 150–300 phút aerobic cường độ vừa, hoặc 75–150 phút aerobic cường độ mạnh, hoặc kết hợp tương đương.”*.
- **Giao diện hiển thị:** Đầy đủ `INFORMATION` badge, thẻ trích dẫn, giới hạn áp dụng và cảnh báo miễn trừ y khoa.
- **Artifacts:**
  - [Ảnh màn hình kết quả câu hỏi WHO](file:///C:/Users/Admin/.gemini/antigravity-cli/brain/8456b62b-ef1e-4186-95f0-a8bd9655289d/emulator_result_live_openai.png)
  - [Ảnh màn hình cuộn chi tiết trích dẫn](file:///C:/Users/Admin/.gemini/antigravity-cli/brain/8456b62b-ef1e-4186-95f0-a8bd9655289d/emulator_result_live_openai_scrolled.png)

### 2.2. Ca 2: Kháng Lực — Số Ngày Mỗi Tuần (PASS Bổ Sung)

- **Mục tiêu:** Kiểm chứng ca hỏi thuận (positive question) có bằng chứng trực tiếp trong corpus.
- **Run ID:** `3e515a7b-6743-46d3-a5d7-8e4c4462e495` | **Correlation ID:** `52e23c30-3683-4a16-8b1f-79df85e0d5d3`.
- **Latency:** `8,185 ms` | **Token Provider:** `2,437` prompt, `418` completion (Tổng: `2,855` tokens).
- **Nội dung trả lời:** Nêu rõ khuyến nghị thực hiện các bài tập tăng cường cơ ở cường độ vừa hoặc cao hơn tác động đến tất cả nhóm cơ chính ít nhất 2 ngày mỗi tuần.
- **Trích dẫn:** `WHO-PA-SB-2020-VI-05 v1.1.0` (mục 1 và 4: "ít nhất 2 ngày/tuần").
- **Artifact:** [Ảnh màn hình kết quả Ca 2](file:///C:/Users/Admin/.gemini/antigravity-cli/brain/8456b62b-ef1e-4186-95f0-a8bd9655289d/emulator_result_live_ca2.png).

### 2.3. Ca 3: Kháng Lực — Số Phút Mỗi Tuần (A-52 Case 1 Gốc: PASS)

- **Bối cảnh ca lỗi gốc:** Trong quá khứ (Run `30802098...`), khi người dùng hỏi *"bao nhiêu phút mỗi tuần"*, mô hình OpenAI đã ảo giác và gán nhầm số phút aerobic (150–300 phút) sang cho tập kháng lực, dẫn đến trích dẫn sai và bị validator từ chối (`REJECTED_BY_VALIDATOR`).
- **Thực thi trên Mobile:** Nhập câu hỏi chính xác: *"Theo WHO, người trưởng thành nên vận động kháng lực cường độ vừa bao nhiêu phút mỗi tuần?"* và bấm gửi.
- **Run ID:** `d2434996-3e21-4ad2-80e7-b4de1e00a38c` | **Correlation ID:** `316ee43c-3d90-43fc-b663-6681c081a6f7`.
- **Latency:** `10,147 ms` | **Token Provider:** `2,481` prompt, `401` completion (Tổng: `2,882` tokens).
- **Nội dung trả lời thực tế:**
  > *"Theo khuyến nghị của WHO, người trưởng thành từ 18 đến 64 tuổi nên thực hiện hoạt động tăng cường cơ ở cường độ vừa hoặc cao hơn, tác động đến tất cả các nhóm cơ chính ít nhất 2 ngày mỗi tuần. Điều này cũng nằm trong bối cảnh mà các cá nhân nên thực hiện hoạt động thể chất thường xuyên, với khuyến nghị 150-300 phút aerobic cường độ vừa hoặc 75-150 phút aerobic cường độ mạnh mỗi tuần."*
- **Đánh giá chất lượng:**
  1. Mô hình **không gán nhầm** số phút aerobic sang cho tập kháng lực: nêu rõ tăng cường cơ là **ít nhất 2 ngày mỗi tuần**, tách biệt rõ với 150–300 phút aerobic.
  2. Mô hình **không tự bịa** ra con số phút cho bài tập kháng lực.
  3. Trích dẫn nguyên văn (`quoteOrExcerpt`): *"Tăng cường cơ ở cường độ vừa hoặc cao hơn, tác động tất cả nhóm cơ chính, ít nhất 2 ngày/tuần."* khớp chính xác 100% với tài liệu nguồn `WHO-PA-SB-2020-VI-05 v1.1.0` (Mục 1).
  4. Vượt qua rào chắn `F01RuntimeOutputValidator` an toàn, đạt `runStatus: SUCCEEDED`, `resultType: INFORMATION`.
- **Artifacts:**
  - [Ảnh màn hình Ca kháng lực hỏi số phút](file:///C:/Users/Admin/.gemini/antigravity-cli/brain/8456b62b-ef1e-4186-95f0-a8bd9655289d/emulator_result_resistance_minutes.png)
  - [Ảnh màn hình cuộn chi tiết trích dẫn](file:///C:/Users/Admin/.gemini/antigravity-cli/brain/8456b62b-ef1e-4186-95f0-a8bd9655289d/emulator_result_resistance_minutes_scrolled.png)

### 2.4. Ca 4: Giấc Ngủ & Phục Hồi Cơ Bắp Qua Toàn Bộ F01 (A-52 Case 2 Gốc: PASS)

- **Bối cảnh ca lỗi gốc:** Trong quá khứ (Run `183d951c...`), khi hỏi về giấc ngủ và phục hồi trong tập luyện, mô hình OpenAI cố gắng trả lời và bịa trích dẫn ngoài tài liệu dẫn tới `REJECTED_BY_VALIDATOR`.
- **Thực thi qua Mobile Client:** Nhập câu hỏi: *"Giấc ngủ đóng vai trò như thế nào trong phục hồi cơ bắp khi tập luyện?"* và thực hiện qua toàn bộ luồng nghiệp vụ F01.
- **Run ID:** `aac3a2a5-aed2-4b71-b61f-ffe6e322a094` | **Correlation ID:** `b2850eee-601c-4ce8-b976-473ace6727d4`.
- **Latency:** `4,642 ms` | **Token Provider:** `2,905` prompt, `131` completion (Tổng: `3,036` tokens).
- **Kết quả trả về:**
  - `runStatus`: `SUCCEEDED`
  - `resultType`: `INSUFFICIENT_DATA`
  - `reasonCode`: `NO_RELEVANT_EVIDENCE`
  - `answer`: Rỗng (tuân thủ quy định: `INSUFFICIENT_DATA` không được chứa câu trả lời suy diễn).
  - `citations`: Danh sách rỗng `[]` (tuân thủ quy định: `INSUFFICIENT_DATA` không gắn trích dẫn giả).
  - `limitations`:
    - *"Nội dung không bao gồm thông tin cụ thể về vai trò của giấc ngủ trong phục hồi cơ bắp."*
    - *"Tài liệu chủ yếu tập trung vào giấc ngủ và mối quan hệ với cân nặng, chưa đề cập đến tác động của giấc ngủ đối với phục hồi sau tập luyện."*
  - `missingData`:
    - *"Thông tin về cách giấc ngủ ảnh hưởng đến quá trình phục hồi cơ bắp."*
    - *"Các nghiên cứu cụ thể về giấc ngủ và phục hồi thể chất."*
- **Đánh giá giao diện Mobile Client:**
  - Hiển thị đúng card cảnh báo `CHƯA ĐỦ DỮ LIỆU CHỨNG THỰC` (`ai-knowledge-insufficient-card`).
  - Tiêu đề: `Không đủ bằng chứng trong kho tri thức`.
  - Liệt kê trung thực các thông tin cần bổ sung theo đúng hợp đồng dữ liệu.
  - Vượt qua kiểm tra của `F01RuntimeOutputValidator` mà không bị từ chối.
- **Artifact:** [Ảnh màn hình Ca Giấc ngủ INSUFFICIENT_DATA](file:///C:/Users/Admin/.gemini/antigravity-cli/brain/8456b62b-ef1e-4186-95f0-a8bd9655289d/emulator_result_sleep_insufficient_data.png).

---

## 3. Tổng Hợp Số Lần Gọi Provider, Tokens Và Chi Phí Ước Tính Toàn Bộ

### 3.1. Phiên kiểm chứng ban đầu (Ca 1 & Ca 2)
- Embedding: 2 requests (WHO, Kháng lực ngày) + 1 request truy xuất nội bộ = 3 requests.
- Generation: 2 requests (WHO, Kháng lực ngày).
- Token generation Provider: 4,805 prompt, 768 completion = 5,573 tokens.
- Chi phí ước tính phiên ban đầu: ~$0.001183 USD.

### 3.2. Phiên kiểm chứng bổ sung (Ca 3 & Ca 4)
- **Embedding:** Đúng **2 requests** (Ca kháng lực phút + Ca giấc ngủ toàn luồng; 0 retry).
- **Generation:** Đúng **2 requests** (Ca kháng lực phút + Ca giấc ngủ toàn luồng; 0 retry).
- **Token generation Provider:**
  - Ca 3: 2,481 prompt, 401 completion = 2,882 tokens.
  - Ca 4: 2,905 prompt, 131 completion = 3,036 tokens.
  - Tổng phiên bổ sung: 5,386 prompt, 532 completion = **5,918 tokens** (nằm trong giới hạn 6,000 tokens).
- **Chi phí ước tính phiên bổ sung:**
  - Prompt: 5,386 $\times$ $0.15 / 1M = $0.000808 USD.
  - Completion: 532 $\times$ $0.60 / 1M = $0.000319 USD.
  - Embedding (~33 tokens): ~$0.000001 USD.
  - **Tổng chi phí ước tính phiên bổ sung:** **~$0.001128 USD** (rất thấp so với trần bổ sung 0.01 USD).

### 3.3. Bảng tổng kết chi phí ước tính toàn bộ quá trình kiểm chứng

| Giai đoạn kiểm chứng | Số Embedding | Số Generation | Tổng Tokens Generation | Chi phí ước tính (USD) | Hạn mức cấp |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Phiên ban đầu** | 3 | 2 | 5,573 | ~$0.001183 | 0.01 USD |
| **Phiên bổ sung** | 2 | 2 | 5,918 | ~$0.001128 | 0.01 USD |
| **TỔNG CỘNG** | **5** | **4** | **11,491** | **~$0.002311** | **0.02 USD** |

> *Ghi chú: Toàn bộ chi phí là con số ước tính dựa trên usage token được Provider trả về nhân với bảng giá niêm yết của OpenAI; chưa đối soát với hóa đơn billing dashboard chính thức.*

---

## 4. Danh Mục Artifacts Kiểm Chứng

| Tên Artifact | Đường dẫn tuyệt đối | Gắn với Case / Run | Mô tả bằng chứng |
| :--- | :--- | :--- | :--- |
| `emulator_result_live_openai.png` | `C:\Users\Admin\.gemini\antigravity-cli\brain\8456b62b-ef1e-4186-95f0-a8bd9655289d\emulator_result_live_openai.png` | Ca 1 (`ab86b5a5...`) | Màn hình câu hỏi WHO, badge INFORMATION, câu trả lời đầy đủ và trích dẫn. |
| `emulator_result_live_openai_scrolled.png` | `C:\Users\Admin\.gemini\antigravity-cli\brain\8456b62b-ef1e-4186-95f0-a8bd9655289d\emulator_result_live_openai_scrolled.png` | Ca 1 (`ab86b5a5...`) | Màn hình cuộn chi tiết trích dẫn nguồn, trích dẫn nguyên văn và miễn trừ y khoa. |
| `emulator_result_live_ca2.png` | `C:\Users\Admin\.gemini\antigravity-cli\brain\8456b62b-ef1e-4186-95f0-a8bd9655289d\emulator_result_live_ca2.png` | Ca 2 (`3e515a7b...`) | Màn hình câu hỏi số ngày tập kháng lực với trích dẫn WHO. |
| `emulator_result_resistance_minutes.png` | `C:\Users\Admin\.gemini\antigravity-cli\brain\8456b62b-ef1e-4186-95f0-a8bd9655289d\emulator_result_resistance_minutes.png` | Ca 3 (`d2434996...`) | Màn hình câu hỏi số phút kháng lực không bị ảo giác số phút, nêu rõ khuyến nghị >= 2 ngày/tuần. |
| `emulator_result_resistance_minutes_scrolled.png` | `C:\Users\Admin\.gemini\antigravity-cli\brain\8456b62b-ef1e-4186-95f0-a8bd9655289d\emulator_result_resistance_minutes_scrolled.png` | Ca 3 (`d2434996...`) | Màn hình cuộn trích dẫn chính xác chunk tăng cường cơ của WHO. |
| `emulator_result_sleep_insufficient_data.png` | `C:\Users\Admin\.gemini\antigravity-cli\brain\8456b62b-ef1e-4186-95f0-a8bd9655289d\emulator_result_sleep_insufficient_data.png` | Ca 4 (`aac3a2a5...`) | Màn hình hiển thị `INSUFFICIENT_DATA` với card `CHƯA ĐỦ DỮ LIỆU CHỨNG THỰC` và danh sách thông tin cần bổ sung. |

---

## 5. Rà Soát Khoảng Trống Kiến Trúc P2-M2 Còn Lại

1. **Revalidation trước khi xuất kết quả:** Đã có JWT check ban đầu và kiểm tra quyền sở hữu khi lấy Run, nhưng chưa có hook revalidation ngay trước lúc trả payload nếu phiên bị thu hồi trong khi AI đang xử lý.
2. **AI Kill Switch:** Đã có cờ cấu hình tĩnh (`ai.service.enabled=false`), chưa có bảng runtime flag động trên Admin Web.
3. **Phục hồi bền vững (Durable Recovery):** Đã có cơ chế bắt lỗi timeout/provider error đồng bộ, chưa có background sweeper tự động thu hồi các run bị bỏ rơi nếu backend crash lúc đang `RUNNING`.
4. **Cơ chế `store: false`:** Đã cấu hình cứng `"store": false` trong payload, ngăn OpenAI lưu trữ dữ liệu vào application store nhưng không miễn trừ chính sách lưu log bảo mật/giám sát lạm dụng tạm thời (abuse monitoring retention) của nhà cung cấp.
5. **Tài khoản Trainer:** Tài khoản `smoke.trainer@example.com` có role `TRAINER` nhưng mật khẩu fixture không có trong cấu hình. Tuân thủ `AGENTS.md`, không sửa trực tiếp DB; giao diện Trainer được ghi nhận trung thực là chưa kiểm chứng live.

---

## 6. Phạm Vi Nghiệm Thu & Khuyến Cáo

> [!WARNING]
> **Phân định rõ ranh giới nghiệm thu:**
> 1. Việc hoàn tất 4 ca trong bộ development live regression nhỏ này chứng minh **tính tương thích tích hợp thực tế (Runtime Integration)** của Mobile Client F01 và khắc phục được các lỗi kỹ thuật cụ thể đã phát hiện trong A-52.
> 2. Kết quả này **KHÔNG đồng nhất** với việc hoàn tất toàn bộ đánh giá chất lượng mô hình **A-52** (vốn yêu cầu chạy benchmark trên tập Holdout độc lập 60 ca và thẩm định của chuyên gia thể hình).
> 3. Kết quả này cũng **chưa cấu thành việc nghiệm thu toàn bộ tính năng F01 hay hoàn thành Milestone P2-M2** của dự án.
