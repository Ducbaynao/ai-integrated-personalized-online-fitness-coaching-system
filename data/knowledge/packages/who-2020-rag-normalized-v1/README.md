# WHO 2020 — bộ kiến thức chuẩn hóa cho F01

Phiên bản 1.0.0 · Ngày 2026-10-06 · DRAFT / chưa duyệt chuyên môn / chưa ACTIVE.

## Bộ này chứa gì?
- Toàn bộ PDF gốc 104 trang, giữ nguyên byte.
- Toàn bộ văn bản tiếng Anh trích xuất có mốc từng trang và 27 phần theo cấu trúc nguồn; các bảng giữ khoảng cách trong khối text.
- 16 bài diễn giải tiếng Việt kèm metadata; 38 khuyến nghị có nhóm đối tượng, độ mạnh, độ chắc chắn và điều kiện riêng.
- Bản đồ trang, manifest, báo cáo review và bộ câu hỏi evaluation ứng viên.

Đây là chuẩn hóa dữ liệu toàn tài liệu, không phải dịch toàn văn 104 trang sang tiếng Việt. Mọi phần tiếng Anh được giữ, kể cả bằng chứng, tham khảo, phụ lục. Đồ họa/hình ảnh vẫn xem trong PDF; không được tái dựng thành bảng số hay media mới. Web Annex ngoài PDF chưa được nhập.

## Cách đặt vào dự án
Thư mục data/knowledge trong gói là cấu trúc đề xuất để chuẩn bị corpus; chưa phải đường dẫn/contract được phê duyệt của repository. Có thể đặt vào gốc repo sau khi thống nhất cấu trúc với người tích hợp. Không thay thế docs Phase 2, DB schema hay API.

1. Đọc LICENSE-ATTRIBUTION.md và review-report.md.
2. Review curated/who-2020/*.vi.md cùng source-normalized và PDF.
3. Chốt reviewer, phạm vi F01 và mapping metadata tới Knowledge API/DB.
4. Nhập qua workflow Knowledge của dự án, review/publish đúng quyền; chỉ retrieval phiên bản ACTIVE.
5. Chia chunk sau khi chọn representation. Không ingest mọi bản trùng nội dung cùng lúc.
6. Tạo embedding trong model/config space được chốt; kiểm tra retrieval rồi generation/citation.
7. evaluation/ chỉ dành cho đo chất lượng; không nhập như kiến thức.

## Đọc ưu tiên cho F01 kiến thức chung
01 phạm vi → 02 thuật ngữ → 03 cường độ → 05 vận động trưởng thành → 06 sedentary → 12 cách đọc bằng chứng → 13 giới hạn.
Các nhóm đặc thù được bảo toàn trong 04/07/08/09/10/11; chưa đồng nghĩa phát hành chức năng tư vấn cá nhân cho các nhóm đó.

## Phân vai các file
| Đường dẫn trong data/knowledge | Mục đích |
|---|---|
| raw/*.pdf | Bản gốc đối chiếu, không phải lệnh bật retrieval |
| source-normalized/who-2020-fulltext.en.md | Toàn văn tiếng Anh theo trang |
| source-normalized/who-2020/*.en.md | Cùng nguồn tiếng Anh, tách theo phần; không index đồng thời với fulltext |
| curated/who-2020/*.vi.md | Bản diễn giải tiếng Việt chờ review |
| curated/who-2020/*.metadata.json | Metadata format chuẩn bị dữ liệu |
| recommendations.vi.json | 38 khuyến nghị cấu trúc, không phải policy Rule Engine |
| source-page-map.json | Đối chiếu PDF page với printed page |
| source-manifest.json | Nguồn, checksum và phạm vi bảo toàn |
| evaluation/f01-who-2020-candidate-cases.jsonl | Câu hỏi thử, chưa chạy benchmark |

## Quy tắc dùng
Tách physical inactivity khỏi sedentary; ngủ không phải sedentary; xe lăn/ngồi không tự chứng minh ít vận động. Giữ 'average across week' cho trẻ, OR cho hai mức aerobic, conditional cho mức vượt, điều kiện lâm sàng cho nhóm đặc thù. Không suy ra set/rep/kcal/protein/ngưỡng nghỉ giữa buổi từ nguồn này.

Nguồn năm 2020 được ghi rõ, không khẳng định đã kiểm tra mọi cập nhật đến 2026. File giữ DRAFT; reviewer=null; retrieval_enabled=false. Không có migration, thay đổi code, embedding hoặc thao tác lên DB thật.

## Truy cập nhanh

- [Bản đọc tiếng Việt tổng hợp](who-2020-normalized.vi.md)
- [Báo cáo review](review-report.md)
- [Giấy phép](LICENSE-ATTRIBUTION.md)
