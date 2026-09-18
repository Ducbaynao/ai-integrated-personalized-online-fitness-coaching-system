# Screen States

## Loading

- Skeleton phải phản ánh cấu trúc thật, không dùng spinner toàn màn hình cho dữ liệu từng section.
- CTA gây mutation hiển thị loading riêng và chống double submit.

## Empty

Phân biệt:

- First use: chưa tạo dữ liệu, có CTA bắt đầu.
- No result: filter/search không có kết quả, có Clear filter.
- No activity: kỳ thời gian chưa có hoạt động.
- Not applicable: dữ liệu không áp dụng cho goal hoặc mode hiện tại.

## Error

- Network/server error: retry được.
- Validation error: gắn với field và summary khi form dài.
- Business conflict: mô tả xung đột, dữ liệu liên quan và hành động tiếp theo.
- Stale write/version conflict: yêu cầu reload/review, không ghi đè âm thầm.

## Permission denied

Không hiển thị dữ liệu bị cấm rồi chỉ khóa nút. Thay bằng state giải thích quyền hoặc sharing scope cần thiết. Không tiết lộ sự tồn tại của resource nhạy cảm khi policy cấm.

## Offline

Workout logging có thể lưu draft local nếu sản phẩm hỗ trợ. Các mutation cần server authority như Accept Proposal hoặc admin privileged action phải bị chặn và giải thích rõ.

## Partial và stale data

- Hiển thị thời điểm cập nhật gần nhất.
- Đánh dấu section thiếu dữ liệu.
- Không tính ngày nutrition thiếu log thành 0.
- Không kết luận trend từ một measurement đơn lẻ.

## Success

Success feedback phải nói rõ resource và trạng thái mới. Proposal accepted cần link đến Goal/Nutrition version mới. Reschedule accepted cần hiển thị lịch mới và trạng thái workout liên quan.

## Confirmation

Bắt buộc cho:

- Accept strategic proposal.
- End coaching hoặc switch trainer.
- Replace/start new goal.
- Delete/anonymize/suspend/revoke/publish critical admin action.
- Apply AI-generated plan change khi policy yêu cầu review.

