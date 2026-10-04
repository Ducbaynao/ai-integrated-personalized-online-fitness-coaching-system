# Dữ liệu và quyền từng chức năng Phase 2

**Dự án:** HỆ THỐNG HUẤN LUYỆN THỂ HÌNH TRỰC TUYẾN ĐƯỢC CÁ NHÂN HÓA TÍCH HỢP TRÍ TUỆ NHÂN TẠO\
**Phiên bản tài liệu:** 1.0\
**Trạng thái:** APPROVED — đã phê duyệt phạm vi và thiết kế, sẵn sàng lập backlog/triển khai\
**Ngày cập nhật:** 04/10/2026\
**Vị trí trong repository:** `docs/08-ai/phase-2-feature-data-authority-matrix.md`\
**Người phê duyệt / ngày phê duyệt:** Chủ dự án (người dùng) / 04/10/2026\
**Baseline tham chiếu:** `d354e0a594b5e703d18a43264c0bf4325a0911a6` — snapshot lịch sử, không đại diện trạng thái nhánh hiện tại.

**Mục đích:** Định nghĩa đầu vào, nguồn dữ liệu, quyền, xử lý dữ liệu thiếu và tính nhất quán khi áp dụng từng chức năng AI.

Tài liệu dành cho thành viên phát triển, người review và AI agent. Phạm vi và thiết kế trong bản 1.0 được chủ dự án phê duyệt ngày 04/10/2026 theo yêu cầu trong phiên làm việc: “bổ sung đầy đủ các quy tắc, hướng dẫn cần thiết cho agent và chuyển các tài liệu vừa thêm ở dạng draft thành đã được phê duyệt sẵn sàng cho dự án”. Phê duyệt này cho phép dùng làm baseline triển khai; không chứng minh prerequisite, code, test/evaluation hoặc bản phát hành đã hoàn thành. Danh tính người thực hiện và reviewer của từng công việc được ghi tại issue/PR, không suy ra từ người đang đọc. Student, Trainer và Admin là vai trò sản phẩm, không phải thành viên phát triển.

**Cách sử dụng:** đọc phạm vi phát hành → ma trận dữ liệu/quyền → kế hoạch triển khai. Tuân thủ `AGENTS.md` và hướng dẫn component trong repository khi thực hiện công việc. Tài liệu kế hoạch không tự cấp quyền Git, triển khai, xuất bản nội dung hoặc thay đổi dữ liệu. Khi tài liệu mới, tài liệu domain và code/schema khác nhau, ghi nhận xung đột và quyết định được phê duyệt trước phần triển khai bị ảnh hưởng; không tự chọn bản thuận tiện hơn. Nguồn trích dẫn tại baseline dùng để truy vết, không dùng để suy đoán các thay đổi về sau.

Tài liệu cụ thể hóa F01–F11 của [phạm vi phát hành](../00-project-overview/phase-2-release-scope.md); milestone và test nằm trong [kế hoạch triển khai](../07-development/phase-2-implementation-plan.md). Tên DTO, mã lỗi, revision token và thời hạn trong thiết kế không có nghĩa đã tồn tại trong DB/API. Invariant về quyền quyết định kế thừa domain; các chi tiết triển khai chưa cụ thể phải được hoàn thiện và review theo milestone, không cần xin lại phê duyệt cho D01–D07 đã chốt.

## 1. Các quy tắc áp dụng cho mọi chức năng

### 1.1. Phân biệt dữ liệu theo mục đích

| Nhóm | Nguồn đúng | Cách dùng |
|---|---|---|
| Dữ liệu cá nhân | Student, Goal, Coaching, Workout, Measurement, Nutrition qua Spring | Context có scope theo request; không đưa toàn bộ hồ sơ vào vector store chung |
| Chỉ số phân tích | Progress Engine với công thức/version và dữ liệu nguồn | AI giải thích kết quả; không tự tính lại để trở thành nguồn sự thật |
| Danh mục bài tập | Exercise catalog | ID, trạng thái, thiết bị, độ khó và variation hợp lệ để backend kiểm tra |
| Kiến thức chuyên môn | Knowledge Document/Version/Chunk ACTIVE | RAG hỗ trợ lập luận, hướng dẫn, bằng chứng; không cấp quyền và không thay field còn thiếu |
| Dữ liệu dinh dưỡng chuẩn | Nutrition Food Database với serving, unit, source/revision | Tính calories/macros xác định; không dùng đoạn văn RAG làm bảng dinh dưỡng |
| Quyền và policy | Auth/User, Trainer, Coaching, Administration, rule policy | Chỉ backend quyết định; không tin role, studentId, approval hoặc version do model tự khai |

### 1.2. Context envelope tối thiểu

Spring tạo request identity, purpose, actor, subject, scope và resource refs; AI service chỉ biến đổi context đã được phép. Không truyền JWT, email, số điện thoại, private chat, progress photo hoặc hồ sơ đầy đủ chỉ vì chúng sẵn có.

| Trường thiết kế | Ý nghĩa |
|---|---|
| `requestId`, `requestType`, `schemaVersion` | Liên kết request, loại chức năng và contract |
| `subjectRef`, `asOf`, `timezone` | Đối tượng đã kiểm quyền, thời điểm chụp dữ liệu và timezone |
| `purpose`, `allowedActions` | Mục đích và whitelist hành động backend cho phép; không để model tự mở quyền |
| `facts[]` | Mỗi fact có value/unit, availability, observed/effective time, source, validation/quality |
| `continuity` | Last workout, inactivity, coverage và known/unknown; bắt buộc có trạng thái cho nhiệm vụ recovery/progression |
| `sourceRefs[]` | Resource ID + version/revision hoặc watermark/digest của truy vấn có giới hạn |
| `constraints` | Thiết bị, thời gian, lịch, supervision và rule policy version liên quan |
| `evidenceRefs[]` | Knowledge Version/Chunk hoặc nguồn domain; server xác thực trước hiển thị/lưu |

`NONE_DECLARED`, `NO_PRIOR_HISTORY` và `NOT_AVAILABLE` khác nhau. Lịch sử trống do người dùng mới đã xác nhận không được đánh đồng với lịch sử bị che vì scope. Khối lượng cơ, body fat, lượng ăn và khả năng chịu tải không được suy diễn từ dữ liệu thiếu.

### 1.3. Kiểm tra quyền nhiều thời điểm

1. **Khi yêu cầu:** xác thực, đúng subject, capability, mục đích, quyền domain, period/relationship còn hiệu lực.
2. **Trước gửi model:** lấy tập dữ liệu tối thiểu bằng query contract; không bỏ qua phạm vi lịch sử trước coaching.
3. **Khi job trả kết quả và khi xem:** kiểm tra quyền hiện tại. Nếu scope đã bị thu hồi, không giao output dựa trên dữ liệu nay không được phép xem. Audit nội bộ có chính sách truy cập riêng.
4. **Khi quyết định/áp dụng:** kiểm tra quyền hiện tại và dữ liệu liên quan trong transaction. Quyền lúc tạo không đủ để chấp nhận lúc sau.

Trainer cần verified/active, relationship và period hợp lệ, domain scope và access level thích hợp. View-only không đủ để áp dụng thay đổi giáo án. Admin không dùng quyền vận hành để quyết định coaching.

Quy tắc Phase 2 đã phê duyệt: PT cũ không xem lại nháp AI chứa context cá nhân qua các API AI thông thường sau khi hết scope. Việc giữ hồ sơ PT từng tác giả thuộc retention policy riêng. Student vẫn xem/tiếp tục dùng plan đã được giao; không vì thế được xem mọi ghi chú riêng của Trainer.

### 1.4. Kết quả và tác động

- `INFORMATION`: giải thích/tóm tắt có evidence, missingData, giới hạn; không có action áp dụng.
- `PROPOSAL`: payload có kiểu cụ thể; chỉ sau schema + domain validation mới thành recommendation có thể chấp nhận.
- `ESTIMATE`: món/khẩu phần chờ xác nhận, chưa là confirmed Food Log.
- `INSUFFICIENT_DATA`: danh sách field cần bổ sung và chức năng nào còn dùng được; không tạo recommendation áp dụng.
- `BLOCKED`: nêu lý do/rule và bước tiếp theo; không cho model tự bỏ qua rule.

Đây là phân loại kết quả API của thiết kế đã phê duyệt, không phải enum lưu DB. Dùng bảng ánh xạ mục 1.6: DB hiện có SUCCEEDED/FAILED/REJECTED_BY_VALIDATOR; TIMEOUT, RULE_BLOCKED hoặc VALIDATION_FAILED là reason/error code theo trường hợp, không tự thêm enum. API phải phân biệt trạng thái xử lý, resultType và reasonCode.

`confidence` của model không phải xác suất đã hiệu chỉnh, không tự quyết định quyền hoặc bypass validation. UI ưu tiên nguồn, dữ liệu thiếu và giới hạn hơn một con số phần trăm chắc chắn.

### 1.5. Dữ liệu đổi và xử lý chấp nhận

- Backend giữ snapshot refs của **toàn bộ phụ thuộc quyết định**, không chỉ planVersion. Goal version, period, sharing revision, preferences/equipment, workout data watermark, schedule và rule version có thể liên quan.
- Version/revision phải do module nguồn cung cấp. Nếu hiện chưa có, P2-M1 bổ sung query revision/digest ổn định cho phạm vi dữ liệu đó; không dùng thời điểm hiện tại giả làm source version.
- Phân loại thay đổi theo mục 1.8: hard conflict, revalidation hoặc cần làm mới thông tin. Không tự áp dụng bản cũ khi hard conflict; không bắt sinh lại chỉ vì watermark có thay đổi ngoài phạm vi quyết định. Giữ snapshot gốc và lưu revalidation evidence riêng; không âm thầm thay căn cứ.
- Quyền luôn được kiểm tra mới. Thay đổi không liên quan, như ảnh đại diện, không mặc nhiên vô hiệu hóa giáo án.
- Đề xuất hết hạn không áp dụng. Đề xuất mặc định thử nghiệm: F02/F03 24 giờ, F05 30 phút, F06/F07 draft estimate 24 giờ; expiry là kiểm tra bổ sung, không thay source revalidation. P2-M0 có thể duyệt giá trị khác trước rollout.
- Knowledge ACTIVE tại thời điểm tạo được ghi lại. Đề xuất chưa áp dụng dùng nguồn bị archive/withdraw phải review/tạo lại theo bản ACTIVE; plan đã áp dụng không bị viết lại tự động. Người dùng không thấy nguồn lịch sử vượt quyền.
- Content/rule thay đổi trọng yếu buộc revalidation; không coi source version khớp là đủ để bỏ qua rule mới.
- Enum recommendation hiện có: `PENDING`, `ACCEPTED`, `REJECTED`, `APPLIED`, `EXPIRED`, `CANCELLED`; không có `APPLY_FAILED` hoặc `STALE`. V1 chọn Accept-and-Apply nguyên tử theo mục 1.7: `PENDING → ACCEPTED → APPLIED` trong cùng transaction; phía ngoài chỉ thấy APPLIED sau commit. Reject/expire/cancel dùng transition hợp lệ riêng. UI chỉ báo dữ liệu đã cập nhật khi có APPLIED và resource ref.
- V1 kiểm tra + ghi domain + ghi kết quả/audit trong transaction cục bộ Spring. Lỗi bất kỳ bước bắt buộc nào rollback toàn bộ; recommendation giữ trạng thái trước transaction (thường PENDING), không lưu ACCEPTED dở dang. Lỗi attempt được ghi riêng sau rollback, không giả là audit thành công. Không gọi model trong transaction áp dụng.
- Chấp nhận lặp hoặc hai request đồng thời không tạo hai plan/log/change. Dùng kiểm tra trạng thái, khóa/version và uniqueness phù hợp; không tuyên bố repo đã có `Idempotency-Key`.

### 1.6. Ánh xạ trạng thái DB, API và UI

Baseline V1__foundation.sql có hai enum riêng; bảng dưới là phương án đã phê duyệt để dùng chúng, chưa phải API đã triển khai.

| Tình huống | ai_run_status trong DB | resultType / reasonCode theo thiết kế | UI |
|---|---|---|---|
| Đã nhận/chưa xử lý | QUEUED | Chưa có kết quả | Đang chờ |
| Đang xử lý | RUNNING | Chưa có kết quả | Đang xử lý |
| Thông tin/đề xuất/ước lượng hợp lệ | SUCCEEDED | INFORMATION / PROPOSAL / ESTIMATE | INFORMATION: chỉ xem; PROPOSAL: Accept/Reject recommendation; ESTIMATE: Confirm/Correct qua Nutrition |
| Thiếu đầu vào nghiệp vụ được phát hiện trong pipeline | SUCCEEDED | INSUFFICIENT_DATA / MISSING_REQUIRED_CONTEXT | Bổ sung dữ liệu; không apply |
| Rule chặn | REJECTED_BY_VALIDATOR | BLOCKED / RULE_BLOCKED | Lý do và bước tiếp theo |
| Output sai schema hoặc domain | REJECTED_BY_VALIDATOR | Không có payload áp dụng / VALIDATION_FAILED | Không có đề xuất hợp lệ |
| Provider/model lỗi | FAILED | MODEL_ERROR hoặc PROVIDER_ERROR | Thử lại theo policy |
| Quá thời gian | FAILED | TIMEOUT | Đã hết thời gian chờ |
| Xử lý nội bộ lỗi | FAILED | PROCESSING_ERROR | Lỗi xử lý |
| Job bị hủy | CANCELLED | CANCELLED | Đã hủy |

Request sai HTTP schema hoặc không có quyền bị từ chối ở API; không bắt buộc tạo AI Run chỉ để ghi lỗi đầu vào. SUCCEEDED + INSUFFICIENT_DATA không được tính là đã sinh proposal thành công. Thu hồi quyền khiến result API từ chối đọc; không trả payload nhạy cảm chỉ vì run đã SUCCEEDED. Nếu generation đã hoàn tất, giữ trạng thái run thực tế cho audit thay vì đổi lịch sử thành CANCELLED.

Recommendation chỉ được tạo PENDING từ PROPOSAL qua validation. INFORMATION, ESTIMATE và evaluation không dùng recommendation như đối tượng có quyền apply. Mã lỗi apply nằm ở response/attempt metadata, không phải ai_run_status của generation đã hoàn tất. Migration chỉ được đề xuất khi contract thực sự thiếu trường/constraint, không mặc định thêm các enum cùng tên nhãn UI.

### 1.7. Accept-and-Apply, rollback và retry trong V1

1. Spring mở transaction, khóa recommendation và kiểm quyền hiện tại, status/expiry, source guard, payload và rule. Domain owner cung cấp command/guard có cơ chế concurrency tương ứng.
2. Ghi quyết định ACCEPTED và approver trong transaction; gọi domain command; ghi application references, decision/business audit bắt buộc; chuyển APPLIED rồi commit. ACCEPTED là bước nội bộ để tương thích constraints hiện có, không tạo hàng đợi apply sau commit trong V1.
3. Nếu conflict, hết quyền hoặc audit/domain write lỗi, rollback tất cả thay đổi của transaction. Không giữ domain change mà thiếu audit. Recommendation PENDING không đồng nghĩa còn áp dụng được: mọi retry vẫn kiểm quyền, expiry và source mới. Expiry có thể được đánh dấu bằng transition riêng được kiểm soát.
4. Sau rollback, ghi operational attempt/error với correlation ID vào cơ chế vận hành tách transaction. Nếu cần attempt bền vững trong DB, thiết kế storage/migration riêng; không tự nhận schema hiện đã có. Lỗi ghi error không được biến request thành thành công hay commit domain change.
5. Nếu commit thành công nhưng response bị mất, chỉ request lặp đúng operation/actor/subject/recommendation, decision revision và payload đã chuẩn hóa mới được trả APPLIED cùng resource refs cũ, sau kiểm quyền đọc hiện tại. Không chạy command lần hai. Cùng định danh thao tác nhưng revision/payload khác trả conflict, không báo bản sửa mới đã được lưu. Actor đã mất quyền không nhận payload cũ. Lỗi tạm thời trước commit có thể retry; hard conflict yêu cầu refresh/review.
6. Khóa/version và constraint chống trùng phải phù hợp một recommendation có thể tạo nhiều resource refs; không mặc định unique chỉ recommendationId nếu domain cần nhiều application rows. Chọn logical application key và kiểm thử race/timeout.

**Định danh quyết định và payload:** contract P2-M0/P2-M1 phải xác định operation ID (hoặc cơ chế tương đương), actor/subject, recommendation ID, decision revision và fingerprint do server tính từ payload đã chuẩn hóa. Fingerprint bao gồm action, target, giá trị được chấp nhận và source/preview revisions có ý nghĩa; loại trừ correlation ID hoặc thời điểm vận chuyển không làm đổi ý định. Chuẩn hóa có phiên bản, unit rõ ràng; không tin hash client tự gửi. Không bắt buộc dùng header `Idempotency-Key` và không khẳng định DB hiện có các trường này.

Lưu định danh, fingerprint, quyết định và kết quả đã commit một cách nguyên tử; thiết kế storage/constraint ở P2-M1 nếu thiếu. Retry thành công trả cùng kết quả thao tác lịch sử; đọc trạng thái tài nguyên hiện tại là request riêng, không áp dụng lại khi tài nguyên đã đổi sau lần commit đầu. Proposal đã APPLIED không nhận payload sửa mới; phải dùng workflow thay đổi mới. Kiểm quyền trước khi trả kết quả hoặc chi tiết conflict. Request chưa commit vẫn phải kiểm quyền quyết định và source guard mới; retry receipt đã commit không tái kiểm nguồn để áp dụng lần hai.

Không gọi model/provider trong transaction. Nếu sau này cần tách Accept và Apply bất đồng bộ, phải thiết kế state machine, durable job, recovery và tái kiểm quyền riêng trước khi đổi contract.

### 1.8. Source guard theo tác động và bảo vệ concurrency

| Chức năng | Hard conflict/chặn | Revalidation trong phạm vi quyết định | Chỉ cần làm mới thông tin |
|---|---|---|---|
| F02 | Mất authority; đổi mode/Goal version; active plan xuất hiện/đổi; constraint người dùng thay đổi ảnh hưởng payload | Catalog/rule còn hợp lệ; actual/progress mới trong cửa sổ liên quan; conflict lịch hiện tại. Chỉ giữ payload nếu kiểm tra lại xác nhận vẫn phù hợp | Dữ liệu ngoài cửa sổ, ảnh đại diện không vô hiệu hóa plan |
| F03 | Target plan/session đổi; buổi đích đã hoàn thành; thay Goal/authority hoặc constraints liên quan | Lịch sử mới, status bài thay, recovery và rule theo loại thay đổi | Nguồn không dùng để quyết định change set |
| F05 | Buổi đã hoàn thành; mất authority; planned session đích đã đổi | Availability, recovery, appointment và conflict trên slot được đề xuất | Lịch không giao khoảng kiểm tra |
| F06/F07 | Mất ownership; draft/log đã xác nhận hoặc revision đổi; ảnh bị thay/xóa. Ngoại lệ retry đúng thao tác đã commit trả receipt theo mục 1.9, không ghi lại | Food revision/conversion đổi: tính lại, trả preview mới và yêu cầu Student xác nhận lại, không tự commit số mới | Goal/PT thay đổi không tự đổi facts của Food Log |
| F01/F04/F08 | Mất quyền đọc: chặn trả output | Nguồn bị rút phải gắn cảnh báo/giới hạn hoặc chặn theo policy | Facts mới: giữ bản asOf cũ, hiển thị cần làm mới; request mới tạo output mới |

Với mọi proposal, knowledge bị archive/withdraw phải review/tạo lại theo mục 1.5; rule mới trọng yếu phải được kiểm lại. Không áp dụng nếu không thể chứng minh payload vẫn hợp lệ. Revalidation chỉ giữ nguyên payload trong phạm vi đã xác nhận; nếu đổi nội dung, lịch hoặc giá trị phải tạo preview/revision để người dùng quyết định lại. Snapshot generation bất biến, evidence revalidation ghi riêng.

Mỗi source contract phải chốt entity/window, field có ý nghĩa, revision do nguồn cấp, change classification, hành động và test. Không dùng một global watermark của Student cho mọi chức năng. Đối với F05, khi Appointment change request được chấp nhận, refresh proposal/preview để ghi nhận appointment version mới rồi kiểm conflict lại; không mắc vòng lặp dùng mãi snapshot trước reschedule.

Digest chỉ phát hiện thay đổi, không ngăn race. Transaction apply cần khóa/version nguồn hoặc aggregate guard mà writer tương ứng cũng tuân thủ. Truy vấn “chưa có plan”, “không có log mới” và “không trùng lịch” cần bảo vệ cả insert đồng thời bằng guard/constraint/isolation phù hợp. Không chỉ SELECT lại rồi ghi dưới READ COMMITTED và coi đã an toàn. P2-M1 chọn cơ chế cho từng command; test T05/T06/T23 phải có writer cạnh tranh từ workflow Phase 1, không chỉ hai request AI.

### 1.9. Food Confirmation nguyên tử cho F06/F07

Đây là command của Nutrition, không phải Accept AI Recommendation. ESTIMATE chưa là confirmed Food Log. Phương án V1: Student review từng item, chọn một hoặc nhiều item rồi xác nhận **nguyên tử toàn bộ nhóm được chọn**. Item không được chọn giữ ở draft; nếu một item được chọn không hợp lệ, không ghi một phần nhóm. UI cho bỏ chọn item chưa rõ và gửi nhóm hợp lệ mới; không coi xác nhận nhóm là xác nhận bữa/ngày đã log đầy đủ.

1. Request chứa operation ID, draft/preview revision, meal/date/timezone, danh sách selected item IDs cùng food identity, quantity/unit và catalog revision đã xem. Preview lưu ở server hoặc có cơ chế kiểm toàn vẹn; client không quyết định giá trị nutrients cuối cùng.
2. Kiểm ownership/quyền hiện tại; nếu là retry đã commit, đối chiếu actor, operation và fingerprint payload như mục 1.7 rồi trả cùng receipt/Log IDs. Cùng operation nhưng payload/revision khác trả conflict. Nếu operation mới chứa item đã xác nhận, trả conflict; muốn sửa log dùng correction workflow riêng.
3. Mở transaction; khóa/guard draft và các item theo thứ tự ổn định, kiểm revision/expiry, trạng thái chưa xác nhận, meal ownership, quantity/unit và food identity. Kiểm media ref/quyền theo F07; kiểm catalog revision và conversion. Writers của draft/catalog/log phải tuân thủ guard tương ứng để tránh race.
4. Catalog hoặc conversion đổi có ảnh hưởng preview: không ghi confirmed log. Kết thúc lần xác nhận không áp dụng, trả conflict kèm yêu cầu refresh. Tạo preview revision mới qua luồng preview và yêu cầu Student xem/xác nhận lại bằng thao tác mới; không tự xác nhận số mới.
5. Nutrition Calculation Engine tính từ structured food record/version và quantity đã xác nhận. Trong cùng transaction, ghi Food Log Items, provenance, nutrient calculation/source refs, trạng thái xác nhận, receipt chống trùng và audit bắt buộc. Một lỗi bất kỳ rollback toàn bộ nhóm; không để item bị đánh dấu confirmed mà chưa có log hoặc ngược lại.
6. Sau commit mới trả receipt với Log IDs và kết quả tính; thông báo/tổng hợp nền xử lý sau commit theo cơ chế bền vững, chống trùng của domain. Nếu response mất, retry cùng thao tác không tính/ghi lại và không tự dùng catalog mới để đổi receipt cũ. Quyền đọc kết quả vẫn kiểm hiện tại.
7. Lỗi trước commit có thể retry theo policy sau kiểm lại dữ liệu; operational error ghi riêng sau rollback. Correction của log đã xác nhận là command mới có version/history, không dùng lại operation ID để sửa dữ liệu âm thầm.

Fingerprint nhóm dựa trên tập item đã sắp xếp chuẩn, food identity/quantity/unit, meal/date và revisions có ý nghĩa. Thứ tự hiển thị khác nhưng cùng ý định có thể chuẩn hóa giống nhau; thay lượng/món hoặc thành viên nhóm là payload khác. Chốt storage, constraints và code lỗi ở P2-M1/P2-M5; đây chưa phải mô tả endpoint hoặc enum đã triển khai.

### 1.10. Tương thích embedding và index

Baseline `V9__ai_and_knowledge.sql` khai báo `knowledge_embeddings.embedding vector(1536)`. Trước ingest, kiểm output dimension của model/cấu hình đã chọn khớp column/index. Nếu khác, cần migration/index được review và thử trước; không sửa migration đã áp dụng hoặc cắt/đệm vector tùy tiện để vừa cột.

Query và document embeddings phải thuộc cùng không gian embedding đã định danh: model version, cấu hình ảnh hưởng vector, dimension, preprocessing và distance metric tương thích. Hai model cùng 1536 chiều không mặc nhiên so sánh được. Retrieval phải chọn đúng model/index partition/version; không trộn vector từ nhiều không gian trong cùng tập tìm kiếm chỉ vì schema lưu được.

Đổi model: tạo embeddings/index tương ứng có lineage, kiểm benchmark rồi chuyển cấu hình query cùng index nhất quán; rollback cũng phải chuyển cả cặp. Giữ source/version refs để audit, không viết đè evidence lịch sử. Gate P2-M2 phải từ chối cấu hình sai dimension hoặc sai model space trước khi trả kết quả production.

## 2. F01 — Giải thích kỹ thuật và nguyên tắc tập

| Câu hỏi | Hợp đồng thiết kế |
|---|---|
| Cần dữ liệu gì? | Bắt buộc: câu hỏi trong phạm vi, ngôn ngữ, tập tri thức ACTIVE liên quan. Nếu hỏi bài cụ thể: Exercise ID/catalog metadata. Context cá nhân chỉ khi người dùng chọn câu hỏi cá nhân hóa; trường cần lấy phụ thuộc câu hỏi, không bắt buộc cân nặng/giới tính cho giải thích chung. |
| Module nào cung cấp? | Exercise/Content và knowledge service; Student/Coaching/Workout chỉ khi cần context được cấp quyền; AI điều phối. |
| Thiếu dữ liệu thì sao? | Không có bằng chứng đủ phù hợp: trả thiếu cơ sở hoặc chỉ thông tin đã có nguồn, hỏi rõ câu hỏi. Không lấy DRAFT để trả lời. Thiếu context cá nhân: giải thích chung, không kết luận cá nhân hóa. |
| Ai được yêu cầu/xem? | Student hoặc Trainer có capability phù hợp. Hỏi kiến thức chung không cần relationship; hỏi về Student khác phải kiểm scope đầy đủ. Người khác không đọc lịch sử hỏi đáp mặc nhiên. |
| Ai quyết định? | Không có quyết định áp dụng. Muốn thay giáo án phải khởi tạo F02/F03 với quyền và dữ liệu riêng. |
| Dữ liệu đã đổi thì sao? | Gắn asOf, nguồn/version; kiểm quyền khi mở lại. Nội dung cũ có nguồn bị rút phải được đánh dấu, tránh dùng làm cơ sở mới; request mới chỉ dùng ACTIVE. |

**Đầu ra:** answer, claims/evidenceRefs, limitations, missingData; không chấp nhận `suggestedActions` có hiệu lực nghiệp vụ.  
**Nghiệm thu:** hỏi kỹ thuật không gửi toàn bộ profile; không tạo plan/log; citation phải trỏ nguồn thực đã lấy; nội dung tài liệu yêu cầu bỏ qua quyền không được thực thi.

## 3. F02 — Tạo đề xuất giáo án

| Câu hỏi | Hợp đồng thiết kế |
|---|---|
| Cần dữ liệu gì? | Bắt buộc: Goal ACTIVE và version/target liên quan; authority/period; kinh nghiệm; ngày có thể tập và thời lượng; thiết bị hoặc xác nhận bodyweight; trả lời về hạn chế/bài tránh; ngày bắt đầu; trạng thái lịch sử và continuity. Lấy active plan + version nếu đã có, hoặc xác nhận chưa có plan. Catalog ACTIVE và constraints/rule đã duyệt. Tùy chọn: sở thích, measurements đã validate, lịch sử thực hiện/RPE nếu được phép. Tuổi/chiều cao/cân nặng chỉ bắt buộc khi policy hoặc mục đích cụ thể cần; thiếu body fat không chặn giáo án chung. |
| Module nào cung cấp? | Goal; Coaching + Auth/Trainer; Student; Exercise; Workout; Schedule; Progress; Measurement nếu liên quan; Knowledge cung cấp căn cứ chuyên môn. |
| Thiếu dữ liệu thì sao? | Thiếu bắt buộc: trả field cần bổ sung. Người mới xác nhận chưa có lịch sử có thể dùng mức giới thiệu theo rule đã review, không bịa mức tạ kg. Lịch sử bị hạn chế không đồng nghĩa mới tập. Continuity không rõ hoặc nghỉ dài: không sinh progressive overload bình thường; yêu cầu đánh giá trở lại tập theo policy. |
| Ai được yêu cầu/xem? | SELF_DIRECTED: Student của chính mình. HUMAN_COACH: Trainer có quyền manage workout và các scope dữ liệu cần dùng. Student chỉ xem nháp khi Trainer chia sẻ/giao. Student vẫn dùng F01/F04 cho phần dữ liệu của mình. |
| Ai quyết định? | Student trong SELF_DIRECTED; Trainer hợp lệ trong HUMAN_COACH. Không chuyển quyền sang Student chỉ vì Trainer bận. |
| Dữ liệu đã đổi thì sao? | Guard: Goal/status/version, Coaching Period/authority/sharing, profile constraints revision, Exercise availability, plan/current-plan revision, workout/progress watermark, schedule window và rule/knowledge refs đã dùng. Có plan mới trong lúc chờ cũng là conflict dù lúc tạo chưa có plan. Đổi Goal/thiết bị ảnh hưởng payload hoặc mode là hard conflict; lịch/actual mới xử lý theo phân loại mục 1.8, không mặc định mọi watermark đổi đều phải gọi lại model. |

**Payload:** planName, goalRef, startDate, timezone, một tuần sessions; mỗi session có ngày dự kiến, duration, supervision requirement; mỗi exercise có Exercise ID, thứ tự, sets, rep range hoặc duration có unit, restSeconds, load guidance nếu có cơ sở, reasoning/evidence. Tách exercise kê theo reps và theo thời lượng bằng schema có kiểu, không bắt mọi bài có cùng bộ field. Các con số còn phải qua rule/domain validation.

**Áp dụng:** xem trước diff và tác động; tạo plan hoặc version qua Workout command. Không tạo Actual Workout, Goal mới, Nutrition Target hay Appointment. Phạm vi materialize các Planned Workout phải được hiển thị trước xác nhận.  
**Nghiệm thu:** người mới/thiếu body fat vẫn có đường hợp lệ; exercise không tồn tại hoặc sai thiết bị bị loại; Student HUMAN_COACH không được tự activate; Goal đổi sau generation bị chặn; accept hai lần chỉ một lần có tác động.

## 4. F03 — Thay bài và điều chỉnh volume/load

| Câu hỏi | Hợp đồng thiết kế |
|---|---|
| Cần dữ liệu gì? | Chung: plan/version và session/exercise đích, lý do, authority, thiết bị/hạn chế hiện tại, Goal liên quan. Thay bài: catalog alternatives, movement/equipment/supervision. Tăng/giảm tải hoặc volume: thêm actual sets/reps/load/RPE đủ chất lượng, coverage window, Progress và continuity/recovery theo rule. |
| Module nào cung cấp? | Workout, Exercise, Student, Goal, Coaching, Schedule, Progress; Knowledge cho lý do chuyên môn. |
| Thiếu dữ liệu thì sao? | Không có RPE/lịch sử đủ theo policy: không khẳng định nên tăng tải; có thể chỉ hỗ trợ thay bài nếu đủ dữ liệu riêng. Không có bài thay hợp lệ: trả không tìm được, không bịa Exercise ID. Nghỉ dài: yêu cầu review trở lại tập. |
| Ai được yêu cầu/xem? | Như F02; scope mỗi nguồn được kiểm độc lập. View history không tự cấp manage plan. |
| Ai quyết định? | Student tự tập hoặc Trainer có authority tại thời điểm áp dụng. Người có quyền có thể sửa đề xuất; backend validate lại và giữ diff giữa AI draft và bản người sửa. |
| Dữ liệu đã đổi thì sao? | Plan/session revision, exercise status, profile constraints, Goal/period/sharing và actual/progress watermark liên quan. Buổi đã hoàn thành không được sửa như thay kế hoạch chưa thực hiện. |

**Payload:** typed change set gồm targetRef, before/after, reason, scope, evidence; không dùng object tự do cho `suggestedActions`.  
**Nghiệm thu:** thay tải một buổi có change history; thay cấu trúc/tần suất/volume chiến lược tạo plan version mới theo domain policy. Không suy ra “ít field đổi” đồng nghĩa “minor”. Actual Workout giữ nguyên.

## 5. F04 — Tóm tắt tiến độ và hỗ trợ Trainer review

| Câu hỏi | Hợp đồng thiết kế |
|---|---|
| Cần dữ liệu gì? | Khoảng ngày; Goal/version theo thời gian; planned/actual, completion và adherence riêng; Progress signals, coverage, inactivity; measurements chỉ nếu có và phù hợp. Nutrition không lấy mặc định. |
| Module nào cung cấp? | Progress là nguồn chỉ số; Workout, Goal, Coaching, Measurement cung cấp facts/ref đã chuẩn hóa. |
| Thiếu dữ liệu thì sao? | Không có đủ lịch sử: tóm tắt mức dữ liệu đang có, không suy ra xu hướng. Bị thiếu scope chỉ trình bày phạm vi được phép, không tiết lộ sự tồn tại/giá trị dữ liệu bị che. Không có body composition thì không kết luận tăng cơ từ cân nặng. |
| Ai được yêu cầu/xem? | Student xem dữ liệu mình; Trainer xem Student trong relationship, time scope và domain scope hợp lệ. |
| Ai quyết định? | Không có Accept áp dụng cho bản tóm tắt. Có thể mở F03/F05; đó là request/approval riêng. Rule Attention Signal giữ workflow sẵn có, LLM không tự đóng signal. |
| Dữ liệu đã đổi thì sao? | Gắn asOf/window, Progress computationVersion và source watermark. Có log/measurement correction mới thì đánh dấu cần làm mới; không sửa bản tóm tắt lịch sử thành sự thật mới. Kiểm scope khi xem lại. |

**Nghiệm thu:** tập thứ Ba thay thứ Hai có thể hoàn thành nhưng lệch lịch; nghỉ 100 ngày phải hiện trong phân tích; Student đổi PT không mất lịch sử nhưng PT mới chỉ thấy phần được chia sẻ.

## 6. F05 — Đề xuất xếp lại lịch tập sau buổi bỏ lỡ

| Câu hỏi | Hợp đồng thiết kế |
|---|---|
| Cần dữ liệu gì? | Planned Workout chưa hoàn thành, plan/session revision; actual status; thời gian rảnh, timezone, lịch liên quan; recovery/continuity; supervision requirement; authority. Appointment ref và trạng thái nếu buổi có liên kết, chỉ dữ liệu được phép xem. |
| Module nào cung cấp? | Workout sở hữu Planned Workout; Schedule sở hữu Appointment/conflict; Student cung cấp availability; Coaching và Progress cung cấp scope/continuity. |
| Thiếu dữ liệu thì sao? | Không có slot hợp lệ: yêu cầu chọn thêm, không tự bỏ recovery. Không biết lịch PT hoặc COACH_REQUIRED thiếu PT: chỉ trả cần phối hợp, không hạ supervision để cho tự tập. |
| Ai được yêu cầu/xem? | Người đang có quyền giáo án như F02. Student HUMAN_COACH có thể dùng workflow yêu cầu đổi lịch hẹn hiện có, không qua đó giành quyền sửa plan. |
| Ai quyết định? | Đổi Planned Workout: authority giáo án. Đổi Appointment: hai bên theo workflow Schedule. Accept đề xuất workout không đồng thời coi bên còn lại đã đồng ý hẹn. |
| Dữ liệu đã đổi thì sao? | Reload session completion/version, availability/schedule window, appointment nếu có, period/authority và recovery facts. Log mới kích hoạt revalidation recovery/completion; conflict mới không giải quyết được với payload đã xác nhận thì chặn và đề nghị chọn lại, theo mục 1.8. |

**Giới hạn:** một Planned Workout mỗi lần; không tự đổi cả recurring series. Khi cần đổi Appointment, tạo/mở change request riêng; chờ được chấp nhận rồi revalidate đề xuất workout. Không dùng một giao dịch nửa hoàn tất để giả định hai đối tượng đã đồng bộ.  
**Nghiệm thu:** không sửa ngày performed của Actual Workout; cancel hẹn không cancel workout tự tập; không bypass COACH_REQUIRED; accept lại phải kiểm tra conflict.

## 7. F06 — Nhập thực phẩm bằng văn bản

| Câu hỏi | Hợp đồng thiết kế |
|---|---|
| Cần dữ liệu gì? | Mô tả món/amount/unit, meal/date/timezone, ownership. Food catalog có canonical ID, serving/conversion, nutrient source/revision. Nếu sửa log: Food Log/item revision. Không cần Goal/cân nặng để nhận diện món. |
| Module nào cung cấp? | Nutrition + Food Database và Calculation Engine; AI phân tích text; User/Student kiểm ownership. |
| Thiếu dữ liệu thì sao? | Không rõ món hoặc lượng/đơn vị: đưa candidates và hỏi Student; không dùng mặc định như đã xác nhận. Không match được catalog: tìm/chọn thủ công hoặc giữ draft, không tạo calories từ LLM. |
| Ai được yêu cầu/xem? | Student sở hữu meal/draft. Trainer chỉ xem confirmed log theo nutrition scope; không truy cập draft riêng chỉ nhờ có role. |
| Ai quyết định? | Student xác nhận/correct food identity và quantity trong cả hai mode. |
| Dữ liệu đã đổi thì sao? | Kiểm draft/log revision, ownership, confirmation state và Food Database revision trước tính lại/lưu. Catalog đổi: hiển thị tính toán mới để Student review trước xác nhận. Đổi Goal không tự làm đổi Food Log thực tế. |

**Payload:** candidates với foodId/candidateIds, amount/unit hoặc unknown, provenance, uncertainty; không tin giá trị calories/macros model tự trả.  
**Nghiệm thu:** xác nhận hoặc sửa trước lưu confirmed; tính toán backend từ catalog; retry không nhân đôi log; correction giữ nguồn và lịch sử. V1 giữ estimate riêng, không đưa vào tổng confirmed intake trước xác nhận. Transaction nhóm item, preview revision, receipt và retry thực hiện theo mục 1.9.

## 8. F07 — Nhận diện thức ăn bằng ảnh

| Câu hỏi | Hợp đồng thiết kế |
|---|---|
| Cần dữ liệu gì? | Ảnh thuộc Student, media ref/checksum, định dạng/kích thước hợp lệ, meal/date; Food Database như F06. Thông tin món/serving bổ sung nếu ảnh không đủ. Không gửi metadata ảnh hoặc profile không cần thiết sang provider. |
| Module nào cung cấp? | Object storage qua backend media contract có scope; Nutrition, Food Database, AI vision/portion/matching. Không cần lập module media mới chỉ để đáp ứng tài liệu này. |
| Thiếu dữ liệu thì sao? | Ảnh mờ, không phải đồ ăn, món không trong catalog, nhiều thành phần không rõ: hỏi lại/chọn thủ công. Không thấy kích thước chuẩn thì quantity là ước lượng hoặc unknown, bắt buộc Student nhập/xác nhận; không cam kết gram chính xác. |
| Ai được yêu cầu/xem? | Student sở hữu ảnh/draft; link ảnh có kiểm quyền và thời hạn. Trainer không mặc nhiên xem ảnh gốc khi được chia sẻ Nutrition log. Admin chỉ truy cập khi có permission/purpose hợp lệ. |
| Ai quyết định? | Student xác nhận/correct món và lượng; Trainer không xác nhận hộ trong Phase 2. |
| Dữ liệu đã đổi thì sao? | Ảnh thay/xóa hoặc mất quyền: không trả estimate cũ như của ảnh mới. Guard media checksum/ref + draft/log + catalog revision. Trước xác nhận xử lý như F06. |

**Giới hạn:** một ảnh bữa ăn mỗi request; nhiều món được review từng item và xác nhận nguyên tử nhóm được chọn theo mục 1.9. Không suy đoán chính xác dầu/gia vị/thành phần bị che. Portion estimation chỉ được nghiệm thu trên tập hỗ trợ và gate coverage/sai số tại kế hoạch mục 11.2; luôn trả unknown/manual là fallback, không chứng minh đã hoàn thành ước lượng.  
**Nghiệm thu:** ảnh không đọc được có fallback text/manual; item chưa xác nhận không cộng vào confirmed intake; provenance ESTIMATED/USER_CONFIRMED/USER_CORRECTED vẫn truy được sau sửa.

## 9. F08 — Giải thích tiến độ dinh dưỡng

| Câu hỏi | Hợp đồng thiết kế |
|---|---|
| Cần dữ liệu gì? | Window/timezone, confirmed Food Logs và completeness; Daily Targets đã resolve theo ngày, target version/effective period/override nếu có; Progress nutrition signals. Fitness Goal chỉ nếu cần giải thích liên kết/alignment. |
| Module nào cung cấp? | Nutrition tính target/actual và hiệu lực; Progress tổng hợp chỉ số; Goal/Coaching cấp liên kết và scope. Knowledge chỉ khi giải thích nguyên tắc liên quan. |
| Thiếu dữ liệu thì sao? | NOT_LOGGED không phải 0; PARTIAL chỉ phản ánh phần đã ghi. Không có target: tóm tắt intake đã xác nhận, không chấm adherence. Không đủ ngày hợp lệ: không đưa kết luận xu hướng. |
| Ai được yêu cầu/xem? | Student; Trainer có nutrition scope phù hợp và quyền đọc khoảng lịch sử tương ứng. Không cấp nutrition scope chỉ vì có workout scope. |
| Ai quyết định? | Không có thao tác áp dụng. Có thể hướng người dùng tới workflow Nutrition Goal/Proposal hiện có; AI không sinh target định lượng mới ở phạm vi này. |
| Dữ liệu đã đổi thì sao? | Snapshot Food Log watermark, target/override effective versions và Progress version. Khi có correction/target change liên quan: tạo bản phân tích mới; không sửa historical target hoặc log để khớp lời giải thích. |

**Nghiệm thu:** đổi target giữa kỳ vẫn dùng đúng target từng ngày; ngày thiếu log không bị kết luận ăn thiếu; ngày complete phải là dữ liệu completeness của domain, không do LLM tự gán.

## 10. F09 — Quản trị và xuất bản tri thức

| Câu hỏi | Hợp đồng thiết kế |
|---|---|
| Cần dữ liệu gì? | Nội dung, title/topic, nguồn/author, quyền sử dụng nguồn, source date nếu biết, language, review status/reviewer; Document/Version/Chunk/Embedding refs, embedding model/dimension/version, processing status. |
| Module nào cung cấp? | Content/Knowledge, AI retrieval/indexing, Object storage, Administration và Audit; taxonomy Exercise chỉ để liên kết nội dung. |
| Thiếu dữ liệu thì sao? | Thiếu nguồn/quyền sử dụng/review hoặc processing lỗi: giữ DRAFT, không publish. Source date không xác định phải ghi unknown và reviewer quyết định tính phù hợp; không bịa ngày. |
| Ai được yêu cầu/xem? | Admin được gán author/review/publish/archive theo permission cụ thể. Student/Trainer chỉ dùng bản ACTIVE được phép; không được xem nội dung nháp. |
| Ai quyết định? | Knowledge publisher có quyền, step-up theo policy; quyền author không tự bao gồm publish. Một người có thể mang nhiều permission trong nhóm nhỏ nhưng từng thao tác vẫn phải kiểm quyền/audit. |
| Dữ liệu đã đổi thì sao? | Active version bất biến; tạo draft mới, review, publish và archive phiên bản thay thế có chủ đích. Chunk/index refs phải khớp version đang dùng; cache phải lọc lại eligibility. Không destructively re-embed làm mất nguồn của AI Run cũ. Kiểm dimension/model space và chuyển index theo mục 1.10. |

**Nghiệm thu:** import → review → publish → truy xuất → thay version → không retrieve version cũ; audit vẫn truy nguồn lịch sử. Nội dung tài liệu được coi là dữ liệu, không thể ra lệnh cấp quyền hoặc gọi tool. Không đưa hồ sơ Student vào kho tri thức chung.

## 11. F10 — AI Run, vận hành và evaluation/replay

| Câu hỏi | Hợp đồng thiết kế |
|---|---|
| Cần dữ liệu gì? | Run metadata: request type/ref, actor/subject refs cần thiết, prompt/model/rule/schema versions, selected source refs, retrieved versions, validation, result/status, time/latency/usage, decision refs. Evaluation dataset đã được phép/sanitized, rubric và version. |
| Module nào cung cấp? | AI orchestration, Administration, Audit, các domain query có scope khi cần replay. Không copy raw private payload vào log vận hành phổ thông. |
| Thiếu dữ liệu thì sao? | Thiếu metadata bắt buộc thì không phát hành recommendation có thể áp dụng. Run thất bại có error metadata. Không có dữ liệu replay hợp lệ hoặc source đã xóa theo policy: chỉ replay synthetic/sanitized tương đương và nêu giới hạn tái lập. |
| Ai được yêu cầu/xem? | User xem trạng thái và kết quả riêng được phép; Admin quyền AI_RUN_VIEW/đánh giá/config tương ứng. Sensitive payload cần scope/purpose, và privileged access theo policy; không suy từ role ADMIN. |
| Ai quyết định? | AI Admin chạy evaluation; người có quyền config duyệt kích hoạt phiên bản đã đánh giá. Không có quyền Accept plan/food thay Student/Trainer. |
| Dữ liệu đã đổi thì sao? | Replay cố định snapshot/version được phép hoặc ghi rõ chạy trên dữ liệu mới; không gọi kết quả đó là tái lập chính xác. Source mất quyền không được lấy lại chỉ vì có run cũ. Config đổi có history; run đang xử lý giữ version đã chọn. |

**Nghiệm thu:** evaluation output vào vùng evaluation, không tạo live recommendation hoặc gọi apply command; provider timeout có trạng thái/lỗi hiểu được; tắt AI vẫn dùng nghiệp vụ thủ công; dashboard không hiện raw sensitive context mặc định.

## 12. F11 — Feedback và kết quả sau áp dụng

| Câu hỏi | Hợp đồng thiết kế |
|---|---|
| Cần dữ liệu gì? | Run/recommendation ID được phép xem, actor, helpful/not helpful hoặc reason tùy chọn, timestamp; nếu đánh giá outcome: link plan/session → actual/progress window, coverage/continuity và version. Acceptance/rejection là quyết định riêng đã có audit. |
| Module nào cung cấp? | AI/feedback, Workout, Progress, Coaching, Audit. Không tạo lại Actual Workout trong module AI. |
| Thiếu dữ liệu thì sao? | Chưa có outcome thì chỉ ghi subjective feedback; không gán thành thất bại tập luyện. Không đủ scope không liên kết dữ liệu nhạy cảm. Không bắt nhập ghi chú để Reject đề xuất. |
| Ai được yêu cầu/xem? | Student/Trainer chỉ phản hồi kết quả họ được xem; Admin xem aggregate hoặc evaluation data được phép. Không tiết lộ private Trainer notes cho Student qua feedback. |
| Ai quyết định? | User gửi/sửa feedback theo policy có history; AI Admin review chất lượng. Feedback không tự tạo nhãn chuẩn hoặc thay production model. |
| Dữ liệu đã đổi thì sao? | Giữ feedback gắn đúng phiên bản output; outcome mới là quan sát bổ sung có watermark, không ghi đè ý kiến cũ hoặc tự thay kết quả accept/reject. Recheck scope khi liên kết dữ liệu về sau. |

**Nghiệm thu:** “không thích bài tập” không tự thành “AI sai”; thiếu log không tự thành không tuân thủ; nhiều event lặp không nhân đôi thống kê; feedback không gọi train hoặc apply command.

## 13. Bộ contract cần cụ thể hóa khi triển khai

1. Input contract riêng cho từng F01–F08; contract quản trị F09/F10 và feedback F11.
2. Output schema phân loại INFORMATION/PROPOSAL/ESTIMATE/INSUFFICIENT_DATA/BLOCKED; `recommendationType` là tập kiểu đã biết, mỗi action có typed payload.
3. Server envelope giữ target/source refs, người có quyền quyết định, expiry, validation và apply result. Không yêu cầu LLM tự tính quyền hoặc tự khai source version đáng tin.
4. Request/result/decision APIs có actor/resource checks; đọc job result cũng kiểm quyền; lỗi rõ input thiếu, rule blocked, source conflict, expired, already decided, provider error.
5. Media authorization và confirmation contract cho F07; hợp đồng publish/version cho F09; evaluation không có apply interface.
6. Chuẩn unit/date/timezone, dữ liệu không có, revision token và policy version thống nhất giữa Spring/FastAPI/client.
7. Contract operation identity, server-side payload fingerprint, committed receipt và conflict cho payload/revision khác; Nutrition confirmation riêng theo mục 1.9.
8. Retrieval contract chỉ rõ embedding space/index và dimension theo mục 1.10.

## Tài liệu hiện hành trong repository

Đọc các tài liệu hiện hành dưới đây khi thực hiện công việc. Liên kết theo commit ở phần nguồn là snapshot để truy vết; nếu code/contract mới khác baseline, ghi nhận khác biệt trước khi triển khai phần chịu ảnh hưởng. APPROVED xác nhận phạm vi và thiết kế; không chứng minh API đã triển khai hoặc test đã đạt. Các invariant đã xác nhận tiếp tục có hiệu lực.

- [Context, rules và RAG](context-rules-rag.md)
- [Recommendation lifecycle](recommendation-lifecycle.md)
- [Quyền nghiệp vụ](../02-domain/permissions-and-authority.md)
- [Module boundaries](../03-architecture/module-boundaries.md)
- [API conventions](../05-api/README.md)

## 14. Nguồn đối chiếu

- Mô tả hệ thống tham chiếu (`mô tả ý tưởng đồ án.docx`): mục 6, 8–9, 14–24, 29–49, 64, 91.
- [Context/Rules/RAG](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/08-ai/context-rules-rag.md).
- [Recommendation lifecycle](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/08-ai/recommendation-lifecycle.md).
- [Permissions](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/02-domain/permissions-and-authority.md).
- [Module ownership](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/03-architecture/module-boundaries.md).
- [Data flow](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/03-architecture/data-flow.md).
- [Lifecycle](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/02-domain/lifecycles.md).
- [Schema hiện có cần cụ thể hóa](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/contracts/ai-schemas/recommendation.schema.json).

- [Enum DB tại baseline — V1](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/services/backend/src/main/resources/db/migration/V1__foundation.sql).
- [AI/Knowledge schema, vector(1536) — V9](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/services/backend/src/main/resources/db/migration/V9__ai_and_knowledge.sql).
- [Recommendation authority/application constraints — V16](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/services/backend/src/main/resources/db/migration/V16__domain_completeness_and_invariants.sql).
