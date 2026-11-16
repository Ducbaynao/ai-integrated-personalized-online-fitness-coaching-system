# F03 Draft Editing, Revalidation, Revision Diff & Draft Sharing — Backend Handover (Milestone P2-M4)

> **Mã tính năng:** F03 — AI Workout Adjustment Proposal (Chỉnh sửa bản thảo đề xuất, revalidation, structured diff và chia sẻ nháp Trainer–Student)  
> **Milestone phụ trách:** P2-M4 — Mở Rộng Hỗ Trợ Tập Luyện  
> **Repository:** `D:\Projects\ai-integrated-personalized-online-fitness-coaching-system`  
> **Branch thực hiện:** `feat/p2-m4-f03-draft-edit-share`  
> **Baseline commit xác minh:** `6716f563db3e33ae126c926d62880eba209eba47` (origin/main)  
> **Ngày lập hồ sơ:** 2026-11-15 (UTC+7)  
> **Tài liệu căn cứ:**  
> - [`phase-2-release-scope.md`](../00-project-overview/phase-2-release-scope.md) (D01–D07, F03 scope)  
> - [`phase-2-feature-data-authority-matrix.md`](phase-2-feature-data-authority-matrix.md) (Mục 1.6, 1.7, 1.8, 4)  
> - [`f03-contract-and-authority-design.md`](f03-contract-and-authority-design.md)  
> - [`f03-backend-acceptance-report-20261101.md`](f03-backend-acceptance-report-20261101.md)  
> - [`p2-m4-f03-closure-checklist.md`](p2-m4-f03-closure-checklist.md)  

---

## 1. Tổng Quan Triển Khai Backend & Domain

Trong phiên triển khai DEV-A này, backend đã hoàn thiện trọn vẹn phần năng lực kỹ thuật và nghiệp vụ theo phê duyệt Phase 2 cho F03:
1. **Chỉnh sửa đề xuất PENDING còn hiệu lực:**
   - Hỗ trợ cả 2 endpoint: `PUT /ai/workout-adjustments/{recommendationId}/draft` và `PATCH /ai/workout-adjustments/{recommendationId}`.
   - Kiểm tra `expectedRevision` chống ghi đè mất mát (lost update) khi chỉnh sửa đồng thời.
   - Tái thẩm định toàn diện (`F03RuntimeOutputValidator.validate`) trên cấu trúc typed changes (`EXERCISE_SUBSTITUTION`, `LOAD_ADJUSTMENT`, `VOLUME_ADJUSTMENT`), bài tập active trong catalog, an toàn mức tạ/khối lượng và đơn vị đo lường.
   - Giữ nguyên `original_payload` ban đầu do AI sinh ra, lưu `source_snapshot`, tăng `revision` ($V \to V+1$), đánh dấu `is_customized = true`.
   - Tính toán và trả về Structured Diff (`F03DraftDiff`) gồm `summary`, `changeType`, danh sách chi tiết thay đổi theo từng trường (`fieldDiffs`: `field`, `before`, `after`, `delta`).
   - Ghi nhận bắt buộc sự kiện kiểm toán `AI_RECOMMENDATION_DRAFT_UPDATED`.

2. **Chia sẻ bản thảo có chủ đích (Trainer $\to$ Student) trong `HUMAN_COACH`:**
   - Mặc định bản nháp đề xuất của Trainer là **riêng tư** (`shared_with_student = false`).
   - Endpoint: `POST /ai/workout-adjustments/{recommendationId}/share`.
   - Khi Trainer chủ động gọi chia sẻ, snapshot tại revision hiện thời được cố định vào `shared_revision` và `shared_payload`.
   - Học viên (`Student`) khi gọi `GET /ai/workout-adjustments/{recommendationId}` chỉ đọc được bản nháp snapshot đã chia sẻ này (`shared_payload` và `shared_revision`). Nếu chưa được chia sẻ, hệ thống từ chối truy cập bằng mã HTTP 403 `AI_RUN_ACCESS_DENIED`.
   - **Bảo toàn quyền hạn:** Việc chia sẻ nháp **không cấp quyền quyết định giáo án** cho học viên trong chế độ `HUMAN_COACH`. Nút Accept/Reject vẫn bị cấm đối với học viên.
   - **Cô lập sửa đổi tiếp theo:** Nếu Trainer tiếp tục chỉnh sửa ($V \to V+1$), revision mới chỉ hiển thị cho Trainer; Student tiếp tục thấy snapshot đã chia sẻ cho đến khi Trainer chủ động gọi share lại.
   - Ghi nhận sự kiện kiểm toán `AI_RECOMMENDATION_SHARED`.

3. **Bảo vệ cạnh tranh & Idempotent Replay với Fingerprint SHA-256:**
   - Áp dụng khóa bi quan dòng (`findByIdForUpdate`) trên PostgreSQL cho cả luồng `updateDraft`, `shareDraft`, `rejectAdjustment` và `acceptAdjustment`.
   - Chặn xung đột `edit/edit`, `edit/accept`, `edit/reject`, `edit/share` bằng kiểm tra `expectedRevision` và locking.
   - **Chuẩn hóa Canonicalization**: Thuật toán đệ quy sắp xếp thứ tự các key trong `JsonNode` theo bảng chữ cái trước khi chuyển thành chuỗi và hash SHA-256. Đảm bảo hai chuỗi JSON có nội dung tương đương nhưng khác thứ tự key (ví dụ: `{"b":2,"a":1}` và `{"a":1,"b":2}`) cho ra cùng một fingerprint tất định.
   - Hỗ trợ cả legacy receipts (không có trường `payloadFingerprint`) và receipts mới (đối chiếu fingerprint chặt chẽ).
   - Tái gửi cùng `commandKey` với fingerprint khớp: Trả lại kết quả đã áp dụng (`replayed = true`), không thực thi lại domain mutation.
   - Tái gửi cùng `commandKey` nhưng sai lệch payload/fingerprint/targetVersion: Trả ngay HTTP 409 `AI_RUN_LIFECYCLE_CONFLICT`. Không dùng fallback ngẫu nhiên.

4. **Cô lập triệt để Shared Snapshot View & Source Verification khi Share:**
   - Khi chia sẻ, hệ thống bắt buộc kiểm tra các điều kiện nguồn (Target Scope, Occurrence Lifecycle `SCHEDULED`, Occurrence Version hoặc Active Plan Aggregate Version). Mọi lỗi nguồn đều chặn chia sẻ và tuyệt đối không ghi nhận sharing snapshot hay audit log thành công.
   - `withSharedView()` loại bỏ 100% fallback sang nội dung live draft đang sửa dở (`title`, `explanation`, `updatedAt`, `lastModifiedBy`).
   - Phân định rõ trường bắt buộc (`sharedRevision >= 1`, `sharedPayload`, `sharedAt`, `sharedBy`) và trường nullable (`sharedTitle`, `sharedExplanation`). Giá trị null hợp lệ giữ nguyên ý nghĩa null tại thời điểm share. Nếu dữ liệu snapshot thiếu hoặc hỏng, trả HTTP 409 `AI_RUN_LIFECYCLE_CONFLICT`.

5. **Bảo toàn tính nguyên tử Accept-and-Apply, Actual Workout Invariant & Đơn Vị Tải (Authoritative Load Units & Code–ID Consistency):**
   - Toàn bộ giao dịch áp dụng điều chỉnh buổi tập (`adjustOccurrence`) hoặc phiên bản giáo án (`publishVersion`), cập nhật trạng thái recommendation, ghi nhận receipt và audit log nằm trọn vẹn trong một Spring `@Transactional`.
   - Bất kỳ lỗi nào (kể cả lỗi audit cuối cùng `AI_RECOMMENDATION_APPLIED`) đều kích hoạt rollback 100% trên PostgreSQL.
   - Tuyệt đối không can thiệp buổi tập có trạng thái khác `SCHEDULED` (`COMPLETED`, `IN_PROGRESS`, `ATTEMPTED` bị chặn với HTTP 409).
   - **Xác thực Đơn Vị Tải từ Nguồn Có Thẩm Quyền (`MeasurementUnitReferenceQuery`):**
     * Backend xác minh đơn vị đo lường trực tiếp qua `MeasurementUnitReferenceQuery` (tuân thủ nghiêm ngặt module boundaries). Bắt buộc chiều đo là `MASS`.
     * Đối chiếu mã (`loadUnit`) và ID (`loadUnitId`). Cặp code–ID mâu thuẫn (như mã `KG` với ID của `LB`, hoặc mã `LB` với ID của `KG`) bị từ chối fail-closed ngay tại validator, tuyệt đối không ghi bản ghi mutation hay receipt nào xuống cơ sở dữ liệu và trạng thái recommendation giữ nguyên `PENDING`.
     * Hỗ trợ đầy đủ các ID đơn vị động từ cơ sở dữ liệu (`fitness.measurement_units`), không hardcode `KG=1, LBS=2`.
     * Chuẩn hóa alias `LBS` sang `LB` trước khi kiểm tra.
     * Mức tạ ban đầu thiếu (`before.targetLoad == null` hoặc snapshot `beforeTargetLoad == null`) mang ý nghĩa `unknown` hoặc bài tự trọng (bodyweight), **tuyệt đối không tự suy diễn thành 0**. Hệ thống từ chối tính toán `INCREASE`/`DECREASE` từ baseline chưa xác định; mức tạ bằng 0 tường minh (thanh đòn rỗng) được chấp nhận.
     * Tính toán `direction` và `deltaLoad` sau khi quy đổi `before` và `after` về cùng đơn vị đo lường. Xử lý chính xác hiện tượng đảo chiều vật lý do chuyển đổi đơn vị.
     * Phía Mobile (`AiWorkoutAdjustmentModal.tsx`): tự động resolve ID khớp với đơn vị đang chọn từ `unitReferences`, `prescriptions`, proposal changes và `measurementApi.getMetricDefinitions()`. Khi người dùng đổi đơn vị, lập tức hủy bỏ ID cũ; nếu không resolve được ID hợp lệ, chặn lưu và hiển thị lỗi trường `adjustment-edit-load-unit-error`.
     * Khi Accept, `loadUnitId` và `targetLoad` được lưu trữ chính xác vào các cột `typed_load_unit_id` và `typed_target_load` trong `workout_session_adjustments` và kiểm chứng đọc lại (domain readback) qua `WorkoutExecutionSnapshotPersistencePort.adjustments(occurrenceId)`.

---

## 2. Chi Tiết API Contracts Cho Mobile Client

### 2.1. Cập nhật bản thảo (Edit Draft)
- **Phương thức:** `PUT /ai/workout-adjustments/{recommendationId}/draft` hoặc `PATCH /ai/workout-adjustments/{recommendationId}`
- **Quyền gọi (Authority):**
  - Chế độ `HUMAN_COACH`: Chỉ Trainer phụ trách có quan hệ coaching hợp lệ.
  - Chế độ `SELF_DIRECTED`: Chỉ chính Student sở hữu kế hoạch.
- **Request Headers:**
  - `Authorization: Bearer <token>`
  - `Content-Type: application/json`
- **Request Body (`UpdateAdjustmentDraftApiRequest`):**
```json
{
  "expectedRevision": 1,
  "title": "Thay bài Squat bằng Dumbbell Lunges và giảm tạ",
  "explanation": "Học viên phản hồi đau khớp gối nhẹ, chuyển sang Lunges để giảm áp lực cột sống.",
  "structuredPayload": {
    "targetRef": {
      "scope": "ONE_MATERIALIZED_OCCURRENCE",
      "plannedWorkoutId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "plannedSessionExerciseId": "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    },
    "changes": [
      {
        "changeType": "EXERCISE_SUBSTITUTION",
        "before": {
          "exerciseVariationId": "22222222-2222-4222-8222-222222222222",
          "exerciseName": "Barbell Back Squat",
          "movementPattern": "SQUAT",
          "equipmentType": "BARBELL"
        },
        "after": {
          "replacementVariationId": "33333333-3333-4333-8333-333333333333",
          "replacementExerciseName": "Dumbbell Walking Lunge",
          "movementPattern": "LUNGE",
          "equipmentType": "DUMBBELL",
          "substitutionCategory": "EQUIPMENT_UNAVAILABLE"
        }
      }
    ],
    "rationale": {
      "primaryReason": "Giảm áp lực cột sống và khớp gối",
      "safetyFlags": []
    }
  }
}
```
- **Response (`UpdateAdjustmentDraftApiResponse` - HTTP 200 OK):**
```json
{
  "recommendationId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "revision": 2,
  "isCustomized": true,
  "status": "PENDING",
  "title": "Thay bài Squat bằng Dumbbell Lunges và giảm tạ",
  "explanation": "Học viên phản hồi đau khớp gối nhẹ, chuyển sang Lunges để giảm áp lực cột sống.",
  "updatedAt": "2026-11-15T13:40:00Z",
  "structuredPayload": { ... },
  "originalPayload": { ... },
  "diff": {
    "hasChanges": true,
    "changeType": "EXERCISE_SUBSTITUTION",
    "summary": "Updated 1 change items",
    "fieldDiffs": [
      {
        "field": "replacementVariationId",
        "before": "44444444-4444-4444-8444-444444444444",
        "after": "33333333-3333-4333-8333-333333333333",
        "delta": null
      }
    ]
  }
}
```

### 2.2. Chia sẻ bản thảo cho Student (Share Draft)
- **Phương thức:** `POST /ai/workout-adjustments/{recommendationId}/share`
- **Quyền gọi (Authority):**
  - Chế độ `HUMAN_COACH`: Chỉ Trainer phụ trách có quan hệ coaching hợp lệ.
  - Chế độ `SELF_DIRECTED`: Bị từ chối với HTTP 400 (Student tự quản lý đề xuất, không có khái niệm chia sẻ cho chính mình).
- **Request Body (`ShareAdjustmentDraftApiRequest`):**
```json
{
  "expectedRevision": 2,
  "shareNote": "Em xem qua đề xuất thay bài này nhé, nếu đồng ý nhắn lại anh."
}
```
- **Response (`ShareAdjustmentDraftApiResponse` - HTTP 200 OK):**
```json
{
  "recommendationId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "sharedWithStudent": true,
  "sharedRevision": 2,
  "sharedAt": "2026-11-15T13:41:00Z",
  "sharedBy": "11111111-1111-4111-8111-111111111111"
}
```

### 2.3. Đọc chi tiết đề xuất (Get Adjustment Detail)
- **Phương thức:** `GET /ai/workout-adjustments/{recommendationId}`
- **Hành vi theo quyền:**
  - Trainer đọc: Trả về trạng thái nháp mới nhất, bao gồm `sharedWithStudent`, `sharedRevision`, `sharedAt`, `sharedBy`.
  - Student đọc khi `sharedWithStudent = true`: Trả về snapshot đóng băng tại `sharedRevision` gồm `sharedTitle`, `sharedExplanation`, `sharedPayload`, `sharedAt`, `sharedBy`. Toàn bộ các chỉnh sửa v3 sau đó của Trainer được cô lập và không bị rò rỉ.
  - Student đọc khi `sharedWithStudent = false`: Trả về HTTP 403 `AI_FORBIDDEN` ("Access denied: Private trainer draft has not been shared with the student.").
  - Nếu `sharedWithStudent = true` nhưng snapshot bị thiếu hoặc hỏng payload JSON: Trả về HTTP 409 `AI_RUN_LIFECYCLE_CONFLICT`, không bao giờ trả draft đang sửa dở.
- **Response Model (`AiRecommendationDetailApiResponse`):**
  * Lưu ý tương thích: DTO cung cấp cả `id` và `recommendationId` (alias), cả `runId` và `aiRunId` (alias) để hỗ trợ đồng thời cả client cũ và mới.
```json
{
  "id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "recommendationId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "runId": "a1b2c3d4-e5f6-4a1b-8c2d-3e4f5a6b7c8d",
  "aiRunId": "a1b2c3d4-e5f6-4a1b-8c2d-3e4f5a6b7c8d",
  "studentId": "22222222-2222-4222-8222-222222222222",
  "coachingPeriodId": "33333333-3333-4333-8333-333333333333",
  "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
  "title": "Thay bài Squat bằng Dumbbell Lunges và giảm tạ",
  "explanation": "...",
  "status": "PENDING",
  "decisionAuthority": "TRAINER",
  "targetEntityType": "WORKOUT_OCCURRENCE",
  "targetEntityId": "44444444-4444-4444-8444-444444444444",
  "decidedBy": null,
  "decidedAt": null,
  "decisionNote": null,
  "expiresAt": "2026-11-16T13:30:00Z",
  "revision": 2,
  "isCustomized": true,
  "lastModifiedBy": "11111111-1111-4111-8111-111111111111",
  "createdAt": "2026-11-15T13:30:00Z",
  "updatedAt": "2026-11-15T13:41:00Z",
  "structuredPayload": { ... },
  "originalPayload": { ... },
  "sharedWithStudent": true,
  "sharedRevision": 2,
  "sharedAt": "2026-11-15T13:41:00Z",
  "sharedBy": "11111111-1111-4111-8111-111111111111"
}
```

### 2.4. Mã lỗi chuẩn hóa (Error Mapping)
Dựa trên `GlobalExceptionHandler` chuẩn của backend:

| HTTP Status | Mã lỗi `errorCode` | Ý nghĩa & Hướng dẫn xử lý trên Mobile Client |
| :---: | :--- | :--- |
| `400 BAD_REQUEST` | `VALIDATION_FAILED` | Cấu trúc payload sửa đổi không hợp lệ, vi phạm JSON schema, targetRef rỗng hoặc sai đơn vị đo lường. |
| `403 FORBIDDEN` | `AI_FORBIDDEN` | Học viên cố gắng chỉnh sửa proposal trong `HUMAN_COACH`, hoặc xem proposal chưa được Trainer chia sẻ, hoặc gọi share trong `SELF_DIRECTED`, hoặc tài khoản/coaching relationship đã bị thu hồi/không hợp lệ. |
| `404 NOT_FOUND` | `AI_RUN_NOT_FOUND` | Không tìm thấy recommendation hoặc run theo ID. |
| `409 CONFLICT` | `AI_RUN_LIFECYCLE_CONFLICT` | Các tình huống xung đột vòng đời:<br>1. Xung đột revision (`expectedRevision` không khớp revision hiện tại).<br>2. Đề xuất đã được Accept hoặc Reject trước đó.<br>3. Đề xuất đã hết hạn (`expiresAt`).<br>4. Buổi tập đích không còn ở trạng thái `SCHEDULED` (đã bắt đầu hoặc hoàn thành).<br>5. Bản nháp chia sẻ bị thiếu hoặc hỏng snapshot data.<br>6. Tái gửi lệnh Apply cùng `commandKey` nhưng sai lệch thông số/fingerprint SHA-256. |

---

### 2.5. Chi Tiết Schema Cơ Sở Dữ Liệu (Flyway Migration V60)
File migration: `V60__ai_recommendation_draft_sharing.sql`
```sql
ALTER TABLE fitness.ai_recommendations
    ADD COLUMN IF NOT EXISTS shared_with_student boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS shared_revision integer,
    ADD COLUMN IF NOT EXISTS shared_at timestamptz,
    ADD COLUMN IF NOT EXISTS shared_by uuid REFERENCES fitness.users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS shared_title text,
    ADD COLUMN IF NOT EXISTS shared_explanation text,
    ADD COLUMN IF NOT EXISTS shared_payload jsonb;

CREATE INDEX IF NOT EXISTS idx_ai_recommendations_sharing
    ON fitness.ai_recommendations(student_id, shared_with_student)
    WHERE shared_with_student IS TRUE;
```

---

## 3. Khuyến Nghị Giao Diện & Tương Tác Cho Mobile

1. **Giao diện Trainer Workspace:**
   - Hiển thị nút **"Chỉnh sửa đề xuất" (Edit Proposal)** trên Card đề xuất điều chỉnh buổi tập.
   - Cung cấp form điều chỉnh bài tập thay thế, mức tạ, số sets/reps với validation trực tiếp theo schema.
   - Hiển thị Badge trạng thái:
     - `Bản nháp riêng tư (Chưa chia sẻ)`: Học viên chưa nhìn thấy.
     - `Đã chia sẻ (v{sharedRevision})`: Học viên đã nhìn thấy snapshot này.
   - Nút **"Chia sẻ cho học viên" (Share with Student)**: Cho phép Trainer gửi bản xem trước cho học viên tham khảo trước khi chính thức áp dụng.
   - Sau khi Trainer chỉnh sửa tiếp ($V \to V+1$), hiển thị cảnh báo: *"Bạn đang có chỉnh sửa mới chưa chia sẻ lại cho học viên. Học viên hiện đang thấy bản v{sharedRevision}."*

2. **Giao diện Student View (trong `HUMAN_COACH`):**
   - Chỉ xuất hiện Card đề xuất khi Trainer đã chủ động chia sẻ.
   - Hiển thị nhãn rõ ràng: *"Đề xuất từ Huấn luyện viên (Bản nháp tham khảo)"*.
   - **Tuyệt đối ẩn các nút Áp dụng (Accept) và Từ chối (Reject)** đối với Học viên trong `HUMAN_COACH`.
   - Có thể cung cấp nút *"Nhắn tin cho PT"* để học viên phản hồi ý kiến qua hệ thống Chat.

---

## 4. Kết Quả Kiểm Thử Backend Đã Chạy & Xác Minh (Test Execution Evidence)

Tất cả các bộ kiểm thử đã được thực thi thực tế trong phiên và đạt 100% PASS:

1. **Unit Tests (`AiWorkoutAdjustmentServiceUnitTest`):**
   - Lệnh: `mvn test -f services/backend/pom.xml "-Dtest=AiWorkoutAdjustmentServiceUnitTest"`
   - Kết quả: **38/38 PASS** (0 failures, 0 errors).
   - Bao phủ: Quyền sửa/chia sẻ/đọc nháp, stale revision/source target conflict, payload invalid, expired, snapshot missing/corrupted, null title/explanation không fallback live draft, source verification failure (không ghi snapshot/audit), idempotency replay với key order khác nhau qua recursive canonicalization, và các ca lỗi theo T02–T06, T11, T18, T23, T25, T27.

2. **Web Controller Tests (`AiWorkoutAdjustmentControllerUnitTest`):**
   - Lệnh: `mvn test -f services/backend/pom.xml "-Dtest=AiWorkoutAdjustmentControllerUnitTest"`
   - Kết quả: **9/9 PASS** (0 failures, 0 errors).
   - Bao phủ: GET detail (id, recommendationId, aiRunId), 403 `AI_FORBIDDEN` cho unshared draft, PUT draft 200, PATCH draft 200, POST share 200, 409 `AI_RUN_LIFECYCLE_CONFLICT` cho stale revision, Student đọc snapshot đóng băng khi Trainer sửa tiếp v3, snapshot với null title/explanation, và 409 conflict khi snapshot hỏng.

3. **Full Slice Integration Tests (`AiWorkoutAdjustmentFullSliceIntegrationTest`):**
   - Lệnh: `mvn test -f services/backend/pom.xml "-Dtest=AiWorkoutAdjustmentFullSliceIntegrationTest"`
   - Kết quả: **30/30 PASS** (0 failures, 0 errors) trên Testcontainers PostgreSQL (`pgvector/pgvector:pg18`).
   - Bao phủ: Vòng đời F03 hoàn chỉnh Trainer edit $\to$ share $\to$ Student đọc snapshot; đồng thời kiểm chứng các cuộc đua cạnh tranh thực tế (`edit/edit`, `edit/accept`, `edit/reject`, `edit/share`); kiểm chứng 2 thứ tự có kiểm soát (Order 1: share thắng trước $\to$ Student giữ snapshot đóng băng; Order 2: edit thắng trước $\to$ share với expectedRevision cũ bị 409); kiểm chứng atomic rollback 100% khi audit thất bại cho cả updateDraft và shareDraft; và kiểm chứng Accept tải với `loadUnitId` chuẩn xác trong DB (`acceptLoadAdjustment_withAuthoritativeLoadUnit_persistsCorrectly`) và đảo chiều đơn vị (`acceptLoadAdjustment_reverseBaselineLbToKg_persistsCorrectly`).

4. **Runtime Output Validator Tests (`F03RuntimeOutputValidatorUnitTest`):**
   - Lệnh: `mvn test -f services/backend/pom.xml "-Dtest=F03RuntimeOutputValidatorUnitTest"`
   - Kết quả: **17/17 PASS** (0 failures, 0 errors).
   - Bao phủ: Thẩm định substitution bài tập, volume increase/decrease, load increase/decrease, đảo chiều vật lý do chuyển đổi đơn vị LB $\leftrightarrow$ KG, chuẩn hóa alias LBS, chặn tăng tải từ baseline unknown, cho phép duy trì bài tự trọng (bodyweight), và xác thực đơn vị có thẩm quyền qua `MeasurementUnitReferenceQuery` (từ chối mã KG với ID của LB, từ chối mã LB với ID của KG, từ chối unit không thuộc chiều MASS, từ chối loadUnitId không tồn tại).

5. **F02 Regression Tests:**
   - Lệnh: `mvn test -f services/backend/pom.xml "-Dtest=AiWorkoutProposalServiceUnitTest,AiWorkoutProposalFullSliceIntegrationTest"`
   - Kết quả: **58/58 PASS** (33 unit + 25 integration, 0 failures, 0 errors).

6. **Contract & OpenAPI Validation:**
   - Lệnh: `npm run validate:contracts`
     - Redocly lint `openapi.yaml`: 0 errors.
     - `validate-ai-schemas.mjs`: 9 schemas, 40 fixtures verified.
   - Lệnh: `npm run test:contracts`: **21/21 tests passed**.

7. **Mobile Integration & Component Tests (apps/mobile):**
   - Lệnh: `npm test --prefix apps/mobile -- src/__tests__/aiWorkoutAdjustmentApi.test.ts src/__tests__/AiWorkoutAdjustmentModal.test.tsx`
     - `aiWorkoutAdjustmentApi.test.ts`: **22/22 PASS**
     - `AiWorkoutAdjustmentModal.test.tsx`: **36/36 PASS** (bao phủ trọn vẹn luồng edit draft, share/reshare, student read-only view isolation, self-directed authority, 403 unshared private draft, 409 revision conflict reload, exercise catalog picker modal, trùng bài tập gốc bị chặn, volume integer/reps range & delta recalculation, load increase/decrease/units synchronization, load giữ nguyên bị từ chối, A → B ID navigation switch, in-flight competing operations block, bảo tồn unit IDs động khác 1/2, chuẩn hóa alias LBS sang LB, chặn tính delta từ baseline unknown/bodyweight, xử lý chính xác đảo chiều do chuyển đổi đơn vị, và xác thực đơn vị đo lường có thẩm quyền: chặn lưu và hiển thị lỗi trường khi unit ID chưa được phân giải từ catalog metric definitions).
   - Lệnh: `npm run typecheck --prefix apps/mobile`: **PASS (0 errors)**.
   - Lệnh: `npm run lint --prefix apps/mobile`: **PASS (0 errors, 0 warnings)**.
   - Toàn bộ Mobile test suites: **93/93 suites PASS, 792/792 tests PASS**.
   - Toàn bộ Backend F03 test suite: **105/105 tests PASS** (`AiWorkoutAdjustment*Test`, `F03*Test`).

---

## 5. Các Gate Còn Mở (Open Checkpoints) Cần Lưu Ý
1. **F04 OPEN checkpoint:** PR #157 đã merge vào main đạt 6/6 jobs CI, tuy nhiên checkpoint F04 tiếp tục duy trì trạng thái OPEN chờ đánh giá tổng thể milestone.
2. **Live GUI và ảnh evidence:** Sáu PNG cũ bị encoding văn bản đã được thay bằng ảnh chụp lại qua screencap trên emulator và adb pull. Xem [biên bản runtime và manifest](artifacts/f03-mobile-runtime-acceptance-20261115/verification.md) để đối chiếu từng ca GUI. Script 13 bước `scratch/run_full_live_test.py` kiểm HTTP/API và database, kết quả lượt trước được dẫn lại riêng.
3. **D06 / OD-3:** Các ngưỡng tỷ lệ tăng tải tối đa và danh mục bài tập tương đương từ Hội đồng Chuyên môn Thể hình vẫn tiếp tục để mở, hệ thống hiện bảo vệ theo quy tắc an toàn fail-closed.
4. **LLM Provider Mode:** Toàn bộ kiểm thử hiện dùng Simulated Provider (`SimulatedF03AiServiceClient` - $0.00 chi phí), chưa đo lường live tokens với OpenAI/Anthropic/Gemini trả phí.

## 6. Đối chiếu bổ sung ngày 15/11/2026

Mobile nhận field diff đúng tên Backend `originalValue` và `customizedValue`, đồng thời hỗ trợ response `before`/`after` cũ. Regression đã tái hiện lỗi `null → null` trước sửa và đạt sau sửa. Diff header/rows có wrap trên màn hình hẹp.

F04 A → B → A là chuyển Student A → Student B → Student A, gồm response/rejection đến muộn. Component tests đã chạy lại và đạt, Android GUI tương ứng vẫn NOT RUN.

[Dự thảo PR để review](f03-draft-edit-share-pr-description.md).

