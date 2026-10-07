# Cách dùng bộ ISSN trong F01

Document ID: ISSN-PROTEIN-EXERCISE-2017-VI-18 · Version: 1.0.0 · Language: vi · Status: DRAFT

Source: ISSN-PROTEIN-EXERCISE-2017, PDF pages 1–2, 19–20.

## Quy tắc xử lý dữ liệu đề xuất cho dự án — không phải tuyên bố của ISSN
- File curated/metadata đang DRAFT, reviewer=null và retrieval_enabled=false; không tạo Knowledge ACTIVE bằng thao tác chép file.
- Nội dung là INFORMATION, gồm giải thích số khuyến nghị nguồn. Không tự đặt/chỉnh Nutrition Target, Goal hoặc ghi Nutrition Log từ câu trả lời.
- Chỉ tính mục tiêu cá nhân khi có luồng/contract/quyền được phê duyệt và dữ liệu phù hợp; không coi việc lưu một dải g/kg là quyền tự tạo target.
- Tách mức ngày / lần ăn / giờ vận động, mg / g, khối lượng cơ thể / khối nạc và g protein / g bột. Đặc biệt 0,25 g/kg/lần khác 0,25 g/kg/giờ sức bền.
- Metadata và JSON ở đây là format chuẩn bị corpus, không phải API/DB contract. Không tự thêm field vào migration đã áp dụng.
- Chọn curated tiếng Việt hoặc nguồn tiếng Anh có policy chống trùng; không index đồng thời fulltext, 25 page files và bản tổng hợp tiếng Việt.
- Page file là công cụ đối chiếu, không phải chunk ngữ nghĩa. Khi chunking giữ cả điều kiện, nhóm đích, nguồn, phiên bản, trang và dấu [n]; không cắt giữa câu hoặc phần đi qua trang.
- Không gán GRADE WHO cho ISSN; để null, lưu mô tả 'preliminary', 'mixed', 'acute' theo ngữ cảnh.
- File reference-only và hướng dẫn dự án không index mặc định vào kiến thức phổ thông. evaluation chỉ dùng kiểm thử, không đưa vào corpus.
- Khi ghép với WHO: giữ source ID và licence riêng; WHO CC BY-NC-SA 3.0 IGO không được đổi thành CC BY chỉ vì bộ ISSN dùng CC BY 4.0.
- Chưa chạy retrieval/generation benchmark, chưa tạo embeddings; model/dimension/space phải theo cấu hình được chốt của dự án.
- Review chuyên môn và dịch/diễn giải trước publish. Dữ liệu 2017 chưa chứng minh tính hiện hành 2026.

## Nguồn và review

- [Nguồn tiếng Anh, PDF trang 1](../../source-normalized/issn-protein-2017-fulltext.en.md#pdf-page-001); phạm vi đến trang 2.
- [Nguồn tiếng Anh, PDF trang 19](../../source-normalized/issn-protein-2017-fulltext.en.md#pdf-page-019); phạm vi đến trang 20.

Jäger R, Kerksick CM, Campbell BI, et al. International Society of Sports Nutrition Position Stand: protein and exercise. Journal of the International Society of Sports Nutrition. 2017;14:20. DOI: 10.1186/s12970-017-0177-8.

https://doi.org/10.1186/s12970-017-0177-8

Licence: CC BY 4.0.

Bản tiếng Việt do AI hỗ trợ diễn giải và chuẩn hóa từ bài năm 2017; không phải bản dịch chính thức của ISSN hoặc tác giả. Chưa được review chuyên môn. Các thay đổi gồm chuyển ngữ, tổ chức chủ đề và thêm hướng dẫn sử dụng trong dự án; đối chiếu bản gốc khi cần chi tiết.
