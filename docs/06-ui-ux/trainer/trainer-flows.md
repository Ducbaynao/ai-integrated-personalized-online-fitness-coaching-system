# Trainer Flows

## 1. Trainer verification

1. User tạo Trainer Profile.
2. Nộp application và certifications.
3. Theo dõi `SUBMITTED`, `UNDER_REVIEW`, `NEEDS_INFORMATION`, `APPROVED` hoặc `REJECTED`.
4. Chỉ khi verification và activity policy hợp lệ, capability coaching mới hoạt động.

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

