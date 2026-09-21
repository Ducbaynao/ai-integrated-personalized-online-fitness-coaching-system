# Trainer Flows

## 1. Trainer verification

1. User tạo Trainer Profile.
2. Nộp application và certificates/documents (`POST /api/v1/trainer-applications`). Media binary upload được deferred; endpoint nhận các media identifier đã tồn tại và được cấp phép. Chỉ cho phép nộp application khi trạng thái xác minh của profile là `NOT_SUBMITTED`. Nếu profile đang `PENDING` hoặc đã có active application, hệ thống trả về `409 TRAINER_APPLICATION_ALREADY_ACTIVE`. Nếu profile đang `REJECTED`, `VERIFIED` hoặc `SUSPENDED`, hệ thống chặn với `409 INVALID_LIFECYCLE_TRANSITION`. Quy trình nộp lại hồ sơ (resubmission) sau khi bị `REJECTED` hiện đang deferred sang các milestone sau.
3. Theo dõi trạng thái verification application (`GET /api/v1/trainer-applications/me/current`): các trạng thái được backend hỗ trợ gồm `NOT_SUBMITTED`, `PENDING`, `VERIFIED`, `REJECTED`, `SUSPENDED` (trạng thái ban đầu sau khi submit là `PENDING`). Quy trình Admin review được deferred sang milestone TRAINER-03.
4. Việc nộp application không đồng nghĩa với verification approval (`canCoach` vẫn là `false`). Chỉ khi verification status là `VERIFIED` và activity policy là `ACTIVE`, capability coaching mới hoạt động.

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

