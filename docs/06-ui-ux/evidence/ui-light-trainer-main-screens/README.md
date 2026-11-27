# Light UI — Trainer Home và các màn chính

Base: main `12ce8d3ad6a91bab01054e91d6a4c23c98ec54f2`; branch `codex/ui-light-trainer-main-screens`. Tái sử dụng checkout sạch của task, không sửa branch #185.

| Màn / route hiện hữu | Source dưới `apps/mobile/src/features/` | Kết quả trong patch |
|---|---|---|
| Trainer Home, `/` khi TRAINER | `trainer/TrainerHomeScreen.tsx` | Đã đồng bộ: heading, authority card/CTA, coaching, nhóm công cụ, profile/cross-activation/logout. Giữ verification/accepting-students, refresh generation/actor guards và mọi đích điều hướng. Không thêm KPI hay nhiệm vụ giả. |
| Student Home | `auth/HomeScreen.tsx` | Giữ nguyên layout #185; kiểm tương thích caller của shared UI mới. |
| Student calendar, `/workouts/schedule` | `workout-schedule/StudentWorkoutScheduleScreen.tsx` | Đã đồng bộ: header, nhóm entry tiles, card/state, button hierarchy/icons và chiều rộng content. START/SKIP, confirmation, availableActions/body/key/retry giữ nguyên. |
| Trainer calendar, `/coaching/[relationshipId]/workout-schedule` | `workout-schedule/TrainerWorkoutScheduleScreen.tsx` | Đã đồng bộ list/overview/state. Giữ revalidation/share/actor gates; không thêm Student execution actions. |
| Goal, `/goals` | `goal/CurrentGoalScreen.tsx`, `goal/components/GoalOverviewTab.tsx` | Đã đồng bộ header, overview/empty/error card và entry actions, thay glyph trang trí bằng icon. Targets/history/proposal detail và các modal không redesign; giữ reconciliation/lifecycle guards. |
| Student Nutrition, `/nutrition` | `nutrition/NutritionScreen.tsx` | Đã đồng bộ entry tiles, ngày/múi giờ, summary/list và state. Form ghi/sửa món chi tiết bên dưới giữ nguyên legacy UI; không đổi scope, DST, payload/key, unknown/zero. |
| Trainer Nutrition, `/coaching/[relationshipId]/nutrition` | `nutrition/TrainerNutritionScreen.tsx` | Đã đồng bộ read-only overview/list/context/state; giữ timezone học viên và quyền hiện hành. |
| Progress, `/progress`, `/coaching/[relationshipId]/progress` | `progress/ProgressDashboardScreen.tsx` | Đã đồng bộ header, cards, actions, loading/error và scope tab accessibility. Giữ readOnly upload/delete gates, formatter, unit, missing/partial/zero semantics. |
| Chat inbox, `/chat` | `chat/ChatScreens.tsx` — ChatInboxScreen | Đã đồng bộ header, conversation row/icon và pagination/state; Conversation/linked-resource screens giữ nguyên. |
| Notification, `/notifications` | `chat/ChatScreens.tsx` — NotificationsScreen | Đã đồng bộ header, entry tiles, card/title/icon, secondary/ghost actions và state; giữ source-specific navigation và read/delivery callbacks. Push Settings giữ nguyên. |
| Attention, `/attention`, `/coaching/[relationshipId]/attention` | `attention/AttentionReviewScreen.tsx` | Đã đồng bộ header/card/state/buttons. System-rule vs AI labels, review/return-status privacy, body/key/retry và decision guards giữ nguyên. |

Shared API được mở rộng trên đúng AppButton/AppCard/AppIcon/NavigationAction, không tạo bộ tương đương và không thay legacy wrapper toàn app. Hàng điều hướng chỉ có một press target; AppCard không nhận onPress. Button/nav/retry gọi callback không đối số. Native event-shaped tests kiểm default retry intent và disabled/loading dispatch.

Reference: tái sử dụng context đã xem thực ở #185: Figma Workout plans `2835:719`, My Workout `2785:86`, Notification `2763:61`, file `ONFwRWdC9bj4Z6mPSe6yr4`. Không có screenshot/frame mới được xác minh trong đợt này. Trainer workspace và các màn nghiệp vụ được thiết kế bổ sung theo pattern, không tuyên bố có frame gốc tương ứng.

**Visual preview / before-after mới: NOT RUN.** Baseline checkout riêng tại `12ce8d3` đã bundle bằng Expo web, fixture/adapter cục bộ (SPA + media-library legacy web + light appearance, tương tự #185). Browser inventory báo không có local browser access cho chat; native Chrome fallback bị Computer Use dừng vì không xác định URL đủ chắc chắn để thực thi policy. Không bypass bằng automation khác. Chưa kiểm trực quan các màn mới ở 360/390/430 px hoặc desktop; không dùng ảnh #185 làm evidence mới. Fixture/adapter không nằm trong patch và runtime riêng đã dừng.

Self-review: AST comparison xác nhận logic function của 10 file màn hình và 164 callback onPress/onRetry/onRefresh/onChange/onSubmit/onClose/onApplied giữ nguyên. Shared dispatch wrapper là thay đổi có chủ đích để cách ly event. Không sửa services, auth/guards, route/deep-link, Backend, OpenAPI, Flyway hay AI schema. QR Dev-A giữ nguyên.

Validation và kết quả cuối được ghi trong PR; các log FAIL và caller-review JSON được giữ tại thư mục evidence cục bộ của task, không nhân bản log dài vào repo. Các test cũ giữ assertions; selector loading/Trainer navigation được đổi sang control/state thực, test Nutrition đợi query context sẵn sàng rồi bấm native event thay vì kích hoạt nút còn disabled.

Giới hạn: chưa QC Android/thiết bị thật/backend runtime hoặc dynamic type; chưa polish dark. Đây là checkpoint code cho entry/list/overview, không phải hoàn tất mọi light/dark/detail/modal hay release acceptance.
