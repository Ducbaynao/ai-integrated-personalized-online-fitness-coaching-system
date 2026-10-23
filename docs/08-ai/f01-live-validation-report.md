# Báo Cáo Kiểm Chứng Live & Bàn Giao Kỹ Thuật F01 / A-52

> **Mã đầu việc:** P2-M2 Mục 9 — Mobile Client F01 & A-52 Live Quality Verification  
> **Branch thực hiện:** `chore/f01-live-validation-20261023`  
> **Commit nền (Base commit):** `4591e91cee9c950337a454669040e2035d3af12f` (Merge PR #103 trên `origin/main`)  
> **Commit kiểm thử (Tested Commits):** `4591e91` (tích hợp PR #101 Mobile Client F01 và PR #100 A-52 fixes)  
> **Commit tài liệu (Doc-only commits):** `5f6ca84`, `a9a7dc3` và commit nghiệm thu hiện tại  
> **Pull Request:** [Draft PR #104](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/pull/104)  
> **Ngày thực hiện:** 2026-10-23  
> **Trạng thái:** HOÀN THÀNH BỘ DEVELOPMENT LIVE REGRESSION A-52 TRONG PHẠM VI CÁC CA ĐÃ KIỂM THỬ

---

## 1. Bảng Tổng Hợp Trạng Thái Các Ca Kiểm Chứng Live (4 Ca Thực Tế Trên Mobile)

| STT | Ca kiểm thử | Câu hỏi thực tế trên Mobile | Run ID trong PostgreSQL | runStatus | resultType | reasonCode | Trạng thái kỹ thuật |
| :---: | :--- | :--- | :--- | :---: | :---: | :---: | :---: |
| **1** | **WHO Hoạt động thể chất hàng tuần** | *"WHO khuyến nghị thời gian hoạt động thể chất hàng tuần cho người lớn là bao nhiêu?"* | `ab86b5a5-916f-46f2-badf-0c420fdcb8e3` | `SUCCEEDED` | `INFORMATION` | `NONE` | **PASS** |
| **2** | **Kháng lực (Số ngày mỗi tuần)** *(Kiểm chứng bổ sung)* | *"Khuyến nghị tập tăng cường cơ bắp bao nhiêu ngày mỗi tuần?"* | `3e515a7b-6743-46d3-a5d7-8e4c4462e495` | `SUCCEEDED` | `INFORMATION` | `NONE` | **PASS** *(Bổ sung)* |
| **3** | **Kháng lực (Số phút mỗi tuần)** *(A-52 Case 1 gốc)* | *"Theo WHO, người trưởng thành nên vận động kháng lực cường độ vừa bao nhiêu phút mỗi tuần?"* | `d2434996-3e21-4ad2-80e7-b4de1e00a38c` | `SUCCEEDED` | `INFORMATION` | `NONE` | **PASS** *(Trong phạm vi ca kiểm thử)* |
| **4** | **Giấc ngủ & Phục hồi cơ bắp** *(A-52 Case 2 gốc qua toàn bộ F01)* | *"Giấc ngủ đóng vai trò như thế nào trong phục hồi cơ bắp khi tập luyện?"* | `aac3a2a5-aed2-4b71-b61f-ffe6e322a094` | `SUCCEEDED` | `INSUFFICIENT_DATA` | `NO_RELEVANT_EVIDENCE` | **PASS** *(Trong phạm vi ca kiểm thử)* |

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
  - [Ảnh màn hình kết quả câu hỏi WHO](./artifacts/f01-live-validation/emulator_result_live_openai.png)
  - [Ảnh màn hình cuộn chi tiết trích dẫn](./artifacts/f01-live-validation/emulator_result_live_openai_scrolled.png)

### 2.2. Ca 2: Kháng Lực — Số Ngày Mỗi Tuần (PASS Bổ Sung)

- **Mục tiêu:** Kiểm chứng ca hỏi thuận (positive question) có bằng chứng trực tiếp trong corpus.
- **Run ID:** `3e515a7b-6743-46d3-a5d7-8e4c4462e495` | **Correlation ID:** `52e23c30-3683-4a16-8b1f-79df85e0d5d3`.
- **Latency:** `8,185 ms` | **Token Provider:** `2,437` prompt, `418` completion (Tổng: `2,855` tokens).
- **Nội dung trả lời:** Nêu rõ khuyến nghị thực hiện các bài tập tăng cường cơ ở cường độ vừa hoặc cao hơn tác động đến tất cả nhóm cơ chính ít nhất 2 ngày mỗi tuần.
- **Trích dẫn:** `WHO-PA-SB-2020-VI-05 v1.1.0` (mục 1 và 4: "ít nhất 2 ngày/tuần").
- **Artifact:** [Ảnh màn hình kết quả Ca 2](./artifacts/f01-live-validation/emulator_result_live_ca2.png).

### 2.3. Ca 3: Kháng Lực — Số Phút Mỗi Tuần (A-52 Case 1 Gốc: PASS Trong Phạm Vi Ca Kiểm Thử)

- **Bối cảnh ca lỗi gốc:** Trong quá khứ (Run `30802098...`), khi người dùng hỏi *"bao nhiêu phút mỗi tuần"*, mô hình OpenAI đã gán nhầm số phút aerobic (150–300 phút) sang cho tập kháng lực, dẫn đến trích dẫn sai và bị validator từ chối (`REJECTED_BY_VALIDATOR`).
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
  - [Ảnh màn hình Ca kháng lực hỏi số phút](./artifacts/f01-live-validation/emulator_result_resistance_minutes.png)
  - [Ảnh màn hình cuộn chi tiết trích dẫn](./artifacts/f01-live-validation/emulator_result_resistance_minutes_scrolled.png)

### 2.4. Ca 4: Giấc Ngủ & Phục Hồi Cơ Bắp Qua Toàn Bộ F01 (A-52 Case 2 Gốc: PASS Trong Phạm Vi Ca Kiểm Thử)

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
- **Artifact:** [Ảnh màn hình Ca Giấc ngủ INSUFFICIENT_DATA](./artifacts/f01-live-validation/emulator_result_sleep_insufficient_data.png).

---

## 3. Thống Kê Số Lần Gọi Provider, Tokens Và Chi Phí Ước Tính

### 3.1. Đối Chiếu Số Lần Gọi Provider (Phân Định Thành Công Và Lỗi Xác Thực 401)

Nhật ký tiến trình và DB ghi nhận chính xác toàn bộ lịch sử kết nối mạng tới OpenAI:

1. **Các lượt gọi ở các phiên thành công (5 lượt embedding, 4 lượt generation):**
   - **Embedding:** Đúng **5 lượt** (Ca 1 WHO: 1, Ca 2 Kháng lực ngày: 1, Ca kiểm tra retrieval nội bộ: 1, Ca 3 Kháng lực phút: 1, Ca 4 Giấc ngủ toàn luồng: 1). Toàn bộ 5 lượt đạt `HTTP 200 OK`, 0 retry.
   - **Generation:** Đúng **4 lượt** (Ca 1 WHO: 1, Ca 2 Kháng lực ngày: 1, Ca 3 Kháng lực phút: 1, Ca 4 Giấc ngủ toàn luồng: 1). Toàn bộ 4 lượt đạt `HTTP 200 OK`, 0 retry.
2. **Các lượt gọi bị từ chối xác thực ở phiên trước (3 lượt embedding):**
   - Đúng **3 lượt gọi HTTP** tới endpoint embedding bị Gateway OpenAI từ chối với `HTTP 401 Unauthorized` (`invalid_api_key`) do API key cũ hết hạn.
3. **Tổng số lượt phát sinh trên mạng:**
   - **Tổng Embedding:** $5 \text{ (thành công)} + 3 \text{ (401)} = \mathbf{8\text{ lượt}}$.
   - **Tổng Generation:** $\mathbf{4\text{ lượt}}$ (0 lượt thất bại).

### 3.2. Bảng Tổng Hợp Chi Phí Ước Tính Toàn Bộ (Ước Tính Từ Usage Token)

| Hạng mục | Số Embedding | Số Generation | Tokens Generation (Prompt / Completion) | Chi phí ước tính (USD) | Ghi chú |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **Phiên cũ (Key 401)** | 3 | 0 | 0 / 0 | $0.000000 | Bị chặn tại gateway, 0 token |
| **Phiên mới (Ca 1 & Ca 2)** | 3 | 2 | 4,805 / 768 (5,573) | ~$0.001183 | 2 run thành công + 1 internal retrieval |
| **Phiên bổ sung (Ca 3 & Ca 4)** | 2 | 2 | 5,386 / 532 (5,918) | ~$0.001128 | 2 run regression thành công trên Mobile |
| **TỔNG CỘNG TÍCH LŨY** | **8** | **4** | **10,191 / 1,300 (11,491)** | **~$0.002311** | **Chi phí ước tính từ usage** |

> [!IMPORTANT]
> **Lưu ý về số liệu tài chính:**
> 1. Con số **0.002311 USD** là **chi phí ước tính từ usage** theo đơn giá niêm yết của OpenAI ($0.150/1M prompt, $0.600/1M completion cho `gpt-4o-mini`, $0.020/1M cho `text-embedding-3-small`). Dự án chưa đối soát trực tiếp với hóa đơn billing dashboard chính thức từ OpenAI.
> 2. Mặc dù số tiền còn dư lớn so với hạn mức ngân sách cấp (0.02 USD), số lượng request được phê duyệt đã hoàn thành trọn vẹn. Không tự ý thực hiện thêm cuộc gọi API nào khi chưa có hạn mức mới.

---

## 4. Danh Mục Artifacts Kiểm Chứng (Đã Khử Khuẩn Secret)

Toàn bộ artifacts hình ảnh đã được sao chép vào thư mục tài liệu của repository tại `docs/08-ai/artifacts/f01-live-validation/` để người review Pull Request #104 có thể xem trực tiếp:

| Tên Artifact | Đường dẫn trong Repository | Gắn với Case / Run | Mô tả bằng chứng |
| :--- | :--- | :--- | :--- |
| `emulator_result_live_openai.png` | `docs/08-ai/artifacts/f01-live-validation/emulator_result_live_openai.png` | Ca 1 (`ab86b5a5...`) | Màn hình câu hỏi WHO, badge INFORMATION, câu trả lời đầy đủ và trích dẫn. |
| `emulator_result_live_openai_scrolled.png` | `docs/08-ai/artifacts/f01-live-validation/emulator_result_live_openai_scrolled.png` | Ca 1 (`ab86b5a5...`) | Màn hình cuộn chi tiết trích dẫn nguồn, trích dẫn nguyên văn và miễn trừ y khoa. |
| `emulator_result_live_ca2.png` | `docs/08-ai/artifacts/f01-live-validation/emulator_result_live_ca2.png` | Ca 2 (`3e515a7b...`) | Màn hình câu hỏi số ngày tập kháng lực với trích dẫn WHO. |
| `emulator_result_resistance_minutes.png` | `docs/08-ai/artifacts/f01-live-validation/emulator_result_resistance_minutes.png` | Ca 3 (`d2434996...`) | Màn hình câu hỏi số phút kháng lực: không bị ảo giác số phút, nêu rõ khuyến nghị >= 2 ngày/tuần. |
| `emulator_result_resistance_minutes_scrolled.png` | `docs/08-ai/artifacts/f01-live-validation/emulator_result_resistance_minutes_scrolled.png` | Ca 3 (`d2434996...`) | Màn hình cuộn trích dẫn chính xác chunk tăng cường cơ của WHO. |
| `emulator_result_sleep_insufficient_data.png` | `docs/08-ai/artifacts/f01-live-validation/emulator_result_sleep_insufficient_data.png` | Ca 4 (`aac3a2a5...`) | Màn hình hiển thị `INSUFFICIENT_DATA` với card `CHƯA ĐỦ DỮ LIỆU CHỨNG THỰC` và danh sách thông tin cần bổ sung. |

---

## 5. Rà Soát Khoảng Trống Kiến Trúc P2-M2 Còn Lại

1. **Revalidation trước khi xuất kết quả:** Đã có JWT check ban đầu và kiểm tra quyền sở hữu khi lấy Run, nhưng chưa có hook revalidation ngay trước lúc trả payload nếu phiên bị thu hồi hoặc tài khoản bị khóa trong khi AI đang xử lý (sẽ được giải quyết tại đầu việc triển khai tiếp theo).
2. **AI Kill Switch:** Đã có cờ cấu hình tĩnh (`ai.service.enabled=false`), chưa có bảng runtime flag động trên Admin Web.
3. **Phục hồi bền vững (Durable Recovery):** Đã có cơ chế bắt lỗi timeout/provider error đồng bộ, chưa có background sweeper tự động thu hồi các run bị bỏ rơi nếu backend crash lúc đang `RUNNING`.
4. **Cơ chế `store: false`:** Đã cấu hình cứng `"store": false` trong payload, ngăn OpenAI lưu trữ dữ liệu vào application store nhưng không miễn trừ chính sách lưu log bảo mật/giám sát lạm dụng tạm thời (abuse monitoring retention) của nhà cung cấp.
5. **Tài khoản Trainer:** Tài khoản `smoke.trainer@example.com` có role `TRAINER` nhưng mật khẩu fixture không có trong cấu hình. Tuân thủ `AGENTS.md`, không sửa trực tiếp DB; giao diện Trainer được ghi nhận trung thực là chưa kiểm chứng live.

---

## 6. Phạm Vi Nghiệm Thu & Khuyến Cáo

> [!WARNING]
> **Phân định rõ ranh giới nghiệm thu:**
> 1. Việc hoàn tất 4 ca trong bộ development live regression nhỏ này chứng minh **tính tương thích tích hợp thực tế (Runtime Integration)** của Mobile Client F01 và khắc phục được các lỗi kỹ thuật cụ thể **trong phạm vi các ca kiểm thử đã thực hiện**. Báo cáo không khẳng định mô hình đã được "khắc phục hoàn toàn" khỏi mọi dạng ảo giác trong tương lai.
> 2. Khung đánh giá rubric 6 tiêu chí và tập Independent Holdout 60 ca hiện giữ trạng thái **đề xuất kỹ thuật (Proposed)**, chưa có biên bản phê duyệt chính thức từ chủ dự án.
> 3. Kết quả này **KHÔNG đồng nhất** với việc hoàn tất toàn bộ đánh giá chất lượng mô hình **A-52** hay nghiệm thu toàn bộ Milestone P2-M2 của dự án.
