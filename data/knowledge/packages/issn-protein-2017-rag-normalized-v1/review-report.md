# Review ISSN protein and exercise 2017

2026-10-06 · Version 1.0.0 · DRAFT.

## Kiểm tra kỹ thuật và độ bao phủ
- PDF nguồn 25 trang; SHA-256 `7a97a81db0334c95ddd17a2009200e7f38945988b3e1dd5aa70f73a502fe8140`.
- Bản raw giữ nguyên byte. Toàn văn tiếng Anh và 25 file trang có mốc 1–25 liên tục; chỉ bỏ running header và khoảng trắng dư. Hình thức hai cột giữ trong khối text, không tái tạo layout đồ họa.
- 18 bài Việt + metadata, 13 tuyên bố đánh số đúng như Abstract, 30 câu hỏi kiểm thử ứng viên có tài liệu/trang kỳ vọng.
- 224 số mục tham khảo 1–224 đều hiện diện trong phần nguồn. Không chứng minh đã đọc toàn văn 224 nghiên cứu.
- JSON parse được; source ID, trang, nhóm đích, đơn vị và DRAFT được giữ. Không gán GRADE cho bài không chấm GRADE.

## Đối chiếu 13 position statements
| Số mục | Chủ đề | Trang PDF | Bài Việt |
|---|---|---|---|
| 1 | Tập và protein phối hợp MPS | 1 | 05 |
| 2, 3, 8 | Tổng lượng, mức >3, thực phẩm/supplement | 1–2 | 03 |
| 4, 5 | Mỗi lần ăn, leucine/EAA | 1 | 04 |
| 6, 7 | Phân bố và timing | 1 | 05 |
| 9, 10, 11 | Chất lượng, sinh khả dụng, EAA | 2 | 10 |
| 12 | Sức bền/carbohydrate | 2 | 07 |
| 13 | Casein trước ngủ | 2 | 06 |

## Các điểm đã đánh dấu, không âm thầm sửa
1. Abstract nêu 1,4–2,0 g/kg/ngày đủ cho phần lớn; Key points/Conclusion dùng cách diễn đạt 'minimum'. Không biến 2,0 thành trần hoặc nhu cầu chính xác cho mọi người.
2. Trang 11 giải thích 9 EAA/11 NEAA; trang 12 Key points có câu 'seven ... essential (nine conditionally)'. Bản gốc giữ nguyên, bản Việt nêu bất nhất và chờ review.
3. Trang 10 Key points ghi carbohydrate <1,2 g/kg/day trong câu glycogen, trong khi đoạn trang 13 dùng lượng trong protocol. Không tự đổi thành g/kg/hour và không dùng con số này làm policy; cần xem nghiên cứu gốc khi muốn áp dụng.
4. Trên 3,0 g/kg/ngày là bằng chứng sơ bộ; không tự áp dụng cho bệnh thận/gan hoặc phát biểu an toàn suốt đời.
5. Timing/MPS cấp thời, whole-body balance, FFM và tăng cơ dài hạn là các endpoint khác nhau.
6. Casein trước ngủ có study khác tổng protein giữa nhóm; không biến lợi ích timing hoặc lipolysis thành lời hứa giảm mỡ.
7. Đoạn soy/AMPK/mTOR, các peptide và enzyme cần review cơ chế/bối cảnh; không chuyển thành cảnh báo ăn đậu nành, điều trị ung thư hay phục hồi chắc chắn sau 6 giờ.
8. Bài ghi No funding nhưng có khai báo quan hệ ngành supplement. Không xóa thông tin này hoặc diễn giải không có xung đột lợi ích.
9. Số kcal/protein/vi chất thực phẩm trong review không tự thay Food DB hay nhãn sản phẩm; chưa kiểm chứng nguồn composition độc lập.

## Phạm vi review và việc còn lại
Kiểm tra này tập trung cấu trúc/độ bao phủ và đối chiếu phần tuyên bố, các đơn vị và giới hạn nội dung. Không phải xác nhận y khoa, thẩm định lại mọi nghiên cứu hoặc review dịch từng câu bởi chuyên gia. Phần tiếng Việt diễn giải, không dịch toàn bộ thống kê/tài liệu tham khảo.

Cần reviewer fitness/nutrition, kiểm tra bản dịch và điểm bất nhất, rà soát cập nhật sau 2017 khi dùng làm khuyến nghị hiện hành, mapping metadata/contract thật, phân development/holdout, kiểm thử retrieval/citation và publish Knowledge đúng workflow. Chưa chạy benchmark hoặc tạo vector. Tất cả metadata DRAFT, retrieval_enabled=false.

Giấy phép bài CC BY 4.0 được giữ riêng với giấy phép WHO. Không có thay đổi code hoặc DB trong công việc này.
