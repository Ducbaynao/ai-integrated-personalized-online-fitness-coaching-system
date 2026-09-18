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

1. Student nhận notification.
2. Proposal detail hiển thị current vs proposed, proposer, reason và impact.
3. Student chọn Accept hoặc Reject.
4. Accept mở confirmation summary.
5. Backend validate và tạo Goal Version hoặc Goal mới.
6. Success state link đến version/transition vừa tạo.

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

