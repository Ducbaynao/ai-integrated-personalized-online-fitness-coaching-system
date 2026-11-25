# Trainer Flows

## 1. Trainer verification

1. User tạo Trainer Profile (khuyến nghị giữ phân biệt rõ: `publicSlug` / "Định danh hồ sơ công khai" chỉ là định danh URL hồ sơ, **không** phải mã tìm kiếm để học viên thêm HLV và **không** tự động xác minh hay đưa HLV vào danh bạ học viên; mặc định onboarding giữ `acceptingStudents = false` cho đến khi HLV chủ động bật nhận học viên).
2. Upload tài liệu xác minh (`POST /api/v1/trainer-applications/documents`, multipart `file`) và nộp/gửi lại application (`POST /api/v1/trainer-applications` kèm `applicantNote`, `certificateIds`, `documentMediaIds`):
   - Hợp lệ khi trạng thái xác minh hiện tại của profile là `NOT_SUBMITTED` hoặc `REJECTED` (cho phép chỉnh sửa/bổ sung tài liệu và nộp lại).
   - Nếu đang có hồ sơ `PENDING`, trả về `409 TRAINER_APPLICATION_ALREADY_ACTIVE`. Nếu đang `VERIFIED` hoặc `SUSPENDED`, chặn với `409 INVALID_LIFECYCLE_TRANSITION`.
   - Mobile (`TrainerHomeScreen` và `TrainerProfileScreen`) hiển thị trạng thái xác minh hiện tại (`NOT_SUBMITTED`, `PENDING`, `REJECTED`, `VERIFIED`, `SUSPENDED`), lý do từ chối nếu bị `REJECTED`, nút `"Nộp hồ sơ xác minh"` / `"Làm mới trạng thái"` / kéo để làm mới, và đồng bộ `refreshUser()` + invalidate query coaching sau khi tải/lưu/nộp lại để `user.capabilities.canCoach` được cập nhật mà không cần đăng xuất.
3. Theo dõi trạng thái verification application (`GET /api/v1/trainer-applications/me/current`): các trạng thái được backend hỗ trợ gồm `NOT_SUBMITTED`, `PENDING`, `VERIFIED`, `REJECTED`, `SUSPENDED` (trạng thái ban đầu sau khi submit là `PENDING`).
4. Quy trình Admin review (yêu cầu account `ACTIVE`, active role `ADMIN` và permission `TRAINER_VERIFY` từ DB):
   - Administrator truy cập hàng đợi thẩm định qua `GET /api/v1/admin/trainer-applications` (mặc định queue `PENDING` khi bỏ trống `status`, hỗ trợ `status=ALL` để xem toàn bộ trạng thái hoặc lọc từng enum `NOT_SUBMITTED`/`PENDING`/`VERIFIED`/`REJECTED`/`SUSPENDED`, sắp xếp theo thứ tự nộp `submitted_at ASC, id ASC`). Admin Web mặc định mở bộ lọc `Tất cả trạng thái` (`status=ALL`).
   - Administrator xem chi tiết hồ sơ bằng `GET /api/v1/admin/trainer-applications/{applicationId}` và xem/tải tài liệu qua `GET /api/v1/admin/trainer-applications/{applicationId}/documents` & `.../download`, gồm bằng cấp, tài liệu xác minh, thông tin người duyệt và ghi chú nội bộ (`reviewNotes`).
   - Administrator ra quyết định qua `POST /api/v1/admin/trainer-applications/{applicationId}/decisions`:
     - Nếu `APPROVE`: application chuyển sang `VERIFIED`, profile chuyển sang `VERIFIED` kèm `verified_at` và `verified_by`.
     - Nếu `REJECT`: bắt buộc cung cấp `rejectionReason` giải thích lý do; profile chuyển sang `REJECTED`.
     - Quyết định được bảo vệ chống tranh chấp đồng thời bằng pessimistic lock và conditional update (trả về `409 TRAINER_APPLICATION_ALREADY_DECIDED` nếu application không còn `PENDING`).
   - Trainer theo dõi kết quả qua `GET /api/v1/trainer-applications/me/current`: thấy được `status` và `rejectionReason` (nếu bị từ chối), nhưng ghi chú quản trị `reviewNotes` hoàn toàn được bảo mật và không bao giờ lộ ra phía trainer.
5. Việc nộp application hoặc Admin phê duyệt không tự động bỏ qua các điều kiện coaching hay danh bạ học viên:
   - Approval chỉ thiết lập `verificationStatus = VERIFIED`.
   - Quyền huấn luyện (`canCoach`) được quyết định bởi policy engine duy nhất: chỉ trả về `true` khi tài khoản `ACTIVE`, role `TRAINER` active trong DB, profile tồn tại và active (`isActive = true`), `activityStatus = ACTIVE`, và không có restriction. Phê duyệt xác minh không tạo Coaching Relationship.
   - Danh bạ học viên (`GET /api/v1/coaching/trainers`, tìm theo `displayName`) chỉ liệt kê HLV khi đồng thời thỏa `verification_status = VERIFIED`, `activity_status = ACTIVE`, `is_active = true`, `is_accepting_students = true`, tài khoản `ACTIVE` và role `TRAINER` còn hiệu lực, đồng thời không bao giờ lộ `publicSlug`, email hay tài liệu xác minh riêng tư. Hiện tại không có luồng tìm HLV bằng QR hoặc số điện thoại.

## 2. Daily management by exception

1. Trainer mở Overview.
2. Review Today's Sessions, Students Need Review và Attention Queue.
3. Filter signal theo severity/type/status.
4. Mở Student Coaching Workspace từ signal.
5. Xem evidence và xử lý: acknowledge, message, request check-in, review plan hoặc resolve.

Không bắt Trainer mở từng học viên mỗi ngày.

## 3. Student Coaching Workspace

Workspace giữ context Student và permission scope. Tabs:

- Overview
- Program
- Workouts
- Progress
- Nutrition
- Schedule
- Messages
- AI Insights
- History

Section bị giới hạn quyền phải hiển thị permission state, không giả như chưa có dữ liệu.

## 4. Create or update Workout Plan

1. Trainer chọn Student và current goal context.
2. Tạo plan từ scratch/template/previous plan.
3. Thêm session, exercise, set, rep, load, rest, RPE target và supervision requirement.
4. Review conflict và schedule fit.
5. Publish/assign plan.
6. Minor adjustment update in-place với audit; significant change tạo version mới.

## 5. Goal Proposal

Trainer không sửa Goal trực tiếp. Flow gồm current goal, proposed target/timeline, reason, evidence và Student review. Pending proposal không thay đổi active goal.

## 6. Coaching Review Cycle

1. Review task được tạo theo weekly/biweekly/monthly cadence.
2. Workspace tổng hợp factual data, trend, attention và AI insights.
3. Trainer review, gửi feedback, request more data hoặc điều chỉnh plan.
4. Check-in factual data không cần approval; strategic goal/nutrition change dùng proposal.

## 7. Trainer initiated reschedule

Trainer đề xuất slot mới và recurrence scope. Student quyết định. Hệ thống revalidate conflict khi accept. Appointment thay đổi không mặc định hủy Planned Workout.

## 8. AI recommendation review

AI insight hiển thị evidence, missing data và expected impact. Trainer Accept, Modify hoặc Reject. Accept chỉ tạo mutation qua backend validation; AI không tự sửa plan.

