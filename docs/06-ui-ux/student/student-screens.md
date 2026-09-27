# Student Screen Specifications

## ST-01 Student Home

Thứ tự section:

1. Header: lời chào, coaching context, notification.
2. Action Required: pending proposal, overdue check-in, conflict hoặc missing confirmation.
3. Today: workout và coaching appointment độc lập.
4. Current Goal Progress.
5. Nutrition Today: calories, protein, completeness.
6. Recent AI Recommendation có nhãn riêng.
7. Weekly completion và training continuity.

Không dùng Daily Challenge làm hero mặc định.

## ST-02 Current Goal

Màn hình quản lý Fitness Goal của Student (`/(app)/goals`):

- **Header**: Nút Back, tiêu đề màn hình `Fitness Goal`, hỗ trợ Pull-to-refresh.
- **Status Banner**: Khi Goal ở trạng thái `PAUSED`, hiển thị banner cảnh báo nổi bật (`warningSurface`), ghi rõ thời gian đóng băng active training time, mốc target date và fitness history được bảo toàn nguyên vẹn, kèm `pausedAt` và `statusReason`.
- **Quick Action CTA**:
  - Khi `ACTIVE`: Nút `Pause Goal` mở `PauseResumeGoalModal` (action `pause`).
  - Khi `PAUSED`: Nút `Resume Goal` mở `PauseResumeGoalModal` (action `resume`).
  - Modal bắt buộc nhập lý do (tối đa 1000 ký tự, trimmed, không khoảng trắng rỗng), bước xác nhận 2 giai đoạn (preview reason), chặn double-submit (`isSubmitting` disable button).
- **Navigation Tabs**:
  - `Overview`: Tên Goal, badge trạng thái (`GoalStatusBadge`), số thứ tự Goal Version hiện tại, Start Date, Target Date, Duration (days), thời điểm cập nhật lần cuối, ghi chú trạng thái và danh sách Objectives (Primary / Secondary).
  - `Targets`: Danh sách các chỉ số mục tiêu định lượng (`GoalTarget`). Mỗi card hiển thị tên metric, unit code & symbol, baseline ("Not recorded" nếu null), target value / range ("Unknown" nếu thiếu, tuyệt đối không hiển thị 0 khi missing), target repetitions, target date và notes.
  - `History`: Chia 2 sub-tab có giải thích ngữ nghĩa rõ ràng:
    - `Goal Versions`: Các version / cột mốc chiến lược trong cùng một hành trình goal. Hiển thị version number, badge CURRENT, thời gian hiệu lực (`effectiveFrom` – `effectiveUntil`), change reason, change summary và lock reason.
    - `Transitions`: Lịch sử chuyển tiếp giữa các hành trình fitness goal (`newGoalId`, `previousGoalId`, `transitionReason`, `transitionedAt`, notes).
  - `Proposals`: Danh sách đề xuất thay đổi goal của Student. Filter theo `ALL`, `PENDING`, `ACCEPTED`, `REJECTED`. Mỗi card hiển thị source (STUDENT / TRAINER / AI_ASSISTANCE / SYSTEM). Ghi rõ: AI_ASSISTANCE chỉ là nguồn phát sinh (provenance) của proposal; AI assistance không phải coaching mode, không có business authority; Student vẫn phải trực tiếp review và tự quyết định Accept hoặc Reject. Chạm vào card điều hướng sang ST-03.
- **Empty State**: Khi Student chưa có Active hoặc Paused Goal (HTTP 404), hiển thị card hướng dẫn thân thiện và CTA `Check Goal Proposals` để xem các proposal đang chờ duyệt.

## ST-03 Goal Proposal Detail

Màn hình chi tiết và so sánh đề xuất Fitness Goal (`/(app)/goal-proposals/[proposalId]`):

- **Header**: Nút Back, tiêu đề `Goal Proposal`.
- **Status Banner**: Hiển thị trạng thái proposal (`PENDING`, `ACCEPTED`, `REJECTED`, `CANCELLED`, `EXPIRED`), nguồn đề xuất (`source`), lý do đề xuất (`reason`), thời điểm quyết định (`decidedAt`) và ghi chú quyết định (`decisionNote`).
- **Plan Changes Comparison**: Bảng so sánh trực quan song song (Current vs Proposed):
  - Goal Title: Current Title vs Proposed Title.
  - Start Date: Current Start Date vs Proposed Start Date.
  - Target Date: Current Target Date vs Proposed Target Date.
  - Duration: Current Duration Days vs Proposed Duration Days.
- **Proposed Objectives & Targets**:
  - Danh sách Proposed Objectives với loại mục tiêu, độ ưu tiên Primary/Secondary và notes.
  - Danh sách Proposed Targets với metric name, đơn vị, baseline và proposed target value/range.
- **Decision Actions**:
  - Chỉ hiển thị cho Student khi proposal ở trạng thái `PENDING`. Trainer và AI không thể thay Student quyết định.
  - Nút `Accept Proposal` và nút `Reject Proposal`.
  - Mở Modal xác nhận quyết định: mô tả rõ ràng hệ quả rằng ACCEPT có thể tạo Goal Version tiếp theo (nếu cùng Goal journey) hoặc bắt đầu Goal journey mới (nếu thay đổi primary goal type); REJECT giữ nguyên Goal và version hiện tại. Kèm ô nhập `decisionNote` (tùy chọn với Accept, bắt buộc với Reject và không được để trống sau khi trim, tối đa 2000 ký tự).
  - Chống double-submit, hiển thị loading spinner và disable buttons khi đang gửi request.
  - Sau khi xác nhận thành công, proposal được cập nhật trạng thái:
    - Khi `ACCEPTED`:
      - **Cùng Goal journey** (Primary goal type không đổi): đóng current version, tạo `FitnessGoalVersion` tiếp theo trong cùng Fitness Goal, giữ nguyên Goal identity và bảo toàn version history.
      - **Goal journey mới** (Primary goal type thay đổi): Goal cũ chuyển sang `REPLACED`, tạo Fitness Goal mới bắt đầu từ Version 1 (`ACTIVE`), tạo `Goal Transition` liên kết Goal cũ với Goal mới và bảo toàn toàn bộ fitness history.
    - Khi `REJECTED`: giữ nguyên Goal và version hiện tại.
  - Xử lý lỗi xung đột: Nếu proposal không còn ở trạng thái PENDING nên không thể quyết định lại, hệ thống trả HTTP 409 `GOAL_PROPOSAL_ALREADY_DECIDED`; nếu base goal version không còn active (đã bị cập nhật), trả HTTP 409 `STALE_GOAL_PROPOSAL`. Thông báo rõ ràng cho Student để tải lại dữ liệu mới nhất.

## ST-05 Plan and Calendar

Calendar marker tách appointment, planned workout, completed, missed và rescheduled. Day detail hiển thị hai lifecycle riêng. Filter theo planned/completed/missed.

## ST-06 Workout Plan Detail

Header có owner/creator, status, duration, current version và source. Danh sách day/session/exercise. Badge `Trainer assigned`, `Student created`, `Template` hoặc `AI proposal accepted`. Minor change không tạo version mới; significant change hiển thị version event.

## ST-07 Workout Execution

Sticky header: exercise progress và timer. Mỗi set có planned vs actual rep/load, complete checkbox, RPE sau exercise hoặc session. Quick actions: substitute, skip with reason, add set, note. Không tự áp dụng AI substitution nếu chưa được authority phê duyệt.

## ST-09 Progress Dashboard

Tabs `Current Goal` và `Lifetime`. Filters theo time range và metric. Chart phải có data quality, sample count và textual summary. Section gồm body metric trend, training volume, frequency, completion, schedule adherence, nutrition trend, photos và inactivity period.

## ST-10 Measurements

Metric list hiển thị current valid value, trend, last measured time và source. Add Measurement form có unit, time, method, source và note. Optional metric chưa có dữ liệu không cản progress.

## ST-12 Nutrition Today

Header hiển thị day type và target version. Summary: calories, protein, carbs, fat và logging completeness. Meals list, Add Food CTA và daily override marker. Target không được ghi đè bởi actual.

## ST-14 Food Photo Confirmation

Ảnh, detected foods, portion estimate, confidence, nutrition estimate, source và warning. Student sửa food/portion hoặc xác nhận. Trạng thái lưu provenance `ESTIMATED`, sau xác nhận `CONFIRMED`, sau chỉnh sửa `CORRECTED`.

## ST-16 AI Recommendation Detail

Các section: Recommendation, Why, Data used, Missing/uncertain data, Expected impact, Safety/limitations, Decision history. Action thay đổi theo Coaching Mode. Có feedback helpful/not helpful nhưng feedback không tự áp dụng recommendation.

## ST-17 Coaching Profile

Hiển thị current mode, Trainer nếu có, current Coaching Period, relationship status, shared data scope và coaching history. End coaching/switch trainer là flow xác nhận có impact summary.

## ST-18 Reschedule Request

Current appointment, proposed slot, timezone, conflict result, recurrence scope và supervision requirement. Accept/decline; khi accept phải revalidate.

## ST-19 Chat

Conversation header gắn Trainer/Student và Coaching Relationship. Có context attachment cho workout, proposal hoặc measurement. Khi relationship kết thúc, quyền gửi/đọc tuân policy và history retention.

