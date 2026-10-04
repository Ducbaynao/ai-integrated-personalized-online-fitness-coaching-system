# Phạm vi phát hành Phase 2

**Dự án:** HỆ THỐNG HUẤN LUYỆN THỂ HÌNH TRỰC TUYẾN ĐƯỢC CÁ NHÂN HÓA TÍCH HỢP TRÍ TUỆ NHÂN TẠO\
**Phiên bản tài liệu:** 1.0\
**Trạng thái:** APPROVED — đã phê duyệt phạm vi và thiết kế, sẵn sàng lập backlog/triển khai\
**Ngày cập nhật:** 04/10/2026\
**Vị trí trong repository:** `docs/00-project-overview/phase-2-release-scope.md`\
**Người phê duyệt / ngày phê duyệt:** Chủ dự án (người dùng) / 04/10/2026\
**Baseline tham chiếu:** `d354e0a594b5e703d18a43264c0bf4325a0911a6` — snapshot lịch sử, không đại diện trạng thái nhánh hiện tại.

**Mục đích:** Xác định phạm vi phát hành, giới hạn, người sử dụng và điều kiện nghiệm thu Phase 2.

Tài liệu dành cho thành viên phát triển, người review và AI agent. Phạm vi và thiết kế trong bản 1.0 được chủ dự án phê duyệt ngày 04/10/2026 theo yêu cầu trong phiên làm việc: “bổ sung đầy đủ các quy tắc, hướng dẫn cần thiết cho agent và chuyển các tài liệu vừa thêm ở dạng draft thành đã được phê duyệt sẵn sàng cho dự án”. Phê duyệt này cho phép dùng làm baseline triển khai; không chứng minh prerequisite, code, test/evaluation hoặc bản phát hành đã hoàn thành. Danh tính người thực hiện và reviewer của từng công việc được ghi tại issue/PR, không suy ra từ người đang đọc. Student, Trainer và Admin là vai trò sản phẩm, không phải thành viên phát triển.

**Cách sử dụng:** đọc phạm vi phát hành → ma trận dữ liệu/quyền → kế hoạch triển khai. Tuân thủ `AGENTS.md` và hướng dẫn component trong repository khi thực hiện công việc. Tài liệu kế hoạch không tự cấp quyền Git, triển khai, xuất bản nội dung hoặc thay đổi dữ liệu. Khi tài liệu mới, tài liệu domain và code/schema khác nhau, ghi nhận xung đột và quyết định được phê duyệt trước phần triển khai bị ảnh hưởng; không tự chọn bản thuận tiện hơn. Nguồn trích dẫn tại baseline dùng để truy vết, không dùng để suy đoán các thay đổi về sau.

## 1. Căn cứ và cách đọc

Cơ sở thiết kế gồm mô tả hệ thống `mô tả ý tưởng đồ án.docx`, tên chính thức và các tài liệu repository tại baseline nêu trên. Các liên kết nguồn được cố định theo commit để kiểm tra lại được.

- **Kế thừa:** invariant của domain/kiến trúc đã có; chuẩn hóa tài liệu không thay đổi các invariant này.
- **Đã phê duyệt:** phạm vi, thứ tự, giới hạn và tiêu chí Phase 2 cùng D01–D07 là baseline bản 1.0. Từ “phải” là yêu cầu triển khai/nghiệm thu, không phải xác nhận đã có trong sản phẩm.
- **Target product:** mô tả hệ thống toàn diện; chức năng được mô tả ở đó không mặc nhiên thuộc bản phát hành Phase 2.
- **Trạng thái thực hiện:** được xác nhận bằng issue/PR và evidence test; tách biệt DRAFT/APPROVED của tài liệu.

Tài liệu liên quan: [dữ liệu và quyền](../08-ai/phase-2-feature-data-authority-matrix.md), [kế hoạch triển khai](../07-development/phase-2-implementation-plan.md).

## 2. Kết quả sản phẩm khi kết thúc Phase 2

Student tự tập và Trainer hợp lệ có thể dùng dữ liệu được phép để nhận giải thích, phân tích và đề xuất giáo án. Người có thẩm quyền xem căn cứ, giới hạn, sửa hoặc từ chối đề xuất; backend chỉ áp dụng sau khi xác nhận và kiểm tra lại. Student có thể nhập thực phẩm bằng văn bản/ảnh, sửa nhận diện và khẩu phần trước khi xác nhận Food Log. Admin có công cụ quản trị tri thức, giám sát AI và đánh giá phiên bản trong môi trường không tác động dữ liệu coaching.

**Giữ Nutrition AI, food recognition và portion estimation trong Phase 2 cuối cùng theo D01 đã phê duyệt**, đúng roadmap hiện có. Có thể demo giáo án trước, nhưng không gọi demo đó là hoàn thành toàn bộ Phase 2.

## 3. Nguyên tắc kế thừa bắt buộc

1. Chỉ có `SELF_DIRECTED` và `HUMAN_COACH`; AI không phải mode thứ ba.
2. Quyền giáo án thuộc Student khi tự tập, hoặc Trainer đủ điều kiện trong coaching. Quyền Goal và mục tiêu dinh dưỡng chiến lược thuộc Student trong cả hai mode.
3. Coaching Mode thuộc Coaching Period. Đổi PT, đổi Goal, pause/resume hoặc version mới không xóa lịch sử.
4. Student vẫn sử dụng được giáo án PT đã giao theo chính sách lưu giữ; PT cũ không tiếp tục sửa hoặc đọc dữ liệu mới.
5. Spring Boot kiểm tra quyền, điều phối và ghi dữ liệu nghiệp vụ. FastAPI không tự do đọc toàn bộ DB và không áp dụng thay đổi nghiệp vụ.
6. Progress Engine và quy tắc tính toán tạo chỉ số; LLM giải thích và đề xuất dựa trên chỉ số đó.
7. Thiếu dữ liệu là unknown; measurement đáng ngờ, log chưa đầy đủ và dữ liệu cũ không được diễn giải thành sự thật hiện tại.
8. RAG chỉ truy xuất Knowledge Version ACTIVE được phép; giữ nguồn và phiên bản.
9. Food recognition là ước lượng. Nutrition Database và phép tính xác định tính calories/macros sau khi món, đơn vị và lượng đã được xác định.
10. Admin quản trị nền tảng, không thay Student/Trainer quyết định coaching; replay chỉ tạo kết quả đánh giá.

## 4. Danh mục chức năng phát hành đã phê duyệt

Tất cả F01–F11 là **bắt buộc ở bản kết thúc Phase 2 theo phạm vi giới hạn dưới đây**. “Làm sau” trong bảng vẫn nằm trong Phase 2. Chức năng nền tảng cần có phần tối thiểu trước khi làm chức năng người dùng.

| ID | Chức năng và giới hạn bản đầu | Student | Trainer | Admin | Loại kết quả/tác động | Mốc hoàn thành |
|---|---|---|---|---|---|---|
| F01 | Hỏi/giải thích kỹ thuật, nguyên tắc tập có nguồn | Hỏi kiến thức, xem dữ liệu cá nhân được dùng nếu có | Hỏi kiến thức; dùng context học viên khi có scope | Quản trị nguồn qua F09 | Thông tin; không có thao tác áp dụng | P2-M2 luồng kiến thức chung; P2-M4 đầy đủ |
| F02 | Tạo Workout Plan Proposal có split, buổi, bài, set/rep hoặc thời lượng, nghỉ và lịch dự kiến | Yêu cầu/quyết định trong SELF_DIRECTED; xem bản PT chia sẻ trong HUMAN_COACH | Yêu cầu/quyết định cho học viên được quản lý | Không quyết định | Đề xuất; có thể tạo/kích hoạt plan qua command sau duyệt | P2-M3 |
| F03 | Thay bài, điều chỉnh volume/load trong kế hoạch hiện có | Theo quyền giáo án | Theo quyền giáo án | Không quyết định | Đề xuất thay đổi nhỏ hoặc version mới theo ý nghĩa nghiệp vụ | P2-M4 |
| F04 | Tóm tắt workout/progress, giải thích signal; hỗ trợ Trainer review | Xem tiến độ của mình | Xem học viên theo scope | Chỉ đánh giá dữ liệu đã được phép qua F10 | Thông tin; gợi ý hành động dẫn sang F03/F05, không tự sửa | P2-M4 |
| F05 | Đề xuất bố trí lại Planned Workout sau buổi bỏ lỡ | Quyền giáo án theo mode; có thể tham gia luồng lịch hẹn hiện có | Quyền giáo án và luồng lịch hẹn hợp lệ | Không quyết định | Áp dụng lịch Planned Workout sau duyệt; Appointment dùng change request riêng | P2-M4 |
| F06 | Phân tích mô tả bữa ăn bằng văn bản và khớp Food Database | Nhập, sửa, xác nhận | Xem log đã xác nhận nếu có nutrition scope | Không xác nhận hộ | Bản ước lượng → Student xác nhận → ghi Food Log | P2-M5 |
| F07 | Nhận diện món/thành phần và ước lượng khẩu phần từ một ảnh bữa ăn | Gửi ảnh, sửa, xác nhận | Xem log được chia sẻ; không mặc nhiên xem ảnh gốc | Quản trị vận hành với quyền riêng | Như F06; ảnh không tự trở thành dữ liệu đã xác nhận | P2-M5 |
| F08 | Giải thích dinh dưỡng đã ghi so với target có hiệu lực, chỉ rõ completeness | Xem của mình | Xem khi có nutrition scope | Không coaching | Thông tin/review signal; không tạo target mới trong bản này | P2-M5 |
| F09 | Import, review, publish/archive và truy xuất tri thức có phiên bản | Dùng nguồn ACTIVE qua AI | Dùng nguồn ACTIVE qua AI | Author/reviewer/publisher có quyền | Thay đổi nội dung hệ thống; không thay dữ liệu học viên | P2-M2 |
| F10 | AI Run, phiên bản prompt/model/rule, giám sát, evaluation/replay và tắt tính năng | Xem trạng thái/kết quả của request được phép | Như Student, theo scope | Vận hành/đánh giá theo permission | Run/audit/evaluation/config; replay không tạo đề xuất áp dụng | P2-M2 tối thiểu; P2-M6 đầy đủ |
| F11 | Feedback quyết định và kết quả tập thực tế để đánh giá chất lượng | Gửi đánh giá kết quả đã xem | Gửi đánh giá trong scope | Xem tổng hợp/đánh giá được phép | Ghi feedback; không tự train, tự đổi prompt hoặc tự sửa plan | P2-M3 quyết định; P2-M6 đầy đủ |

**Phân loại tác động:** F01/F04/F08 vẫn có thể ghi AI Run hoặc audit; “thông tin” nghĩa là không thay đổi Goal, Plan, Appointment, Measurement, Actual Workout, Food Log hoặc Nutrition Target. Feedback không thay thế Accept/Reject. Publish tri thức là thay đổi dữ liệu hệ thống, không phải human approval cho giáo án.

## 5. Thứ tự triển khai và công nghệ

| Thứ tự | Công việc | Công nghệ/vai trò, theo kiến trúc sẵn có |
|---|---|---|
| 1 — P2-M0 | Duyệt phạm vi, hợp đồng, tiêu chí; kiểm tra khả năng cung cấp dữ liệu Phase 1 | Tài liệu, OpenAPI/JSON Schema dự kiến; chưa chọn model vì tên gọi phổ biến |
| 2 — P2-M1 | Query có scope, chuẩn hóa dữ liệu, Progress và kiểm tra quyền/version | Spring Boot, PostgreSQL, các module domain hiện có |
| 3 — P2-M2 | Tri thức nhỏ có review; Context Builder, Rule Engine, AI Run, validation, evaluation runner tối thiểu và F01 kiến thức chung xuyên suốt | FastAPI/Python, pgvector, LLM/embedding qua adapter; object storage cho tài liệu |
| 4 — P2-M3 | Hoàn thành một luồng tạo giáo án từ đầu đến cuối | React Native → Spring → FastAPI → Spring → Student/Trainer duyệt |
| 5 — P2-M4 | Giải thích, điều chỉnh giáo án, phân tích tiến độ và lịch tập | Tái sử dụng pipeline; mở schema theo từng loại kết quả |
| 6 — P2-M5 | Food text trước, vision sau; phân tích nutrition | Vision/LLM nhận diện; Food DB + Nutrition Calculation Engine; Student xác nhận |
| 7 — P2-M6 | Feedback đầy đủ, evaluation, quyền, lỗi, vận hành và phát hành | Admin React, audit/metrics, kiểm thử liên luồng, feature flags |

Không yêu cầu fine-tune hoặc tự huấn luyện LLM ở Phase 2. Chọn model/embedding/vision bằng bài đánh giá có dữ liệu dự án và ngân sách, không xem khả năng trả JSON là bằng chứng chất lượng nghiệp vụ. Redis chỉ phục vụ cache/job tạm, PostgreSQL giữ trạng thái cần khôi phục.

## 6. Chủ động ngoài phạm vi

| Nội dung | Căn cứ và cách xử lý |
|---|---|
| Commerce, payment, subscription, marketplace, calendar/email integration | Kế thừa roadmap Phase 3; không kéo vào để hoàn thành AI |
| Pose estimation, đếm rep, sửa tư thế thời gian thực, thiết bị/health platform integrations | Kế thừa roadmap Phase 4; không gộp với food vision |
| AI tự áp dụng giáo án, tự sửa Goal/Target/Actual Workout | Bị cấm bởi authority model, không phải backlog để bật sau |
| Admin chấp nhận đề xuất giáo án thay người dùng | Bị cấm bởi authority model |
| Tự train/retrain sau khi có feedback; fine-tuning dashboard | Hoãn trong bản phát hành; Phase 2 chỉ evaluation và thay cấu hình đã review |
| AI tạo Fitness Goal Proposal hoặc strategic Nutrition Target Proposal định lượng | Hoãn trong bản phát hành riêng phần AI sinh đề xuất này. Không bỏ Goal/Nutrition Proposal thủ công của target product; F08 chỉ hỗ trợ review target hiện có |
| Meal plan tự động nhiều ngày, kê chế độ điều trị/phục hồi, chẩn đoán từ hồ sơ/ảnh | Không phát hành trong phạm vi AI Assistance ban đầu |
| Chatbot mở vô hạn, duyệt web trực tiếp khi trả lời, autonomously dùng công cụ ghi DB | Ngoài phạm vi bản phát hành; F01 là hỏi đáp kiến thức đã quản trị |
| Nhận diện mọi món ăn/ingredient ẩn, xác định chính xác gram từ ảnh bất kỳ | Không cam kết. Bản đầu giới hạn catalog được kiểm thử và yêu cầu Student xác nhận lượng |
| Tự tối ưu cả chu kỳ dài của Goal, cam kết đạt mục tiêu hoặc tự tăng tải liên tục | Ngoài phạm vi bản phát hành; F02 tạo một tuần lịch mẫu có ngày hiệu lực, các lần điều chỉnh phải được review |
| Tự đổi hàng loạt recurring Appointment từ AI | Hoãn trong bản phát hành; F05 thao tác một Planned Workout mỗi lần và mở workflow Appointment riêng khi cần |

Các giới hạn/hoãn trong bảng được chấp thuận trong baseline 1.0, đặc biệt D02–D05. Chúng giới hạn bản phát hành, không xóa khả năng của target product. Mở rộng hoặc giảm phạm vi sau đó cần quyết định thay đổi được ghi nhận.

## 7. Giới hạn đầu vào và trải nghiệm bản đầu

- Giao diện và bộ kiểm thử ưu tiên tiếng Việt; nguồn có thể tiếng Việt/Anh nhưng câu trả lời phải gắn đúng nguồn. Hỗ trợ ngôn ngữ khác chưa là release gate.
- F02 tạo một tuần lịch mẫu cho các ngày người dùng chọn, có ngày bắt đầu và thời lượng buổi. Không sinh cứng cả 90/120 ngày. Backend chỉ tạo các buổi trong phạm vi người có quyền xác nhận; không tự áp lịch hẹn với PT.
- Dùng Exercise ID ACTIVE có thật. Hướng dẫn kỹ thuật lấy từ tri thức; tư cách của bài tập lấy từ catalog. Không dùng tên bài tự do làm tham chiếu để ghi plan.
- Khai báo “không có thiết bị”, “chưa từng tập”, “không có hạn chế được biết” là giá trị do người dùng xác nhận; khác với chưa trả lời. Không dùng RAG để bù hồ sơ còn thiếu.
- Trong HUMAN_COACH, đề xuất giáo án do Trainer yêu cầu trước hết là bản nháp của Trainer; Student xem khi Trainer chia sẻ/giao. Student không tự kích hoạt kế hoạch AI cạnh tranh với plan của PT.
- F06/F07 chỉ Student yêu cầu nhận diện và xác nhận trong bản đầu. Nutrition sharing không tự cấp quyền xem ảnh bữa ăn gốc hoặc kết quả AI nháp.
- F08 không có target thì vẫn tóm tắt dữ liệu đã ghi, nhưng không đánh giá đạt/thiếu so với target. Không có log hoặc log PARTIAL thì không kết luận thiếu ăn.
- Khi AI lỗi hoặc tính năng bị tắt, các luồng Phase 1 tạo plan/log bằng tay phải tiếp tục dùng được.
- F07 chỉ cam kết portion estimation trên tập tình huống hỗ trợ đã chốt, có lượng tham chiếu và nguồn chuyển đổi đơn vị. Báo coverage cùng sai số; không lấy việc Student nhập tay thành bằng chứng AI đã ước lượng được.
- V1 dùng Accept-and-Apply nguyên tử cho F02/F03/F05 theo ma trận mục 1.7. Không thêm trạng thái DB `APPLY_FAILED`; lỗi apply ghi riêng và rollback. Retry chỉ trả kết quả cũ khi cùng ý định/revision/payload; bản sửa khác phải conflict hoặc đi workflow mới.
- F06/F07 dùng Food Confirmation riêng: review từng item, xác nhận nguyên tử nhóm được chọn; item không chọn vẫn draft. Nếu một item được chọn lỗi thì cả nhóm không ghi. Catalog thay đổi cần preview mới và Student xác nhận lại. Chỉ ghi confirmed log sau transaction thành công, không tự đánh dấu ngày COMPLETE.
- UI: INFORMATION chỉ xem; PROPOSAL có Accept/Reject recommendation; ESTIMATE có Confirm/Correct thuộc Nutrition, không gọi API Apply recommendation.
- RAG kiểm tương thích dimension và không gian embedding; query/index phải dùng cùng model/cấu hình tương thích. Schema baseline là vector(1536); thay dimension cần migration/index đã review.
- F01 kiến thức chung chạy xuyên suốt tại P2-M2 để kiểm chứng pipeline sớm. F02 tại P2-M3 là mốc đầu tiên có phê duyệt và thay đổi dữ liệu nghiệp vụ, không phải luồng AI đầu tiên.

## 8. Điều kiện kết thúc Phase 2

Chỉ coi là hoàn thành khi đồng thời:

1. F01–F11 đạt tiêu chí riêng trong ma trận; có UI cần thiết cho Student, Trainer và Admin tương ứng.
2. Không có đường gọi từ client trực tiếp đến AI để vượt kiểm tra Spring; không có quyền ghi nghiệp vụ từ model.
3. Tất cả kiểm thử quyền, source conflict, lịch sử, dữ liệu thiếu và xác nhận phải đạt. Lỗi mô hình không được tạo đề xuất có thể áp dụng.
4. RAG có tập nguồn đã review, truy xuất đúng version và không lấy DRAFT/ARCHIVED; benchmark và kết quả được lưu.
5. Model, prompt, rule policy, schema, knowledge, nutrition catalog dùng trong bản phát hành có tham chiếu phiên bản xác định.
6. Đề xuất cũ sau thay đổi dữ liệu liên quan hoặc quyền phải bị chặn/đánh giá lại như ma trận, không ghi đè quyết định mới.
7. Food Log có identity/quantity được Student xác nhận, tính toán xác định và provenance; ảnh hoặc text không tự biến thành log đã xác nhận.
8. Admin replay không ghi domain state; nhật ký và giao diện không làm lộ dữ liệu ngoài quyền.
9. Báo cáo chất lượng, độ trễ, chi phí theo chức năng, giới hạn catalog, xử lý sự cố và rollback cấu hình đã được review. Ngưỡng đề xuất ở kế hoạch không được báo cáo thành số đo thực tế.
10. Các quyết định D01–D07 dưới đây có người duyệt; không có câu hỏi quyền truy cập hoặc chính sách nghiệp vụ quan trọng còn bỏ ngỏ.
11. Portion estimation có tập hỗ trợ, ground truth, coverage và sai số đã đo theo kế hoạch mục 11.2. Luôn trả unknown/manual không đủ để nghiệm thu khả năng ước lượng; nếu chưa đạt phải ghi experimental/chưa hoàn thành hoặc duyệt thay đổi phạm vi.
12. Có bảng bàn giao Phase 1 và bảng bổ sung cho AI riêng, kèm evidence và owner. Chưa có bằng chứng prerequisite thì chưa cam kết lịch tích hợp tương ứng.
13. Retry payload khác, Food Confirmation rollback/retry, UI theo resultType và embedding mismatch đạt T25–T28. Tài liệu lifecycle/API/schema được đồng bộ theo V1 được duyệt trước tích hợp, không còn hai cách diễn giải trạng thái trái nhau.

## 9. Các quyết định phạm vi đã phê duyệt

| ID | Phương án đã phê duyệt | Tác động |
|---|---|---|
| D01 | Giữ cả workout AI và nutrition text/image trong Phase 2 cuối | Demo giáo án trước không đồng nghĩa kết thúc Phase 2 |
| D02 | F02 sinh một tuần mẫu; F05 một Planned Workout/lần | Giảm độ phức tạp; không tự sinh cả hành trình hoặc đổi recurring series |
| D03 | HUMAN_COACH: Trainer yêu cầu/quyết định plan; chia sẻ nháp mới cho Student xem | Quyền xem không đồng nhất với quyền quyết định; kế hoạch đã giao tiếp tục được Student dùng |
| D04 | F06/F07 Student-only; ảnh gốc có scope riêng; V1 xác nhận nguyên tử nhóm item được chọn | Một item lỗi làm cả nhóm không ghi; item bỏ chọn giữ draft; Trainer không xác nhận hộ |
| D05 | Hoãn AI sinh Goal/strategic Nutrition Target Proposal; F08 chỉ phân tích/review | Giới hạn bản phát hành đã phê duyệt; giữ trong scope; workflow thủ công vẫn giữ |
| D06 | Benchmark, catalog thử nghiệm và rule policy phải được review trước mở tính năng | Có thể tiếp tục phát triển bằng fixture, không bật một chức năng khi chính sách chưa được duyệt |
| D07 | Gán owner/reviewer theo issue; migration, authority và contract dùng chung có trách nhiệm review/tích hợp rõ ràng theo kế hoạch mục 9 | Phân công cá nhân quản lý riêng; đổi người thực hiện không đổi quy tắc nghiệp vụ |

### 9.1. Ghi nhận phê duyệt

D01–D07 được duyệt theo yêu cầu trực tiếp của chủ dự án trong phiên làm việc ngày 04/10/2026; chưa có issue/PR phê duyệt riêng. Khi có issue/PR, bổ sung liên kết để truy vết mà không thay ngày phê duyệt. Quyết định thay đổi sau này phải ghi nội dung, người duyệt, ngày và version. Thông qua phân công kỹ thuật không đồng nghĩa phê duyệt chuyên môn. Khi có quyết định thay đổi phạm vi, cập nhật đồng thời các contract/milestone bị ảnh hưởng.

| Quyết định | Trạng thái | Người duyệt | Ngày / tham chiếu |
|---|---|---|---|
| D01 | APPROVED — bản 1.0 | Chủ dự án (người dùng) | 04/10/2026; yêu cầu phê duyệt trong phiên làm việc này |
| D02 | APPROVED — bản 1.0 | Chủ dự án (người dùng) | 04/10/2026; yêu cầu phê duyệt trong phiên làm việc này |
| D03 | APPROVED — bản 1.0 | Chủ dự án (người dùng) | 04/10/2026; yêu cầu phê duyệt trong phiên làm việc này |
| D04 | APPROVED — bản 1.0 | Chủ dự án (người dùng) | 04/10/2026; yêu cầu phê duyệt trong phiên làm việc này |
| D05 | APPROVED — bản 1.0 | Chủ dự án (người dùng) | 04/10/2026; yêu cầu phê duyệt trong phiên làm việc này |
| D06 | APPROVED — bản 1.0 | Chủ dự án (người dùng) | 04/10/2026; yêu cầu phê duyệt trong phiên làm việc này |
| D07 | APPROVED — bản 1.0 | Chủ dự án (người dùng) | 04/10/2026; yêu cầu phê duyệt trong phiên làm việc này |

## Tài liệu hiện hành trong repository

Đọc các tài liệu hiện hành dưới đây khi thực hiện công việc. Liên kết theo commit ở phần nguồn là snapshot để truy vết; nếu code/contract mới khác baseline, ghi nhận khác biệt trước khi triển khai phần chịu ảnh hưởng. APPROVED xác nhận phạm vi và thiết kế; không chứng minh API đã triển khai hoặc test đã đạt. Các invariant đã xác nhận tiếp tục có hiệu lực.

- [Roadmap](scope-and-roadmap.md)
- [AI subsystem](../08-ai/README.md)
- [Quyền nghiệp vụ](../02-domain/permissions-and-authority.md)

## 10. Nguồn đối chiếu

- Mô tả hệ thống tham chiếu (`mô tả ý tưởng đồ án.docx`): mục 2, 6, 8–9, 14–24, 29–49, 53, 85–91. Tên hiển thị lấy từ `tên-chính-thức-của-dự-án.txt`.
- [Roadmap](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/00-project-overview/scope-and-roadmap.md).
- [AI capabilities](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/08-ai/README.md).
- [Authority](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/02-domain/permissions-and-authority.md).
- [Acceptance criteria](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/01-requirements/acceptance-criteria.md).
