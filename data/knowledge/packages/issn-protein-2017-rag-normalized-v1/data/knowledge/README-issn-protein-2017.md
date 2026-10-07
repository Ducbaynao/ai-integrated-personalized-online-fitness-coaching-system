# ISSN protein and exercise 2017 — bộ chuẩn hóa cho F01

Version 1.0.0 · 2026-10-06 · DRAFT / chưa duyệt chuyên môn / chưa ACTIVE.

## Nội dung
- PDF gốc 25 trang giữ nguyên byte và toàn văn tiếng Anh có mốc từng trang.
- 25 file nguồn theo trang để đối chiếu, không phải chunk ngữ nghĩa.
- 18 bài diễn giải tiếng Việt kèm metadata.
- 13/13 position statements từ Abstract được cấu trúc hóa, giữ đơn vị/nhóm đích/giới hạn; không tự gán GRADE.
- 224 mục tham khảo trong bài được bảo toàn; chưa nhập toàn văn các nghiên cứu đó.
- 30 case kiểm thử ứng viên, chưa chạy benchmark; bản đồ trang, manifest, báo cáo review và giấy phép.

Toàn bộ dữ liệu nguồn được bảo toàn, nhưng đây không phải bản dịch toàn văn 25 trang. Phần tiếng Việt diễn giải các chủ đề; chi tiết trial/statistics/declarations nằm ở nguồn tiếng Anh/PDF. Chủ đề có thể bắt đầu giữa trang nên phạm vi trang trong manifest có chồng nhau; các page exports vẫn duy nhất 1–25.

## Sử dụng trong dự án
Đây là cấu trúc corpus đề xuất, không phải API/DB contract đã duyệt. Thư mục curated/issn-protein-2017 và raw/issn-protein-and-exercise-2017.pdf tách nguồn WHO để có thể ghép không ghi đè.

1. Đọc LICENSE-ATTRIBUTION.md và review-report.md.
2. Review các bài tiếng Việt cùng PDF/nguồn, đặc biệt số định lượng, an toàn, soy và thông tin không nhất quán.
3. Chốt reviewer chuyên môn, phạm vi F01, mapping metadata tới Knowledge Document/Version thật.
4. Nhập/publish đúng workflow và quyền; retrieval chỉ dùng phiên bản ACTIVE đã duyệt.
5. Chọn representation và policy chống trùng; không index fulltext + page files + consolidated + curated cùng lúc.
6. Chunk theo ngữ nghĩa, giữ điều kiện và nguồn qua ranh giới trang; sau đó embedding/retrieval/generation evaluation.
7. evaluation/ không đưa vào corpus. Quyết định development/holdout trước chỉnh prompt.

## File và vai trò
| Đường dẫn | Vai trò |
|---|---|
| raw/issn-protein-and-exercise-2017.pdf | Bản gốc đối chiếu |
| source-normalized/issn-protein-2017-fulltext.en.md | Toàn văn tiếng Anh |
| source-normalized/issn-protein-2017/page-*.en.md | Nguồn theo trang, cùng dữ liệu fulltext |
| curated/issn-protein-2017/*.vi.md | Kiến thức Việt DRAFT |
| curated/issn-protein-2017/*.metadata.json | Metadata chuẩn bị dữ liệu |
| issn-protein-2017-position-statements.vi.json | 13 tuyên bố cấu trúc, không phải policy thực thi |
| issn-protein-2017-source-manifest.json | Provenance, checksum, bản đồ chủ đề |
| issn-protein-2017-source-page-map.json | 25 trang vật lý/trang bài |
| evaluation/f01-issn-protein-2017-candidate-cases.jsonl | 30 câu hỏi thử có nguồn kỳ vọng |

## Phạm vi và trạng thái
Nguồn 2017 cho người khỏe mạnh có tập. Không kê protein cá nhân hoặc tự đổi Nutrition Target. Giữ g/kg/ngày, g/kg/lần, g/kg/giờ, mg/g và protein/bột riêng. MPS cấp thời không bằng tăng cơ dài hạn. Bằng chứng không thấy hại trong healthy sample không mở rộng mọi bệnh/liều/thời gian.

Licence ISSN CC BY 4.0 khác WHO CC BY-NC-SA 3.0 IGO. Giữ attribution/changelog và giấy phép riêng khi ghép. Không sửa repository, DB, migration hoặc tạo embedding trong công việc chuẩn hóa này.

Nguồn: Jäger R, Kerksick CM, Campbell BI, et al. International Society of Sports Nutrition Position Stand: protein and exercise. Journal of the International Society of Sports Nutrition. 2017;14:20. DOI: 10.1186/s12970-017-0177-8.
