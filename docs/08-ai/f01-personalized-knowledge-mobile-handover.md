# Hướng dẫn Bàn giao Tích hợp Mobile: F01 Kiến thức Thể hình Cá nhân hóa

> **Tài liệu:** Hướng dẫn tích hợp Mobile cho F01 (Personalized Fitness Knowledge Inquiry)\
> **Mốc phát hành:** Phase 2, Milestone 4 (P2-M4)\
> **Loại kết quả:** `INFORMATION` (Chỉ xem / Giải thích; tuyệt đối không có thao tác `Apply` hay làm thay đổi Goal/Plan/Workout/Nutrition)\
> **Ngày cập nhật:** 18/11/2026\
> **Trạng thái:** Triển khai Backend/AI Service hoàn tất, đã kiểm chứng qua contract tests và integration tests.

---

## 1. Phân định Phạm vi: F01 vs F05

Để tránh nhầm lẫn trong quá trình phát triển ứng dụng di động (React Native / Expo), nhóm Mobile cần lưu ý phân định rạch ròi:

| Tiêu chí | F01 (Tính năng này) | F05 (Buổi tập bị lỡ & Đổi lịch) |
| :--- | :--- | :--- |
| **Bản chất nghiệp vụ** | Hỏi đáp, giải thích nguyên tắc khoa học & kỹ thuật vận động có trích dẫn | Bố trí lại lịch tập của Planned Workout sau khi học viên bỏ lỡ buổi tập |
| **Loại kết quả** | `INFORMATION` | `PROPOSAL` $\rightarrow$ `APPLIED` |
| **Thao tác người dùng** | Chỉ đọc, hiển thị câu trả lời và nguồn trích dẫn | `Accept` / `Reject` đề xuất; yêu cầu xác nhận hai phía nếu có lịch hẹn |
| **Tác động dữ liệu** | Không sửa bất kỳ thực thể domain nào (chỉ lưu AI Run phục vụ truy vết/audit) | Cập nhật ngày tập của Planned Workout; chuyển giao buổi tập và cập nhật appointment |
| **Tài liệu tham chiếu** | Tài liệu này (`docs/08-ai/f01-personalized-knowledge-mobile-handover.md`) | `docs/08-ai/f05-rescheduling-pr-description.md` |

---

## 2. Đặc tả API Contract

### Endpoint
- **URL:** `POST /api/v1/ai/knowledge/query`
- **Authentication:** `Bearer <JWT_TOKEN>` (Header: `Authorization: Bearer <TOKEN>`)
- **Content-Type:** `application/json`

### Request Schema (`F01KnowledgeQueryApiRequest`)

| Trường | Kiểu | Bắt buộc | Mặc định | Ý nghĩa & Quy tắc nghiệp vụ |
| :--- | :--- | :---: | :---: | :--- |
| `question` | string | **Có** | - | Câu hỏi của người dùng (từ 5 đến 1000 ký tự, không được chỉ chứa khoảng trắng). |
| `language` | string | Không | `"vi"` | Ngôn ngữ tổng hợp câu trả lời (`"vi"` hoặc `"en"`). |
| `exerciseId` | UUID | Không | `null` | UUID của bài tập trong danh mục nếu hỏi về kỹ thuật bài cụ thể. |
| `correlationId` | UUID | Không | `null` | Mã UUID phía client sinh ra phục vụ distributed tracing (không phải Idempotency Key). |
| `studentId` | UUID | Tùy ngữ cảnh | `null` | UUID của học viên. Xem chi tiết phân quyền bên dưới. |
| `includePersonalContext` | boolean | Không | `false` | Bật/tắt việc tổng hợp bối cảnh cá nhân hóa của học viên vào câu trả lời. |

### Quy tắc Phân quyền (Authority Matrix)
1. **Dành cho Student (`actor.role = 'STUDENT'`)**:
   - Khi hỏi kiến thức chung: Đặt `includePersonalContext: false` (hoặc bỏ qua). Không cần truyền `studentId`.
   - Khi hỏi cá nhân hóa: Đặt `includePersonalContext: true`. Trường `studentId` có thể bỏ trống (server tự gán theo tài khoản đăng nhập) hoặc truyền đúng ID của chính mình.
   - **Chặn truy cập (T03)**: Nếu Student truyền `studentId` của người khác (kể cả khi `includePersonalContext: false`), server trả về **HTTP 403 Forbidden** (`errorCode: "AI_FORBIDDEN"`).
2. **Dành cho Trainer (`actor.role = 'TRAINER'`)**:
   - Khi hỏi kiến thức chung: Đặt `includePersonalContext: false` (hoặc bỏ qua). Không cần truyền `studentId`.
   - Khi hỏi cho học viên: Đặt `includePersonalContext: true` và **bắt buộc** truyền `studentId`.
   - **Xác thực quyền quản lý (T03)**: Server kiểm tra quan hệ huấn luyện trong `fitness.coaching_periods`. Nếu Trainer không được phân công học viên này hoặc kỳ coaching đã kết thúc $\rightarrow$ Trả về **HTTP 403 Forbidden** (`errorCode: "AI_FORBIDDEN"`).
   - Nếu Trainer bật `includePersonalContext: true` nhưng thiếu `studentId` $\rightarrow$ Trả về **HTTP 400 Bad Request** (`errorCode: "VALIDATION_FAILED"`).

---

## 3. Các Ví dụ Minh họa Contract

*(Ghi chú: Toàn bộ UUID dưới đây là UUID v4 hợp lệ theo chuẩn RFC 4122)*

### Ví dụ 1: Student hỏi kiến thức thể hình chung (Không kèm personal context)
**Request:**
```http
POST /api/v1/ai/knowledge/query HTTP/1.1
Host: api.fitnesscoaching.local
Authorization: Bearer <STUDENT_JWT_TOKEN>
Content-Type: application/json

{
  "question": "Lượng protein tối ưu mỗi bữa ăn để tối đa hóa tổng hợp protein cơ bắp (MPS) là bao nhiêu?",
  "language": "vi",
  "correlationId": "8b23c914-72de-4d37-9755-6b47285a11c1",
  "includePersonalContext": false
}
```

**Response (200 OK):**
```json
{
  "runId": "4a18b762-2114-41d3-82ef-a6f95d827bf1",
  "correlationId": "8b23c914-72de-4d37-9755-6b47285a11c1",
  "runStatus": "SUCCEEDED",
  "resultType": "INFORMATION",
  "reasonCode": "NONE",
  "answer": "Thông tin giải thích dựa trên tài liệu ISSN-PROTEIN-EXERCISE-2017-VI-04 (phiên bản 1.0.0): Khuyến nghị lượng leucine tối thiểu từ 2.0 đến 3.0g mỗi bữa ăn, tương đương khoảng 20-40g protein chất lượng cao tùy trọng lượng cơ thể, nhằm tối đa hóa tổng hợp protein cơ bắp (MPS).",
  "citations": [
    {
      "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
      "version": "1.0.0",
      "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
      "locator": {
        "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
        "heading": "Protein, leucine và EAA trong từng lần ăn",
        "pageRange": [1, 3],
        "sectionIndex": 0
      },
      "quoteOrExcerpt": "Khuyến nghị leucine tối thiểu 2.0 - 3.0g mỗi bữa và phân bổ protein tối ưu để tối đa hóa MPS trong tập luyện."
    }
  ],
  "limitations": [
    "Khuyến nghị áp dụng cho người trưởng thành khỏe mạnh tham gia tập luyện thể chất.",
    "Không thay thế tư vấn y khoa hoặc điều trị bệnh lý chuyên sâu."
  ],
  "missingData": [],
  "asOf": "2026-11-18T14:35:45Z"
}
```

---

### Ví dụ 2: Student yêu cầu tư vấn cá nhân hóa (Personal Context)
**Request:**
```http
POST /api/v1/ai/knowledge/query HTTP/1.1
Host: api.fitnesscoaching.local
Authorization: Bearer <STUDENT_JWT_TOKEN>
Content-Type: application/json

{
  "question": "Tôi nên phân bổ thời gian nghỉ giữa các hiệp tập Squat như thế nào để vừa an toàn vừa tăng cơ tốt?",
  "language": "vi",
  "correlationId": "9c34d825-83ef-4e48-a866-7c58396b22d2",
  "includePersonalContext": true
}
```

**Response (200 OK):**
```json
{
  "runId": "5b29c873-3225-42e4-93fa-b70a6e938cf2",
  "correlationId": "9c34d825-83ef-4e48-a866-7c58396b22d2",
  "runStatus": "SUCCEEDED",
  "resultType": "INFORMATION",
  "reasonCode": "NONE",
  "answer": "Dựa trên các nghiên cứu về khoa học vận động và hướng dẫn ISSN/WHO: Đối với các bài tập đa khớp (compound) như Squat, thời gian nghỉ từ 2 đến 3 phút giúp phục hồi hệ thần kinh và tối ưu hóa thể tích tập luyện (volume). [Áp dụng cho học viên: Chế độ Tự tập (SELF_DIRECTED), kinh nghiệm: INTERMEDIATE, mục tiêu: Tăng cơ giảm mỡ (v2)].",
  "citations": [
    {
      "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
      "version": "1.0.0",
      "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
      "locator": {
        "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
        "heading": "Nguyên tắc phục hồi và tổng hợp cơ",
        "pageRange": [2, 4],
        "sectionIndex": 1
      },
      "quoteOrExcerpt": "Thời gian nghỉ đầy đủ giữa các hiệp tập kháng lực nặng giúp duy trì cường độ và sản sinh lực."
    }
  ],
  "limitations": [
    "Khuyến nghị áp dụng cho người tập thể hình phong trào, không áp dụng cho vận động viên cử tạ thi đấu chuyên nghiệp."
  ],
  "missingData": [],
  "asOf": "2026-11-18T14:35:50Z"
}
```

---

### Ví dụ 3: Trainer hỏi bối cảnh học viên được quản lý
**Request:**
```http
POST /api/v1/ai/knowledge/query HTTP/1.1
Host: api.fitnesscoaching.local
Authorization: Bearer <TRAINER_JWT_TOKEN>
Content-Type: application/json

{
  "question": "Học viên này nên tập bài thay thế nào cho Barbell Bench Press nếu phòng tập chỉ có tạ đơn?",
  "language": "vi",
  "studentId": "e3b0c442-98fc-1c14-9afb-4c7b8c9d0e1f",
  "correlationId": "ad45e936-94f0-4f59-b977-8d694a7c33e3",
  "includePersonalContext": true
}
```

**Response (200 OK):**
```json
{
  "runId": "6c3ad984-4336-43f5-a40b-c81b7f049d03",
  "correlationId": "ad45e936-94f0-4f59-b977-8d694a7c33e3",
  "runStatus": "SUCCEEDED",
  "resultType": "INFORMATION",
  "reasonCode": "NONE",
  "answer": "Theo các tài liệu kỹ thuật vận động học: Bài tập Dumbbell Flat Bench Press là biến thể tương đương tốt nhất khi không có đòn tạ (barbell), cho phép biên độ chuyển động tự nhiên hơn cho khớp vai. [Áp dụng cho học viên: Chế độ Có HLV (HUMAN_COACH), kinh nghiệm: BEGINNER, mục tiêu: Tăng cường sức mạnh nền tảng (v1)]. Huấn luyện viên có thể áp dụng bài tập này vào đề xuất giáo án qua chức năng F03.",
  "citations": [
    {
      "documentId": "ISSN-PROTEIN-EXERCISE-2017-VI-04",
      "version": "1.0.0",
      "sourceId": "ISSN-PROTEIN-EXERCISE-2017",
      "locator": {
        "sourcePath": "data/knowledge/curated/issn-protein-2017/04-serving-leucine.vi.md",
        "heading": "Kỹ thuật vận động thay thế",
        "sectionIndex": 0
      },
      "quoteOrExcerpt": "Việc thay đổi dụng cụ tập luyện cần bảo toàn mô thức chuyển động chính."
    }
  ],
  "limitations": [
    "Câu trả lời mang tính chất thông tin tham khảo. Quyết định thay đổi giáo án chính thức thuộc thẩm quyền của Huấn luyện viên thông qua luồng F03."
  ],
  "missingData": [],
  "asOf": "2026-11-18T14:35:55Z"
}
```

---

## 4. Xử lý Trạng thái Khuyết thiếu Dữ liệu (`missingData`)

Theo nguyên tắc bất biến của hệ thống: **"Thiếu dữ liệu là unknown, tuyệt đối không bịa đặt hay giả định là 0"**.

Khi học viên chưa hoàn thành khảo sát kinh nghiệm, hoặc chưa tạo `FitnessGoal` có trạng thái `ACTIVE`:
- Response vẫn trả về **HTTP 200 OK** với `resultType: "INFORMATION"`.
- Mảng `missingData` sẽ chứa thông báo chi tiết:
  ```json
  {
    "missingData": [
      "Học viên chưa thiết lập Fitness Goal đang hoạt động."
    ]
  }
  ```
- **Khuyến nghị UI Mobile**: Khi mảng `missingData` có phần tử, UI nên hiển thị một banner/card nhỏ:
  > *"Thông tin phản hồi hiện chỉ áp dụng mức chung do bạn chưa thiết lập Mục tiêu tập luyện. [Cập nhật Mục tiêu ngay]"*

---

## 5. Xử lý Lỗi & Bảng Ánh xạ Mã lỗi (Error Mapping)

| HTTP Status | Error Code (`errorCode`) | Tình huống phát sinh | Hành động khuyến nghị trên Mobile |
| :---: | :--- | :--- | :--- |
| `400` | `VALIDATION_FAILED` | Câu hỏi quá ngắn (<5 ký tự), câu hỏi rỗng, hoặc Trainer gửi `includePersonalContext: true` nhưng thiếu `studentId`. | Hiển thị thông báo form validation để người dùng sửa lại câu hỏi hoặc chọn học viên. |
| `401` | `UNAUTHORIZED` | JWT Token hết hạn hoặc không hợp lệ. | Điều hướng người dùng về màn hình Đăng nhập hoặc refresh token. |
| `403` | `AI_FORBIDDEN` | - Student truyền `studentId` của học viên khác.<br>- Trainer truy vấn học viên không được phân công.<br>- Quyền huấn luyện bị thu hồi trước hoặc trong khi xử lý. | Hiển thị thông báo: *"Bạn không có quyền truy cập dữ liệu cá nhân của học viên này."* |
| `502` | `BAD_GATEWAY` | AI service trả về lỗi mô hình (`MODEL_ERROR`) hoặc câu trả lời vi phạm kiểm tra an toàn/trích dẫn (`VALIDATION_FAILED`). | Hiển thị: *"Hệ thống AI đang bảo trì hoặc gặp sự cố phản hồi. Vui lòng thử lại sau ít phút."* |
| `503` | `SERVICE_UNAVAILABLE` | Tính năng F01 bị Admin tạm tắt bằng Feature Flag (`AiFeatureDisabledException`). | Hiển thị: *"Tính năng Trợ lý Kiến thức Thể hình tạm thời gián đoạn theo lịch vận hành hệ thống."* |
| `504` | `GATEWAY_TIMEOUT` | Thời gian gọi mô hình AI vượt quá deadline cho phép (quá 25 giây). | Hiển thị: *"Yêu cầu phản hồi quá thời gian chờ. Vui lòng thử lại câu hỏi ngắn gọn hơn."* |

---

## 6. Bảo vệ An toàn khi Thu hồi Quyền (T04 Guard)

1. **Thu hồi quyền giữa chừng (In-flight Revocation)**: Nếu Trainer bị thu hồi quan hệ huấn luyện trong lúc AI Service đang sinh câu trả lời, server sẽ chặn hoàn toàn việc gửi kết quả về client và trả về **HTTP 403 `AI_FORBIDDEN`**.
2. **Đọc lại lịch sử (Readback Protection)**: Khi gọi `GET /api/v1/ai/runs/{runId}` để xem lại câu hỏi trong quá khứ, nếu là Trainer đọc câu hỏi có chứa bối cảnh học viên, server tái kiểm tra tính hợp lệ của quan hệ huấn luyện tại thời điểm hiện tại. Nếu quan hệ đã kết thúc $\rightarrow$ Trả về **HTTP 403 `AI_FORBIDDEN`**.
