# Design Tokens

Token triển khai mẫu nằm tại `apps/mobile/src/design-system/tokens`. Mọi component phải lấy giá trị từ token thay vì hardcode.

Các giá trị dưới đây là token sản phẩm đã chuẩn hóa, không phải bản sao 1:1 của SuperFit. Figma MCP xác minh preset dùng `Blue/500 #5B33E6`, `Netral/50 #F8F9FA`, `Netral/100 #F1F3F4` và `Netral/500 #9AA0A6`; sản phẩm cố ý dùng brand/neutral palette bên dưới để có semantic states và consistency riêng. Không đổi token code chỉ để khớp preset nếu chưa có quyết định design-system.

## Màu

| Token | Giá trị | Mục đích |
|---|---:|---|
| `brand.50` | `#F4F0FF` | Nền AI và brand nhẹ |
| `brand.100` | `#E9E0FF` | Selected/AI surface |
| `brand.500` | `#6D3DF5` | Brand chính |
| `brand.600` | `#5B2EEA` | Primary action |
| `brand.700` | `#4720C7` | Pressed/strong brand |
| `neutral.0` | `#FFFFFF` | Surface |
| `neutral.50` | `#F8F9FC` | Canvas |
| `neutral.100` | `#F0F2F7` | Subtle surface |
| `neutral.200` | `#E1E5EC` | Border |
| `neutral.500` | `#7B8496` | Secondary text |
| `neutral.900` | `#151822` | Primary text |
| `success.600` | `#159A61` | Completed/on-track |
| `warning.600` | `#D88800` | Review/medium attention |
| `danger.600` | `#D9434E` | Error/high attention/destructive |

Không dùng đỏ cho AI recommendation. Đỏ chỉ dành cho error, dangerous action hoặc high-severity deterministic alert.

## Typography

Font chính: **Be Vietnam Pro** (Google Fonts, SIL OFL 1.1, qua `@expo-google-fonts/be-vietnam-pro`). Mỗi weight dùng một file riêng: 400 Regular, 500 Medium, 600 SemiBold, 700 Bold; token code ở `apps/mobile/src/design-system/tokens/typography.ts`. Fallback: `system-ui`, `sans-serif`.

P7-012: Be Vietnam Pro thay DM Sans vì DM Sans chỉ có subset `latin`/`latin-ext`, thiếu `ơ`, `ư` và các chữ có dấu thanh thuộc Latin Extended Additional (ví dụ `ạ ả ế ộ ự`), nên các ký tự đó bị thay bằng glyph của font hệ thống. Xem [handoff P7-012](../07-development/p7-012-vietnamese-fonts-handoff.md).

| Style | Size/Line | Weight | Dùng cho |
|---|---|---|---|
| Display | 32/38 | 700 | Cover hoặc milestone hiếm gặp |
| H1 | 28/34 | 700 | Tiêu đề màn hình |
| H2 | 22/28 | 700 | Section lớn |
| H3 | 18/24 | 600 | Card title |
| Body | 16/24 | 400 | Nội dung chính |
| Body Small | 14/20 | 400 | Metadata, supporting text |
| Label | 14/18 | 600 | Button, tab, field label |
| Caption | 12/16 | 500 | Timestamp, provenance |

Không sử dụng body text nhỏ hơn 14 px trên mobile cho nội dung cần đọc liên tục.

**Weight và nghiêng:** chọn đúng `fontFamily.*` của token (`regular`, `medium`, `semiBold`, `bold`, `regularItalic`, `mediumItalic`), không đặt `fontWeight` hoặc `fontStyle: 'italic'` lên một family token. Mỗi family là một file riêng; `fontWeight`/`fontStyle` chồng lên file Regular khiến nền tảng tự tạo chữ đậm/nghiêng giả thay vì dùng face Be Vietnam Pro thật. Chỉ face thực sự có caller mới được đăng ký trong `appFontAssets` (hiện có 4 weight đứng và hai italic Regular/Medium); cần face mới thì thêm token và asset cùng lúc. Text không dùng token (ví dụ ký tự ✓/✕ hoặc modal dùng chữ hệ thống) dùng font hệ thống và có thể giữ `fontWeight`; danh sách này được khóa trong `src/__tests__/fontFaceOverrides.test.ts`. Kiểm tra này là kiểm tra mã nguồn, không thay thế kiểm tra hiển thị trên thiết bị.

## Spacing và radius

- Spacing: `2, 4, 8, 12, 16, 24, 32`.
- Screen padding mobile: `16`.
- Khoảng giữa section: `24`.
- Card radius: `16`; hero/feature card có thể dùng `24`.
- Button/input radius: `12` hoặc pill khi là filter chip.
- Touch target tối thiểu: `44 × 44`.

## Elevation

- `shadow.sm`: card nổi nhẹ, không thay cho border.
- `shadow.md`: bottom sheet, modal hoặc sticky action.
- Không dùng shadow đậm cho mọi card; dashboard dữ liệu ưu tiên border nhẹ.

## Semantic status

| Loại | Nền | Chữ/icon | Ví dụ |
|---|---|---|---|
| Success | xanh nhạt | xanh đậm | Completed, On track |
| Warning | vàng nhạt | vàng đậm | Review due, medium alert |
| Danger | đỏ nhạt | đỏ đậm | Conflict, high alert |
| AI | tím nhạt | tím đậm | AI Recommendation |
| Neutral | xám nhạt | xám đậm | Draft, unknown, unavailable |


Component sizing triển khai trong `spacing.ts`: button 48/54 px (min-height), icon 20/24/32 px, feature icon container 64 px, content Home tối đa 640 px trên viewport rộng. Nội dung dài được tự xuống dòng và tăng chiều cao.
