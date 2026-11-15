# Audit tiếng Việt cho Mobile (worktree B06)

Nguồn kiểm ban đầu: cây `apps/mobile/src` tại branch `feature/b06-phase1-schedule-appointment-closure`, HEAD `db289f3ef66c4c8e49f4943b0888cc2ba5b9cbcb`. Sau khi tích hợp `main` tại `6716f56`, inventory được tạo lại từ source thực tế: 81 tệp route/layout Expo Router (77 route, 4 layout), 56 tệp `*Screen.tsx`, 9 component dùng chung trong `src/components`, 21 nhóm feature. Không suy màn đã có từ tài liệu thiết kế.

- [Danh sách từng route/layout và screen/feature được import](mobile-vi-route-inventory.tsv)
- [Danh sách 94 screen/view/component và nhánh trạng thái phát hiện từ source sau tích hợp](mobile-vi-source-inventory.tsv)
- [Ma trận 537 cặp màn/component → chuỗi tiếng Anh ban đầu → cách xử lý ở baseline B06](mobile-vi-string-matrix.tsv)
- Cách tái kiểm: trong `apps/mobile`, chạy `npm run audit:ui-strings` và `npm run audit:ui-mixed`. Script thứ nhất rà JSX, nhãn accessibility, placeholder, literal và template; script thứ hai tìm câu tiếng Việt pha thuật ngữ tiếng Anh. Kết quả cần được duyệt theo ngữ cảnh, vì mã API, tên biến và dữ liệu người dùng cũng có thể được liệt kê.

## Coverage theo màn và trạng thái có trong source

| Nhóm màn thực tế | Chuỗi và trạng thái đã audit | Xử lý |
| --- | --- | --- |
| Auth, Home, chọn vai trò | Đăng nhập, đăng ký, xác minh email, session gate, Home Student/Trainer, lựa chọn onboarding; nút, lỗi 400/401, nhãn truy cập | Việt hóa nhãn và lỗi; giữ route, capability và thông tin thiết bị gửi API |
| Onboarding và hồ sơ Student/Trainer | Loading, không có hồ sơ, lỗi mạng/403/409, xem, sửa, validation, nộp/xét duyệt hồ sơ huấn luyện viên, tài liệu xác minh | Việt hóa toàn bộ copy cố định, đơn vị, lựa chọn giới tính/kinh nghiệm, trạng thái xác minh/hoạt động/quyền; giữ diff safe area và Home của P7-005 |
| Exercise | Danh sách, bộ lọc, picker, chi tiết, biến thể, rỗng/lỗi/tải thêm | Các màn đã dùng copy Việt; rà fallback và nhãn. Tên bài tập từ catalog được giữ theo dữ liệu gốc |
| Goal và đề xuất Goal | Overview, targets, history, proposals, chi tiết đề xuất, tạo mục tiêu/phiên bản/chuyển hành trình, Student quyết định và Trainer đề xuất; loading, rỗng, validation, 409 | Việt hóa copy, tab, badge, enum hiển thị, ngày và alert; giữ mã `GoalLifecycleStatus`, `ProposalStatus`, goal type code và quyết định API |
| Coaching, chia sẻ và Attention | Hub, tìm huấn luyện viên/học viên, quan hệ, quyền chia sẻ, goal của học viên, attention; trạng thái quyền và xác nhận | Chuỗi cố định đã Việt hóa; câu pha `backend` được đổi thành “hệ thống”; quyền Student/Trainer không đổi |
| Workout Plan và đề xuất AI | Danh sách, detail, builder, lịch sử, giáo án Trainer, đề xuất và điều chỉnh AI; nháp, áp dụng, xung đột, thiếu nguồn | Việt hóa đơn vị, validation, cảnh báo, enum đề xuất hiển thị và câu pha tiếng Anh; giữ mã trạng thái, idempotency và nguồn gốc bản ghi |
| Workout Execution/History, Recurrence | Buổi hiện tại, nhật ký, lịch sử, series; rỗng, lỗi, xác nhận, hiệp và RPE | Việt hóa đơn vị “lần”, “hiệp giảm tải” và câu hướng dẫn; giữ RPE và tên bài tập |
| Schedule, Appointment, change/schedule proposals | Danh sách, chi tiết, tạo, đổi/hủy, xác nhận theo đợt, linked appointment, Trainer/Student proposal; lỗi quyền/409/đã qua giờ | Việt hóa copy pha enum `ACTIVE`, `PENDING`, `CONFIRMED`, `ACCEPT/REJECT`; giữ enum và thời điểm/API, không đổi date picker |
| Nutrition và Nutrition Strategy | Nhật ký, danh mục, đơn vị, correction, mục tiêu dinh dưỡng, ngày đã chốt, lịch sử, đề xuất; rỗng/lỗi/xác nhận | Việt hóa `catalog`, `target`, `nutrient`, `correction`, `Food Log`, nhãn đơn vị và accessibility; giữ ID, version, provenance và giá trị null là chưa rõ |
| Chat, linked resources, inbox, reminders | Hội thoại, tệp đính kèm, tài nguyên liên kết, thông báo, cấu hình thông báo đẩy và nhắc lịch; trạng thái quyền/lỗi/tệp | Rà nhãn, thông báo, accessibility; Việt hóa “push” thành “thông báo đẩy”; giữ tên file và dữ liệu tin nhắn |
| AI knowledge và progress summary | Tra cứu, dẫn nguồn, giới hạn, trạng thái chạy, số liệu tiến độ, lỗi mạng/timeout/403/404 | Copy cố định và lỗi được Việt hóa; dữ liệu/đầu ra từ AI và nguồn trích dẫn được giữ theo nội dung trả về |
| Progress và Measurements được bổ sung từ `main` | Dashboard, biểu đồ, ghi/sửa số đo, ảnh tiến độ và màn Trainer xem tiến độ | Đã rà source sau tích hợp; nhãn UI dùng tiếng Việt, bộ quét còn mã enum/định dạng và thuật ngữ chuyên ngành. Chưa kiểm trên Pixel_7 |

Các màn trong bảng được audit bằng source và test, **chưa xác nhận trực tiếp trên Pixel_7**. Không có bằng chứng thao tác/chụp ảnh thiết bị cho từng route trong đợt này. Các nhánh phụ thuộc dữ liệu server thật hoặc quyền từng tài khoản không thể kiểm trực tiếp bằng Jest.

## Quy tắc hiển thị và phần còn lại

- Dùng `src/localization/displayLabels.ts` cho các enum Student, Trainer, Goal và đề xuất AI; chỉ đổi nhãn, không đổi giá trị gửi/lưu qua API. Lỗi HTTP phổ biến được hiển thị bằng câu tiếng Việt; mã và chi tiết gốc vẫn nằm trong `ApiError.errorResponse` để xử lý nghiệp vụ.
- Giữ tên riêng/viết tắt theo lựa chọn của người dùng: **Digital Fitness, USDA FDC, RPE, URL**; tương tự giữ `Email`, `UTC`, `IANA`, `KiB`, `ID`, tên định dạng tệp, tên thiết bị/hệ điều hành và chuỗi định dạng ngày `YYYY-MM-DD` / `YYYY-MM-DDTHH:mm`. Ví dụ `coach-alex`, `hocvien@example.com`, `Alex Smith` là dữ liệu mẫu. `Trang:` và `→ sau` đã là tiếng Việt nhưng bị bộ quét ASCII liệt kê.
- 20 literal/template tiếng Anh còn lại do bộ quét nêu là thông điệp lập trình nội bộ, chuỗi so khớp lỗi backend, thông tin thiết bị gửi API, khóa/định dạng hoặc kiểm tra UUID; không được render trực tiếp như nhãn giao diện. Mã route, enum, ID, field name và API contract tiếp tục giữ nguyên. Tên bài tập, tên thực phẩm, nội dung chat, lý do do người dùng/quản trị nhập, văn bản AI và tên nguồn có thể là tiếng Anh vì là dữ liệu động; không dịch tùy tiện để tránh đổi ý nghĩa.
- `audit:ui-mixed` còn 4 kết quả được giữ: nhãn URL/ví dụ `coach-alex` trong hồ sơ huấn luyện viên và câu giải thích Progress Engine/AI ở màn Progress.

## Giới hạn xác minh

Test tự động kiểm tra ánh xạ nhãn, validation, loading/error/edit/navigation/profile, quyền và các vòng đời hiện có; chúng không xác nhận bố cục/chạm trên Pixel_7 hoặc nội dung từ dữ liệu server thật. Bốn file đã có diff P7-005 trước khi bắt đầu được giữ nguyên phần safe area/navigation; đợt này chỉ bổ sung Việt hóa và kỳ vọng test liên quan.
