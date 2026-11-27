# Mobile Design System

Các token trong `tokens` là nền tảng dùng chung cho Student và Trainer mobile. Khi project đã có theme/tokens, hãy map các giá trị vào kiến trúc hiện tại thay vì tạo hai hệ thống song song.

Component được khuyến nghị:

- AppButton
- AppTextField
- ScreenHeader
- MobileBottomNavigation
- StatusBadge
- MetricCard
- GoalProgressCard
- WorkoutCard
- NutritionSummaryCard
- AttentionSignalCard
- AIRecommendationCard
- ProposalCard
- DataQualityBadge
- TimelineEvent
- EmptyState

API và trạng thái của component được mô tả tại `docs/06-ui-ux/component-specifications.md`.


Các component đã triển khai trong `components/`: `AppButton`, `AppCard`, `AppIcon`, `NavigationAction`; được dùng ở Student/Trainer Home và các bề mặt entry/list/overview trong đợt light UI. AppCard hỗ trợ title/icon/message/state/retry; NavigationAction hỗ trợ description. Mọi action mới cách ly press event khỏi callback nghiệp vụ. Icon dùng thư viện `expo-symbols` sẵn có, không thêm dependency. Các component được khuyến nghị khác ở trên chưa đồng nghĩa đã triển khai.
