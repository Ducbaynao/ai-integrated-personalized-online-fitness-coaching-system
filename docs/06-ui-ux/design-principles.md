# Nguyên Tắc Thiết Kế

## 1. Coaching trước gamification

Home phải ưu tiên việc người dùng cần làm hôm nay: workout, lịch với PT, check-in, nutrition và attention item. Streak, badge hoặc challenge chỉ là nội dung phụ và không được đẩy nhiệm vụ coaching xuống dưới.

## 2. Quyền quyết định phải nhìn thấy được

Mọi đề xuất thay đổi Goal, Workout Plan hoặc Nutrition Target phải thể hiện:

- Ai tạo đề xuất: Student, Trainer, Rule Engine hay AI.
- Ai có quyền quyết định cuối cùng.
- Trạng thái: pending, accepted, rejected, expired hoặc withdrawn.
- Dữ liệu và lý do liên quan.

Không dùng nút mơ hồ như “Apply” nếu thao tác thực tế cần Student xác nhận hoặc backend validation.

## 3. AI có thể giải thích nhưng không âm thầm hành động

AI Recommendation phải có nhãn AI, mục tiêu, lý do, dữ liệu đã dùng, dữ liệu còn thiếu và hành động khả dụng. Trong `HUMAN_COACH`, recommendation về plan chủ yếu đi đến Trainer review. Trong `SELF_DIRECTED`, Student có thể review và chủ động áp dụng.

## 4. Management by exception

Trainer và Admin dashboard ưu tiên hàng đợi cần hành động thay vì chỉ hiển thị KPI. Mỗi Attention Signal phải có severity, detected time, evidence và trạng thái xử lý.

## 5. Tách dữ liệu thực tế khỏi diễn giải

- Measurement thô khác trend.
- Planned Date khác Performed Date.
- Nutrition Target khác Actual Intake.
- System Alert khác AI Recommendation.
- Current Goal Progress khác Lifetime Progress.

Các cặp này không được gộp thành một con số hoặc một trạng thái duy nhất.

## 6. Lịch sử không biến mất

Khi chuyển Coaching Mode, thay PT, tạo Goal mới hoặc dùng Workout Plan mới, giao diện phải giữ entry lịch sử và cho phép xem transition. Không dùng thao tác “reset progress” để xóa dữ liệu cũ.

## 7. Thiết kế cho dữ liệu không hoàn hảo

Sử dụng các trạng thái `Chưa có dữ liệu`, `Thiếu dữ liệu`, `Đang ước lượng`, `Cần xác nhận`, `Dữ liệu cũ` và `Có dấu hiệu bất thường`. Không hiển thị biểu đồ chính xác giả khi chưa đủ dữ liệu.

## 8. Một hệ thống, hai mật độ giao diện

Mobile dùng card, khoảng trắng và hành động theo ngữ cảnh. Admin Web dùng sidebar, data table, filter, bulk-selection có kiểm soát và audit context. Cả hai dùng chung brand, status language và typography nhưng không ép layout mobile lên web.

## 9. An toàn và riêng tư mặc định

Thông tin nhạy cảm như Progress Photo, chat, nutrition và body metric chỉ hiển thị khi actor có scope phù hợp. Không dùng nội dung nhạy cảm trong notification preview nếu người dùng chưa cho phép.

## 10. Phong cách tham khảo SuperFit

Giữ DM Sans, nền sáng, tím chủ đạo, card bo tròn, khoảng trắng rộng và metric card màu nhẹ. Giảm ảnh trang trí ở màn hình nghiệp vụ dày dữ liệu; ưu tiên hierarchy và readability.

