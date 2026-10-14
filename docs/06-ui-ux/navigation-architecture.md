# Navigation Architecture

## Common account và role switching

Một User có thể có Student Profile, Trainer Profile hoặc cả hai. Role switcher chỉ xuất hiện khi người dùng có nhiều capability đang hoạt động. Trainer Profile chưa verified không được mở các chức năng coaching có authority.

## Student Mobile

Bottom navigation gồm năm destination:

1. `Home`: hôm nay, action required và tóm tắt. Cung cấp lối vào trực tiếp quản lý mục tiêu (`Manage Fitness Goals →`).
2. `Plan`: workout plan, schedule và workout execution.
3. `Progress`: current goal, lifetime, measurement và progress photo.
4. `Nutrition`: daily target, food log và nutrition progress.
5. `Profile`: account, coaching, data sharing và preferences.

Stack navigation hỗ trợ các route chuyên biệt cho Student Goal Management:

- `/(app)/goals`: màn hình quản lý Goal hiện tại (`CurrentGoalScreen` - ST-02) với các tab Overview, Targets, History (Versions & Transitions) và Proposals.
- `/(app)/goals/[goalId]`: màn hình xem chi tiết một Goal cụ thể theo identifier.
- `/(app)/goal-proposals/[proposalId]`: màn hình xem chi tiết so sánh và quyết định Accept/Reject đề xuất mục tiêu (`GoalProposalDetailScreen` - ST-03).

Exercise catalog dùng route authenticated chung cho Student và Trainer:

- `/(app)/exercises`: màn hình duyệt, tìm kiếm và lọc Exercise Library (`ExerciseCatalogScreen` - ST-21). Route được mở từ landing screen của từng capability và được tái sử dụng qua Exercise Picker trong Plan Builder; không tạo thêm bottom tab.
- `/(app)/exercises/[exerciseId]`: màn hình chi tiết Exercise (`ExerciseDetailScreen` - ST-22), mở từ row ST-21 bằng Exercise UUID. Route dùng chung cho Student và Trainer, xử lý tham số không hợp lệ trước khi gửi request và không tạo thêm bottom tab.

Coaching relationship dùng stack authenticated chung, với nội dung và mutation được giới hạn theo active capability và authority từ backend:

- `/(app)/coaching`: danh sách quan hệ của actor, gồm pending, active, paused và historical states; mở từ landing screen Student hoặc Trainer trong checkpoint hiện tại.
- `/(app)/coaching/trainers`: Student-only Trainer directory và flow gửi yêu cầu.
- `/(app)/coaching/invite`: Trainer-only exact-email Student lookup và flow gửi lời mời; yêu cầu capability `canCoach` hiện có, còn backend vẫn revalidate khi submit.
- `/(app)/coaching/[relationshipId]`: trạng thái quan hệ, kỳ hiện tại được phép xem, accept/reject/cancel/pause/resume/end.
- `/(app)/coaching/[relationshipId]/sharing`: authoritative sharing summary; Student owner chỉnh quyền khi relationship active/paused, Trainer chỉ đọc trạng thái backend trả về.
- `/(app)/coaching/[relationshipId]/program`: Trainer-only TR-03 Program context. Route tải Workout Plan khi relationship đang ACTIVE và backend summary cho phép VIEW; PAUSED/ended/authority-loss không preload dữ liệu riêng tư.
- `/(app)/coaching/[relationshipId]/workout-schedule`: Trainer đọc lịch Planned Workout đã xác nhận của đúng Student trong TR-03 qua `GET /students/{studentId}/planned-workouts`, chỉ khi Coaching Period và WORKOUT_PLAN VIEW+ còn hiệu lực. Lịch này chỉ đọc, không có START/SKIP hay liên kết tới Workout Execution; cache tách khỏi lịch Student và được xóa khi mất quyền.
- `/(app)/coaching/[relationshipId]/change-proposals`: Trainer VIEW+ xem các đề xuất đổi/hủy **do chính mình tạo** cho Student qua GET theo Student; có filter trạng thái và phân trang. Detail `[proposalId]` chỉ cho rút PENDING khi còn CONTRIBUTE+; mọi read/mutation đều được backend kiểm quyền hiện hành. Route `/new?occurrenceId=...` mở từ occurrence thật trên Trainer calendar cho CONTRIBUTE+, chọn một RESCHEDULE hoặc CANCEL và preview trước POST. Tạo đề xuất không đổi occurrence; Student quyết định ở route riêng dưới đây. Không dùng `availableActions` rỗng của Trainer calendar để suy quyền tạo.
- `/(app)/workout-change-proposals` và `[proposalId]`: Student vào từ Plan hub hoặc lịch tập, xem danh sách của chính mình qua GET `/planned-workout-change-proposals/me`, lọc/trang và xem detail với giờ cũ so với giờ đề xuất, timezone/UTC offsets và dữ liệu nguồn nullable. Chỉ PENDING có ACCEPT/REJECT với proposal version thật và xác nhận; sau 409 tải lại, không tự ACCEPT lần nữa. ACCEPT được backend recheck quyền Trainer, occurrence, Appointment và xung đột; REJECT không đổi lịch. Student vẫn đọc/REJECT sau khi Coaching Period kết thúc. Cache theo Student và che dữ liệu khi 403/404.
- `/(app)/coaching/[relationshipId]/schedule-proposals` và detail: Trainer xem đề xuất lịch do chính mình tạo cho Student trong TR-03 khi còn Coaching Period và WORKOUT_PLAN VIEW hiện hành. VIEW là chỉ đọc; CONTRIBUTE trở lên mới có thể rút đề xuất PENDING.
- `/(app)/coaching/[relationshipId]/schedule-proposals/new`: Trainer có WORKOUT_PLAN CONTRIBUTE/MANAGE tạo đề xuất nhiều buổi từ phiên bản kế hoạch ACTIVE đã xuất bản. Trainer chọn tường minh IANA timezone cho đề xuất, ngày/giờ địa phương và UTC offset hợp lệ cho từng đầu thời gian; không dùng ngầm timezone thiết bị hay hồ sơ Student. Student xem các giá trị này trước khi ACCEPT/REJECT toàn bộ. COACH_REQUIRED không chọn được trong luồng đầu.
- `/(app)/coaching/[relationshipId]/workout-plans/new`: Trainer tạo complete DRAFT snapshot cho Student trong relationship hiện tại khi backend cho phép MANAGE.
- `/(app)/students/[studentId]/workout-plans/[planId]`: plan summary, immutable version history, archived/unavailable Exercise presentation và lifecycle commands. `studentId` là cache scope; backend vẫn xác minh plan ownership/visibility.
- `/(app)/students/[studentId]/workout-plans/[planId]/builder`: edit DRAFT hoặc publish significant replacement version. Route không hỗ trợ occurrence-scoped minor adjustments.

Các route B03 không suy authority từ role. Backend vẫn revalidate mọi command; invalid/stale identifiers hoặc relationship đã bị conceal được hiển thị như trạng thái không còn quyền xem.

Workout Plan của Student dùng stack authenticated và yêu cầu Student Profile:

- `/(app)/workout-plans`: ST-05 Plan hub; current ACTIVE luôn lấy từ endpoint current authoritative, còn collection chỉ dùng để mở draft, paused, completed và historical plans.
- `/(app)/workout-plans/new`: tạo SELF_DIRECTED DRAFT; backend quyết định eligibility theo Coaching Period hiện tại.
- `/(app)/workout-plans/[planId]`: ST-06 metadata, owner/read context, current version content, activation và lifecycle theo authority.
- `/(app)/workout-plans/[planId]/schedule`: ST-05 Student tự xác nhận một đợt lịch từ kế hoạch ACTIVE và phiên bản đã xuất bản hiện hành. Chọn session thật, thứ Hai đầu tuần, múi giờ IANA, local start/end và UTC offset tường minh; xem trước toàn bộ trước POST `/planned-workout-batches`. Backend kiểm tra lại version, quyền, DST, giới hạn 90 ngày và xung đột; không tạo lịch khi chỉ activate plan. Sau thành công mở calendar để đọc occurrence thật.
- `/(app)/workout-plans/[planId]/history`: version list/detail bất biến và explicit Student successor từ Trainer-authored locked version.
- `/(app)/workout-plans/[planId]/edit`: complete replacement DRAFT snapshot hoặc significant version publication; không dùng cho occurrence-scoped minor adjustment.
- `/(app)/workouts/current`: ST-07, đọc authoritative execution đang `IN_PROGRESS`; ghi từng set, thay Exercise Variation cho actual execution và gửi lệnh complete/abort bằng version backend mới nhất.
- `/(app)/workouts/schedule`: ST-05 Student Planned Workout calendar, mở từ Plan hub; dùng occurrence ID/version thật và chỉ hiện START/SKIP khi backend trả `availableActions`.
- `/(app)/workouts/schedule/[occurrenceId]`: Student xem một occurrence kể cả trạng thái terminal và chuỗi đổi lịch trước/sau bằng ID thật. Chỉ hiện Đổi lịch/Hủy khi detail trả `availableActions`; form chọn tường minh múi giờ IANA, giờ địa phương và UTC offset, xác nhận trước lệnh. Backend quyết định cuối cùng; 409 yêu cầu xem lại, lỗi mạng giữ nguyên lệnh khi thử lại.
- `/(app)/workout-schedule-proposals`: ST-05 danh sách đề xuất lịch của Student, mở từ Plan hub; dữ liệu từ endpoint Student-owned, không phải danh sách Trainer.
- `/(app)/workout-schedule-proposals/[proposalId]`: ST-05 chi tiết đề xuất lịch, Student xác nhận ACCEPT/REJECT toàn bộ với proposal version và command key thật; lịch chỉ được tạo sau khi backend xác nhận ACCEPT.
- `/(app)/workouts/history`: ST-08 history phân trang của Student; tách planned time và performed time.
- `/(app)/workouts/[executionId]`: chi tiết execution visible; terminal record chỉ đọc, legacy record đánh dấu dữ liệu nguồn có thể thiếu.

START/SKIP Planned Workout chỉ được nối vào Mobile khi read contract/calendar cung cấp đồng thời `occurrenceId` và `occurrenceVersion`; route plan/version/session không được dùng thay occurrence identity.

Workout Plan query keys được scope theo Student identity và bị xóa cùng toàn bộ React Query cache khi logout/forced logout. Published history, archived Exercise và unavailable Exercise luôn read-only; canonical metadata chỉ để tham khảo, không remap historical reference.

Chat, notification và AI Assistance mở theo icon hoặc từ context; không biến AI thành tab/coaching mode riêng.

## Trainer Mobile

Bottom navigation:

1. `Overview`: session hôm nay, review due và attention queue.
2. `Students`: danh sách và Student Coaching Workspace.
3. `Schedule`: appointment, conflict và reschedule request.
4. `Messages`: conversation theo coaching relationship.
5. `Profile`: availability, verification, capacity và role switcher.

Workout Plan routes của Trainer luôn đi từ Student Coaching Workspace. `VIEW` và `WORKOUT_PLAN_HISTORY VIEW` chỉ mở nội dung tương ứng; chỉ `WORKOUT_PLAN MANAGE` mới hiển thị authoring entry, và backend recheck authority, optimistic version cùng command key khi submit. Cache key chứa `studentId`; END/403/404 concealment xóa toàn bộ Workout Plan cache của Student trước khi trở về route an toàn.

## Admin Web

Sidebar theo permission, không hiển thị capability bị cấm:

- Dashboard
- Users and Roles
- Trainers and Verification
- Moderation and Support
- Content and Exercise
- Knowledge
- AI Operations
- Data Operations and Integrations
- Notifications Jobs and System Health
- Security and Audit
- Configuration and Feature Flags

Exercise governance dùng các route được bảo vệ bằng effective permission `CATALOG_MANAGE`:

- `/exercises`: danh sách Exercise ở mọi trạng thái.
- `/exercises/new`: tạo Exercise `DRAFT`.
- `/exercises/:exerciseId`: chi tiết read-only.
- `/exercises/:exerciseId/edit`: chỉnh sửa chỉ khi Exercise còn là `DRAFT`; route trực tiếp của `ACTIVE`/`ARCHIVED` hiển thị trạng thái bị chặn.

## Deep link và context

Notification và deep link phải điều hướng đến đúng resource và scope:
- Mọi route thuộc `/(app)/goals` và `/(app)/goal-proposals/[proposalId]` được bảo vệ bởi guard `canAccessFitnessGoals(user)` (yêu cầu capability `hasStudentProfile`). Nếu User không có Student Profile, hệ thống chuyển hướng về `/(app)`.
- Proposal notification dẫn trực tiếp đến `/(app)/goal-proposals/[proposalId]`. Nếu proposal không tồn tại hoặc đã xử lý, màn hình hiển thị trạng thái tương ứng kèm nút quay lại an toàn.
- Nếu actor không còn quyền truy cập, hiển thị permission state thay vì fallback sang dữ liệu khác.

## Navigation invariants

- Coaching Mode không phải destination cố định; nó là context thể hiện trong Profile, Goal và Plan.
- Goal detail luôn cho phép xem version và transition history.
- Student Workspace là hub của Trainer, không bắt PT ghép dữ liệu từ nhiều màn hình rời rạc.
- Admin action nhạy cảm phải giữ resource context, reason và audit reference trong flow.

