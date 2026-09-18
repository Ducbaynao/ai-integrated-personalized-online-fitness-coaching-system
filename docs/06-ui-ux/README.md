# UX UI Specification

Đây là nguồn chuẩn cho giao diện Student Mobile, Trainer Mobile và Admin Web. Mục tiêu là tạo một trải nghiệm thống nhất với tài liệu nghiệp vụ, đồng thời giữ tinh thần thị giác hiện đại của SuperFit mà không sao chép nguyên trạng template.

## Cách đọc

Trước mọi công việc UI, đọc:

1. [Nguyên tắc thiết kế](design-principles.md)
2. [Design tokens](design-tokens.md)
3. [Navigation](navigation-architecture.md)
4. [Component](component-specifications.md)
5. Tài liệu actor tương ứng trong `student`, `trainer` hoặc `admin`
6. [Ma trận truy vết](references/traceability-matrix.md) khi thêm hoặc bỏ màn hình
7. [Ánh xạ nguồn nghiệp vụ](references/source-section-map.md) khi kiểm tra độ phủ so với tài liệu mô tả

## Bề mặt sản phẩm

| Bề mặt | Nền tảng | Người dùng | Mục tiêu |
|---|---|---|---|
| Student App | React Native | Học viên tự tập hoặc có PT | Thực hiện kế hoạch, theo dõi goal, workout, nutrition và progress |
| Trainer App | React Native | PT đã được xác minh | Quản lý học viên theo exception, xây plan và review progress |
| Admin Web | ReactJS | Admin theo permission | Quản trị nền tảng, nội dung, AI operations, support và audit |

## Bất biến không được phá vỡ

- Chỉ có hai Coaching Mode: `SELF_DIRECTED` và `HUMAN_COACH`.
- AI Assistance không phải Coaching Mode và không có business authority.
- Fitness Goal và Nutrition Goal thuộc Student; thay đổi chiến lược trong `HUMAN_COACH` đi qua proposal và Student xác nhận.
- Trainer có authority đối với Workout Plan trong coaching hợp lệ, nhưng không được trực tiếp đổi goal cá nhân.
- Coaching Mode được quản lý theo Coaching Period, không gắn cố định vào Student Profile.
- Goal mới, đổi PT hoặc kết thúc coaching không xóa fitness history.
- Coaching Appointment và Planned Workout có lifecycle độc lập.
- Missing nutrition hoặc measurement data phải hiển thị là thiếu/không xác định, không được biến thành số 0.
- System Alert và AI Recommendation phải khác nhau về nhãn, nguồn, hành động và màu sắc.
- Admin là Platform Authority, không phải Coaching Authority.

## Quy tắc hoàn thành màn hình

Một màn hình chỉ được coi là hoàn thành khi có:

- Trạng thái mặc định, loading, empty, error và permission denied khi phù hợp.
- Nội dung thực tế bằng tiếng Việt, không dùng lorem ipsum.
- Hành động chính và authority rõ ràng.
- Dữ liệu thiếu, chất lượng thấp hoặc ước lượng được đánh dấu.
- Touch target tối thiểu 44 × 44 trên mobile.
- Không hardcode màu, spacing, radius hoặc typography ngoài design tokens.
