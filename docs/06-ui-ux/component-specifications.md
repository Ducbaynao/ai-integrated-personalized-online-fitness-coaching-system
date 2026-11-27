# Component Specifications

## Quy tắc chung

Mọi component hỗ trợ nội dung dài, localization, dynamic type và trạng thái disabled. Component nghiệp vụ không được chỉ thể hiện đẹp ở dữ liệu mẫu.

## AppButton

- Variant: `primary`, `secondary`, `ghost`, `danger`.
- Size: `medium` 48 px, `large` 54 px.
- State: default, pressed, focused, loading, disabled.
- Destructive action phải ghi rõ đối tượng bị ảnh hưởng; không dùng icon đơn độc.
- Implementation `AppButton` tại `apps/mobile/src/design-system/components`: icon trang trí tùy chọn, nhãn tự xuống dòng, loading giữ nhãn + spinner và chặn thao tác cùng disabled/busy semantics; focus bàn phím có viền. Hiện áp dụng cho Student Home, chưa thay các button nghiệp vụ khác.

## AppCard, AppIcon & NavigationAction

- `AppCard`: surface theo theme, border nhẹ, radius 16, padding 16 và gap 12; không thêm dữ liệu hay semantics tương tác.
- `AppIcon`: dùng `expo-symbols` hiện có (Material Symbols trên Android/web, SF Symbols trên iOS), mặc định 24 px/regular; icon chỉ trang trí, nhãn được cung cấp bởi action cha.
- `NavigationAction`: icon + nhãn + chevron cho hàng, hoặc icon + nhãn trong tile co giãn; touch target tối thiểu 44 px, hỗ trợ xuống dòng và pressed state. Không cắt nhãn tiếng Việt.
- ST-01 dùng CTA kế hoạch chính, nhóm tập luyện/theo dõi/coaching-AI, rồi account/capability/logout. Đây là thiết kế bổ sung cho Home hiện có, chưa triển khai các summary dữ liệu trong đặc tả mục tiêu ST-01.

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

## WorkoutPlanCard

Hiển thị tên plan, source/creator, duration, số session/exercise, media khi có và CTA theo authority. Pattern ảnh phủ và metadata của SuperFit có thể dùng làm visual reference, nhưng card không được mang tên `Recommendation` nếu nó không phải AI output.

## ExerciseListItem

Dùng cho Exercise Library, Exercise picker và playlist trong plan. Hiển thị tên, primary muscle, equipment, variation/canonical relation và media availability khi phù hợp. `Archived` hoặc `Unavailable` phải rõ ràng trong historical view và không được chọn cho plan mới. Đây là component sản phẩm cần cho B02; chưa có frame Exercise Library tương ứng được xác minh trong SuperFit.

## ExercisePicker

Component contextual dùng lại catalog query, search, metadata-driven filters, pagination và `ExerciseListItem`; không có production route riêng. Picker dùng single-selection radio semantics, nhận controlled `selectedExercise`, trả typed `ExerciseSummary` khi người dùng xác nhận và hỗ trợ `excludedExerciseIds`. Selection hợp lệ được giữ khi search/filter làm row tạm thời không còn visible; confirm bị khóa khi chưa chọn, khi selection bị exclude hoặc khi catalog chưa sẵn sàng. Static copy và accessibility text dùng tiếng Việt, còn tên Exercise giữ nguyên từ API.

## SearchAndFilterBar

Search input có label truy cập, clear action và trạng thái đang tìm; filter action có touch target tối thiểu 44 × 44 và summary filter đang áp dụng. Phân biệt first-use empty với no-result và cung cấp Clear filter cho no-result.

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

## DateInputField, DatePickerModal & LocalDateTimeInputField

- `DateInputField` + `DatePickerModal` là thành phần chọn ngày chuẩn cross-platform (Android & Web) thay thế nhập chuỗi `YYYY-MM-DD`: hiển thị ngày tiếng Việt `DD/MM/YYYY`, giữ payload nội bộ `YYYY-MM-DD` không convert qua UTC, không tự điền ngày mặc định khi field đang rỗng, hỗ trợ chọn năm xa/tháng/ngày không áp trần giả trước 1920, xử lý minh bạch ngày không tồn tại khi đổi tháng/năm (không clamp âm thầm), hủy không đổi giá trị, nút xóa cho field tùy chọn, giới hạn `minDate`/`maxDate`/`mondayOnly`, layout responsive theo `useWindowDimensions` bảo đảm chạm tối thiểu `44 × 44` ngay cả ở màn hình `360px` không tràn ngang, `SafeAreaView` và cô lập callback theo operation ID bất biến từng lần mở + unmount/disabled/`sessionKey`.
- `LocalDateTimeInputField` ghép `DateInputField` cho phần ngày với ô giờ `HH:mm` riêng cho các màn lịch hẹn/lịch tập địa phương, mặc định `Asia/Ho_Chi_Minh` (`Giờ Việt Nam`) cho bản ghi mới khi phù hợp và giữ nguyên múi giờ phi Việt Nam/lịch sử cùng phân giải DST UTC offset tường minh.

## MealTypeSelector

- Bộ chọn bữa ăn dùng chung cho Nhật ký dinh dưỡng (`NutritionScreen`) và Phân tích bữa ăn từ mô tả (`FoodAnalysisScreen`), giữ nguyên 5 giá trị enum nghiệp vụ (`BREAKFAST`, `LUNCH`, `DINNER`, `SNACK`, `OTHER`) và nhãn tiếng Việt tương ứng (`Bữa sáng`, `Bữa trưa`, `Bữa tối`, `Bữa phụ`, `Khác`).
- Không tự suy đoán hay tự chuyển bữa ăn theo giờ đồng hồ thiết bị.
- Bảo đảm `accessibilityRole="radiogroup"` / `"radio"`, trạng thái `checked`/`selected`/`disabled`, vùng chạm tối thiểu `44 × 44` (`flexWrap: 'wrap'`, `flexShrink: 1` không cắt chữ khi nhãn dài ở chiều rộng `360px`), và dấu hiệu chọn kép (nền nhấn mạnh, viền `2px` và dấu `✓`) không phụ thuộc duy nhất vào màu sắc hoặc ký tự tròn nhỏ.

## Hiển thị thời điểm không phụ thuộc múi giờ thiết bị

- **Mốc thông thường** (thời gian tin nhắn, thông báo trong ứng dụng, "cập nhật lúc", thời điểm phát hiện/sự kiện của Attention, tạo đề xuất, tạm dừng/cập nhật Goal, hiệu lực phiên bản Goal/Plan, đo cơ thể, nộp/xác minh hồ sơ Trainer, ranh giới chia sẻ và bắt đầu/kết thúc tập thực tế) là `Instant` thông thường: hiển thị theo `Asia/Ho_Chi_Minh` bằng `formatInstantVietnam` / `formatInstantDateVietnam` (`src/localization/instantDisplay.ts`), không đọc múi giờ máy hay điện thoại.
- **Mốc thuộc bản ghi đã lưu** (lịch tập, lịch hẹn gửi trong Chat, bằng chứng 100 ngày, khoảng dự kiến của Attention) giữ múi giờ/ngữ cảnh của bản ghi và hiển thị nhãn múi giờ (kèm offset theo đúng thời điểm, nên DST được phản ánh): dùng `formatRecordedScheduleTime` (bản ghi không có múi giờ hiện UTC kèm ghi chú chưa xác định, không đoán). Ngày đã lưu (`previousLocalDate`, `dueDate`) không bị đổi thành instant.
- **Mốc không có bối cảnh** như `takenAt` của ảnh tiến độ hoặc `completedAt` của lần xử lý AI vẫn hiện UTC có nhãn (`formatContextFreeInstant`).
- Thiếu giá trị, chỉ có ngày, không có offset/`Z` hoặc không parse được thì hiện "Chưa rõ thời điểm" (hoặc nhãn chưa rõ riêng của màn), không đoán giờ và không hiện "Invalid Date".
- Snapshot Workout Execution không trả timezone đã lưu của Planned Workout: giờ dự kiến trong lịch sử/chi tiết dùng `formatRecordedScheduleTime(instant, null)` để hiện UTC kèm ghi chú múi giờ bản ghi chưa xác định. Không lấy timezone tài khoản hiện tại hoặc đọc lịch đang sống để diễn giải lại snapshot. Giờ tập thực tế dùng quy ước mốc thông thường; duration/timer vẫn là khoảng thời gian.
- Đối chiếu đầy đủ inventory ngoài Chat/Attention, gồm vị trí giữ nguyên, tại [handoff time display consistency](../07-development/p7-time-display-consistency-handoff.md).

## Friendly Timezone Defaults & `TimezoneContextPicker` (`timezoneUtils`)

- Giao diện Mobile thường không hiển thị ô tìm/nhập hay chuỗi mã IANA thô: mọi tạo mới thực sự (`resolveNewRecordDefaultTimezone`) mặc định `Asia/Ho_Chi_Minh` (`Giờ Việt Nam`) bất kể múi giờ tài khoản hay thiết bị.
- Dữ liệu hiện có (`resolveExistingRecordTimezone`, chiến lược/version mục tiêu, lịch hẹn, occurrence, đề xuất, setting đã lưu) được giữ nguyên mã IANA và DST/offset trong dữ liệu/payload, nhưng hiển thị trên UI bằng nhãn tiếng Việt thân thiện phân biệt rõ từng vùng qua `formatTimezoneDisplay` (không gom thành nhãn mơ hồ); bản ghi thiếu múi giờ lịch sử vẫn giữ trạng thái chưa có múi giờ, không gán nhầm sang Việt Nam.
- `TimezoneContextPicker` dùng radio semantics (`44 × 44`, không nhập mã IANA) để chọn hoặc khôi phục ngữ cảnh thân thiện (bao gồm tình huống `409 NUTRITION_TIMEZONE_CONFLICT`) mà không tự động thử hàng loạt múi giờ hoặc bỏ qua xác nhận bắt buộc (xác nhận ngày/ngữ cảnh dinh dưỡng, xác nhận múi giờ đánh giá 100 ngày, opt-in thông báo đẩy theo từng danh mục/phiên, xem trước lịch và chọn UTC offset khi DST nhập nhằng).
- Tập lựa chọn của `TimezoneContextPicker` chỉ gồm giao của danh mục `ZoneId.getAvailableZoneIds()` mà Backend kiểm membership (sinh và đối chiếu bằng `apps/mobile/scripts/generate-backend-timezone-catalog.cjs [--check]` với JDK khớp runtime Backend) và các vùng `moment-timezone` hiển thị được; giữ alias hợp lệ như `Asia/Saigon`. Mã đã lưu nằm ngoài tập này vẫn hiển thị nhãn thân thiện và không bị đổi/canonicalize, nhưng không được đưa ra làm lựa chọn mới.

