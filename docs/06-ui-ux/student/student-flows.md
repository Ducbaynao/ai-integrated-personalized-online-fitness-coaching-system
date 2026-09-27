# Student Flows

## 1. Onboarding và common account

1. User đăng ký common account.
2. Chọn mục đích hiện tại: tập cho bản thân, coaching học viên hoặc cả hai.
3. Khi chọn tập cho bản thân, tạo Student Profile.
4. Thu thập profile, kinh nghiệm, lịch rảnh, thiết bị, sở thích và hạn chế tự khai báo.
5. Tạo Fitness Goal draft với type, target và timeline.
6. Chọn `SELF_DIRECTED` hoặc tìm/kết nối PT.

Lựa chọn onboarding không phải quyền vĩnh viễn. Student có thể Become a Trainer sau này mà không tạo tài khoản mới.

## 2. Self Directed bắt đầu plan

1. Student xem Current Goal.
2. Chọn tự tạo plan, dùng template hoặc yêu cầu AI Workout Plan Proposal.
3. AI proposal hiển thị dữ liệu đã dùng và thiếu.
4. Student review, chỉnh sửa hoặc chấp nhận.
5. Backend validate rồi mới kích hoạt plan.

AI không tự activate plan.

## 3. Chuyển sang Human Coach

1. Student chọn Trainer hoặc chấp nhận coaching request.
2. Review phạm vi dữ liệu được chia sẻ, gồm loại dữ liệu và time scope.
3. Tạo Coaching Relationship và Coaching Period mới.
4. Goal hiện tại không tự reset.
5. PT có thể tiếp tục plan cũ, tạo plan mới hoặc đề xuất Goal mới.
6. Nếu bắt đầu hành trình mới, Goal cũ chuyển `REPLACED` và history vẫn tồn tại.

## 4. Goal Proposal

1. Student nhận notification hoặc mở tab `Proposals` trong ST-02 / deep-link `/goal-proposals/[proposalId]`.
2. Màn hình Proposal Detail (ST-03) hiển thị bảng so sánh song song giữa Current Version và Proposed Values (Title, Start Date, Target Date, Duration, Objectives, Targets).
3. Student chọn Accept hoặc Reject. Trainer hoặc AI không có quyền thay Student quyết định.
4. Modal xác nhận hiển thị tóm tắt hệ quả tùy theo nội dung đề xuất: ACCEPT có thể tạo version mới hoặc bắt đầu journey mới tùy vào thay đổi primary goal type; REJECT giữ nguyên Goal và version hiện tại. Cho phép nhập ghi chú phản hồi (`decisionNote` tùy chọn khi Accept, bắt buộc khi Reject và không được để trống sau khi trim, tối đa 2000 ký tự).
5. Student xác nhận quyết định; hệ thống gửi request với cơ chế chống double-submit.
6. Backend validate và xử lý quyết định theo hai nhánh nghiệp vụ khi ACCEPT (hoặc đánh dấu `REJECTED` khi Reject):
   - **Cùng Goal journey** (Nếu primary goal type không đổi):
     - Đóng current version (`effectiveUntil = now()`).
     - Tạo `FitnessGoalVersion` tiếp theo trong cùng Fitness Goal.
     - Giữ nguyên Goal identity.
     - Bảo toàn version history.
   - **Goal journey mới** (Nếu primary goal type thay đổi):
     - Goal cũ chuyển sang `REPLACED`.
     - Tạo Fitness Goal mới bắt đầu từ Version 1 (`ACTIVE`).
     - Tạo `Goal Transition` liên kết Goal cũ với Goal mới.
     - Bảo toàn toàn bộ fitness history.
   UI cập nhật trạng thái ngay lập tức.
7. Nếu proposal không còn ở trạng thái PENDING nên không thể quyết định lại, backend trả HTTP 409 với error code `GOAL_PROPOSAL_ALREADY_DECIDED`; nếu base goal version không còn active (đã bị cập nhật trước đó), trả HTTP 409 `STALE_GOAL_PROPOSAL`. Màn hình hiển thị thông báo lỗi xung đột tương ứng để Student refresh dữ liệu.

## 5. Planned workout và actual workout

1. Student mở Plan/Calendar.
2. Chọn Planned Workout theo planned date.
3. Start workout và ghi set, rep, load, duration, RPE, note.
4. Nếu thực hiện ngày khác, giữ cả planned date và performed date.
5. Complete workout tạo Actual Workout/Workout Log.
6. Dashboard cập nhật completion và schedule adherence riêng biệt.

Hoàn thành workout không đồng nghĩa đúng lịch.

## 6. Appointment reschedule

1. Student hoặc Trainer tạo reschedule request.
2. Người còn lại xem current time, proposed time và conflict.
3. Khi Accept, hệ thống revalidate conflict.
4. Appointment thay đổi; Planned Workout chỉ thay đổi khi scope và supervision rule yêu cầu.
5. Nếu PT bận, Student chỉ tự tập khi supervision requirement cho phép.

## 7. Long inactivity và resume

1. Hệ thống hiển thị inactivity period và return-to-training notice.
2. Student chọn tiếp tục Goal hoặc tạo Goal mới.
3. Nếu tiếp tục, UI hiển thị calendar time, active training time và updated projection riêng.
4. Nếu tạo mới, history cũ vẫn trong Lifetime Progress.

## 8. Nutrition logging

1. Student mở Nutrition Today, thấy daily target theo day type.
2. Thêm food bằng search/text/manual hoặc photo.
3. Photo AI trả kết quả ước lượng với confidence và provenance.
4. Student xác nhận hoặc sửa trước khi lưu.
5. Nutrition Summary cập nhật actual và completeness.

Ngày thiếu log không được coi là 0 kcal.

## 9. Measurement check-in

1. Student chọn metric và nhập value, unit, measured time, method.
2. UI validate range và duplicate.
3. Nếu suspect/outlier, yêu cầu xác nhận thay vì chặn mọi trường hợp.
4. Lưu source và quality.
5. Progress phân biệt raw value với trend.

## 10. Goal Pause and Resume

1. **Pause Goal**:
   - Student sở hữu goal đang ở trạng thái `ACTIVE` chọn `Pause Goal` trên ST-02.
   - Modal yêu cầu nhập lý do bắt buộc (1–1000 ký tự, trimmed).
   - Bước xác nhận hiển thị cảnh báo: active training time sẽ bị đóng băng, lịch sử và target dates giữ nguyên.
   - Khi confirm, goal chuyển sang `PAUSED`, banner cảnh báo xuất hiện trên màn hình, nút chuyển thành `Resume Goal`.
2. **Resume Goal**:
   - Student chọn `Resume Goal` từ goal đang `PAUSED`.
   - Modal yêu cầu nhập lý do kích hoạt lại bắt buộc (1–1000 ký tự, trimmed).
   - Bước xác nhận cảnh báo kiểm tra mục tiêu đang chạy.
   - Nếu student đã có một active goal khác cùng thời điểm, hệ thống trả HTTP 409 `ACTIVE_FITNESS_GOAL_ALREADY_EXISTS` và hiển thị alert giải thích: mỗi student chỉ được có tối đa 1 active goal.
   - Nếu thành công, goal chuyển lại `ACTIVE`, các tab overview và targets cập nhật ngay lập tức.

