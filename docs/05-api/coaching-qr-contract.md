# F12 Coaching QR — Contract thiết kế (A-QR-01)

> **Trạng thái:** `A-QR-02 BACKEND + OPENAPI & A-QR-03/A-QR-04 MOBILE IMPLEMENTED` (`A-QR-05` kiểm thử camera/ảnh trên thiết bị thật `QR01–QR10` vẫn `PLANNED`; **reviewer kỹ thuật chưa được chỉ định**).  
> **Lưu ý quan trọng:** Migration thực tế sau khi tích hợp `main` là `V65__coaching_qr_identities.sql` (chuyển từ checkpoint `V61__coaching_qr_identities.sql` sau `V61`–`V64` trên `main`) và các endpoint `/api/v1/coaching/qr/*` đã có trong `contracts/openapi/openapi.yaml` + `services/backend`. Không đánh dấu `QR01–QR10` hoàn tất cho đến khi xong Mobile và kiểm thử camera/ảnh trên thiết bị thật.

## 1. Phạm vi và nguyên tắc

- **Luồng nghiệp vụ:** Student tìm PT hoặc PT tìm Student bằng camera hoặc chọn ảnh QR → resolve hồ sơ tối thiểu từ backend → chủ động gửi yêu cầu/lời mời qua B03 → counterparty chấp nhận.
- **Không có bước kết bạn:** Không tạo friend list hay trạng thái friendship.
- **Tách biệt rõ trạng thái & quyền:**
  - **Quét QR / Resolve / Preview (`SH-07` → `SH-09`):** Chỉ đọc hồ sơ tối thiểu và trạng thái cặp; **không** tạo `coaching_relationships`, `coaching_periods`, `data_sharing_permissions` hay `conversations`.
  - **Gửi yêu cầu / lời mời (`POST /relationships/requests` hoặc `/invitations`):** Tạo bản ghi `coaching_relationships` ở trạng thái `PENDING` qua B03. Trạng thái `PENDING` **không** cấp bất kỳ quyền truy cập dữ liệu sức khỏe nào.
  - **Chấp nhận (`POST /relationships/{id}/accept`):** Counterparty thực hiện transition `PENDING → ACTIVE` và đóng/mở `coaching_periods` (`HUMAN_COACH`) theo B03. Ngay cả khi đã `ACTIVE`, dữ liệu sức khỏe vẫn bị từ chối cho đến khi Student cấp `data_sharing_permissions` riêng.
- **Tái sử dụng B03:** Mọi thao tác gửi (`requests`/`invitations`) và quyết định (`accept`/`reject`/`cancel`) dùng nguyên contract B03 hiện có cùng `commandKey` và `expectedVersion`.

---

## 2. Định dạng Payload QR

- **Scheme & Route:** Tái sử dụng scheme `ai-fitness-coaching` đã khai báo trong `apps/mobile/app.json`.
- **URI chuẩn:**
  ```text
  ai-fitness-coaching://coaching/qr?v=1&c=<publicCode>
  ```
- **Thành phần:**
  - `v`: phiên bản payload, hiện chỉ chấp nhận giá trị nguyên `1`.
  - `c` (`publicCode`): mã công khai ngẫu nhiên do backend sinh bằng `SecureRandom` (**24 bytes = 192-bit entropy**, mã hóa **Base64URL không padding**, độ dài **chính xác 32 ký tự** khớp `^[A-Za-z0-9_-]{32}$`).
- **Ràng buộc tài khoản và tư cách:**
  - URI **không** chứa `userId`, `role`, email, số điện thoại, JWT, refresh token hay dữ liệu sức khỏe.
  - Bảng `fitness.coaching_qr_codes` trong PostgreSQL ánh xạ duy nhất `public_code` → `(user_id, capability_role)` (`STUDENT` hoặc `TRAINER`).
  - Client không thể sửa `role` hay `userId` trong link để đánh lừa resolver; `expectedTargetRole` gửi lên chỉ để đối chiếu khớp ngữ cảnh người quét.

**Ví dụ payload QR hợp lệ (32 ký tự Base64URL):**
```text
ai-fitness-coaching://coaching/qr?v=1&c=k8X9mP2vQ7nR4tW1zY6bN3cF5hJ0sL8a
```

---

## 3. Endpoints dự kiến (`/api/v1/coaching/qr`)

Tất cả endpoint dưới đây yêu cầu `Authorization: Bearer <JWT>`, tài khoản ở trạng thái `ACTIVE`, và kiểm tra capability hiện hành trên backend. Sử dụng `ErrorResponse` chuẩn (`errorCode`, `message`, `timestamp`, `requestId`, `fieldErrors`).

### 3.1. Đọc hoặc cấp lần đầu QR cá nhân theo tư cách

Để giữ đúng semantics HTTP (`GET` thuần đọc không tạo dữ liệu mới), contract tách rõ đọc thuần và cấp lần đầu idempotent:

1. **`GET /api/v1/coaching/qr/me?role={STUDENT|TRAINER}`** *(PLANNED — pure read)*
   - Trả `200 OK` (`CoachingQrIdentityDto`) nếu actor đã có mã `ACTIVE` cho tư cách này; trả `404 COACHING_QR_NOT_FOUND` nếu chưa từng cấp.
   - Không tự tạo và không tự xoay mã.

2. **`POST /api/v1/coaching/qr/me`** *(PLANNED — idempotent issue-if-absent)*
   - **Request body (`IssueCoachingQrRequest`):**
     ```json
     {
       "role": "TRAINER"
     }
     ```
   - **Hành vi:** Nếu đã có mã `ACTIVE` cho `(actor, role)`, trả `200 OK` với mã `ACTIVE` hiện có (**không** xoay mã để ảnh QR đã lưu tiếp tục dùng được). Nếu chưa có, cấp mã mới nguyên tử (`uq_active_coaching_qr_owner_role`), ghi audit `COACHING_QR_ISSUED` (không lưu `publicCode`), và trả `201 Created`.
   - **Kiểm tra quyền (áp dụng cho cả `GET` và `POST`):**
     - `role = STUDENT`: actor phải có role `STUDENT` chưa thu hồi và có `student_profiles`.
     - `role = TRAINER`: actor phải có role `TRAINER` chưa thu hồi và `TrainerCoachingEligibilityQuery.canCoach(actor) == true` (hồ sơ active & `verificationStatus == VERIFIED`). Nếu `isAcceptingStudents == false`, vẫn cho xem/lưu QR đã có, nhưng khi người khác resolve/gửi yêu cầu sẽ bị chặn theo policy B03.

- **Response `200 OK` / `201 Created` (`CoachingQrIdentityDto`):**
  ```json
  {
    "qrId": "7f3c2d10-8b4a-4e21-9c12-3a4b5c6d7e8f",
    "role": "TRAINER",
    "publicCode": "k8X9mP2vQ7nR4tW1zY6bN3cF5hJ0sL8a",
    "qrUri": "ai-fitness-coaching://coaching/qr?v=1&c=k8X9mP2vQ7nR4tW1zY6bN3cF5hJ0sL8a",
    "payloadVersion": 1,
    "displayName": "Nguyễn Văn A",
    "issuedAt": "2026-11-25T16:30:00Z",
    "version": 1
  }
  ```

### 3.2. Tạo lại mã QR và vô hiệu mã cũ

- **Method & Path:** `POST /api/v1/coaching/qr/me/rotate` *(PLANNED)*
- **Mục đích:** Vô hiệu mã `ACTIVE` hiện tại (`status = 'REVOKED'`, `revoke_reason = 'ROTATED'`) và cấp mã mới trong cùng transaction PostgreSQL.
- **Request body (`RotateCoachingQrRequest`):**
  ```json
  {
    "role": "TRAINER",
    "expectedVersion": 1,
    "commandKey": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
  }
  ```
  - `role`: `STUDENT` | `TRAINER` (`@NotNull`).
  - `expectedVersion`: `@NotNull @Min(1) Long` (so khớp với `version` hiện tại của mã `ACTIVE`; chống race/double-submit).
  - `commandKey`: `@NotNull UUID` (idempotency replay theo `coaching_command_receipts`, khớp quy ước B03).
- **Monotonic version & Idempotency replay:**
  - `version` tăng đơn điệu theo chuỗi rotate của `(user_id, capability_role)` (`newVersion = currentActive.version + 1`), không reset về `1` trên bản ghi mới.
  - Khả thi tái sử dụng `coaching_command_receipts` (`PRIMARY KEY (actor_id, command_key)`, `command_name = "QR_ROTATE"`, `payload_hash = SHA-256("QR_ROTATE:" + role + ":" + expectedVersion)`).
  - Khi replay cùng `actor_id` + `commandKey`:
    - Nếu khác `command_name` hoặc `payload_hash` → `409 COACHING_IDEMPOTENCY_CONFLICT`.
    - Nếu khớp → **kiểm tra lại trạng thái hiện tại của `saved.qrId` trong `coaching_qr_codes`**: chỉ trả `200 OK` nếu `saved.qrId` vẫn đang `ACTIVE`; nếu mã đó đã bị `REVOKED` bởi một lần rotate kế tiếp, **không** trả mã cũ như thể vẫn `ACTIVE`, mà trả `409 COACHING_QR_VERSION_CONFLICT` để client tải lại mã mới nhất.
- **Response `200 OK` (`CoachingQrIdentityDto`):** Trả `qrId` mới, `publicCode` mới, `qrUri` mới, `issuedAt` mới và `version` mới (`expectedVersion + 1`).

### 3.3. Resolve QR thành hồ sơ tối thiểu & trạng thái quan hệ

- **Method & Path:** `POST /api/v1/coaching/qr/resolve` *(PLANNED)*
- **Lý do dùng `POST`:** Tránh đưa `publicCode` vào query string của `GET` (tránh lộ token qua access log/proxy/URL history).
- **Request body (`ResolveCoachingQrRequest`):**
  ```json
  {
    "publicCode": "k8X9mP2vQ7nR4tW1zY6bN3cF5hJ0sL8a",
    "expectedTargetRole": "TRAINER",
    "payloadVersion": 1
  }
  ```
  - `publicCode`: `@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{32}$")` (đồng bộ chính xác 24 bytes Base64URL không padding = 32 ký tự).
  - `expectedTargetRole`: `@NotNull` (`TRAINER` khi Student tìm PT; `STUDENT` khi PT tìm Student).
  - `payloadVersion`: `@NotNull @Min(1) @Max(1) Integer`.
- **Thứ tự kiểm tra quyền & che giấu (bắt buộc giữ đúng thứ tự để không lộ role/tài khoản không khả dụng):**
  1. **Actor auth & capability:** Actor có tài khoản `ACTIVE` (`403 ACCOUNT_UNAVAILABLE`) và capability đối ứng hợp lệ (`expectedTargetRole = TRAINER` → actor có capability `STUDENT`; `expectedTargetRole = STUDENT` → actor có capability `TRAINER`, `canCoach == true` và `isAcceptingStudents == true`).
  2. **Rate limit:** Kiểm tra giới hạn tần suất theo `actorId` (`429 RATE_LIMIT_EXCEEDED`).
  3. **Tra cứu mã:** Tìm chính xác `public_code = ? AND status = 'ACTIVE'` (không prefix/LIKE). Không thấy → `404 COACHING_QR_NOT_FOUND`.
  4. **Kiểm tra che giấu tài khoản/capability target (TRƯỚC self-scan và role-mismatch):** Target phải có tài khoản `ACTIVE` và capability tương ứng với `qr.capabilityRole` còn hiệu lực (`student_profiles` nếu `STUDENT`; role `TRAINER` + `trainer_profiles` nếu `TRAINER`). Nếu không đạt → trả `404 COACHING_QR_NOT_FOUND` (giống hệt mã không tồn tại/đã hủy, không làm lộ `role` hay trạng thái khóa/xóa tài khoản).
  5. **Chống tự quét:** Nếu `qr.userId == actorId` → `400 COACHING_QR_SELF_SCAN`.
  6. **Khớp tư cách:** Nếu `qr.capabilityRole != expectedTargetRole` → `409 COACHING_QR_ROLE_MISMATCH`.
  7. **Eligibility PT target (khi `expectedTargetRole == TRAINER`):** Kiểm tra `canCoach(target) && isAcceptingStudents(target)`. Nếu không đạt → `403 TRAINER_NOT_ELIGIBLE`.
  8. **Trạng thái quan hệ của cặp:** Truy vấn trạng thái hiện hành của đúng cặp `(studentId, trainerId)` (`PENDING`, `ACTIVE`, `PAUSED`). Không suy diễn hay làm lộ quan hệ của Student với PT khác.
- **Response `200 OK` (`CoachingQrProfilePreviewDto`):**
  ```json
  {
    "counterpartyId": "2f8a1c34-5678-4abc-9def-0123456789ab",
    "targetRole": "TRAINER",
    "displayName": "Nguyễn Văn A",
    "avatarUrl": null,
    "trainerSummary": {
      "verificationStatus": "VERIFIED",
      "specialties": ["Tăng cơ", "Giảm mỡ"],
      "yearsExperience": 4.5,
      "isAcceptingStudents": true
    },
    "relationshipState": {
      "status": "NONE",
      "relationshipId": null,
      "direction": null,
      "version": null,
      "canInitiate": true
    }
  }
  ```
  - Khi `targetRole = "STUDENT"`, `trainerSummary` là `null`.
  - **Quy tắc `relationshipState` & `canInitiate` (đối chiếu B03 `CoachingLifecycleService`, `JdbcCoachingStore`, `V4`):**
    - `relationshipState.status`: `"NONE"` | `"PENDING"` | `"ACTIVE"` | `"PAUSED"`.
    - Chỉ khi cặp `(studentId, trainerId)` **chưa có** bản ghi `PENDING`/`ACTIVE`/`PAUSED` (kể cả khi trước đó từng có `ENDED`, `REJECTED`, `CANCELLED` — vì `uq_active_coaching_relationship_pair` chỉ khóa `PENDING/ACTIVE/PAUSED`): `status = "NONE"`, `relationshipId = null`, `direction = null`, `version = null`, `canInitiate = true`.
    - Khi cặp đã có `PENDING`, `ACTIVE` hoặc `PAUSED`: trả đúng `status`, `relationshipId`, `direction` (`"INCOMING"` | `"OUTGOING"` theo actor), `version`, và **`canInitiate = false`** (UI không cho gửi trùng, điều hướng sang xử lý/xem quan hệ hiện có).
    - Nếu Student đang có `ACTIVE`/`PAUSED` với một **PT khác**: QR resolve không làm lộ thông tin này; B03 vẫn cho phép tạo `PENDING` và chỉ chặn khi `ACCEPT` (`409 COACHING_STUDENT_ALREADY_ASSIGNED`), giữ nguyên lifecycle B03.

---

## 4. Field hồ sơ thật và khoảng trống Public Projection

| Field | Nguồn trong PostgreSQL (đã kiểm tra) | Trạng thái hiện có | Cách xử lý ở F12 |
|---|---|---|---|
| `counterpartyId` | `users.id` | Đã có (`UserDisplaySummary.userId`) | Trả trực tiếp |
| `displayName` | `users.display_name` | Đã có (`UserDisplaySummary.displayName`) | Trả trực tiếp |
| `avatarUrl` | `users.avatar_media_id` → `media_files` (V6) | **Thiếu public projection** (`UserDirectoryQuery` chưa expose avatar) | Bổ sung scoped media-URL port ở `A-QR-02` hoặc trả `null` nếu user chưa có ảnh / chưa mở projection |
| `trainerSummary.verificationStatus` | `trainer_profiles.verification_status` (`TrainerVerificationStatus.VERIFIED`) | **Thiếu public projection** (`TrainerDirectoryItem` chỉ có `trainerId`, `displayName`) | Bổ sung `TrainerPublicPreviewQuery` ở module `trainer` trong `A-QR-02` |
| `trainerSummary.specialties` | `trainer_specialty_assignments` + `trainer_specialties.name` (V2) | **Thiếu public projection** | Bổ sung query read-only trong `TrainerPublicPreviewQuery` (trả `[]` nếu chưa gán) |
| `trainerSummary.yearsExperience` | `trainer_profiles.years_experience` (V2) | **Thiếu public projection** | Bổ sung vào `TrainerPublicPreviewQuery` (có thể `null`) |
| `trainerSummary.isAcceptingStudents` | `trainer_profiles.is_accepting_students` (V2) | Có trong `TrainerAvailabilityQuery` | Trả trong `trainerSummary` |
| Email, SĐT, ngày sinh, giới tính, mục tiêu, chỉ số cơ thể, lịch sử tập, ghi chú | `users`, `student_profiles`, `fitness_goals`, ... | Riêng tư | **Cấm tuyệt đối** trả về qua QR preview |

---

## 5. Bảng mã lỗi, HTTP Status và hành vi UI

| Tình huống | HTTP | `errorCode` | Hành vi UI Mobile |
|---|---:|---|---|
| URI sai scheme/path, `v != 1`, ảnh không có QR, nhiều QR | — *(Client)* | — | Không gọi API. Báo lỗi tiếng Việt rõ ràng; nếu nhiều QR hợp lệ, mở danh sách chọn mã |
| `publicCode` sai định dạng / không đủ 32 ký tự Base64URL | `400` | `VALIDATION_FAILED` | Báo mã QR không hợp lệ, cho phép quét lại hoặc chọn ảnh khác |
| Tự quét mã QR của chính mình | `400` | `COACHING_QR_SELF_SCAN` | Thông báo "Bạn không thể tự quét mã QR của chính mình" |
| Tài khoản caller bị khóa/vô hiệu | `403` | `ACCOUNT_UNAVAILABLE` | Khóa luồng theo policy B01 hiện có |
| Caller thiếu capability Student/Trainer | `403` | `STUDENT_CAPABILITY_UNAVAILABLE` / `TRAINER_CAPABILITY_UNAVAILABLE` | Hướng dẫn kích hoạt hoặc chuyển đúng tư cách |
| Caller/Target PT chưa đủ điều kiện (`VERIFIED` + `ACTIVE`) hoặc tạm ngưng nhận học viên | `403` | `TRAINER_NOT_ELIGIBLE` | Nếu caller là PT: báo cần hoàn tất xác minh/bật nhận học viên. Nếu target là PT: báo "Huấn luyện viên hiện chưa nhận học viên mới" |
| Chưa từng cấp QR (`GET /me`), mã không tồn tại, đã rotate (revoked), hoặc target account/capability không khả dụng | `404` | `COACHING_QR_NOT_FOUND` | Với `GET /me`: gọi `POST /me` để cấp lần đầu. Với `/resolve`: che giấu trạng thái thật, báo "Mã QR không hợp lệ, đã hết hiệu lực hoặc người dùng không khả dụng" |
| Quét nhầm tư cách (ví dụ tìm PT nhưng quét QR Học viên) | `409` | `COACHING_QR_ROLE_MISMATCH` | Hiển thị rõ tư cách không khớp và hướng dẫn quét đúng mã |
| Gửi trùng khi đã có `PENDING` | `409` | `COACHING_REQUEST_ALREADY_PENDING` | Tải lại trạng thái hiện tại, chuyển sang xem yêu cầu đang chờ |
| Đã có quan hệ `ACTIVE`/`PAUSED` (lúc accept hoặc cặp hiện hành) | `409` | `COACHING_STUDENT_ALREADY_ASSIGNED` / `COACHING_RELATIONSHIP_STATE_CONFLICT` | Tải lại danh sách quan hệ, không tự retry |
| Stale `expectedVersion` khi rotate QR hoặc replay `QR_ROTATE` sau khi mã đã bị rotate tiếp | `409` | `COACHING_QR_VERSION_CONFLICT` | Tải lại `GET /me`, yêu cầu xác nhận lại nếu vẫn muốn đổi mã |
| Replay `commandKey` với payload khác | `409` | `COACHING_IDEMPOTENCY_CONFLICT` | Tải lại trạng thái mới nhất, không báo thành công giả |
| Quá giới hạn tần suất `/resolve` | `429` | `RATE_LIMIT_EXCEEDED` | Chặn tạm thời theo `Retry-After`, thông báo thử lại sau ít phút |

---

## 6. Bảo mật, Chống dò mã, Privacy và Concurrency

1. **Chống dò mã (Anti-Enumeration & Rate Limit):**
   - Entropy 192-bit (`SecureRandom` 24 bytes → 32 ký tự Base64URL), tra cứu khớp chính xác (`=`), không hỗ trợ prefix/partial search.
   - Rate limit trên `POST /api/v1/coaching/qr/resolve`: tối đa **20 request / phút / `actorId`** (Redis sliding window hoặc fallback tương đương), vượt ngưỡng trả `429 RATE_LIMIT_EXCEEDED`.
   - Mã không tồn tại, mã đã rotate (`REVOKED`) và target account/capability không khả dụng đều trả cùng `404 COACHING_QR_NOT_FOUND` trước khi kiểm tra role mismatch hay self-scan.
2. **Privacy & Logging:**
   - Không ghi `publicCode`, `qrUri` hoặc ảnh QR vào log ứng dụng, bảng `audit_logs`, hay analytics.
   - Ảnh QR chọn từ thư viện chỉ giải mã trên thiết bị (local), không upload lên backend hay AI service.
   - Khi logout hoặc đổi tài khoản trên Mobile, xóa toàn bộ cache preview QR trong TanStack Query / memory.
3. **Thay đổi sau Preview & Concurrency:**
   - Preview không giữ lock và không đảm bảo điều kiện tại thời điểm bấm gửi/chấp nhận.
   - `POST /relationships/requests` và `POST /relationships/invitations` tiếp tục khóa Student (`store.lockStudent`) và kiểm tra lại account, capability, `TrainerCoachingEligibilityQuery`, `isAcceptingStudents`, và `pendingPair`.
   - `POST /relationships/{id}/accept` khóa theo thứ tự cố định B03 (`Student -> Trainer account -> Trainer role -> Trainer profile`) và kiểm tra lại toàn bộ điều kiện trước khi commit `ACTIVE` + `HUMAN_COACH` period.

---

## 7. Tái sử dụng B03

| Bước trên UI (SH-09 / Hub) | Endpoint B03 tái sử dụng | Payload & Concurrency |
|---|---|---|
| Student gửi yêu cầu cho PT | `POST /api/v1/coaching/relationships/requests` | `{ "counterpartyId": "<trainerId>", "commandKey": "<uuid>" }` |
| PT gửi lời mời cho Student | `POST /api/v1/coaching/relationships/invitations` | `{ "counterpartyId": "<studentId>", "commandKey": "<uuid>" }` |
| Counterparty chấp nhận / từ chối | `POST /api/v1/coaching/relationships/{relationshipId}/{accept\|reject}` | `{ "expectedVersion": <long>, "commandKey": "<uuid>" }` |
| Initiator hủy lời mời `PENDING` | `POST /api/v1/coaching/relationships/{relationshipId}/cancel` | `{ "expectedVersion": <long>, "commandKey": "<uuid>" }` |

- `publicCode` của QR **không** thay thế `commandKey`. Client sinh `commandKey` (UUIDv4) một lần cho mỗi ý định thao tác, giữ nguyên khi retry lỗi mạng/timeout, và tạo `commandKey` mới nếu người dùng thay đổi ý định.

---

## 8. Đề xuất Persistence, Module Ports và Migration (Mức thiết kế)

- **Migration áp dụng sau tích hợp `main`:** `V65__coaching_qr_identities.sql` (lịch sử checkpoint trước tích hợp là `V61__coaching_qr_identities.sql`; sau khi tích hợp `origin/main` `f8e564dca39a27acdc5f7192e31dbb399d471216` đã có `V61`–`V64`, migration QR được dời sang `V65` mà không sửa migration đã có trên `main`):
  - Bảng `fitness.coaching_qr_codes`:
    - `id uuid PRIMARY KEY DEFAULT gen_random_uuid()`
    - `user_id uuid NOT NULL REFERENCES fitness.users(id) ON DELETE CASCADE`
    - `capability_role varchar(16) NOT NULL CHECK (capability_role IN ('STUDENT', 'TRAINER'))`
    - `public_code varchar(32) NOT NULL UNIQUE`
    - `payload_version smallint NOT NULL DEFAULT 1`
    - `status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED'))`
    - `version bigint NOT NULL DEFAULT 1`
    - `issued_at timestamptz NOT NULL DEFAULT now()`
    - `revoked_at timestamptz`
    - `revoke_reason varchar(64)`
  - Partial unique index: `uq_active_coaching_qr_owner_role ON fitness.coaching_qr_codes(user_id, capability_role) WHERE status = 'ACTIVE'` (đảm bảo tối đa 1 mã `ACTIVE` mỗi cặp `(user_id, capability_role)` khi `POST /me` chạy đồng thời).
- **Module Ports (tôn trọng ranh giới Modular Monolith):**
  - `coaching`: sở hữu `CoachingQrController`, `CoachingQrUseCase`, `CoachingQrService`, `CoachingQrStore` (`JdbcCoachingQrStore`). Tái sử dụng `coaching_command_receipts` cho idempotency của `rotate` (kèm re-check trạng thái `ACTIVE` của `saved.qrId`).
  - `trainer`: bổ sung `TrainerPublicPreviewQuery` trả `TrainerPublicPreview` (`verificationStatus` kiểu `TrainerVerificationStatus`, `specialties`, `yearsExperience`, `isAcceptingStudents`).
  - `user`: mở rộng hoặc bổ sung port read-only cho `avatarUrl` (nếu mở projection ảnh đại diện từ `users.avatar_media_id` qua `media_files`).
  - `student`: dùng lại `StudentCapabilityQuery.hasProfile(userId)`.
  - `audit`: ghi `COACHING_QR_ISSUED`, `COACHING_QR_ROTATED` (không lưu `publicCode`).

---

## 9. Mapping Contract vào QR01–QR10 & Điểm cần kiểm chứng

| Test ID | Mapping vào Contract F12 | Điểm cần kiểm chứng khi triển khai |
|---|---|---|
| **QR01** | `POST /qr/me (TRAINER)` / `GET /qr/me` → SH-07 quét → `POST /qr/resolve (expectedTargetRole=TRAINER)` → `POST /relationships/requests` → PT `accept` | Xác nhận `coaching_relationships` = `ACTIVE`, `coaching_periods` = `HUMAN_COACH`, audit đầy đủ, không mock |
| **QR02** | `POST /qr/me (STUDENT)` / `GET /qr/me` → SH-07 quét → `POST /qr/resolve (expectedTargetRole=STUDENT)` → `POST /relationships/invitations` → Student `accept` | Không có trạng thái/bảng friendship; đúng chiều `requested_by` |
| **QR03** | Lưu ảnh QR từ SH-08 → chọn ảnh ở SH-07 → decode local → `POST /qr/resolve` | Round-trip encode/decode hoạt động trên ảnh thật ở cả hai tư cách |
| **QR04** | Payload chỉ có `v=1&c=...` (32 ký tự); `POST /qr/me/rotate` | Sửa `v`, `c` (sai độ dài/ký tự), thêm `role`/`userId` giả, ảnh rỗng/nhiều mã, tự quét (`400`), mã cũ sau rotate (`404`) đều bị chặn |
| **QR05** | `resolve` + `initiate` + `accept` kiểm tra account, capability, `canCoach`, `isAcceptingStudents` | Đổi trạng thái account/eligibility giữa lúc preview và lúc gửi/accept phải bị chặn trên server; account khóa không lộ role |
| **QR06** | `commandKey` trên `rotate`, `requests`, `invitations`, `accept`; `uq_active_coaching_qr_owner_role` & B03 | Double-tap/retry trả receipt cũ (nếu còn `ACTIVE`), replay sau rotate tiếp trả `409`; đổi payload trả `409 COACHING_IDEMPOTENCY_CONFLICT` |
| **QR07** | B03 `accept`/`reject`/`cancel` + `expectedVersion` | Chỉ counterparty quyết định, chỉ initiator hủy; rollback không tạo state/audit dở dang |
| **QR08** | Preview DTO + B03 `CoachingAuthorityQuery` | Quét/preview/`PENDING` không đọc được health/goal/nutrition/chat; `ACTIVE` vẫn đòi hỏi `data_sharing_permissions` |
| **QR09** | Bảng lỗi mục 5 + Mobile states | Camera denied, hủy chọn ảnh, offline, `409`, `429`, đổi role/logout xóa cache preview, nhãn tiếng Việt/a11y |
| **QR10** | Thiết bị thật & bằng chứng | Ghi rõ model máy, OS, build, commit SHA, account test cho cả camera & ảnh ở hai chiều |

---

## 10. Danh sách file dự kiến thay đổi ở A-QR-02 và A-QR-03

### A-QR-02 (Backend & Contract OpenAPI)
- `contracts/openapi/openapi.yaml` (thêm 4 operation: `GET /coaching/qr/me`, `POST /coaching/qr/me`, `POST /coaching/qr/me/rotate`, `POST /coaching/qr/resolve` và schemas DTO/error)
- `services/backend/src/main/resources/db/migration/V<next>__coaching_qr_identities.sql` (dự kiến `V61`, kiểm tra số trống thực tế lúc triển khai)
- `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/adapter/in/web/CoachingQrController.java`
- `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/adapter/in/web/CoachingErrorAdvice.java` (map thêm lỗi QR / rate limit nếu cần)
- `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/application/port/in/CoachingQrUseCase.java`
- `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/application/port/out/CoachingQrStore.java`
- `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/application/service/CoachingQrService.java`
- `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/adapter/out/persistence/JdbcCoachingQrStore.java`
- `services/backend/src/main/java/com/fitnesscoaching/platform/modules/trainer/application/port/in/TrainerPublicPreviewQuery.java` (và adapter trong `trainer`)
- `services/backend/src/test/java/com/fitnesscoaching/platform/CoachingQrIntegrationTest.java` (và unit tests cho service/rate limit/concealment/idempotency replay)

### A-QR-03 (Mobile Expo SDK 57)
- `apps/mobile/package.json` & `apps/mobile/app.json` (kiểm tra & cấu hình dependency camera, image picker, QR SVG renderer, QR image decoder tương thích Expo SDK 57)
- `apps/mobile/src/types/coaching.ts` (DTOs cho QR identity & preview)
- `apps/mobile/src/services/coachingApi.ts` (`getMyQr`, `issueMyQr`, `rotateMyQr`, `resolveQr`)
- `apps/mobile/src/features/coaching/coachingQueries.ts` & `coachingMessages.ts` (query keys, cache invalidation on logout/role switch, error mapping QR)
- `apps/mobile/src/app/(app)/coaching/qr.tsx` (SH-07)
- `apps/mobile/src/app/(app)/coaching/my-qr.tsx` (SH-08)
- `apps/mobile/src/app/(app)/coaching/qr-profile.tsx` (SH-09)
- `apps/mobile/src/features/coaching/CoachingQrScannerScreen.tsx`, `MyCoachingQrScreen.tsx`, `QrProfilePreviewScreen.tsx`
- `apps/mobile/src/features/coaching/CoachingHubScreen.tsx`, `TrainerDirectoryScreen.tsx`, `StudentLookupScreen.tsx` (chuyển entry "Tìm PT" / "Tìm học viên" sang QR, giữ tương thích route)
- `apps/mobile/src/__tests__/coachingQr*.test.ts(x)`

### Dependency & Điểm cần review
- **Reviewer kỹ thuật:** **Chưa được chỉ định**; phải gán reviewer tại issue/PR trước khi merge `A-QR-02` cho migration `V<next>__coaching_qr_identities.sql`, `contracts/openapi/openapi.yaml`, và port `TrainerPublicPreviewQuery` / `avatarUrl` projection.
- **Native dependency (A-QR-03):** Trước khi cài package camera/QR decoder cho Expo SDK 57, kiểm tra tương thích dev client / EAS / Android & iOS permissions trên thiết bị thật.
