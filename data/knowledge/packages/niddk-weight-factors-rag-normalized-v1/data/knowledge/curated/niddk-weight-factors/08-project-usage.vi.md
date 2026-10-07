# Hướng dẫn dùng bộ dữ liệu trong F01

Document ID: NIDDK-WEIGHT-FACTORS-2023-VI-08  
Trạng thái: DRAFT · Version: 1.0.0  
Nguồn: [NIDDK — Factors Affecting Weight & Health](https://www.niddk.nih.gov/health-information/weight-management/adult-overweight-obesity/factors-affecting-weight-health)  
Mục nguồn: Không có: hướng dẫn dự án do biên soạn  
Loại: project_guidance · Bản diễn giải tiếng Việt chưa duyệt chuyên môn

Tài liệu phục vụ F01 giải thích kiến thức chung về các yếu tố ảnh hưởng đến cân nặng. Các vai trò người dùng có thể đọc thông tin chung theo quyền truy cập chức năng của dự án; nội dung này không tạo quyền đọc hồ sơ cá nhân, bệnh sử, thuốc hoặc dữ liệu của học viên khác.

Kết quả có loại INFORMATION: giải thích có dẫn nguồn, không tạo/chấp nhận giáo án, không cập nhật Goal, Nutrition, Workout, Progress hoặc hồ sơ sức khỏe. Cá nhân hóa là luồng riêng, cần dữ liệu và quyền hợp lệ; không suy ra quyền chỉ từ nhãn Student/Trainer/Admin.

Khi câu hỏi thiếu tuổi, có thể trình bày hai khoảng tuổi của nguồn hoặc hỏi tuổi nếu cần chọn một khoảng; không tự điền tuổi. Không cần yêu cầu người dùng cung cấp bệnh sử hoặc danh sách thuốc để trả lời câu hỏi kiến thức chung. Nếu dữ liệu cá nhân được sử dụng ở luồng riêng, phải kiểm tra scope và nguồn/version theo quy tắc backend.

Chỉ các bài có content_role=general_knowledge hoặc overview mới là ứng viên corpus giải thích. Raw, metadata, tài liệu quy trình, báo cáo review và evaluation không được trộn vào index trả lời người dùng. Phần “Giới hạn sử dụng của dự án” trong bài thuốc/bệnh lý là chính sách đề xuất: tách vào policy riêng khi triển khai, không trích dẫn như câu của NIDDK.

Tất cả tài liệu hiện DRAFT, retrieval_enabled=false. Đây là metadata chuẩn bị dữ liệu, chưa phải schema Content/Knowledge chính thức đã được dự án duyệt. Backend cần ánh xạ sang Knowledge document/version, lưu source_id, document_id, version, snapshot hash và vị trí mục nguồn. Chỉ version đã duyệt và ACTIVE mới được retrieval ở môi trường phát hành. Thay đổi nguồn phải tạo version và rà soát lại.

Chưa tạo chunk/embedding, chưa chọn model, chưa chạy retrieval hoặc đánh giá câu trả lời. Sau khi duyệt, chia theo chủ đề nhưng luôn giữ nhóm tuổi/đơn vị/các từ chỉ khả năng/giới hạn cùng nội dung liên quan; tránh tách danh sách thuốc khỏi từ “một số” và tránh tách hành vi bù trừ khỏi cảnh báo không áp dụng.

Bộ này bổ sung kiến thức về cân nặng cho WHO vận động và ISSN protein. Không dùng nó để thay các tài liệu đó hoặc suy ra định lượng kcal, set/rep, protein. Giữ chính sách quyền tái sử dụng riêng của từng nguồn; không gộp tất cả dưới một giấy phép.
