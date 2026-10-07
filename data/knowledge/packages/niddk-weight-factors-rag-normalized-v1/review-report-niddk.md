# Báo cáo review — NIDDK Factors Affecting Weight & Health

## Kết quả

Đã chuẩn hóa đầy đủ sáu nhóm yếu tố trong đoạn người dùng gửi. Tạo tám bài tiếng Việt, tám metadata, 11 mệnh đề truy vết và 18 tình huống đánh giá đề xuất. Không thay đổi code, không import vào DB, không kích hoạt retrieval.

## Đối chiếu nguồn

Trang NIDDK gốc được mở và đối chiếu ngày 2026-10-07; sáu mục và các khoảng tuổi/giờ ngủ khớp nội dung được cung cấp. Trang ghi Last Reviewed May 2023; không suy ra đó là năm xuất bản đầu tiên. Khôi phục bibliography [4]–[8] từ trang, giữ nguyên số chỉ mục vì đây là một phần của bộ tài liệu lớn hơn. Chưa đọc độc lập năm nghiên cứu hoặc các trang thuật ngữ liên kết. Nguồn gốc: https://www.niddk.nih.gov/health-information/weight-management/adult-overweight-obesity/factors-affecting-weight-health

## Các điểm đã xử lý

| Điểm dễ hiểu sai | Cách xử lý |
| --- | --- |
| “May”, “linked”, “more likely” thành chắc chắn | Giữ diễn đạt có thể/liên quan/nguy cơ; không chẩn đoán cá nhân |
| Calorie surplus thành công thức giảm cân | Không thêm kcal mục tiêu, tỷ lệ dinh dưỡng hoặc quy đổi cân nặng |
| Ngồi/nằm thành phải cắt ngủ | Ghi rõ không suy diễn giảm giấc ngủ cần thiết |
| Thiếu môi trường tốt thành lỗi cá nhân | Giữ vai trò điều kiện hỗ trợ, không quy trách nhiệm hoặc suy đoán tín ngưỡng |
| Khoảng ngủ lẫn độ tuổi | 18–64: 7–9 giờ/đêm; từ65: 7–8 giờ/đêm; không áp cho trẻ em |
| Chú thích ảnh lặp cùng khuyến nghị | Giữ trong raw, bỏ lặp ở normalized, không tải ảnh |
| Số 4–8 bị hiểu là một phần số liệu | Định dạng thành tham khảo [4]–[8], bibliography riêng |
| Mọi thuốc trong một nhóm đều gây tăng cân | Giữ “một số”, không thêm tên/liều; gắn yêu cầu reviewer cho nhóm thuốc tim mạch |
| Corticosteroid lẫn steroid đồng hóa | Ghi đúng nhóm corticosteroid |
| Mô tả bulimia thành mẹo giảm cân | Giữ mô tả không thao tác; giới hạn dự án tách rõ khỏi nguồn |
| Gia đình/gen là định mệnh | Giữ nguy cơ cao hơn; không quy tất cả cho gen hoặc đổ lỗi cha mẹ |
| Chính sách đề xuất thành quy tắc repo đã phê duyệt | Tài liệu project_guidance và metadata ghi rõ PROPOSED/DRAFT |
| Thiếu GRADE | Để null, không tự gán độ mạnh bằng chứng |

## Kiểm tra kỹ thuật đã chạy

Đếm đủ sáu mục nguồn; tám cặp bài/metadata; 11 claim ID duy nhất; 18 JSONL case. Parse tất cả JSON/JSONL; kiểm tra liên kết file nguồn và ID tham chiếu; kiểm tra giới hạn tuổi 64/65; xác nhận mọi metadata DRAFT và retrieval tắt. SHA-256 của bản raw nằm trong manifest; đó là hash bản lưu đã chuẩn hóa khoảng trắng, không phải hash nguyên bản tin nhắn hay HTML website. ZIP được kiểm tra toàn vẹn.

## Còn chờ

Chưa duyệt y khoa/dinh dưỡng hoặc bản dịch; chưa đo độ đúng câu trả lời, recall retrieval, độ đúng citation, latency hay cost. Chưa chunk/embed. Các nhóm thuốc và mô tả rối loạn ăn uống cần người có chuyên môn rà soát trước phát hành. Việc mở lại trang nguồn không thay thế cập nhật tổng quan nghiên cứu. Khuyến nghị ngủ lấy từ tài liệu2015 được NIDDK dẫn, không nhận là khuyến nghị mới nhất năm2026.

Dự án cần quyết định schema Content/Knowledge, policy ứng dụng và quy trình duyệt/ACTIVE. Trước ingest, tách phần quy tắc dự án khỏi nội dung trích dẫn NIDDK và giữ đúng chính sách nguồn, không sử dụng ảnh/logo chưa có quyền. Không có điểm PASS cho đánh giá RAG; chỉ kiểm tra cấu trúc dữ liệu đã hoàn thành.
