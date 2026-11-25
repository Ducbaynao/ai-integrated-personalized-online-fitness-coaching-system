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

---

## 7. Bàn giao Triển khai Mobile F01 (Personalized Knowledge Inquiry)

> **Thực hiện bởi:** DEV-A\
> **Branch:** `feat/mobile-f01-personalized-knowledge`\
> **Base Commit:** `f4f92d1` (Merge pull request #168 from Ducbaynao/feat/mobile-f05-rescheduling)\
> **Ngày hoàn thành:** 24/11/2026

### 7.1. Các thành phần đã triển khai

1. **Types & Data Contracts (`apps/mobile/src/types/aiKnowledge.ts`)**:
   - Mở rộng `F01KnowledgeQueryRequest` với `studentId?: string` và `includePersonalContext?: boolean` theo đúng đặc tả OpenAPI / AI schema.
   - Bổ sung interface `AiRunResponse` phục vụ readback qua `GET /api/v1/ai/runs/{runId}`.

2. **API Client & Error Mapping (`apps/mobile/src/services/aiKnowledgeApi.ts`)**:
   - Bổ sung `queryKnowledge` truyền tải đầy đủ `studentId`, `includePersonalContext`, và hỗ trợ `AbortSignal` hủy request client-side.
   - Thêm `getRun(runId, signal)` để kiểm tra và đọc lại lịch sử run với T04 Readback Guard.
   - Chuẩn hóa ánh xạ mã lỗi theo Bảng Mục 5:
     - `400` / `VALIDATION_FAILED`: Lỗi form validation / thiếu học viên trong truy vấn cá nhân của HLV.
     - `401`: Phiên đăng nhập hết hạn.
     - `403` / `AI_FORBIDDEN`: *"Bạn không có quyền truy cập dữ liệu cá nhân của học viên này."* hoặc thông báo cụ thể từ backend.
     - `502`: *"Hệ thống AI đang bảo trì hoặc gặp sự cố phản hồi. Vui lòng thử lại sau ít phút."*
     - `503`: *"Tính năng Trợ lý Kiến thức Thể hình tạm thời gián đoạn theo lịch vận hành hệ thống."*
     - `504` / `TIMEOUT`: *"Yêu cầu tra cứu quá thời gian chờ. Vui lòng thử lại sau."*

3. **Giao diện Người dùng (`apps/mobile/src/features/ai-knowledge/AiKnowledgeScreen.tsx`)**:
   - **Chế độ Student**: Chọn giữa "Kiến thức chung" (`includePersonalContext: false`) và "Cá nhân hóa (Hồ sơ của tôi)" (`includePersonalContext: true`). ID học viên luôn khóa cứng theo `user.id` của người dùng đăng nhập; không cho phép truy vấn ID học viên khác.
   - **Chế độ Trainer**: Chọn giữa "Kiến thức chung" và "Ngữ cảnh học viên".
     - Khi chọn ngữ cảnh học viên, Trainer chỉ được chọn học viên thuộc danh sách quan hệ huấn luyện đang hoạt động (`coachingApi.getRelationships`).
     - Hỗ trợ **phân trang danh sách học viên**: Nút "Tải thêm học viên" (`ai-knowledge-load-more-students-button`) khi danh sách học viên vượt quá kích thước 1 trang (20 mục).
     - Hỗ trợ **làm mới danh sách học viên**: Nút "Làm mới" (`ai-knowledge-refresh-students-button`) để tải lại danh sách học viên active từ backend.
     - Xử lý **mất quyền hoặc quan hệ kết thúc**: Khi gặp lỗi 403 `AI_FORBIDDEN`, hiển thị nút "Đồng bộ lại học viên" (`ai-knowledge-error-refresh-students-button`) trên card báo lỗi để Trainer cập nhật lại danh sách active relationships.
     - Nếu Trainer mở từ chi tiết quan hệ huấn luyện (`studentId` qua prop), học viên được khóa cố định theo ngữ cảnh đó.
   - **Chống Stale Response & Quản lý Race Condition**: Khi đổi ngữ cảnh hoặc đổi học viên, request in-flight tự động bị hủy (`AbortController.abort()`), `activeRequestIdRef` tăng, toàn bộ kết quả và lỗi cũ bị xóa ngay lập tức.
   - **Hiển thị đầy đủ kết quả**:
     - Câu trả lời (`result.answer`).
     - Trích dẫn khoa học (`result.citations`) gồm documentId, version, sourceId, heading, pageRange, quoteOrExcerpt.
     - Phạm vi áp dụng và giới hạn (`result.limitations`).
     - Cảnh báo dữ liệu khuyết thiếu (`result.missingData`): Hiển thị banner cảnh báo thông tin khuyết thiếu theo nguyên tắc bất biến (dữ liệu thiếu là unknown, không giả định là 0).
     - Thời điểm dữ liệu (`result.asOf`).
   - **Kiểm chứng Readback / History**: Hỗ trợ `initialRunId` qua prop / search params; gọi `getRun` kiểm tra quyền hiện tại. Nếu bị thu hồi quyền, hiển thị ngay thông báo lỗi 403 `AI_FORBIDDEN`.
   - **Bảo toàn Invariant INFORMATION**: Toàn bộ kết quả là chỉ xem (read-only); tuyệt đối không có nút `Accept`, `Apply` hay làm biến đổi dữ liệu domain (Goal, Plan, Workout, Nutrition).

4. **Điều hướng & Tuyến đường**:
   - `apps/mobile/src/app/(app)/ai-knowledge/index.tsx`: Đọc search params `studentId`, `relationshipId`, `runId` và truyền vào `AiKnowledgeScreen`.
   - `apps/mobile/src/app/(app)/coaching/[relationshipId]/ai-knowledge.tsx`: Route mới cho phép Trainer mở trực tiếp trợ lý kiến thức cá nhân hóa theo học viên từ màn hình quan hệ huấn luyện.
   - `apps/mobile/src/app/(app)/_layout.tsx`: Đăng ký route `coaching/[relationshipId]/ai-knowledge`.
   - `apps/mobile/src/features/coaching/RelationshipDetailScreen.tsx`: Bổ sung nút "Tra cứu kiến thức cá nhân hóa (AI)" cho HLV trong các mối quan hệ ACTIVE.

### 7.2. Kết quả kiểm thử & Bằng chứng thực thi

#### A. Backend Integration Tests (Spring Boot + PostgreSQL/pgvector + FastAPI)

| Bộ kiểm thử | Số lượng test | Kết quả | Ghi chú & Phạm vi |
| :--- | :---: | :---: | :--- |
| `AiKnowledgeQueryHttpEndToEndIntegrationTest` | 8 | **PASS** | Spring Boot MockMvc + WireMock + Testcontainers PostgreSQL/pgvector. Kiểm chứng đầy đủ các mã lỗi (400, 401, 403, 502, 503, 504), quyền Student/Trainer và kill-switch. |
| `AiKnowledgeQueryRealFastApiIntegrationTest` | 5 | **PASS** | Tích hợp quy trình FastAPI thực tế (`services/ai-service`) kết nối Spring Boot client và Testcontainers pgvector. |
| `AiRunF01PersistenceIntegrationTest` | 9 | **PASS** | Lưu vết thực thể `AiRun`, context snapshot, input references, kiểm chứng Trainer readback run cá nhân hóa của học viên với row-locking PostgreSQL `SELECT ... FOR SHARE`, và từ chối 403 khi quan hệ coaching bị chấm dứt. |

*Lỗi tích hợp Backend đã sửa tối thiểu:*
- **Defect:** `AiRunF01Service.getF01Run` trước đây được gắn `@Transactional(readOnly = true)`. Khi Trainer gọi readback một run có chứa bối cảnh học viên, T04 revalidation gọi qua `CoachingAuthority` thực hiện khóa dòng chia sẻ `SELECT user_id FROM fitness.student_profiles WHERE user_id = ? FOR SHARE`. Trên PostgreSQL, khóa dòng trong transaction chỉ đọc bị từ chối với lỗi `PSQLException: ERROR: cannot execute SELECT FOR SHARE in a read-only transaction (SQLState 25006)`.
- **Khắc phục:** Đổi sang `@Transactional` (read-write) theo đúng pattern đã được ghi nhận trong `docs/07-development/phase-1-person-a-gap-checklist.md` (FINDING-A07-05) và `docs/08-ai/f04-progress-readiness-report.md`.

#### B. Mobile Automated Tests & Static Quality

| Bộ kiểm thử | Số lượng test | Kết quả | Ghi chú |
| :--- | :---: | :---: | :--- |
| `aiKnowledgeApi.test.ts` | 25 | **PASS** | Kiểm chứng input validation, semantics `studentId`/`includePersonalContext`, timeout auto-abort, external signal, error code mappings. |
| `AiKnowledgeScreen.test.tsx` | 20 | **PASS** | Student/Trainer modes, chips selector, pagination & refresh học viên, anti-stale cancellation, readback 403 guard, citations/limitations/asOf rendering, invariant chỉ đọc. |
| `aiKnowledgeNavigation.test.tsx` | 4 | **PASS** | Điều hướng từ route `/ai-knowledge`, route `/coaching/[relationshipId]/ai-knowledge`, nút F01 trên `RelationshipDetailScreen`. |
| `coachingScreens.test.tsx` | 10 | **PASS** | Kiểm tra hồi quy màn hình quan hệ huấn luyện. |
| `tsc --noEmit` (Typecheck) | - | **PASS** | 0 lỗi TypeScript trên toàn bộ ứng dụng mobile. |
| `expo lint` (ESLint) | - | **PASS** | 0 lỗi, 0 cảnh báo. |
| `npm run validate:contracts` | - | **PASS** | OpenAPI lint (Redocly) & 48 contract fixtures (Ajv 2020) đều hợp lệ. |
| `npm run test:contracts` | 21 | **PASS** | 21 unit tests ràng buộc JSON Schema & OpenAPI. |
| `npm run validate:knowledge` | - | **PASS** | Toàn bộ staging và derivatives của kho tri thức hợp lệ. |

#### C. Mobile UI Interactive Runtime Verification (Expo Web → Backend thật)

Thực hiện kiểm chứng tương tác trực tiếp qua trình duyệt Google Chrome tự động hóa bằng Playwright ([verify_f01_mobile_ui_interactive.py](../../tools/verify_f01_mobile_ui_interactive.py)) kết nối Expo Web (`http://localhost:8081`), Spring Boot dev server (`http://localhost:8080/api/v1`), và PostgreSQL 18 (`digital-fitness-postgres:5433`).

- **File bằng chứng máy đọc (JSON):** [f01_mobile_ui_runtime_evidence_20261124.json](artifacts/f01-runtime-evidence/f01_mobile_ui_runtime_evidence_20261124.json)
- **Thư mục ảnh chụp màn hình:** [screenshots/](artifacts/f01-runtime-evidence/screenshots/)

| STT | Kịch bản kiểm chứng | Diễn giải & Kết quả quan sát | Evidence | Trạng thái |
| :---: | :--- | :--- | :--- | :---: |
| 1 | **Hỏi kiến thức chung (Student)** | Đăng nhập `smoke.student@example.com`, mở `/ai-knowledge`, chọn câu hỏi gợi ý WHO, gửi truy vấn. Nhận câu trả lời (281 ký tự), trích dẫn tài liệu WHO-PA-SB-2020-VI-01 v1.1.0, nhãn `asOf`. Kiểm tra toàn trang không có nút Apply/Accept. | [01_student_general_knowledge.png](artifacts/f01-runtime-evidence/screenshots/01_student_general_knowledge.png) | **PASS** |
| 2 | **Hỏi cá nhân hóa (Student)** | Chuyển sang tab "Cá nhân hóa (Hồ sơ của tôi)". Hiển thị ghi chú bối cảnh cá nhân, không hiển thị picker học viên khác. Gửi câu hỏi về Squat và thời gian nghỉ giữa hiệp. Nhận câu trả lời cá nhân hóa. Không có nút Apply/Accept. | [02_student_personalized_knowledge.png](artifacts/f01-runtime-evidence/screenshots/02_student_personalized_knowledge.png) | **PASS** |
| 3 | **Trainer chọn học viên & Hỏi bối cảnh** | Đăng nhập `smoke.trainer@example.com`, mở `/ai-knowledge`, chọn tab "Ngữ cảnh học viên". Danh sách chips học viên hiển thị. Bấm nút "Làm mới", chọn học viên `5c6f49c6-...`, gửi câu hỏi biến thể Bench Press. Nhận câu trả lời và trích dẫn. | [03_trainer_personalized_student.png](artifacts/f01-runtime-evidence/screenshots/03_trainer_personalized_student.png) | **PASS** |
| 4 | **Trainer mở F01 từ Relationship Detail** | Điều hướng vào `/coaching/95501599-414d-4d0d-b663-296978c402a5`. Nút "Tra cứu kiến thức cá nhân hóa (AI)" hiển thị. Bấm nút, chuyển vào route chuyên biệt, học viên bị khóa cố định theo badge quan hệ (`ai-knowledge-locked-student-badge`). | [04_relationship_detail_f01_button.png](artifacts/f01-runtime-evidence/screenshots/04_relationship_detail_f01_button.png)<br>[05_trainer_locked_student_from_relationship.png](artifacts/f01-runtime-evidence/screenshots/05_trainer_locked_student_from_relationship.png) | **PASS** |
| 5 | **Chống phản hồi cũ (Anti-stale)** | Đang gửi câu hỏi dài (in-flight), lập tức click chuyển tab "Kiến thức chung". Request in-flight bị hủy (`AbortController.abort()`), toàn bộ kết quả cũ bị xóa, không bị render đè kết quả cũ lên ngữ cảnh mới. | [06_anti_stale_cancellation.png](artifacts/f01-runtime-evidence/screenshots/06_anti_stale_cancellation.png) | **PASS** |
| 6 | **Readback runId (Quyền sở hữu run & Từ chối khi không sở hữu)** | - Mở `/ai-knowledge?runId={trainer_run_id}`: Hiển thị Card "Lịch sử tra cứu" thành công.<br>- Mở `/ai-knowledge?runId={student_run_id}`: Bị Backend từ chối 403 `AI_FORBIDDEN` do Trainer không sở hữu run của Student (kiểm tra quyền sở hữu `run.requestedBy == actorId`). Hiển thị Error Card với thông điệp từ chối truy cập.<br>*Lưu ý coverage:* Tình huống Trainer bị thu hồi quan hệ coaching (revocation of coaching relationship) sau khi đã tạo run cá nhân hóa cho học viên được kiểm chứng tự động tại tầng Backend (`AiRunF01PersistenceIntegrationTest` test #9); chưa có runtime UI evidence riêng trên Expo Web cho tình huống can thiệp DB kết thúc quan hệ ngay trong lúc app đang chạy. | [07_readback_authorized_success.png](artifacts/f01-runtime-evidence/screenshots/07_readback_authorized_success.png)<br>[08_readback_unauthorized_forbidden.png](artifacts/f01-runtime-evidence/screenshots/08_readback_unauthorized_forbidden.png) | **PASS (Quyền sở hữu run)** |
| 7 | **Validation câu hỏi ngắn (<5 ký tự)** | Nhập câu hỏi 3 ký tự ("abc"). Nút "Gửi câu hỏi" bị vô hiệu hóa (`disabled = true`) ngay tại client, ngăn chặn gửi payload không hợp lệ lên backend. | [09_client_validation_min_length.png](artifacts/f01-runtime-evidence/screenshots/09_client_validation_min_length.png) | **PASS** |

---

### 7.3. Phân định Trạng thái Bàn giao & Các Gate Còn Mở (Delivery Status & Open Gates)

1. **Đã triển khai (Implemented):**
   - Hoàn tất toàn bộ mã nguồn Mobile F01 cá nhân hóa, API client, types, error mappings, navigation, UI screen và bản sửa backend transaction read-write tại working tree của branch `feat/mobile-f01-personalized-knowledge` (hiện chứa các thay đổi chưa commit).
2. **Đã kiểm thử tự động (Auto-tested):**
   - 59/59 mobile unit tests, 77 backend integration tests (`AiRunF01*Test`, `AiKnowledge*Test`), 21 contract tests, OpenAPI validation, typecheck và lint đạt 100% PASS.
3. **Đã kiểm chứng runtime (Runtime-verified):**
   - Đã kiểm chứng Backend HTTP integration (WireMock stub, Testcontainers pgvector, FastAPI real process) và Mobile UI runtime tương tác (Expo Web → Backend Spring Boot dev server → PostgreSQL 18, AI provider simulated).
   - Đã kiểm chứng runtime 7 kịch bản tương tác; ghi nhận rõ tình huống thu hồi quan hệ coaching sau khi tạo run được kiểm thử ở tầng Backend integration, chưa có runtime UI evidence độc lập trên Expo Web.
4. **Nghiệm thu chính thức (Chưa tuyên bố nghiệm thu - Còn mở các gate sau):**
   - **Gate Đánh giá Chuyên môn & Live LLM (A-49 / P2-M4):** Đánh giá chất lượng câu trả lời với mô hình thương mại thật (OpenAI / Gemini API) thông qua bộ testbed `npm run eval:f01` và thẩm định chuyên môn độc lập của huấn luyện viên / chuyên gia y khoa.
   - **Gate Native QA:** Bằng chứng UI ở trên được thực thi trên môi trường Expo Web. Cần thực hiện kiểm thử trên thiết bị vật lý / simulator Android & iOS native; Expo Web không được dùng làm bằng chứng thay thế cho native QA.
   - **Gate Sign-off:** Cần reviewer và project owner phê duyệt PR chính thức.
