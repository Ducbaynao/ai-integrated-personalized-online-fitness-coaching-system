# NIDDK — corpus yếu tố ảnh hưởng đến cân nặng

Bản chuẩn hóa 1.0.0, DRAFT, chuẩn bị 2026-10-07. Chưa duyệt chuyên môn; retrieval_enabled=false.

## Phạm vi

Giữ đủ sáu mục tiếng Anh người dùng gửi. Trang nguồn đã được đối chiếu; ghi Last Reviewed May 2023. Không gọi đây là toàn bộ trang/website. Bibliography [4]–[8] được khôi phục riêng từ trang gốc; các bài nghiên cứu chưa được đọc độc lập. Không coi kiểm tra trang nguồn hôm nay là cập nhật chuyên môn của toàn bộ bằng chứng.

## Thành phần

- data/knowledge/raw/: văn bản người dùng cung cấp; giữ liên kết và chú thích ảnh, chuẩn hóa khoảng trắng.
- data/knowledge/source-normalized/: bản tiếng Anh làm sạch định dạng và bibliography.
- data/knowledge/curated/niddk-weight-factors/: tám bài tiếng Việt và metadata tương ứng; gồm sáu chủ đề nguồn, một tổng quan và một hướng dẫn dự án.
- data/knowledge/niddk-weight-factors-claims.vi.json: 11 mệnh đề có truy vết, hai khoảng ngủ có đơn vị/tuổi; không tự tạo điểm GRADE.
- data/knowledge/niddk-weight-factors-source-manifest.json: phạm vi, version và snapshot hash.
- data/knowledge/evaluation/: 18 trường hợp đánh giá đề xuất, chưa có kết quả chạy; không đưa vào index.
- niddk-weight-factors-normalized.vi.md: bản đọc tổng hợp.
- review-report-niddk.md: kết quả kiểm tra và việc còn chờ duyệt.
- LICENSE-ATTRIBUTION-niddk.md: chính sách riêng của nguồn.

## Cách đưa vào dự án

Các thư mục data/knowledge có thể đặt cùng bộ WHO/ISSN sau khi kiểm tra tên và schema chính thức. Giữ tài liệu gốc, attribution và báo cáo theo source riêng; không ghi đè README hoặc giấy phép của bộ khác. Đây là đề xuất cấu trúc corpus, không phải xác nhận cấu trúc repository đã được duyệt.

Chỉ bài kiến thức/tổng quan là ứng viên retrieval. Chính sách dự án cần tách khỏi các chunk kiến thức trước khi phát hành. Không index README, report, metadata, raw hay evaluation. Chuyên gia cần duyệt bản diễn giải, số liệu và phạm vi thuốc/bệnh lý; dự án cần duyệt policy và ánh xạ schema. Sau đó tạo chunk/embedding, kiểm tra retrieval/answer/citation, đi qua workflow Content/Knowledge để phiên bản được duyệt trở thành ACTIVE. Không sửa code hoặc dữ liệu nghiệp vụ trong lần chuẩn hóa này.
