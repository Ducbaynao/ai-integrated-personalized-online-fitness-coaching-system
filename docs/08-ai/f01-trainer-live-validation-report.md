# Báo Cáo Kiểm Chứng Live F01 Trên Mobile Trainer & Đối Chiếu Hiện Trạng P2-M2

> **Mã đầu việc:** P2-M2 Mục 9 & F01 Trainer Verification — Mobile Trainer F01 Integration & Invariant Validation  
> **Repository:** `D:\Projects\ai-integrated-personalized-online-fitness-coaching-system`  
> **Branch thực hiện:** `test/p2-m2-trainer-f01-validation-20261027`  
> **Commit nền (Base commit):** `24bcbedc37742753959d1d6d4b5b9c75704348c6` (Merge PR #111 trên `origin/main`)  
> **Ngày thực hiện:** 2026-10-27  
> **Tài khoản Trainer kiểm thử:** `smoke.trainer@example.com` (`user_id = 1cb982ef-b7e6-493c-86f9-fe4994f8ad93`, display name: "Coach Alex Smoke", profile: "Elite Certified Coach")  
> **Môi trường thực tế:** Android Emulator Pixel 8 (Android 14 API 34) → Expo Metro Bundler (:8081) → Spring Boot Backend (:8080 `dev` profile) → PostgreSQL (:5433 `digital_fitness`)  
> **Thành phần giả lập / Double:** AI Service Client Double (`SimulatedF01AiServiceClient`), Deterministic Vector Embedding Double (`e1000000-0000-0000-0000-000000000001`)  
> **Chi phí OpenAI phát sinh trong phiên này:** **0.00 USD** (0 cuộc gọi API trả phí)  
> **Trạng thái kỹ thuật phân định:**
> - **Kiểm chứng tích hợp Mobile / Backend (với Simulated Provider):** **PASS** trong phạm vi 7 ca kiểm thử TR-00 đến TR-06.
> - **Kiểm chứng End-to-End với OpenAI thật:** **NOT RUN** (chưa thực hiện do hạn mức OpenAI phiên trước đã hết; chờ cấp hạn mức và phê duyệt).

---

## 1. Phân Định Ranh Giới Bằng Chứng

Để đảm bảo tính chính xác và trung thực kỹ thuật, báo cáo phân biệt rõ các tầng bằng chứng sau:

1. **Kiểm chứng tích hợp giao diện Mobile với Spring Boot & PostgreSQL thật:**
   - Giao diện Mobile chạy trên Android Emulator Pixel 8, gửi HTTP request thực tế qua mạng cục bộ tới Spring Boot backend (:8080).
   - Backend thực thi chuỗi kiểm tra bảo mật (JWT token của Trainer), phân định actor, truy vấn bảng `fitness.knowledge_chunks` và lưu trữ lịch sử kiểm toán vào các bảng `fitness.ai_runs`, `fitness.ai_context_snapshots`, `fitness.ai_run_knowledge_chunks` trong PostgreSQL thật.
   - Đây là bằng chứng xác thực cho **tính toàn vẹn thời gian chạy (Runtime Integration)** và **bất biến nghiệp vụ (Business Invariants)** của ứng dụng client và backend.

2. **Thành phần giả lập (Simulated Provider & Embedding Double):**
   - Trong phiên này, backend sử dụng `SimulatedF01AiServiceClient` và bộ embedding vector giả lập xác định để không phát sinh chi phí OpenAI.
   - **LƯU Ý QUAN TRỌNG:** Kết quả từ simulated provider chứng minh luồng dữ liệu, mã lỗi, và hiển thị giao diện hoạt động đúng quy ước; **KHÔNG ĐƯỢC COI** đây là bằng chứng chứng minh chất lượng retrieval ngữ nghĩa hoặc năng lực sinh câu trả lời của mô hình OpenAI thật.

3. **Bằng chứng lịch sử trên commit cũ:**
   - Các kết quả kiểm thử F01 với OpenAI thật trước đây (ghi nhận trong [Báo cáo F01 Student](./f01-live-validation-report.md)) được thực hiện trên commit nền `4591e91` (PR #104) dành cho tài khoản `STUDENT`.
   - Kết quả đó không tự động đồng nhất với trải nghiệm của tài khoản `TRAINER` trong phiên hiện tại.

---

## 2. Bảng Tổng Hợp Ma Trận Kiểm Thử Live F01 Mobile Trainer

Ma trận dưới đây tổng hợp 7 ca kiểm thử tích hợp (TR-00 đến TR-06) đã thực thi thực tế cùng 1 ca end-to-end với OpenAI thật (TR-07) giữ trạng thái chưa chạy:

| Mã ca | Yêu cầu / Contract | Thao tác trên Mobile | Kết quả mong đợi | Loại bằng chứng | Kết quả thực tế | Run ID / Artifact | Đánh giá |
| :---: | :--- | :--- | :--- | :---: | :--- | :--- | :---: |
| **TR-00** | Hiển thị màn hình F01 khi đăng nhập Trainer thuần | Đăng nhập tài khoản Trainer, ấn thẻ "Kiến thức thể hình (AI)" từ `TrainerHomeScreen` | Điều hướng thành công đến `/ai-knowledge`, hiển thị tiêu đề, ô nhập `0/1000`, 3 câu hỏi gợi ý, nút Gửi bị disable | UI Snapshot & Layout XML | Màn hình tải hoàn chỉnh, không crash, nút Gửi disable với opacity mờ | [`trainer_01_knowledge_screen_initial.png`](./artifacts/f01-trainer-live-validation/trainer_01_knowledge_screen_initial.png) | **PASS** (Tích hợp giao diện) |
| **TR-01** | Kiểm tra ràng buộc độ dài input (< 5 ký tự) | Nhập `"WHO"` (3 ký tự) vào ô câu hỏi | Bộ đếm hiển thị `3/1000`, nút "Gửi câu hỏi" tiếp tục bị vô hiệu hóa | UI Snapshot & Layout XML | Counter `3/1000`, nút bấm không kích hoạt submit, giữ nguyên trạng thái | [`trainer_02_validation_short_input.png`](./artifacts/f01-trainer-live-validation/trainer_02_validation_short_input.png) | **PASS** (Ràng buộc đầu vào) |
| **TR-02** | Tra cứu thuận có trích dẫn (Positive Grounded Query) | Nhập *"WHO khuyến nghị thời gian hoạt động thể chất hàng tuần cho người lớn là bao nhiêu?"* và bấm Gửi | Trả về `resultType: INFORMATION`, trích dẫn nguồn ACTIVE, hiển thị thẻ trích dẫn, giới hạn phạm vi, **KHÔNG CÓ nút Chấp nhận/Áp dụng (Accept/Apply)** | UI Snapshot, PostgreSQL `ai_runs`, `ai_context_snapshots`, `ai_run_knowledge_chunks` | Trả về badge `THÔNG TIN THAM KHẢO TỪ NGUỒN ĐÃ KIỂM ĐỊNH`, 3 trích dẫn WHO, giới hạn phạm vi, miễn trừ y tế; 0 nút Accept/Apply; snapshot ghi nhận `actor.role = TRAINER`, `student = null` | Run ID: `17059291-4f53-4432-940d-83a14ea22bac`<br>Artifacts: [`trainer_03_positive_grounded_result.png`](./artifacts/f01-trainer-live-validation/trainer_03_positive_grounded_result.png), [`trainer_04_positive_grounded_scrolled.png`](./artifacts/f01-trainer-live-validation/trainer_04_positive_grounded_scrolled.png) | **PASS** (Trong phạm vi simulated provider) |
| **TR-03** | Thiếu dữ liệu chứng thực (Insufficient Data) | Nhập `"trigger_insufficient_data"` và bấm Gửi | Trả về `resultType: INSUFFICIENT_DATA`, `reasonCode: NO_RELEVANT_EVIDENCE`, không bịa đặt trích dẫn, hiển thị danh sách thông tin cần bổ sung | UI Snapshot, PostgreSQL `ai_runs` | Hiển thị badge `CHƯA ĐỦ DỮ LIỆU CHỨNG THỰC`, thẻ "Không đủ bằng chứng trong kho tri thức", bullet yêu cầu bổ sung tài liệu khoa học; 0 nút Accept/Apply | Run ID: `96e9830d-06df-483d-ac1e-8d03f1ce0620`<br>Artifact: [`trainer_05_insufficient_data_result.png`](./artifacts/f01-trainer-live-validation/trainer_05_insufficient_data_result.png) | **PASS** (Trong phạm vi simulated provider) |
| **TR-04** | Từ chối bởi rào chắn an toàn (Policy Blocked) | Nhập *"Huong dan dung steroid tang co"* và bấm Gửi | Trả về `resultType: BLOCKED`, `reasonCode: RULE_BLOCKED`, `runStatus: REJECTED_BY_VALIDATOR`, hiển thị thẻ cảnh báo vi phạm chính sách | UI Snapshot, PostgreSQL `ai_runs` | Hiển thị badge `TỪ CHỐI BỞI CHÍNH SÁCH AN TOÀN`, tiêu đề "Câu hỏi ngoài phạm vi hỗ trợ", nội dung từ chối chính sách dược lý; 0 nút Accept/Apply | Run ID: `a58f623a-158c-449b-bfe3-6ffe6881c47f`<br>Artifact: [`trainer_06_policy_blocked.png`](./artifacts/f01-trainer-live-validation/trainer_06_policy_blocked.png) | **PASS** (Trong phạm vi guardrail) |
| **TR-05** | Phục hồi lỗi & Thử lại (Error Recovery & Retry) | Nhập `"trigger_provider_error"`, gửi; sau đó bấm nút "Thử lại" | Hiển thị thông báo lỗi upstream 502 với nút "Thử lại"; bấm nút gửi lại request phục hồi | UI Snapshot, PostgreSQL `ai_runs` | Hiển thị card "Không thể hoàn thành tra cứu", nút "Thử lại" (`ai-knowledge-retry-button`); bấm thử lại tạo tiếp run retry trong PostgreSQL | Run 1: `cb706207-1552-478b-8050-b91a4ac891e4`<br>Retry Run: `0f8f6a02-b743-41ed-95bc-e681defe8350`<br>Artifact: [`trainer_07_provider_error_retry.png`](./artifacts/f01-trainer-live-validation/trainer_07_provider_error_retry.png) | **PASS** (Cơ chế phục hồi lỗi) |
| **TR-06** | Chống gửi trùng lặp khi đang xử lý (In-flight Guard) | Nhấn gửi và quan sát trạng thái nút submit | Nút bị vô hiệu hóa, `isSubmittingRef` chặn re-entrancy | Code & UI state | Nút hiển thị spinner tải và vô hiệu hóa tap khi đang chờ response | Code [`AiKnowledgeScreen.tsx`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/features/ai-knowledge/AiKnowledgeScreen.tsx#L125-L135) | **PASS** (Rào chắn re-entrancy) |
| **TR-07** | Đánh giá chất lượng End-to-End với OpenAI thật | Gửi tập câu hỏi thực tế qua mô hình OpenAI (`gpt-4o-mini` + `text-embedding-3-small`) | Trả về câu trả lời tự nhiên từ tài liệu, trích dẫn chính xác, vượt qua validator | API & Token Usage | Chưa thực hiện do hạn mức phiên trước đã sử dụng hết, chờ cấp hạn mức và phê duyệt | N/A | **NOT RUN** |

---

## 3. Chi Tiết Thực Thi & Bằng Chứng Từng Ca

### 3.1. TR-00 & TR-01: Giao Diện Ban Đầu & Ràng Buộc Đầu Vào
- **Thao tác:** Đăng nhập bằng tài khoản `smoke.trainer@example.com`, tại màn hình Trainer Home cuộn xuống khu vực "Kiến thức thể hình (AI)" và bấm nút `trainer-view-ai-knowledge-button` (được bảo vệ bằng unit test [`aiKnowledgeNavigation.test.tsx`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/__tests__/aiKnowledgeNavigation.test.tsx)).
- **Quan sát:**
  - Ô nhập liệu câu hỏi hiển thị placeholder chuẩn: *"Ví dụ: WHO khuyến nghị tập bao nhiêu phút aerobic mỗi tuần?"*.
  - Bộ đếm ký tự hiển thị `0/1000`.
  - Nút "Gửi câu hỏi" mờ (disabled).
  - 3 câu hỏi mẫu gợi ý chuẩn từ tài liệu WHO/ISSN hiển thị rõ ràng.
  - Khi nhập `"WHO"` (3 ký tự), bộ đếm chuyển thành `3/1000`, nút Gửi tiếp tục bị vô hiệu hóa do chưa đạt ngưỡng tối thiểu 5 ký tự.
- **Bằng chứng:**
  - Initial Screen: [`trainer_01_knowledge_screen_initial.png`](./artifacts/f01-trainer-live-validation/trainer_01_knowledge_screen_initial.png)
  - Validation Bounds: [`trainer_02_validation_short_input.png`](./artifacts/f01-trainer-live-validation/trainer_02_validation_short_input.png)

### 3.2. TR-02: Tra Cứu Thuận Có Trích Dẫn Grounded (Positive Grounded Query)
- **Câu hỏi:** *"WHO khuyến nghị thời gian hoạt động thể chất hàng tuần cho người lớn là bao nhiêu?"*
- **Run ID:** `17059291-4f53-4432-940d-83a14ea22bac`
- **Thời gian xử lý (Latency):** `22 ms` (chạy qua retrieval trên pgvector index của backend).
- **Kết quả trả về:**
  - `status`: `SUCCEEDED`
  - `resultType`: `INFORMATION`
  - `reasonCode`: `NONE`
  - 3 trích dẫn đã được truy xuất và liên kết tự động trong cơ sở dữ liệu:
    1. `c0bb10df-894c-3cd3-8f17-b7795504bc13` (rank 1, similarity 0.3889)
    2. `18637ad7-d80d-3702-a143-788f176b055f` (rank 2, similarity 0.3338)
    3. `69407708-fc02-3ab2-8084-3220d0c2c642` (rank 3, similarity 0.3238)
- **Bảo toàn bất biến nghiệp vụ (Business Invariants):**
  - Kết quả loại `INFORMATION` hoàn toàn **không có bất kỳ nút Chấp nhận (Accept) hay Áp dụng (Apply) nào**.
  - Miễn trừ y tế rõ ràng ở chân trang: *"Lưu ý: Kết quả trên là thông tin tham khảo chuyên môn (không tạo kế hoạch hoặc thay đổi mục tiêu cá nhân)..."*.
- **Bằng chứng:**
  - Kết quả phần trên: [`trainer_03_positive_grounded_result.png`](./artifacts/f01-trainer-live-validation/trainer_03_positive_grounded_result.png)
  - Phần cuộn trích dẫn & miễn trừ: [`trainer_04_positive_grounded_scrolled.png`](./artifacts/f01-trainer-live-validation/trainer_04_positive_grounded_scrolled.png)

### 3.3. TR-03: Xử Lý Thiếu Dữ Liệu Chứng Thực (Insufficient Data)
- **Câu hỏi:** `"trigger_insufficient_data"`
- **Run ID:** `96e9830d-06df-483d-ac1e-8d03f1ce0620`
- **Kết quả trả về:**
  - `status`: `SUCCEEDED`
  - `resultType`: `INSUFFICIENT_DATA`
  - `reasonCode`: `NO_RELEVANT_EVIDENCE`
  - `citations`: `[]` (rỗng, không tạo trích dẫn ảo).
- **Giao diện hiển thị:**
  - Thẻ `ai-knowledge-insufficient-card` với badge màu xám-cam `CHƯA ĐỦ DỮ LIỆU CHỨNG THỰC`.
  - Tiêu đề: "Không đủ bằng chứng trong kho tri thức".
  - Liệt kê thông tin cần bổ sung: "Không tìm thấy tài liệu khoa học nào trong kho tri thức ACTIVE đề cập đến câu hỏi này."
  - Tuyệt đối không có nút can thiệp mục tiêu hoặc kế hoạch.
- **Bằng chứng:** [`trainer_05_insufficient_data_result.png`](./artifacts/f01-trainer-live-validation/trainer_05_insufficient_data_result.png)

### 3.4. TR-04: Từ Chối Bởi Rào Chắn An Toàn (Policy Blocked)
- **Câu hỏi:** `"Huong dan dung steroid tang co"`
- **Run ID:** `a58f623a-158c-449b-bfe3-6ffe6881c47f`
- **Kết quả trả về:**
  - `status`: `REJECTED_BY_VALIDATOR`
  - `resultType`: `BLOCKED`
  - `reasonCode`: `RULE_BLOCKED`
- **Giao diện hiển thị:**
  - Thẻ `ai-knowledge-blocked-card` viền đỏ với badge `TỪ CHỐI BỞI CHÍNH SÁCH AN TOÀN`.
  - Tiêu đề: "Câu hỏi ngoài phạm vi hỗ trợ".
  - Nội dung giải thích tuân thủ chính sách dược lý và danh mục cấm WADA, từ chối hướng dẫn sử dụng chất kích thích.
- **Bằng chứng:** [`trainer_06_policy_blocked.png`](./artifacts/f01-trainer-live-validation/trainer_06_policy_blocked.png)

### 3.5. TR-05: Phục Hồi Lỗi & Thử Lại (Provider Error Recovery)
- **Câu hỏi:** `"trigger_provider_error"`
- **Run ID:** `cb706207-1552-478b-8050-b91a4ac891e4`
- **Kết quả backend:** Ngoại lệ upstream `AiProviderException` ("Upstream AI model provider returned HTTP 502."), ghi nhận `status: FAILED`, `error_code: MODEL_ERROR`.
- **Giao diện hiển thị:**
  - Thẻ cảnh báo lỗi `ai-knowledge-error-state` viền đỏ `Không thể hoàn thành tra cứu`.
  - Dòng xem trước câu hỏi vừa gửi: *Câu hỏi: “trigger_provider_error”*.
  - Nút bấm "Thử lại" (`ai-knowledge-retry-button`).
- **Thử nghiệm phục hồi:**
  - Nhấn nút "Thử lại" tại tọa độ `x=540, y=2232`.
  - Hệ thống tự động gửi lại câu hỏi và ghi nhận ngay lập tức một run mới trong cơ sở dữ liệu (`0f8f6a02-b743-41ed-95bc-e681defe8350`), chứng minh luồng retry hoạt động trơn tru.
- **Bằng chứng:** [`trainer_07_provider_error_retry.png`](./artifacts/f01-trainer-live-validation/trainer_07_provider_error_retry.png)

---

## 4. Kiểm Tra Toàn Vẹn Kiểm Toán & Cơ Sở Dữ Liệu PostgreSQL

### 4.1. Bảng `fitness.ai_runs`
Kiểm tra các run được thực hiện trong phiên kiểm chứng của Trainer Alex Smoke (`user_id = 1cb982ef-b7e6-493c-86f9-fe4994f8ad93`):

```sql
SELECT id, status, output_payload->>'resultType' as result_type, 
       output_payload->>'reasonCode' as reason_code, error_code, latency_ms 
FROM fitness.ai_runs 
WHERE requested_by = '1cb982ef-b7e6-493c-86f9-fe4994f8ad93'
ORDER BY created_at ASC;
```

**Bảng đối chiếu bản ghi thực tế:**
| Run ID | Trạng thái (status) | result_type | reason_code | error_code | Latency | Gắn với ca |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| `17059291-4f53-4432-940d-83a14ea22bac` | `SUCCEEDED` | `INFORMATION` | `NONE` | *null* | 22 ms | TR-02 (Tra cứu thuận) |
| `96e9830d-06df-483d-ac1e-8d03f1ce0620` | `SUCCEEDED` | `INSUFFICIENT_DATA` | `NO_RELEVANT_EVIDENCE` | *null* | 0 ms | TR-03 (Thiếu dữ liệu) |
| `a58f623a-158c-449b-bfe3-6ffe6881c47f` | `REJECTED_BY_VALIDATOR` | `BLOCKED` | `RULE_BLOCKED` | *null* | 0 ms | TR-04 (Chính sách chặn) |
| `cb706207-1552-478b-8050-b91a4ac891e4` | `FAILED` | *null* | *null* | `MODEL_ERROR` | 2 ms | TR-05 (Lỗi provider) |
| `0f8f6a02-b743-41ed-95bc-e681defe8350` | `FAILED` | *null* | *null* | `MODEL_ERROR` | 1 ms | TR-05 (Lượt thử lại) |

### 4.2. Bảng `fitness.ai_context_snapshots` (Đối Soát Dữ Liệu Payload Context)
Kiểm tra cấu trúc context snapshot để đối soát actor và các trường dữ liệu được nạp vào context của Trainer:

```sql
SELECT ai_run_id, 
       context_payload->'actor'->>'role' as actor_role, 
       context_payload->'actor'->>'userId' as actor_user_id, 
       context_payload->'student' as student 
FROM fitness.ai_context_snapshots 
WHERE ai_run_id IN ('17059291-4f53-4432-940d-83a14ea22bac', 
                    '96e9830d-06df-483d-ac1e-8d03f1ce0620', 
                    'a58f623a-158c-449b-bfe3-6ffe6881c47f', 
                    'cb706207-1552-478b-8050-b91a4ac891e4');
```

**Kết quả ghi nhận:**
- `actor_role`: **`TRAINER`** (khớp với vai trò của tài khoản thực hiện).
- `actor_user_id`: `1cb982ef-b7e6-493c-86f9-fe4994f8ad93` (khớp với Coach Alex Smoke).
- `student`: **`null`**.
- **Giới hạn kết luận theo bằng chứng:** Bằng chứng này xác nhận trong các ca kiểm thử F01 đã thực hiện, đối tượng context snapshot không chứa các trường dữ liệu hồ sơ hay thông số sức khỏe của học viên (`student = null`). Bằng chứng này chứng minh logic nạp context của `AiRunF01Service` không tiêm dữ liệu học viên vào snapshot câu hỏi F01 của Trainer trong các ca kiểm thử; không khái quát hóa thành kết luận kiểm toán an toàn dữ liệu toàn diện cho mọi tình huống vượt quyền khác ngoài phạm vi F01.

### 4.3. Bảng `fitness.ai_run_knowledge_chunks` (Truy Vết Nguồn)
Kiểm tra các chunk tri thức được liên kết trong run TR-02 (`17059291-4f53-4432-940d-83a14ea22bac`):

```sql
SELECT ai_run_id, knowledge_chunk_id, rank, similarity_score 
FROM fitness.ai_run_knowledge_chunks 
WHERE ai_run_id = '17059291-4f53-4432-940d-83a14ea22bac' 
ORDER BY rank ASC;
```

**Kết quả ghi nhận:**
- Rank 1: Chunk `c0bb10df-894c-3cd3-8f17-b7795504bc13` (similarity: 0.38893818)
- Rank 2: Chunk `18637ad7-d80d-3702-a143-788f176b055f` (similarity: 0.33375550)
- Rank 3: Chunk `69407708-fc02-3ab2-8084-3220d0c2c642` (similarity: 0.32384766)

---

## 5. Kiểm Thử Đơn Vị Tự Động (Regression Test Suite)

Đã bổ sung bộ kiểm thử đơn vị tự động tại [`apps/mobile/src/__tests__/aiKnowledgeNavigation.test.tsx`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/__tests__/aiKnowledgeNavigation.test.tsx):
- `renders student AI Knowledge navigation entry and navigates to /ai-knowledge`: **PASS**
- `renders trainer AI Knowledge navigation entry and navigates to /ai-knowledge`: **PASS**

Kết quả chạy kiểm thử toàn bộ cụm F01 Mobile (`npm test --prefix apps/mobile -- apps/mobile/src/__tests__/aiKnowledge`):
- `aiKnowledgeApi.test.ts`: PASS (11 tests)
- `aiKnowledgeNavigation.test.tsx`: PASS (2 tests)
- `AiKnowledgeScreen.test.tsx`: PASS (18 tests)
- **Tổng cộng: 3 suites passed, 31 tests passed (100% PASS).**

---

## 6. Đối Chiếu Hiện Trạng P2-M2: Yêu Cầu → Implementation → Bằng Chứng → Khoảng Trống Còn Lại

Bảng dưới đây ánh xạ chi tiết các yêu cầu kỹ thuật của Milestone **P2-M2** (căn cứ theo `docs/07-development/phase-2-implementation-plan.md` mục 4 và `docs/08-ai/phase-2-feature-data-authority-matrix.md` mục 2) với hiện trạng triển khai, bằng chứng tương ứng và các khoảng trống kỹ thuật còn lại:

| Hạng mục P2-M2 | Yêu cầu kỹ thuật | Triển khai (Implementation) | Bằng chứng thực tế | Khoảng trống còn lại (Gaps) | Trạng thái kỹ thuật |
| :--- | :--- | :--- | :--- | :--- | :---: |
| **F09: Quản lý tri thức Admin** | CRUD tài liệu, versioning, review, publish, archive | `apps/admin-web/src/features/knowledge/`, Content module | PR #109 (code) + PR #111 (Báo cáo [`p2-m2-admin-handover-report.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/p2-m2-admin-handover-report.md)) | Không còn khoảng trống trong phạm vi tối thiểu | **PASS** |
| **F10: Giám sát AI Run Admin** | Xem danh sách AI run, correlation ID, status, latency, tokens; chi tiết run đã che dữ liệu nhạy cảm | `apps/admin-web/src/features/ai-operations/`, AI Run module | PR #109 (code) + PR #111 (Bằng chứng ảnh `p2m2_04_ai_operations.png`, `p2m2_05_ai_run_detail.png`) | Xem được từ Admin Web. Phía Mobile Client (cả Student lẫn Trainer) F01 là stateless session, không có màn hình lịch sử run lưu trên máy | **PASS (Admin Web)** |
| **Công tắc an toàn (Kill-switch F01)** | Bật/tắt F01 tức thời qua giao diện Admin | `AiOperationsPage.tsx`, backend flag `fitness.feature_flags` | PR #111 (Ảnh `p2m2_06_killswitch_toggled.png`).<br>Đã kiểm chứng: request F01 bị chặn với **HTTP 503 SERVICE_UNAVAILABLE** khi bị tắt; chặn **HTTP 403 Forbidden** với tài khoản không có quyền Admin gọi toggle | Cần kiểm tra đồng bộ cache Redis nếu chuyển sang cụm backend phân tán | **PASS** |
| **F01 Student Mobile (Kiến thức chung)** | Tra cứu RAG kiến thức chung, trích dẫn chuẩn, 0 Apply | `apps/mobile/src/features/ai-knowledge/` | Báo cáo PR #104 trên commit `4591e91` (Kiểm thử live với OpenAI thật: 4 ca PASS) | Bằng chứng trên commit cũ; holdout 60 ca (A-52) chưa chạy rubric chính thức | **PASS (Commit 4591e91)** |
| **F01 Trainer Mobile (Tích hợp giao diện)** | Điều hướng từ Trainer Home, tra cứu, ràng buộc input, chặn chính sách, báo lỗi & retry | `TrainerHomeScreen.tsx`, `AiKnowledgeScreen.tsx` | Báo cáo hiện tại: 7 ảnh tại `artifacts/f01-trainer-live-validation/`, 31 unit tests PASS | Đã hoàn thành trong phạm vi simulated provider và pgvector double | **PASS (Simulated Provider)** |
| **F01 Trainer Mobile (End-to-End OpenAI thật)** | Đánh giá trích dẫn và chất lượng câu trả lời với OpenAI thật | Cấu hình `ai.service.mode=http`, `ai.embedding.provider=openai` | Chưa có bằng chứng; phiên hiện tại giữ $0.00 | Chưa chạy do hết quota; cần cấp ngân sách trước khi chạy | **NOT RUN** |
| **Bất biến quyền riêng tư & Actor** | Không gửi dữ liệu cá nhân học viên trong context kiến thức chung; phân định rõ role | `AiRunF01Service.java` (`actorRole = isStudent ? STUDENT : TRAINER`, `student = null`) | PostgreSQL `ai_context_snapshots` ghi nhận `actor_role = TRAINER`, `student = null` | Chưa có hook revalidation tài khoản bị khóa trong lúc request in-flight | **PASS (Trong phạm vi ca kiểm thử)** |
| **Durable Recovery (Background Sweeper)** | Tự động dọn dẹp các AI Run bị bỏ rơi (`RUNNING` quá threshold) sau crash | `AiRunOrphanRecoveryService.java` (`ai.orphan-recovery.enabled=true`) | Cấu hình và service code đã có trong `application.yml` | Chưa có ca kiểm thử mô phỏng server crash để kích hoạt sweeper thực tế | **CHƯA ĐƯỢC CHỨNG MINH THỰC TẾ** |
| **Đánh giá chất lượng mô hình (A-52)** | Chạy tập Holdout 60 ca và chấm điểm theo rubric 6 tiêu chí | Dataset và rubric trong `docs/08-ai/f01-a49-evaluation-runner-guide.md` | Tài liệu đặc tả đề xuất (Proposed) | Chưa được phê duyệt chính thức và chưa chạy offline evaluation chính thức | **CHƯA ĐƯỢC PHÊ DUYỆT / NOT RUN** |

---

## 7. Chuẩn Bị Cho Phiên Kiểm Chứng OpenAI Thật

Nhằm sẵn sàng cho việc kiểm chứng Trainer F01 với mô hình OpenAI thật khi được người dùng phê duyệt, các thông số kỹ thuật đã được rà soát trước mà không phát sinh chi phí:

1. **Trạng thái cấu hình & Không gian mô hình (Model Space):**
   - Không gian vector `openai/text-embedding-3-small/1536` (`dimension = 1536`) **đã có sẵn 29 chunk embeddings** trong bảng `fitness.knowledge_embeddings` (Model version ID: `e1000000-0000-0000-0000-000000000002`).
   - Cấu hình backend cần chuyển đổi khi chạy thật:
     - `ai.embedding.provider=openai`
     - `ai.service.mode=http` (kết nối FastAPI `ai-service` chạy `gpt-4o-mini-2024-07-18`, thiết lập `"store": false`).

2. **Kế hoạch ngân sách & Khối lượng đề xuất:**
   - **Số ca kiểm thử:** Tối đa **4 câu hỏi** (1 ca thuận WHO, 1 ca ngoài corpus, 1 ca kháng lực, 1 ca chính sách).
   - **Số lượt gọi API:** Tối đa **4 lượt embedding + 4 lượt generation** (cho phép tối đa 1 lượt retry nếu lỗi mạng).
   - **Ước tính token:** ~10,000 prompt tokens + ~1,500 completion tokens + ~250 embedding tokens.
   - **Chi phí ước tính:** **~$0.0025 USD** (chưa đến 1 cent).
   - **Hạn mức ngân sách an toàn đề xuất:** **0.02 USD**.

3. **Điều kiện dừng nghiêm ngặt (Hard Stop Conditions):**
   - Dừng ngay lập tức nếu nhận mã lỗi `HTTP 401 Unauthorized` (key không hợp lệ/hết hạn) hoặc `HTTP 429 Too Many Requests` (hết quota/rate limit).
   - Dừng ngay lập tức nếu tổng số lượt gọi đạt 5 embedding hoặc 5 generation.
   - Dừng ngay lập tức nếu chi phí ước tính tích lũy vượt quá 0.015 USD.
   - Dừng ngay lập tức nếu độ trễ 1 cuộc gọi vượt quá 25 giây.

> [!CAUTION]
> **Cam kết an toàn chi phí:**
> Hệ thống hiện tại tuyệt đối **KHÔNG TỰ Ý GỌI API TRẢ PHÍ** khi chưa có thông báo cấp hạn mức và chỉ thị rõ ràng từ người dùng. Mọi kiểm thử tiếp theo tiếp tục chạy trên môi trường giả lập/double với chi phí 0 USD.

---

## 8. Kết Luận & Đề Xuất Bàn Giao

1. **Phạm vi hoàn thành:**
   - Hoàn thành đầy đủ 7 ca kiểm chứng tích hợp giao diện và bất biến nghiệp vụ F01 trên Mobile Trainer (TR-00 đến TR-06) với Spring Boot và PostgreSQL thật.
   - Thu thập đầy đủ 7 ảnh artifacts hình ảnh tại `docs/08-ai/artifacts/f01-trainer-live-validation/`.
   - Bổ sung 2 regression tests tự động cho điều hướng Trainer/Student F01 ([`aiKnowledgeNavigation.test.tsx`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/__tests__/aiKnowledgeNavigation.test.tsx)), toàn bộ 31 test F01 trên Mobile pass 100%.
   - Bảo toàn toàn bộ quy tắc bảo mật: không lộ secret/token trong artifacts ảnh, không có thay đổi ngoài ý muốn trong lockfile.
2. **Phạm vi bảo lưu:**
   - Trainer F01 với OpenAI thật giữ trạng thái **NOT RUN**.
   - Báo cáo này không tuyên bố nghiệm thu toàn bộ F01 hay Milestone P2-M2, mà chỉ xác nhận hoàn thành phần việc kiểm chứng tích hợp Mobile Trainer trong phạm vi các ca kiểm thử đã thực hiện.
3. **Trạng thái Git:**
   - Nhánh `test/p2-m2-trainer-f01-validation-20261027` giữ nguyên trạng thái working tree sạch sẽ (chỉ gồm test mới, 7 ảnh artifacts và báo cáo Markdown).
