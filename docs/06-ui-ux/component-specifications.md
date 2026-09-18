# Component Specifications

## Quy tắc chung

Mọi component hỗ trợ nội dung dài, localization, dynamic type và trạng thái disabled. Component nghiệp vụ không được chỉ thể hiện đẹp ở dữ liệu mẫu.

## AppButton

- Variant: `primary`, `secondary`, `ghost`, `danger`.
- Size: `medium` 48 px, `large` 54 px.
- State: default, pressed, focused, loading, disabled.
- Destructive action phải ghi rõ đối tượng bị ảnh hưởng; không dùng icon đơn độc.

## AppTextField

- State: default, focused, filled, error, disabled, read-only.
- Có label, placeholder, helper/error text và optional leading/trailing action.
- Đơn vị đo hiển thị trong field hoặc selector, không ghép vào label mơ hồ.

## StatusBadge

- Dùng semantic status, không dùng màu đơn độc để truyền đạt ý nghĩa.
- Hỗ trợ các nhóm: draft, pending, active, paused, completed, rejected, warning, error, estimated, unknown.

## MetricCard

Hiển thị label, value, unit, trend, measurement time và source/quality khi cần. Nếu thiếu dữ liệu, value là `—` và ghi `Chưa có dữ liệu`; không hiển thị `0`.

## GoalProgressCard

Hiển thị primary goal, target chính, progress, timeline, active training time và trạng thái. Có entry vào Goal Detail; không đặt nút chỉnh sửa trực tiếp trong `HUMAN_COACH` nếu actor không có authority.

## WorkoutCard

Phân biệt Workout Template, Planned Workout và Completed Workout. Hiển thị planned date và performed date riêng nếu khác nhau. Tag supervision requirement khi session yêu cầu PT.

## NutritionSummaryCard

Hiển thị target và actual tách biệt, day type và logging completeness. Ngày thiếu log phải có nhãn `Dữ liệu chưa đầy đủ` và không tính 0 kcal.

## AttentionSignalCard

Chỉ dành cho deterministic/rule signals. Bắt buộc có loại signal, severity, detected time, evidence ngắn, Student và action phù hợp. Màu warning/danger tùy severity; tuyệt đối không gắn nhãn AI.

## AIRecommendationCard

Bắt buộc có:

- Nhãn `AI Recommendation` và loại recommendation.
- Audience: Student hoặc Trainer.
- Tóm tắt đề xuất.
- Lý do và evidence.
- Dữ liệu đã dùng, dữ liệu thiếu, recency/quality.
- Trạng thái lifecycle.
- `View details` và action theo authority.

Trong `HUMAN_COACH`, Student không được thấy nút tự áp dụng thay đổi plan nếu Trainer là authority.

## ProposalCard

Dùng cho Goal Proposal và Nutrition Proposal. Hiển thị current vs proposed, proposer, reason, effective date/duration, status và Student decision. Accept phải mở confirmation summary trước khi gửi.

## DataQualityBadge

Các nhãn chuẩn: `Confirmed`, `Estimated`, `Corrected`, `Stale`, `Suspect`, `Missing`, `Partial`. Tooltip hoặc info sheet giải thích nguồn và ý nghĩa.

## TimelineEvent

Dùng cho Goal Version, Goal Transition, Coaching Period, Plan Version và audit-visible history. Mỗi event có actor, time, summary và link đến resource nếu có quyền.

## EmptyState

Gồm tiêu đề, giải thích nguyên nhân, primary action và optional secondary action. Không dùng cùng một empty state cho “chưa tạo”, “không có quyền” và “lọc không có kết quả”.

## MobileBottomNavigation

Năm destination, icon + label, active state rõ ràng và safe-area padding. Không đưa AI thành destination mặc định.

## AdminDataTable

Hỗ trợ search, filter, sort, pagination, column visibility và row actions theo permission. Bulk action chỉ xuất hiện khi policy cho phép. Sensitive fields masked mặc định.

## AdminActionDialog

Hiển thị target, action, impact, required reason, permission, step-up requirement và audit notice. Hành động nguy hiểm không được hoàn tất chỉ bằng một click từ table.

