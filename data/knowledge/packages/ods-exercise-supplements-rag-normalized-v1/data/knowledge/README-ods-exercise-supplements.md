# NIH ODS — corpus thực phẩm bổ sung thể thao

Version 1.0.0 · DRAFT · Prepared 2026-10-07. Chưa duyệt y khoa/dinh dưỡng, không retrieval, không import DB.

## Phạm vi và thành phần

- Raw: nguyên byte tệp TXT người dùng gửi, SHA-256 kiểm tra khớp.
- Source normalized: toàn bộ 539 dòng,32 phần liên tiếp không bỏ sót;32 cặp section/metadata và bản toàn văn có neo. Giữ các lỗi nghi vấn, không sửa gốc.
- Curated:31 bài tiếng Việt/metadata, gồm 21 nhóm thành phần, ba bài androstenedione/DMAA-DMBA/Ephedra, tổng quan/nền tảng/bằng chứng/quản lý/an toàn/cách tiếp cận và hướng dẫn dự án.
- Table 1:21 hàng tiếng Anh JSON phục hồi bốn cột từ văn bản tab, giữ proposed mechanism và chú thích riêng. Không phải CSV tải từ website.
- Claims:30 mệnh đề tổng hợp có mục nguồn; không GRADE, không tự động dosing rule.
- Numeric contexts:24 quan sát định lượng chọn lọc, có đơn vị, nhóm, loại số liệu và hạn chế; không phải mọi con số trong source, không phải liều khuyến nghị cá nhân.
- Reference markers: chỉ số 1–219 và dòng nguồn; bibliography không có trong input và chưa khôi phục, không nhận đọc nghiên cứu nguyên thủy.
- Review issues:12 điểm cần rà soát; giải quyết trước khi duyệt các phần liên quan.
- Evaluation:42 tình huống đề xuất, không có kết quả thực nghiệm, không index làm knowledge.

## Provenance

SourceURL: https://ods.od.nih.gov/factsheets/ExerciseAndAthleticPerformance-HealthProfessional/
Trang gốc ghi UpdatedApril 1,2024; title/update/policy được xác định ngày 2026-10-07. Tệp người dùng không có ngày/title/bibliography/Disclaimer; chưa chứng minh thuộc đúng edition hoặc khớp từng byte với webpage hiện tại. Không coi ngày truy cập là cập nhật khoa học, pháp lý hoặc anti-doping.

## Sử dụng trong dự án

Đây là chuẩn bị corpus F 01, không thay đổi code hay dữ liệu nghiệp vụ. Cấu trúc data/knowledge tương thích cách tổ chức các bộ trước ở mức đề xuất; cần ánh xạ schema Content/Knowledge chính thức của dự án.

Chỉ bài kiến thức đã duyệt mới là ứng viên index. Raw, bảng source chưa duyệt, numeric contexts, markers, issue register, metadata, report, project_guidance và evaluation không đưa vào index trả lời. Bài historical_regulatory_information cần policy/source cập nhật riêng trước phục vụ quy tắc hiện hành. Toàn bộ bộ này DRAFT / retrieval_enabled=false, không tự thànhACTIVE khi chép vào repo.

Giữ role và scope: F 01 INFORMATION chỉ giải thích, không kê supplement cá nhân, lậpstack, sửa thuốc, đổimục tiêu hoặc kế hoạch. Quy tắc dự án là đề xuất, chưa xác nhận repo duyệt. Chỉ metadata/bookkeeping technical đã kiểm tra; còn cần chuyên gia, reviewer bản dịch, xử lý issue, chunk/embed và thử retrieval/answer/citation.

Khi đặt cạnh WHO/ISSN/NIDDK, giữ đường dẫn con và provenance theo source; không ghi đè README/giấy phép của bộ khác và không gộp license. Kiểm tra điều kiện tái sử dụng riêng cho bản tiếng Việt đã diễn giải trước công bố như tài liệu chính thức.
