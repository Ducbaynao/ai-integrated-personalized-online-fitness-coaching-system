# Hướng dẫn nhập bộ WHO 2020 vào F01

Document ID: WHO-PA-SB-2020-VI-16 · Version: 1.0.0 · Language: vi · Status: DRAFT

Source: WHO-PA-SB-2020, PDF pages 4, 27, 34.

## Quy ước chuẩn bị dữ liệu của dự án — không phải khuyến nghị WHO
Các quy tắc ở mục này là hướng dẫn xử lý dữ liệu được đề xuất cho dự án. Không gán chúng cho WHO.

- Mọi bài/metadata đang DRAFT, reviewer chưa xác định; retrieval_enabled=false. File trong curated chưa phải Knowledge ACTIVE.
- F01 trả thông tin có citation, không ghi Workout Plan, Goal hoặc Nutrition Log. Kiến thức có số định lượng vẫn là INFORMATION, không tự biến thành PROPOSAL.
- Các trường JSON trong bộ này là format chuẩn bị dữ liệu, không phải API hay DB contract đã được phê duyệt. Importer cần mapping với contract thật.
- Chọn bản tiếng Việt hoặc bản gốc tiếng Anh cho một nhánh retrieval; không index đồng thời toàn văn, các phần tiếng Anh và mọi bản tiếng Việt trùng ý mà không có chính sách chống trùng.
- Mỗi chunk sau này phải kế thừa nguồn/phiên bản/trang, nhóm đích, điều kiện và trạng thái duyệt. Không chia rời ngưỡng số khỏi điều kiện và GRADE.
- Metadata tuổi, thai kỳ, tình trạng bệnh/khuyết tật dùng chọn đúng nội dung khi câu hỏi xác định nhóm, không biến thành dữ liệu hồ sơ đã biết. Câu hỏi chung có thể giải thích các nhóm khác nhau; câu hỏi cá nhân chưa đủ dữ liệu phải nêu giới hạn.
- Nhóm đặc thù cần review đúng chuyên môn trước sử dụng thật. Việc lưu đầy đủ nguồn không đồng nghĩa dự án đã hỗ trợ coaching lâm sàng.
- Danh mục tham khảo, danh tính tác giả và quy trình chính sách giữ để audit/review, không index mặc định vào F01 phổ thông.
- Bộ câu hỏi evaluation nằm riêng; không ingest vào corpus hay dùng holdout để chỉnh prompt. Dữ liệu kiểm thử ở bộ này là ứng viên chưa đo.
- Chưa tạo chunk, embedding, chính sách Rule Engine hoặc migration. Kiểm tra model/dimension và không gian embedding tương thích khi triển khai.
- Giấy phép phi thương mại của nguồn cần được tôn trọng. Chưa xác nhận quyền dùng bộ chuyển ngữ trong bản thương mại.

## Truy vết nguồn và trạng thái

- [Nguồn tiếng Anh, PDF trang 4](../../source-normalized/who-2020-fulltext.en.md#pdf-page-004); phạm vi đến trang 4.
- [Nguồn tiếng Anh, PDF trang 27](../../source-normalized/who-2020-fulltext.en.md#pdf-page-027); phạm vi đến trang 27.
- [Nguồn tiếng Anh, PDF trang 34](../../source-normalized/who-2020-fulltext.en.md#pdf-page-034); phạm vi đến trang 34.

https://www.who.int/publications/i/item/9789240015128

Chưa có reviewer chuyên môn; chưa được publish/activate. Nội dung tiếng Việt là bản diễn giải chuẩn hóa, không phải bản dịch chính thức hoặc bản dịch toàn văn.

Bản chuyển ngữ và biên soạn này không do Tổ chức Y tế Thế giới (WHO) thực hiện. WHO không chịu trách nhiệm về nội dung hoặc độ chính xác của bản này. Bản tiếng Anh gốc là bản có giá trị ràng buộc và xác thực.

Licence: CC BY-NC-SA 3.0 IGO.
