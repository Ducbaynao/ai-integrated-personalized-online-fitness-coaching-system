# Navigation Architecture

## B06 Appointment / B08 lifecycle notice candidate (20/10/2026)

From the Coaching relationship detail, Student and Trainer can open `/(app)/appointments` for Appointment list and bilateral requests. `/(app)/appointments/new` asks for an explicit IANA timezone, local start/end and valid UTC offsets; a pending request does not reserve or create a confirmed Appointment. The counterparty opens `/(app)/appointments/requests/[requestId]`, reviews the interval and accepts or rejects with the real request version. A confirmed Appointment opens `/(app)/appointments/[appointmentId]` for detail, reasoned cancellation or a bilateral reschedule request at `/reschedule`. These are local, unmerged screens and remain subject to backend/A01 review. A legacy Appointment without local intent is displayed with nullable fields; Mobile does not invent an offset.

The mixed `/(app)/notifications` inbox gives only a generic preview for committed proposal, Coaching request and Appointment transitions. It deep-links to a resource only after an authoritative API refetch. On 409, the command retry is discarded and the user reviews fresh state; an uncertain network retry keeps the exact body and command key. On authority loss or account switch, the subject-scoped cache is cleared. Neither an inbox row nor a WebSocket invalidation grants read authority. Automatic recurrence, Appointment completion/no-show and external push remain outside this flow.

## B07 basic nutrition runtime (13/10/2026)

Student Home opens `/(app)/nutrition` (ST-12/ST-13). Student must review and confirm the local date and IANA timezone before reading or writing a day. Private food nutrient values are explicitly USER_REPORTED; blank means unknown, never zero. An all-unknown private food stays DRAFT and cannot be selected for a quantified serving until Student enters at least one nutrient; missing other nutrients remain unknown. A description-only item is unquantified and makes its day nutrient total unknown. Known subtotals are labelled separately. Completion requires a separate confirmation, including explicit no-intake confirmation for an empty day. A correction uses item version and keeps history; network retry keeps its command key, while 409 requires review and a new decision.

Trainer opens read-only `/(app)/coaching/[relationshipId]/nutrition` from the Student relationship context. Backend checks current NUTRITION_LOGS VIEW and conceals the day after authority loss. This route does not create strategic targets or authorize Trainer edits. Catalog governance and AI estimation remain later scope. The SuperFit kit informs visual language only, not Nutrition product authority.

## B07 nutrition strategy runtime (15/10/2026)

Student opens `/(app)/nutrition/strategy` from the nutrition log. The screen reads Goal types, nutrient identities and default unit presentation from backend catalogs. Student explicitly chooses NEW_GOAL or CHANGE_TARGET, an IANA target-calendar timezone, an effective date and at least one nutrient target, previews the full change, then confirms publication. Pausing, resuming and ending require a reason and current aggregate version. Proposals show all targets and require a separate ACCEPT/REJECT confirmation; stale 409 clears the pending retry and reloads state. A network-uncertain retry preserves the exact body and command key.

Student day target preview in the same route is read-only until a log write or day confirmation freezes a snapshot. Explicit REST_DAY selection and a version-bound nutrient override require a separate action; a frozen day cannot be changed. Target values never replace actual Food Log or convert unknown intake to zero.

Trainer opens `/(app)/coaching/[relationshipId]/nutrition-strategy` for the current Student. Current NUTRITION_GOAL VIEW permits read; current CONTRIBUTE/MANAGE and Coaching Period permit a proposal, not direct Goal mutation. The proposal remains pending until Student decision. The route hides Student data after authority loss. These B07 routes use the existing visual primitives; SuperFit is a visual reference, not a Nutrition contract. AI estimation and governed food catalog remain deferred.

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
- `/(app)/coaching/[relationshipId]/planned-workout-series`, `/new` và `/{seriesId}`: local candidate B06 recurrence; Trainer có WORKOUT_PLAN VIEW hiện hành xem đề xuất series của chính mình theo Student, CONTRIBUTE/MANAGE mới đề xuất hoặc rút PENDING. Student quyết định; Trainer không tự xác nhận occurrence.
- `/(app)/coaching/[relationshipId]/workout-plans/new`: Trainer tạo complete DRAFT snapshot cho Student trong relationship hiện tại khi backend cho phép MANAGE.
- `/(app)/students/[studentId]/workout-plans/[planId]`: plan summary, immutable version history, archived/unavailable Exercise presentation và lifecycle commands. `studentId` là cache scope; backend vẫn xác minh plan ownership/visibility.
- `/(app)/students/[studentId]/workout-plans/[planId]/builder`: edit DRAFT hoặc publish significant replacement version. Route không hỗ trợ occurrence-scoped minor adjustments.

Các route B03 không suy authority từ role. Backend vẫn revalidate mọi command; invalid/stale identifiers hoặc relationship đã bị conceal được hiển thị như trạng thái không còn quyền xem.

Workout Plan của Student dùng stack authenticated và yêu cầu Student Profile:

- `/(app)/workout-plans`: ST-05 Plan hub; current ACTIVE luôn lấy từ endpoint current authoritative, còn collection chỉ dùng để mở draft, paused, completed và historical plans.
- `/(app)/workout-plans/new`: tạo SELF_DIRECTED DRAFT; backend quyết định eligibility theo Coaching Period hiện tại.
- `/(app)/workout-plans/[planId]`: ST-06 metadata, owner/read context, current version content, activation và lifecycle theo authority.
- `/(app)/workout-plans/[planId]/schedule`: ST-05 Student tự xác nhận một đợt lịch từ kế hoạch ACTIVE và phiên bản đã xuất bản hiện hành. Chọn session thật, thứ Hai đầu tuần, múi giờ IANA, local start/end và UTC offset tường minh; xem trước toàn bộ trước POST `/planned-workout-batches`. Với session `COACH_REQUIRED`, Student còn phải chọn Appointment `CONFIRMED` chưa liên kết bằng ID thật; backend kiểm tra cặp người, Coaching Period hiện hành và interval bao trùm rồi liên kết nguyên tử. Trainer batch proposal vẫn chưa hỗ trợ mode này. Backend kiểm tra lại version, quyền, DST, giới hạn 90 ngày và xung đột; không tạo lịch khi chỉ activate plan. Sau thành công mở calendar để đọc occurrence thật.
- `/(app)/workout-plans/[planId]/series`: local candidate B06 recurrence; Student chọn Monday anchor, IANA timezone và local start/end tường minh cho các session của published version, xem trước block W tuần rồi xác nhận series. Không nhập UTC offset cố định cho cả series: backend chỉ suy offset của từng kỳ khi duy nhất hợp lệ.
- `/(app)/planned-workout-series` và `/{seriesId}`: Student đọc series/đề xuất của mình, ACCEPT/REJECT hoặc dừng bằng version thật, xem kết quả từng kỳ kể cả DST gap/ambiguous và conflict. Lịch sử occurrence vẫn ở calendar; series không tự sinh Appointment.
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

Chat và in-app notification B08 mở từ Coaching hub; gửi tin đầu tiên từ quan hệ HUMAN_COACH ACTIVE, rồi xem các conversation theo period ở `/(app)/chat` và inbox tại `/(app)/notifications`. Resume B03 tạo period và conversation mới; Student vẫn thấy lịch sử cũ, Trainer chỉ thấy period hiện hành. Attachment local candidate được chọn ở conversation, chỉ mở bằng backend download có kiểm quyền/scan; không có URL object storage công khai. Push opt-in local candidate mở từ inbox tại `/(app)/notifications/push`, mặc định tắt, xác nhận IANA timezone và quiet hours; payload chỉ dẫn tới inbox và mọi destination vẫn refetch có authority. Notification chỉ có preview chung, không deep-link vào nội dung khi chưa refetch qua authority. AI Assistance mở theo context; không biến AI thành tab/coaching mode riêng.

M7 review/attention mở từ Coaching hub ở `/(app)/attention` cho Student và từ TR-03 tại `/(app)/coaching/[relationshipId]/attention` cho Trainer hiện hành. Student xác nhận timezone IANA trước khi tạo review tasks; Trainer không xem task/signal period cũ sau pause/end/resume. Mỗi mutation dùng version và command key thật; 409 yêu cầu tải lại, không tự áp dụng lại quyết định cũ.

B08 local candidate: in-app notice DETECTED signal hoặc CREATED/COMPLETED review task chỉ có preview chung. Student mở `/(app)/attention`; Trainer mở Coaching workspace rồi chọn Student hiện hành để đọc Attention/Review qua API kiểm quyền. Notice không chứa evidence, không tự đánh dấu READ từ GET, và không cấp quyền truy cập. Read-only return-status API trả UNKNOWN khi thiếu completion trước, completion time hoặc timezone xác nhận; NO_QUALIFYING_GAP khi có đủ dữ liệu mà chưa đạt 100 ngày, DETECTED khi đạt ngưỡng. Signal hiển thị hai ngày hoàn tất theo timezone Student xác nhận; GET không tạo signal/notice.

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

