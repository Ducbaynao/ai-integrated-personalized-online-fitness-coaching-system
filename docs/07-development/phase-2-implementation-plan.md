# Kế hoạch triển khai Phase 2

**Dự án:** HỆ THỐNG HUẤN LUYỆN THỂ HÌNH TRỰC TUYẾN ĐƯỢC CÁ NHÂN HÓA TÍCH HỢP TRÍ TUỆ NHÂN TẠO\
**Phiên bản tài liệu:** 1.0\
**Trạng thái:** APPROVED — đã phê duyệt phạm vi và thiết kế, sẵn sàng lập backlog/triển khai\
**Ngày cập nhật:** 04/10/2026\
**Vị trí trong repository:** `docs/07-development/phase-2-implementation-plan.md`\
**Người phê duyệt / ngày phê duyệt:** Chủ dự án (người dùng) / 04/10/2026\
**Baseline tham chiếu:** `d354e0a594b5e703d18a43264c0bf4325a0911a6` — snapshot lịch sử, không đại diện trạng thái nhánh hiện tại.

**Mục đích:** Xác định phụ thuộc, đầu ra milestone, quy trình phối hợp và bằng chứng nghiệm thu Phase 2.

Tài liệu dành cho thành viên phát triển, người review và AI agent. Phạm vi và thiết kế trong bản 1.0 được chủ dự án phê duyệt ngày 04/10/2026 theo yêu cầu trong phiên làm việc: “bổ sung đầy đủ các quy tắc, hướng dẫn cần thiết cho agent và chuyển các tài liệu vừa thêm ở dạng draft thành đã được phê duyệt sẵn sàng cho dự án”. Phê duyệt này cho phép dùng làm baseline triển khai; không chứng minh prerequisite, code, test/evaluation hoặc bản phát hành đã hoàn thành. Danh tính người thực hiện và reviewer của từng công việc được ghi tại issue/PR, không suy ra từ người đang đọc. Student, Trainer và Admin là vai trò sản phẩm, không phải thành viên phát triển.

**Cách sử dụng:** đọc phạm vi phát hành → ma trận dữ liệu/quyền → kế hoạch triển khai. Tuân thủ `AGENTS.md` và hướng dẫn component trong repository khi thực hiện công việc. Tài liệu kế hoạch không tự cấp quyền Git, triển khai, xuất bản nội dung hoặc thay đổi dữ liệu. Khi tài liệu mới, tài liệu domain và code/schema khác nhau, ghi nhận xung đột và quyết định được phê duyệt trước phần triển khai bị ảnh hưởng; không tự chọn bản thuận tiện hơn. Nguồn trích dẫn tại baseline dùng để truy vết, không dùng để suy đoán các thay đổi về sau.

Tài liệu liên quan: [phạm vi phát hành](../00-project-overview/phase-2-release-scope.md), [dữ liệu và quyền](../08-ai/phase-2-feature-data-authority-matrix.md). Mã P2-M0–P2-M6 chỉ thuộc Phase 2, không thay thế mã milestone Phase 1. Kế hoạch không gán công việc cho cá nhân; assignee và reviewer được xác định theo từng issue.

## 1. Điều kiện bắt đầu

Không cần chờ toàn bộ mọi màn hình Phase 1 hoàn thiện để đọc nguồn RAG hoặc thử pipeline bằng fixture. Tuy nhiên, mỗi chức năng AI chỉ được tích hợp với dữ liệu thật khi các phụ thuộc domain và kiểm tra quyền tương ứng đã chạy được.

| Phụ thuộc | Bằng chứng cần kiểm tra ở P2-M0/P2-M1 | Chức năng phụ thuộc |
|---|---|---|
| Auth, Student, Trainer eligibility và Coaching Period/scope | API/query có object authorization, scope thời gian và thu hồi quyền | F01 cá nhân hóa, F02–F08, F10–F11 |
| Goal/version/status | Trả đúng active Goal, version và lịch sử; không tự resume/activate | F02–F04, F08 khi dùng alignment |
| Exercise catalog | ID ACTIVE, equipment, variation, supervision/constraints cần thiết | F01–F03 |
| Workout Plan/version, Planned/Actual và log | Tách planned/performed, version/change history; command áp dụng có transaction | F02–F05, F11 |
| Schedule/timezone/conflict/supervision | Query availability, kiểm conflict lúc quyết định; Appointment riêng Workout | F02/F05 |
| Measurement/Progress | Quality, coverage, continuity, công thức/version, watermark | F02–F04/F08/F11 |
| Nutrition | Food catalog, serving/unit, deterministic calculation, completeness và target hiệu lực | F06–F08 |
| Audit, Admin permission, media access | Run/decision audit; publish/config permission; ảnh có ownership | F07/F09/F10 và các mutation |

Đây là checklist kiểm chứng, không phải khẳng định Phase 1 hiện đã hoàn tất. Module thiếu hoặc chưa có contract trở thành backlog prerequisite với owner; không dùng model hoặc mock để che việc thiếu source of truth trong bản phát hành.

### 1.1. Hai bảng theo dõi bắt buộc trước khi ước lượng

Bảng bàn giao Phase 1 và bảng bổ sung cho AI có backlog riêng. Chưa có evidence thì ghi CHƯA KIỂM CHỨNG; không coi số bảng DB hoặc fixture là bằng chứng nghiệp vụ đã chạy. Trạng thái bàn giao phải dẫn đến branch/PR, commit và bằng chứng kiểm thử tương ứng; không suy luận từ việc thiếu nội dung trong một checkout.

| Prerequisite Phase 1 | Trạng thái ban đầu | Evidence cần điền | Owner/ngày bàn giao |
|---|---|---|---|
| Coaching authority và sharing scope | CHƯA KIỂM CHỨNG | API/query + test revoke/time scope | Xác định tại issue bàn giao |
| Workout plan/planned/actual commands | CHƯA KIỂM CHỨNG | Luồng thật + version/concurrency tests | Xác định tại issue bàn giao |
| Progress/measurement/continuity | CHƯA KIỂM CHỨNG | Công thức, quality, test missing data | Xác định tại issue bàn giao |
| Schedule/conflict/supervision | CHƯA KIỂM CHỨNG | Query + acceptance conflict test | Xác định tại issue bàn giao |
| Nutrition catalog/calculation/logging | CHƯA KIỂM CHỨNG | Unit/conversion, target/completeness tests | Xác định tại issue bàn giao |
| Media, audit, Admin guards | CHƯA KIỂM CHỨNG | Ownership + audit/permission evidence | Xác định tại issue bàn giao |

| Bổ sung phục vụ AI | Phụ thuộc | Kết quả cần bàn giao |
|---|---|---|
| Context query/DTO có scope | Domain API đã có | Facts tối thiểu, availability/source refs |
| Source revision và concurrency guard | Domain writer/command | Revision window + khóa/version/constraint + race tests |
| Orchestration/validation/AI Run | Contract và guards | Pipeline và trạng thái khớp DB/API |
| Recommendation apply adapter | Domain command đã có | Transaction, idempotency, audit và rollback |

Phần thiếu thuộc nghiệp vụ Phase 1 phải có issue/estimate riêng; P2-M1 không ngầm nhận xây toàn bộ Progress, Schedule hoặc Nutrition. Mỗi feature có gate tích hợp riêng; F01 kiến thức chung không cần đợi toàn bộ nguồn cá nhân.

## 2. P2-M0 — Chốt thiết kế và phân công

**Đầu vào:** ba tài liệu APPROVED bản 1.0, mô tả gốc, roadmap và domain rules.

**Công việc:**

1. Đối chiếu D01–D07 đã phê duyệt tại release scope bản 1.0; không xin lại phê duyệt cùng quyết định. Ghi phương án thay thế nếu có thay đổi mới. Nếu giảm phạm vi Nutrition, sửa roadmap/phạm vi công khai trước khi gọi bản workout-only là Phase 2 hoàn chỉnh.
2. Kiểm tra từng phụ thuộc ở bảng trên bằng API/domain contract và trạng thái triển khai thực tế; ghi đã có/chưa có, người phụ trách và tiêu chí hoàn thành.
3. Chốt output types, quyền request/view/decide/apply, version guard và lỗi từng chức năng theo ma trận mục 1.6–1.10. Cụ thể hóa Accept-and-Apply nguyên tử V1 và mapping enum DB/API đã phê duyệt, không thêm APPLY_FAILED ngầm. Chốt OpenAPI/JSON Schema đợt đầu và quy ước chung; mở rộng contract theo từng feature, không đợi thiết kế chi tiết toàn bộ F01–F11 mới thử pipeline.
4. Chốt bộ rule policy sẽ dùng: constraints cần có, ai review, revision, hành vi BLOCK/ASK/ALLOW_WITH_LIMITS. Các ngưỡng chuyên môn về inactivity/recovery/progression phải có căn cứ và người review; không tự chọn số ngày/kg chỉ để qua test.
5. Chốt catalog exercise/food ban đầu, nguồn được phép sử dụng, reviewer, benchmark và tiêu chí chất lượng. Dữ liệu đánh giá tách khỏi dữ liệu dùng để chỉnh prompt.
6. Đặt giới hạn vận hành đề xuất: request/image size, timeout, retry, concurrency, rate limit, chi phí mỗi chức năng, retention ảnh/context và dữ liệu gửi provider. Có giá trị cụ thể trước rollout, không để production dùng “TBD”.
7. Phân rã các công việc thành issue theo kế hoạch được duyệt; mỗi issue có assignee, reviewer, path phụ trách, contract đầu vào/ra, dependency và acceptance. Việc gán người được quản lý tại issue, không suy ra từ tài liệu hoặc vai trò sản phẩm.
8. Chốt retry identity/revision/fingerprint/receipt và Nutrition confirmation nguyên tử nhóm item theo ma trận 1.7/1.9; UI ESTIMATE dùng Confirm/Correct riêng. Không chọn HTTP header hoặc thêm field DB chỉ vì ví dụ trong tài liệu.
9. Lập đầu việc đồng bộ `docs/02-domain/lifecycles.md`, `docs/08-ai/recommendation-lifecycle.md`, sơ đồ AI tổng quát và API/schema chịu ảnh hưởng. Baseline lifecycle còn APPLY_FAILED, còn tài liệu AI dùng SUCCESS/TIMEOUT như nhãn vận hành: bản V1 phải giải thích mapping DB/API/reasonCode, rollback và INFORMATION/ESTIMATE không đi qua Apply recommendation. Bản tích hợp tài liệu 0.4 đã cập nhật overview/data-flow và lifecycle để phân biệt enum DB với reason/result types, đồng thời gắn rõ V1 là thiết kế đã phê duyệt, chưa phải bằng chứng code. Gate còn yêu cầu review chi tiết kỹ thuật và cập nhật executable API/schema cùng implementation; chưa đóng chỉ vì có tài liệu. Không đổi enum hoặc sửa migration cũ chỉ để khớp tài liệu cũ.

**Đầu ra:** scope được duyệt, dependency checklist, API/schema draft, rule/catalog/evaluation policy được version hóa, backlog và owner.

**Điều kiện hoàn thành:** không còn mơ hồ ai được đọc/quyết định hoặc ghi vào tài nguyên nào. Các mục chuyên môn/vận hành chưa có giá trị được ghi thành blocking item có owner, không bỏ qua khi phát hành.

## 3. P2-M1 — Hợp đồng dữ liệu và nền tảng quyền

**Phụ thuộc:** P2-M0; có thể song song thu thập nguồn tri thức trong phạm vi đã duyệt.

**Công việc:**

1. Tạo query contract theo module, trả facts, source refs/revision, quality, time và scope; không để module AI đọc repository của module khác.
2. Định nghĩa coverage/missing/unknown rõ ràng: người mới, chưa ghi log, không được chia sẻ, dữ liệu cũ và measurement bị nghi ngờ.
3. Bổ sung adapter/read contract cho Progress/continuity và watermark trong cửa sổ liên quan; nếu engine nguồn chưa có thì ghi blocker Phase 1 riêng. Rule thresholds có policy version, không hard-code vào prompt.
4. Xây authorization service dùng chung cho request, job-result read và acceptance. Trainer verification/activity, relationship/period, access level và historical scope đều được kiểm.
5. Thiết kế source guard theo ma trận mục 1.8, kể cả “chưa có active plan”. Chốt hard conflict/revalidation/refresh-only và cửa sổ dữ liệu. Chọn khóa/version/constraint bảo vệ cả insert đồng thời từ workflow Phase 1; digest không thay concurrency control.
6. Thiết kế storage/constraint cho operation identity, decision revision, server-side fingerprint và committed receipt của mutation; giữ chúng nguyên tử với domain change và audit. Rà schema DB hiện có trước khi thêm migration cho AI Run/Recommendation/Knowledge/feedback. Người được phân công quản lý migration review và tích hợp thay đổi schema; không tạo bảng trùng chỉ vì tên trong tài liệu khác tên hiện có.
7. Tạo fixture tổng hợp cho hai coaching mode, nhiều scope, thiếu dữ liệu, pause/return-to-training và changed-version. Không dùng hồ sơ thật chưa được phép để test.

**Đầu ra:** context DTO/query adapters, quyền dùng chung, source revision contract, migration cần thiết đã review, fixture.

**Điều kiện hoàn thành:** fixture chứng minh không gửi dữ liệu thừa, không rò scope; revoked access bị chặn kể cả khi có run cũ; unknown khác zero; source guard phát hiện đúng thay đổi liên quan. Chưa cần model để chứng minh những điều này.

## 4. P2-M2 — RAG, rules và AI pipeline tối thiểu

**Phụ thuộc:** P2-M1 cho dữ liệu cá nhân; nguồn RAG đã được review theo P2-M0. F01 kiến thức chung có thể chạy với Auth/Content/Audit cần thiết trước khi hoàn tất toàn bộ P2-M1.

**Công việc:**

1. F09: import/create → metadata → chunk/embedding → review → publish/archive, có permission và audit.
2. Xây bộ tri thức nhỏ theo nhu cầu F01–F03: kỹ thuật, thiết bị, thay bài, lập chương trình, recovery và progression; liên kết taxonomy với Exercise catalog. Thêm chủ đề nutrition khi chuẩn bị P2-M5.
3. Chunk giữ Document/Version/section/source refs; lưu embedding model/version/dimension; truy xuất chỉ ACTIVE và được phép, kể cả qua cache. Áp dụng ma trận 1.10: schema baseline vector(1536), dimension phải khớp; query/document thuộc cùng model space tương thích. Nếu đổi dimension, review migration/index trước ingest. Kiểm đổi model/reindex/switch/rollback không trộn không gian embedding; thêm gate T28.
4. Viết Context Builder và deterministic rules; pre-check trước model, schema/domain check sau model. Chặn instruction injection trong câu hỏi hoặc nguồn truy xuất; nội dung nguồn không có quyền ra lệnh cho hệ thống.
5. Tích hợp adapter LLM/embedding và test bằng provider giả lập trước; lựa chọn provider/model thực dựa trên rubric và đo thử được phép. Chưa yêu cầu train model.
6. F10 tối thiểu: AI Run, correlation ID, prompt/model/schema/rule/source refs, usage/latency/status; lỗi không thành recommendation áp dụng.
7. Xử lý background job khi cần: trạng thái bền vững, retry giới hạn, kiểm lại quyền lúc trả kết quả, kết quả trùng không tạo recommendation trùng. Không cần bổ sung một hệ thống agent tự hành.
8. Tạo trang Admin tối thiểu để quản lý tri thức, xem trạng thái run đã che dữ liệu nhạy cảm và tắt tính năng.
9. Hoàn thành F01 kiến thức chung xuyên Mobile → Spring → FastAPI → kết quả có citation; không lấy context cá nhân và không có Apply. Đây là bước kiểm chứng pipeline sớm; F01 cá nhân hóa hoàn thiện ở P2-M4.
10. Xây evaluation runner tối thiểu có dataset/version, rubric và báo cáo so sánh. Chạy retrieval/citation/schema regression ngay từ P2-M2; UI evaluation và báo cáo release hoàn thiện ở P2-M6.

**Đầu ra:** governed RAG, rule catalog, AI pipeline có validation, Run/audit tối thiểu, F01 kiến thức chung xuyên suốt và evaluation runner với benchmark retrieval/citation lần đầu.

**Điều kiện hoàn thành:** không retrieve DRAFT/ARCHIVED; truy được nguồn/version đã dùng; query không có bằng chứng trả giới hạn; provider error có fallback; không có đường ghi business state từ FastAPI.

## 5. P2-M3 — Luồng giáo án hoàn chỉnh đầu tiên

**Phụ thuộc:** P2-M2 và Workout command/version/approval từ P2-M1.

**Công việc:**

1. F02 input/output schema cụ thể; hỗ trợ một tuần mẫu, reps hoặc duration, đơn vị, equipment và supervision.
2. Spring: request → authorized context → AI → validate → AI Run và PENDING recommendation; có expiry/source refs.
3. Mobile: nhập/bổ sung dữ liệu còn thiếu, trạng thái xử lý, xem giáo án, căn cứ/giới hạn, before/after nếu thay plan, Accept/Reject; màn hình đúng mode và authority.
4. Xử lý người dùng sửa bản AI: lưu nguồn draft/diff, validate payload sửa và áp dụng qua cùng domain command; không giữ nhãn “AI đã xác nhận” cho nội dung người dùng vừa đổi.
5. Acceptance transaction theo ma trận mục 1.7: khóa/guard, kiểm quyền/source/rule/expiry, ghi ACCEPTED, domain change, application refs và audit, chuyển APPLIED rồi commit. Lỗi rollback giữ trạng thái trước transaction; error attempt ghi riêng. Kiểm retry sau mất response chỉ trả receipt cũ khi còn quyền và khớp operation/revision/payload; payload sửa khác trả conflict, không báo thành công giả. Không áp dụng lần hai; kiểm T25.
6. F11 phần đầu: ghi Accept/Reject như decision event; tùy chọn feedback/nhận xét tách riêng, không ép lý do để được Reject.
7. Kiểm tra race: double accept, plan vừa đổi, Goal thay, chuyển mode, Trainer bị revoke, timeout/retry và audit failure.

**Đầu ra:** demo được bằng cả SELF_DIRECTED và HUMAN_COACH với dữ liệu fixture, sau đó staging được phép; plan đã áp dụng dùng được trong luồng tập/log hiện có.

**Điều kiện hoàn thành:** F02 đạt ma trận; không đụng Actual Workout/Goal/Appointment; version/change history đúng mức thay đổi; một request chỉ tạo một kết quả nghiệp vụ.

Đây là mốc đầu tiên có AI proposal được phê duyệt và áp dụng vào nghiệp vụ. F01 kiến thức chung đã kiểm chứng pipeline tại P2-M2. Chưa phải hoàn thành toàn bộ Phase 2.

## 6. P2-M4 — Mở rộng hỗ trợ tập luyện

**Phụ thuộc theo chức năng:** F03/F05 cần cơ chế apply từ P2-M3 và domain nguồn. F01 cá nhân hóa cần P2-M1 scope + P2-M2; F04 cần Progress đã bàn giao + P2-M2, không bắt buộc chờ F02 hoàn tất. Mốc này nghiệm thu đủ F01/F03/F04/F05; có thể triển khai các luồng thông tin độc lập sớm hơn.

**Công việc:**

- F01: mở rộng luồng kiến thức chung từ P2-M2 sang cá nhân hóa có scope và citation; INFORMATION không có nút Apply.
- F03: typed changes cho bài/load/volume; phân loại minor/significant theo domain; không tăng tải khi dữ liệu/continuity không đủ.
- F04: tóm tắt từ Progress, nhận biết thiếu dữ liệu và nghỉ dài; view theo scope; liên kết sang đề xuất riêng khi người có quyền yêu cầu.
- F05: một Planned Workout mỗi lần; conflict/recovery/supervision trước và lúc accept. Appointment liên quan phải qua workflow hai bên riêng; không giả định PT đã đồng ý.
- Hoàn thiện Trainer review: nháp riêng, chia sẻ với Student có chủ đích, dữ liệu nguồn bị thu hồi không tiếp tục lộ qua output cũ.

**Đầu ra:** bốn chức năng có UI/API/schema và test riêng; coverage cases nhiều Goal/Trainer/Coaching Period.

**Điều kiện hoàn thành:** F01/F03/F04/F05 đạt ma trận; kết quả thông tin không bị biến thành mutation; planned/actual và appointment/workout vẫn tách biệt.

## 7. P2-M5 — Nutrition AI và food vision

**Phụ thuộc:** P2-M1 nutrition prerequisites, P2-M2 pipeline, quyền/media và catalog đã review. Có thể phát triển catalog/schema với fixture song song P2-M3/P2-M4 sau khi contract ổn định; không mở production sớm hơn các gate cần thiết.

**Công việc:**

1. Chuẩn hóa Food Database: canonical ID, tên/alias món Việt trong phạm vi đã chọn, serving/unit/conversion, nguồn/revision, nutrients. Tính toán xác định phải chạy được trước vision.
2. F06 text → candidates → Student sửa/xác nhận → backend calculate → confirmed log/provenance. Làm hoàn chỉnh Food Confirmation theo ma trận 1.9 trước F07: xác nhận nguyên tử nhóm item được chọn, guard draft/preview/catalog, receipt chống trùng, rollback và correction riêng. Catalog đổi phải refresh preview và Student xác nhận lại; không tự đổi số đã xác nhận. UI ESTIMATE có Confirm/Correct, không gọi recommendation Apply. Kiểm T26/T27.
3. F07 thêm upload có ownership, vision recognition/portion candidates và matching; tái sử dụng confirmation pipeline của F06.
4. Xử lý ảnh mờ/không hỗ trợ, món hỗn hợp/ingredient ẩn, unknown quantity, unmatched food và catalog thay đổi. Fallback text/manual luôn hiện rõ.
5. Không gửi profile đầy đủ để nhận diện món; không cấp Trainer quyền ảnh chỉ vì có nutrition scope.
6. F08 đọc Daily Target đã resolve và Progress completeness; hiển thị estimated/confirmed/corrected đúng ý nghĩa. Không tự sinh target định lượng mới trong scope đã phê duyệt.
7. Đánh giá catalog/vision trên tập ảnh được phép, có danh mục món và đáp án; Student confirmation vẫn bắt buộc kể cả khi benchmark tốt. Chốt tập portion hỗ trợ, coverage tối thiểu và ngưỡng sai số theo nhóm trước holdout; all-unknown không đạt gate portion. Nếu thiếu bằng chứng thì đánh dấu experimental/chưa hoàn thành và không tuyên bố hoàn tất toàn bộ scope.

**Đầu ra:** text + image logging end-to-end, nutrition summary, catalog/version và báo cáo giới hạn nhận diện.

**Điều kiện hoàn thành:** F06–F08 đạt ma trận; không có đường ghi confirmed intake từ ảnh/text chưa xác nhận; backend tính lại khi quantity/catalog đổi; nutrition history giữ nguyên qua đổi PT/Goal.

## 8. P2-M6 — Đánh giá, vận hành và phát hành

**Phụ thuộc:** F01–F09 hoàn thành; Run/audit có từ P2-M2 và decision feedback có từ P2-M3.

**Công việc:**

1. Hoàn thiện F10: search/filter run theo quyền, benchmark phiên bản, replay với dữ liệu authorized/sanitized và output evaluation riêng.
2. Hoàn thiện F11: feedback chủ quan + liên kết outcome từ Actual Workout/Progress có coverage; không coi reject là nhãn lỗi tuyệt đối, không tự retrain.
3. Chạy toàn bộ regression, concurrency, authorization, privacy, failure/fallback và phiên bản nguồn; đối chiếu F01–F11 với tiêu chí.
4. Đo quality, latency, usage/cost theo từng nhóm request; ghi rõ môi trường, model/prompt/knowledge/rule versions, cỡ mẫu và giới hạn.
5. Chuẩn bị hướng dẫn Admin publish/rollback knowledge, thay prompt/model được review, tắt tính năng và xử lý run lỗi. Không rollback bằng cách sửa lịch sử dữ liệu học viên.
6. Kiểm tra retention, ảnh, logs, secret/provider policy; backup/restore cho dữ liệu AI/knowledge quan trọng dựa trên cơ chế của dự án.
7. Chạy pilot có phạm vi trước khi mở rộng; giữ manual path hoạt động. Chốt báo cáo release và danh sách known limitations.

**Đầu ra:** release checklist ký duyệt, báo cáo test/evaluation, runbook, cấu hình phiên bản được chọn, known limitations.

**Điều kiện hoàn thành:** toàn bộ release gates đạt; không còn blocker về quyền, toàn vẹn dữ liệu hoặc chính sách chuyên môn chưa review cho chức năng được bật. Không dùng điểm chất lượng trung bình để bù một lỗi vượt quyền.

## 9. Trách nhiệm kỹ thuật và quy trình phối hợp

Các trách nhiệm sau được gán theo issue/PR, không phải role trong cơ chế phân quyền sản phẩm. Một thành viên có thể đảm nhận nhiều trách nhiệm; một chức danh không tự trao quyền truy cập dữ liệu hoặc quyền merge.

| Trách nhiệm | Phạm vi | Đầu ra cần review |
|---|---|---|
| Người phụ trách domain/module | Invariant, quyền, query/command, source guard và transaction | Contract, quyết định nghiệp vụ, test quyền/concurrency |
| Người quản lý schema/migration | Rà schema hiện có, migration mới, constraints/index, compatibility | Migration và bằng chứng kiểm tra theo quy trình DB |
| Người phụ trách chức năng | Triển khai luồng từ API/adapter đến UI trong phạm vi issue | Chức năng, client states, error handling và tests |
| Người phụ trách AI/RAG | Context, rules, retrieval/index, model adapters, schema validation, Run metadata | Pipeline có nguồn/version và evaluation evidence |
| Người review kỹ thuật | Review contract, thay đổi code, quyền và tính toàn vẹn | Review record và kết quả test có thể kiểm chứng |
| Người review chuyên môn | Review tri thức, rubric và ngưỡng tập luyện/dinh dưỡng trong phạm vi phù hợp | Nội dung/rule/benchmark được review; không thay quyền Student/Trainer |
| Người phê duyệt phạm vi phát hành | Duyệt D01–D07, thay đổi phạm vi và gate release | Quyết định có người duyệt, ngày và version |

Quy tắc phối hợp:

- Mỗi issue có một assignee chịu trách nhiệm bàn giao, reviewer, dependency, path dự kiến và contract bị ảnh hưởng. Khi cần sửa file thuộc công việc khác, thống nhất với người phụ trách trước.
- Mỗi thay đổi migration, OpenAPI hoặc schema dùng chung có một người điều phối tích hợp tại một thời điểm. Mọi thành viên có thể đề xuất; việc review không đồng nghĩa một người phải tự viết mọi thay đổi.
- Chốt DTO/schema của phần việc trước khi làm song song. Consumer có thể dùng fixture đúng contract trong khi provider hoàn thiện; fixture không chứng minh prerequisite production đã sẵn sàng.
- Không sửa migration đã áp dụng; không tạo bản schema thứ hai không được quản lý. Tuân thủ hướng dẫn DB/component trong repository.
- PR giới hạn theo chức năng, có evidence test và reviewer được phân công. Các thao tác Git và deployment tuân thủ quyền, yêu cầu tác vụ và `AGENTS.md`; tài liệu này không tự ủy quyền các thao tác đó.
- Tách backlog Phase 1 prerequisite khỏi backlog AI; có thể làm RAG, UI mock và evaluation fixture song song khi contract đủ rõ. Acceptance transaction phụ thuộc command/source guard thật.
- Đánh giá tải công việc và phụ thuộc trước khi chốt lịch; thay assignee không làm thay đổi authority của domain hoặc phạm vi chức năng.

Mẫu issue tối thiểu: feature/milestone ID; mục tiêu; đầu vào/đầu ra; prerequisite; assignee/reviewer; paths/contracts; acceptance/test IDs; migration nếu có; bằng chứng bàn giao. Tài liệu phân công cá nhân, nếu có, được quản lý riêng và không phải nguồn quy tắc nghiệp vụ.

## 10. Chuẩn bị dữ liệu RAG bắt đầu từ đâu

| Bước | Việc cụ thể | Đầu ra |
|---|---|---|
| 1 | Chọn câu hỏi/use case của F01–F03 trước | Danh sách câu hỏi cần trả lời và chủ đề cần bằng chứng |
| 2 | Chọn nguồn hợp lệ, đánh giá chất lượng và quyền sử dụng | Source register: title, author, URL/ref, date, license/permission, reviewer |
| 3 | Chuẩn hóa nội dung có section và liên kết Exercise taxonomy | Tài liệu review được, không trộn hồ sơ Student vào corpus |
| 4 | Gắn lifecycle và version trước khi chunk | Knowledge Document/Version metadata |
| 5 | Chunk thử, giữ nguồn/version; tạo embedding | Index thử nghiệm có version model/dimension |
| 6 | Dùng câu hỏi chuẩn có expected sources để đo retrieval | Báo cáo hit@k/nguồn sai/không đủ bằng chứng |
| 7 | Sửa nguồn/chunking/retrieval rồi mới publish | Tập ACTIVE nhỏ đã kiểm tra, mở rộng có kiểm soát |

Không lấy số lượng PDF làm thước đo hoàn thành. Food Database là dữ liệu có cấu trúc, Exercise catalog là danh mục nghiệp vụ; chúng không được thay bằng việc nhét tài liệu vào RAG. Ngưỡng recovery/progression cần rule policy có nguồn, không chỉ một đoạn prompt hoặc câu trả lời mô hình.

## 11. Kiểm thử và đánh giá

### 11.1. Các gate xác định bắt buộc

Mọi case bên dưới phải đạt; không có “cho phép lỗi” cho vượt quyền, ghi nhầm dữ liệu hoặc mất lịch sử.

| Case | Tình huống | Kết quả cần chứng minh | F liên quan |
|---|---|---|---|
| T01 | Student tự tập Accept plan | Chỉ plan/phạm vi xác nhận thay đổi; audit/version đúng | F02 |
| T02 | Student HUMAN_COACH tự Accept plan AI | Bị chặn; Trainer hợp lệ mới quyết định | F02/F03/F05 |
| T03 | Trainer không liên quan, view-only, revoked hoặc hết period | Không request/read/apply trái scope | F01–F08 |
| T04 | Thu hồi quyền trong lúc model đang xử lý | Không trả output nhạy cảm khi job hoàn tất hoặc mở lại | F01–F10 |
| T05 | Nguồn đổi trước hoặc trong transaction; insert plan/log/appointment cạnh tranh | Hard conflict hoặc revalidation đúng phân loại; khóa/constraint chặn race; thay đổi không liên quan không bắt sinh lại | F02/F03/F05 |
| T06 | Hai Accept đồng thời hoặc retry sau timeout | Một tác động nghiệp vụ; không duplicate plan/log/audit thành công | F02/F03/F05/F06/F07 |
| T07 | Measurement/body fat thiếu hoặc suspect | Unknown/giới hạn; không bịa zero hoặc suy ra tăng cơ | F02/F04 |
| T08 | Người mới khác người nghỉ 100 ngày | Rule xử lý khác, không lấy tải cũ làm năng lực hiện tại | F02/F03/F04 |
| T09 | Tập thứ Ba thay thứ Hai | Completion và schedule adherence khác nhau, Actual date không bị sửa | F04/F05 |
| T10 | PT bận; SELF_PERFORMABLE hoặc COACH_REQUIRED | Không tự cancel workout hoặc hạ yêu cầu supervision | F05 |
| T11 | Minor load change và strategic change | Change history hoặc plan version đúng policy, không sửa Actual | F03 |
| T12 | Knowledge DRAFT/ARCHIVED, cache cũ, nội dung có prompt injection | Không retrieve nguồn không hợp lệ; không thực thi chỉ dẫn vượt quyền | F01/F02/F03/F09 |
| T13 | Thiếu căn cứ, schema sai, Exercise ID giả, model timeout | Không tạo recommendation có thể áp dụng; có trạng thái/fallback | F01–F08/F10 |
| T14 | Text/ảnh thiếu lượng hoặc food match | Draft hỏi lại; không ghi confirmed calories tự suy đoán | F06/F07 |
| T15 | Student sửa quantity; catalog đổi lúc xác nhận | Tính lại có review và provenance; không nhân đôi log | F06/F07 |
| T16 | NOT_LOGGED/PARTIAL hoặc không có Nutrition Target | Không kết luận 0 intake/ăn thiếu hoặc adherence không có căn cứ | F08 |
| T17 | Target đổi giữa kỳ/override một ngày | Dùng đúng effective target mỗi ngày; không rewrite lịch sử | F08 |
| T18 | Trainer 1 → tự tập → Trainer 2, Goal mới | History còn; plan đã giao còn; quyền PT cũ và mới đúng scope | F02–F08/F11 |
| T19 | Admin replay hoặc Admin muốn accept plan | Replay chỉ evaluation; Admin không coaching authority | F10 |
| T20 | Publish knowledge/config nhạy cảm thiếu quyền/step-up | Bị chặn; publish hợp lệ có immutable audit | F09/F10 |
| T21 | Feedback reject hoặc không có outcome | Không biến thành nhãn chuẩn, không retrain/apply tự động | F11 |
| T22 | Ảnh bữa ăn sai owner hoặc Trainer chỉ có nutrition-log scope | Không xem ảnh/draft vượt quyền | F07 |
| T23 | Expiry, audit failure hoặc mất response sau commit | Rollback không giữ ACCEPTED/domain dở dang; lỗi attempt riêng; retry sau commit trả APPLIED cũ theo quyền | F02/F03/F05 |
| T24 | AI bị tắt/provider lỗi | Tạo plan/log thủ công Phase 1 tiếp tục hoạt động | F02/F06/F07/F10 |
| T25 | Retry cùng operation nhưng payload/revision khác, gồm hai màn hình hoặc mất response | Cùng payload đã commit trả receipt cũ theo quyền; khác payload/revision trả conflict; không báo bản sửa mới đã áp dụng | F02/F03/F05 |
| T26 | Food Confirmation nhiều item, một item lỗi, catalog race, audit lỗi hoặc response mất | Nhóm được chọn all-or-nothing; item bỏ chọn giữ draft; preview đổi phải xác nhận lại; retry cùng payload không tạo log trùng, khác payload conflict; không tự đặt ngày COMPLETE | F06/F07 |
| T27 | Hiển thị và thao tác INFORMATION/PROPOSAL/ESTIMATE | INFORMATION chỉ xem; PROPOSAL Accept/Reject đúng quyền; ESTIMATE Confirm/Correct qua Nutrition; backend từ chối gửi nhầm loại payload vào mutation khác | F01–F08 |
| T28 | Embedding sai dimension hoặc khác model space dù cùng dimension; đổi index | Cấu hình không tương thích bị chặn; query/index switch và rollback đồng bộ; không mất source/version lineage | F01/F02/F03/F09 |

### 11.2. Chất lượng mô hình và retrieval

Đây là **baseline ngưỡng thử nghiệm đã được phê duyệt trong thiết kế bản 1.0**, không phải kết quả đã đo hoặc bảo đảm an toàn chuyên môn. P2-M0 xác định dataset/reviewer/cách đo; ngưỡng chuyên môn còn thiếu và ngưỡng portion cần được review/chốt trước gate tương ứng. Có thể điều chỉnh sau pilot nhưng phải version hóa trước khi chấm bản release, không hạ ngưỡng chỉ để che regression.

| Nhóm | Tập đánh giá tối thiểu theo baseline | Cách chấm và gate |
|---|---|---|
| Retrieval | 30 câu hỏi trong phạm vi có nguồn kỳ vọng + 10 câu không đủ nguồn | Ít nhất 27/30 có nguồn phù hợp trong top 5; 10 câu không đủ nguồn không được trả khẳng định cá nhân hóa thiếu căn cứ |
| Workout proposals | 40 hồ sơ synthetic được review, phủ hai mode, người mới/nghỉ dài và constraints | Tất cả output được coi là áp dụng được phải qua schema/domain gate. Ít nhất 34/40 trả đúng hành vi kỳ vọng: đề xuất phù hợp hoặc yêu cầu bổ sung/chặn đúng. Reviewer chấm nội dung theo rubric, không chỉ JSON hợp lệ |
| Food text/image | 30 text + 30 ảnh được phép, có đáp án identity/quantity ground truth khi đo; tập khó/ngoài catalog riêng | Text: ít nhất 27/30 có đúng candidate trong top 3; ảnh: ít nhất 24/30 trong catalog có candidate đúng top 3. Case ngoài catalog phải có fallback. Đây là gate hỗ trợ nhập, không cho phép bỏ xác nhận |
| Tóm tắt/giải thích | 30 case gồm incomplete, stale, target đổi và inactivity | Ít nhất 27/30 đạt rubric đầy đủ/đúng nguồn; bất kỳ case bịa dữ liệu cá nhân hoặc vượt quyền là blocker dù tổng điểm đạt |
| Portion estimation | Tập hỗ trợ chốt trước holdout, có lượng cân/serving tham chiếu và conversion; tập ngoài phạm vi riêng | Đo coverage và sai số trên phần có ước lượng theo từng nhóm. Chốt coverage tối thiểu lớn hơn 0 và ngưỡng sai số bằng pilot/reviewer trước release test. All-unknown/manual không đạt; nếu chưa có dữ liệu đo thì chỉ experimental/chưa hoàn thành |

Reviewer nội dung cần hiểu tập luyện/dinh dưỡng trong phạm vi đã chọn. Review kỹ thuật của nhóm phát triển không thay thế kiểm tra chuyên môn. Corpus có thể chứa nguồn liên quan để retrieval tìm, nhưng bộ câu hỏi/ảnh/hồ sơ chấm cuối không dùng làm ví dụ few-shot hoặc dữ liệu chỉnh prompt; nếu đã dùng, chuyển thành development set và tạo holdout mới.

### 11.2.1. Cách đo và tránh kết luận vượt bằng chứng

- Các tập 30–40 case ở trên là baseline pilot, không chứng minh chất lượng trên mọi người dùng/món ăn. Chốt sample counts và tiêu chí từng nhóm trước release test; mở rộng holdout khi pilot cho thấy thiếu coverage, không chọn nhóm sau khi đã thấy điểm.
- Workout: báo riêng hai mode, người mới, nghỉ dài, thiếu dữ liệu và constraints. Chấm cả đúng đề xuất lẫn đúng ASK/BLOCK; đo false refusal trên case đủ dữ liệu, tránh vượt gate bằng cách luôn từ chối.
- Retrieval: top-5 hit chỉ là một chỉ số. Reviewer chấm nguồn thực sự hỗ trợ claim, citation đúng version và không gán citation không liên quan. Claims cá nhân bịa hoặc vượt quyền vẫn là blocker.
- Food: top-3 candidate chưa đủ cho ảnh nhiều món. Báo item recall/precision, món bị bỏ sót, món bịa thêm, unmatched và tỷ lệ người sửa. Lượng tham chiếu do đo/nguồn xác nhận, không lấy lượng model tạo làm ground truth.
- Portion coverage = số item có ước lượng lượng hợp lệ trước sửa tay / tổng item đủ điều kiện trong tập hỗ trợ. Báo cả số lượng mẫu, unknown/out-of-scope và coverage từng nhóm; không bỏ case khó khỏi mẫu số sau khi chạy. Sai số chỉ tính trên item đã ước lượng: báo MAE theo đơn vị chuẩn, sai số tương đối khi mẫu số hợp lệ và phân bố theo nhóm, không chỉ một trung bình chung.
- P2-M0 xác định owner/reviewer và cách chọn tập hỗ trợ; pilot P2-M5 chốt ngưỡng coverage/sai số theo nhóm trước khi mở holdout. Chưa chốt ngưỡng hoặc chỉ nhập tay thì chưa nghiệm thu portion estimation; muốn release thiếu phần này phải duyệt điều chỉnh scope D01/D06.
- Mỗi báo cáo ghi dataset split/version, prompt/model/config, rule, knowledge/catalog version, cỡ mẫu và số lần chạy. Chạy lặp để quan sát biến thiên và giữ toàn bộ kết quả; không chọn riêng lần có điểm cao. Regression tự động bắt đầu P2-M2, P2-M6 tổng hợp báo cáo và hoàn thiện UI.

### 11.3. Vận hành

- Đo riêng p50/p95 cho request nhận việc, thời gian trả kết quả AI, acceptance transaction và food confirmation; không gộp mọi API thành một số.
- Đo lỗi provider/validation/rule-block, cost mỗi loại chức năng, retrieval hit, lượng job chờ và tỉ lệ cần người sửa. Rule-block hợp lệ không tự coi là model failure.
- P2-M0 phải chốt giới hạn chi phí/timeout/rate theo ngân sách và provider được chọn. Nếu chưa có đo, ghi “chưa đo”; không phát hành dựa trên con số ước đoán.
- Chốt retention cho ảnh/context/evaluation và cách khôi phục trước pilot; logs vận hành không chứa raw sensitive payload.
- Có công tắc tắt từng chức năng; tắt generation không mặc nhiên xóa plan đã áp dụng. Có thể tạm chặn apply nếu phát hiện rule/content lỗi; không tự sửa history.

## 12. Truy vết yêu cầu và kiểm soát thay đổi

### 12.1. Ánh xạ phạm vi với mô tả và roadmap

| Điểm kiểm tra | Kết quả đối chiếu | Xử lý trong bộ tài liệu |
|---|---|---|
| AI Assistance không chỉ chatbot | Mô tả mục 37–49 và AI README gồm plan, thay bài, progression, lịch, summary | Bao phủ F01–F05, có dữ liệu và authority riêng |
| Nutrition AI nằm trong Phase 2 | Mô tả mục 86 và roadmap đều có food/portion | Giữ F06–F08 trong kết thúc Phase 2; làm sau workout |
| Knowledge, audit, evaluation, feedback là một phần sản phẩm | Mô tả mục 43/48/49 và governance | F09–F11 bắt buộc; Run/audit không đợi tới cuối mới làm |
| Phase 3/4 khác Phase 2 | Roadmap phân riêng commerce và sensing | Giữ ngoài scope; food vision không đồng nhất pose estimation |
| Target product có AI Goal/Nutrition proposal | Mô tả mục 6/29/36 có khả năng này, roadmap chưa phân use case chi tiết | Ghi nhận hoãn theo D05 đã phê duyệt, không giả là đã bị loại trước đó |

### 12.2. Các điểm kiểm soát quyền, dữ liệu và lifecycle

| Nguy cơ/điểm dễ hiểu sai | Quy tắc thiết kế |
|---|---|
| Sơ đồ AI chung dễ khiến mọi câu trả lời phải PENDING/Accept | Tách INFORMATION, PROPOSAL và ESTIMATE; thông tin không có apply |
| Kiểm quyền lúc tạo nhưng lộ output sau revoke | Kiểm lại khi job hoàn tất, khi đọc và khi apply; output chứa dữ liệu đã thu hồi không tự được xem |
| Chỉ kiểm planVersion, bỏ qua Goal, thiết bị, actual hoặc mode | Guard theo dependency từng chức năng, dùng revision/watermark từ module nguồn |
| Student HUMAN_COACH bị coi như không có quyền Goal/Nutrition | Giới hạn quyền quyết định chỉ theo domain; Student luôn giữ ownership Goal/strategic nutrition |
| Student được xem nháp Trainer hoặc PT được xem ảnh nutrition mặc định | Nháp chia sẻ chủ động theo D03 và media scope riêng; log scope không cấp ảnh/draft scope |
| Xếp lại workout đồng thời đổi hẹn PT | Tách domain command, change request hai bên và revalidation; không tự đổi recurring series |
| Ước lượng món được coi là Food Log hoặc calories của LLM là sự thật | Student confirmation + Food DB + deterministic calculation; giữ provenance |
| Template giáo án theo tuần bị coi là cam kết cả Goal 90/120 ngày | Giới hạn tuần mẫu được gắn nhãn D02, giữ lịch sử và review mỗi lần điều chỉnh |
| “Modified AI output” dùng nguyên validation cũ | Sửa bởi người dùng cần revalidate, giữ diff và nguồn draft |
| Confidence hoặc tổng điểm eval che một lỗi quyền/an toàn dữ liệu | Gate xác định bắt buộc; confidence không cấp quyền hoặc bỏ qua rule |
| Cùng account mang nhiều role gây cấp quyền quá rộng | Authority xét actor context, subject, capability và domain, không role name đơn lẻ |

### 12.3. Ánh xạ chức năng, milestone và kiểm thử

| Chức năng | Data/authority contract | Milestone | Test chính |
|---|---|---|---|
| F01 | Ma trận mục 2 | P2-M2 chung + P2-M4 cá nhân hóa | T03/T04/T12/T13/T27/T28 |
| F02 | Ma trận mục 3 | P2-M3 | T01–T08/T13/T18/T23/T24/T25/T27/T28 |
| F03 | Ma trận mục 4 | P2-M4 | T02–T08/T11/T18/T23/T25/T27/T28 |
| F04 | Ma trận mục 5 | P2-M4 | T03/T04/T07–T09/T18 |
| F05 | Ma trận mục 6 | P2-M4 | T02–T06/T09/T10/T23/T25/T27 |
| F06 | Ma trận mục 7 | P2-M5 | T06/T14/T15/T24/T26/T27 |
| F07 | Ma trận mục 8 | P2-M5 | T06/T14/T15/T22/T24/T26/T27 |
| F08 | Ma trận mục 9 | P2-M5 | T03/T04/T16–T18 |
| F09 | Ma trận mục 10 | P2-M2 | T12/T20/T28 |
| F10 | Ma trận mục 11 | P2-M2 Run/runner tối thiểu + P2-M6 đầy đủ | T04/T13/T19/T20/T24 |
| F11 | Ma trận mục 12 | P2-M3 + P2-M6 | T18/T21 |

Các tiêu chí áp dụng chung vẫn có hiệu lực dù không lặp trong cột test chính. F06/F07 Student-only ở request/confirmation nên các test Trainer chỉ kiểm đọc confirmed log theo scope, không cấp thêm use case Trainer nhập hộ.

### 12.4. Lịch sử thay đổi tài liệu

| Phiên bản | Thay đổi chính |
|---|---|
| 0.1 | Đề xuất phạm vi F01–F11, hợp đồng dữ liệu/quyền và milestone |
| 0.2 | Mapping DB/API, transaction V1, source guard phân loại, F01/evaluation sớm và gate portion coverage |
| 0.3 | Retry theo revision/payload; Food Confirmation nguyên tử; tương thích embedding; UI result types; T25–T28 |
| 0.4 | Chuẩn hóa tài liệu dùng chung; tách phân công cá nhân; bổ sung liên kết, metadata phê duyệt và trách nhiệm theo issue; giữ nguyên scope kỹ thuật |
| 1.0 | Chủ dự án phê duyệt phạm vi/thiết kế và D01–D07 ngày 04/10/2026; bổ sung routing agent. Giữ riêng gate triển khai/chuyên môn/evaluation, không ghi thành đã kiểm thử |

Bộ tài liệu định nghĩa 11 chức năng, 7 milestone và 28 tình huống T01–T28. Đây là phạm vi cần kiểm chứng, không phải báo cáo test đã đạt. Kết quả thực tế phải có phiên bản code/dataset, môi trường, thời điểm và evidence tại issue/PR hoặc báo cáo evaluation.

D01–D07 đã được phê duyệt. Các mục còn phải chốt/kiểm chứng: prerequisite Phase 1; ngưỡng rule chuyên môn; catalog/dataset/reviewer; model/provider; ngân sách, timeout, rate limit, retention; kết quả test/evaluation. Mỗi mục có người chịu trách nhiệm và gate tương ứng, không đóng chỉ vì đã có tài liệu.

## 13. Việc bắt đầu theo baseline đã phê duyệt

1. Dùng phạm vi và giới hạn D01–D07 đã ghi nhận APPROVED ngày 04/10/2026; kiểm tra tài liệu/contract bị ảnh hưởng và triển khai theo quy trình review của dự án. Các bước prerequisite, policy chuyên môn, provider/ngân sách và evidence vẫn phải hoàn thành.
2. Kiểm chứng prerequisites cho F02; hoàn thiện input/output schema và query source/version.
3. Chuẩn bị bộ tri thức nhỏ phục vụ F01–F03 cùng câu hỏi đánh giá; làm bằng chứng retrieval trước khi thu thập hàng loạt.
4. Chia prerequisite Phase 1 và phần bổ sung AI thành backlog riêng, cân tải theo assignee và dependency; chạy F01 kiến thức chung và evaluation runner ở P2-M2, bắt đầu F02 sau khi các gate domain đạt.

Không đặt ngày hoàn thành cụ thể khi chưa biết tiến độ Phase 1 và thời gian mỗi người có thể dành cho dự án. Sau P2-M0, ước lượng từng issue từ phạm vi đã kiểm chứng rồi mới ghép thành lịch làm việc.

## Tài liệu hiện hành trong repository

Đọc các tài liệu hiện hành dưới đây khi thực hiện công việc. Liên kết theo commit ở phần nguồn là snapshot để truy vết; nếu code/contract mới khác baseline, ghi nhận khác biệt trước khi triển khai phần chịu ảnh hưởng. APPROVED xác nhận phạm vi và thiết kế; không chứng minh API đã triển khai hoặc test đã đạt. Các invariant đã xác nhận tiếp tục có hiệu lực.

- [Phase 1 prerequisites](phase-1-implementation-plan.md)
- [Testing guide](testing-guide.md)
- [Repository instructions](../../AGENTS.md)
- [AI agent skill](../../.agent/skills/fitness-ai-assistance/SKILL.md)

## 14. Nguồn đối chiếu

- Mô tả hệ thống tham chiếu (`mô tả ý tưởng đồ án.docx`): mục 29–49, 64, 71, 79, 83, 85–91.
- [Roadmap](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/00-project-overview/scope-and-roadmap.md).
- [Acceptance criteria](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/01-requirements/acceptance-criteria.md).
- [Non-functional requirements](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/01-requirements/non-functional-requirements.md).
- [Governance/audit](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/09-security-operations/governance-and-audit.md).
- [AI recommendation lifecycle](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/08-ai/recommendation-lifecycle.md).
- [Phase 1 plan — tham chiếu cách tổ chức milestone, không dùng làm bằng chứng đã hoàn thành Phase 1](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/docs/07-development/phase-1-implementation-plan.md).

- [Enum DB tại baseline — V1](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/services/backend/src/main/resources/db/migration/V1__foundation.sql).
- [AI/Knowledge schema, vector(1536) — V9](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/services/backend/src/main/resources/db/migration/V9__ai_and_knowledge.sql).
- [Recommendation authority/application constraints — V16](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/blob/d354e0a594b5e703d18a43264c0bf4325a0911a6/services/backend/src/main/resources/db/migration/V16__domain_completeness_and_invariants.sql).
