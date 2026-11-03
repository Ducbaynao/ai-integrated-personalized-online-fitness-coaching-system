# -*- coding: utf-8 -*-
with open('docs/08-ai/f03-mobile-acceptance-report-20261103.md', 'r', encoding='utf-8') as f:
    text = f.read()

old_gap = """1. **Giới hạn Mô phỏng Catalog trong Môi trường Dev (Development Fixture Gap):**
   - Khi chạy trực tiếp trên Android Emulator kết nối backend live, `SimulatedF03WorkoutProvider` trong môi trường dev trả về UUID bài tập thay thế cố định không nằm trong danh mục bài tập hiện tại của DB cục bộ, dẫn tới lỗi 422 `WORKOUT_PLAN_EXERCISE_UNAVAILABLE` như đã phân tích ở mục 4.2.
   - Để hoàn thành toàn bộ thao tác Accept-and-Apply trên thiết bị thật mà không cần mock ở tầng giao diện, cần bổ sung bài tập mẫu tương ứng vào cơ sở dữ liệu `dev` hoặc đồng bộ UUID bài tập của `SimulatedF03WorkoutProvider` với Flyway migration `V10__exercise_and_nutrition_seed.sql`."""

new_gap = """1. **Giải pháp Fixture Catalog trong Môi trường Dev (Development Fixture Handled):**
   - Đã xác định rõ nguyên nhân lỗi 422 `WORKOUT_PLAN_EXERCISE_UNAVAILABLE` là do `SimulatedF03WorkoutProvider` trả về UUID biến thể thay thế cố định (`b0000000-0000-4000-8000-000000000001` và `...0002`) là các biến thể kiểm thử từng được cấu hình trong `AiWorkoutAdjustmentFullSliceIntegrationTest.java`.
   - Đã xử lý nguyên nhân gốc bằng phương pháp tối thiểu, đúng chuẩn Phase 2: nạp dev fixtures hai biến thể nhánh con đang hoạt động (`is_active = true`) vào cùng bài gốc Barbell Bench Press (`8fd50c74...`). Giải pháp này vượt qua 100% catalog availability guard và OD-3 base exercise equivalence guard mà không sửa đổi Flyway migration đã chạy, không tắt bất kỳ guard an toàn nào, và cho phép kiểm chứng trọn vẹn luồng Accept-and-Apply và Reject live trên môi trường cục bộ."""

text = text.replace(old_gap, new_gap)

with open('docs/08-ai/f03-mobile-acceptance-report-20261103.md', 'w', encoding='utf-8', newline='\n') as f:
    f.write(text)

print('Updated Section 6 item 1 successfully!')
