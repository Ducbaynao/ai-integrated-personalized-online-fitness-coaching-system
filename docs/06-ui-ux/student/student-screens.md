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

Hiển thị goal type, target, current value, start/target date, active training time, status, current version và coaching context. Tabs `Overview`, `Targets`, `History`. CTA phụ thuộc mode và authority.

## ST-03 Goal Proposal Detail

Hiển thị proposer, timestamp, reason, current vs proposed table, timeline impact, evidence và data gaps. Student có Accept/Reject; Trainer/AI không thể thay Student xác nhận.

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

