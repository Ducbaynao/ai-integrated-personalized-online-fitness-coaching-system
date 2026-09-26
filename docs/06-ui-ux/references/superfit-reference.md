# SuperFit Visual Reference

Nguồn tham khảo: `SuperFit Workout Mobile App UI Kit` trên Figma, file key `ONFwRWdC9bj4Z6mPSe6yr4`, page `UI Template` (`86:2234`). Đây là visual preset, không phải product contract.

## Phạm vi đã xác minh qua Figma MCP

Audit read-only ngày 2026-09-26 đã kiểm tra metadata của page `UI Template` và design context kèm screenshot của:

| Node | Frame | Có thể tham khảo |
|---|---|---|
| `2835:719` | Light Mode - Workout plans | Header, search, filter, lưới Workout Plan card |
| `2817:1426` | Light Mode - Detail Workout Plans | Hero, metadata rows, description, Exercise Playlist, sticky CTA |
| `2785:86` | Light Mode - My Workout | Progress card, saved-plan cards, bottom navigation |
| `2763:61` | Light Mode - Notification | Notification grouping và Notification Item |
| `2885:580`, `2886:694` | Light Sign In / Sign Up | Authentication composition đã được audit ở B01 |
| `2896:11024`, `2896:11055` | Dark Sign In / Sign Up | Dark authentication composition đã được audit ở B01 |

Metadata cũng cho thấy Personalized Goals/Height/Weight và onboarding light/dark. Chưa có design context đủ chi tiết cho các node đó trong audit này.

### Token và pattern đã xác minh

- Typography dùng DM Sans Regular/Medium; các size xuất hiện trong frame đã kiểm tra: 12, 14, 16, 18 và 24 px, line-height chủ yếu 1.4; heading 24 px dùng line-height 1.2.
- Màu preset: `Blue/500 #5B33E6`, `Blue/200 #B4A1F4`, `Blue/50 #EFEBFD`, `Netral/Black #000000`, `Netral/White #FFFFFF`, `Netral/50 #F8F9FA`, `Netral/100 #F1F3F4`, `Netral/400 #BDC1C6`, `Netral/500 #9AA0A6`.
- Spacing quan sát được lặp lại ở 8, 12, 16, 20, 24 và 40 px; mobile canvas rộng 430 px và content ngang 398 px với padding 16 px.
- Radius quan sát được gồm 8 px cho input, 16 px cho row/surface, khoảng 21–24 px cho media card/hero và pill cho button/icon action.
- Component/pattern đã xác minh: Header, Input Field, filled Button, Workout Plan card (tên component trong preset là `Recommendation`), Navigation Bar, Notification Item và Exercise Playlist row.

Tên token `Netral` và `reguler` là lỗi chính tả trong preset; không sao chép các tên này vào code hoặc tài liệu sản phẩm.

### Chưa tìm thấy trong các node đã kiểm tra

- Exercise Library đúng nghĩa: browse/detail theo Exercise, muscle, equipment, variation và trạng thái catalog.
- Coaching, Nutrition, Student/Trainer workspace và Admin Web.
- Các trạng thái loading, permission denied, business conflict, partial/stale data và accessibility behavior.

Các mục trên phải được ghi `NOT FOUND IN INSPECTED NODES`, không được mô tả là frame/component Figma đã tồn tại. Một task sau có thể xác minh thêm node cụ thể nếu được cung cấp hoặc tìm thấy qua metadata.

## Có thể kế thừa

- DM Sans.
- Nền trắng/xám rất nhạt và tím làm màu thương hiệu.
- Card bo tròn 16–24 px.
- Metric cards có màu pastel theo nhóm dữ liệu.
- Workout card có hình ảnh, thời lượng và CTA rõ ràng.
- Bottom navigation tối giản.
- Bố cục mobile rộng 430 px làm canvas tham khảo, nhưng implementation phải responsive.
- Khoảng trắng rộng và hierarchy dễ quét.

## Phải thay đổi

- Home không lấy Daily Challenge làm trung tâm; thay bằng Today Plan và Action Required.
- Goal không chỉ là vài lựa chọn chung; phải có target, timeline, proposal, version và progress.
- Recommendation card phải tách AI Recommendation khỏi workout discovery thông thường.
- Activity phải tách raw data, trend, current goal và lifetime.
- Bổ sung coaching, schedule, nutrition, measurement, data sharing và trainer workflows.
- Trainer và Admin cần information architecture riêng.
- Không sao chép nội dung, thương hiệu, hình ảnh hoặc lỗi chính tả của template.
- `Recommendation` trong preset là card khám phá Workout Plan, không phải `AI Recommendation` của sản phẩm.
- Premium, points, badge/community, challenge-first hierarchy và CTA nhận plan trực tiếp không được đưa vào sản phẩm nếu chưa có requirement riêng.
- Navigation của preset không thay thế navigation theo actor trong `navigation-architecture.md`.

## Không được suy ra từ template

Template không quyết định domain model, authority, role, trạng thái hoặc approval flow. Khi template mâu thuẫn tài liệu sản phẩm, tài liệu sản phẩm luôn thắng.

