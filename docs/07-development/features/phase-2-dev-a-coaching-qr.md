# F12 — Tìm đối tác huấn luyện bằng QR hai chiều

**Phase:** 2 — mở rộng Coaching, không phải tính năng AI.  
**Feature / work package:** `F12` / `P2-A-QR`.  
**Quyết định:** `D08`, chủ dự án phê duyệt trực tiếp trong hội thoại ngày 25/11/2026.  
**Assignee:** Dev A theo chỉ định của chủ dự án; reviewer kỹ thuật chưa chỉ định.  
**Branch:** `feat/phase2-dev-a-coaching-qr`.  
**Trạng thái:** APPROVED về phạm vi/luồng; `A-QR-01` (contract), `A-QR-02` (backend + OpenAPI + Flyway `V61__coaching_qr_identities.sql`), `A-QR-03` (Mobile `SH-07/08/09`, camera/ảnh/PNG codec) và `A-QR-04` (nối hai chiều B03, deep link `c/v`, chuyển entry hub/redirect route cũ) đã triển khai; `A-QR-05` (kiểm thử camera/ảnh trên thiết bị thật `QR01–QR10`) vẫn `PLANNED`. Reviewer kỹ thuật chưa chỉ định. Chưa có issue/PR phê duyệt riêng.  
**Module sở hữu:** Coaching; Auth/User/Student/Trainer cung cấp capability và hồ sơ qua public ports.

## 1. Phạm vi đã chốt

**Quét QR hoặc chọn ảnh QR → xem hồ sơ → gửi lời mời huấn luyện → bên kia chấp nhận.**

- Học viên mở “Tìm PT”; PT mở “Tìm học viên”. Cả hai có “Quét QR”, “Chọn ảnh QR” và “Mã QR của tôi”. Thay UI tìm tên/email; giữ danh sách quan hệ, yêu cầu đến/đã gửi và lịch sử.
- QR cá nhân hiển thị tên và tư cách STUDENT hoặc TRAINER. Tài khoản có hai capability chọn tư cách hợp lệ trước khi chia sẻ; backend ràng buộc mã với tư cách, không tin role client tự khai.
- Camera và ảnh phải giải mã QR thật. Sau khi đọc mã, dừng callback quét lặp, validate payload và tra cứu backend. Không tự gửi lời mời.
- Hồ sơ xem trước hiển thị tên, vai trò và ảnh nếu có nguồn được phép. PT có chuyên môn/trạng thái xác minh khi public projection hỗ trợ; không điền giả trường thiếu hoặc đọc hồ sơ riêng tư để bù.
- Người quét chủ động bấm gửi. Backend tạo PENDING qua command B03 hiện có; counterparty xem yêu cầu đến và chấp nhận/từ chối. Initiator có thể hủy PENDING theo policy hiện có.
- Chỉ hiển thị đã gửi/đã chấp nhận sau response commit thành công. Quét hoặc preview không tạo relationship, period, sharing grant hoặc conversation.
- Không xây friendship, Student directory, marketplace, rating PT hoặc workflow xin quyền dữ liệu mới trong F12.

## 2. Baseline đã kiểm tra và khoảng trống

| Bề mặt | Hiện có trong checkout | Thay đổi dự kiến |
|---|---|---|
| Student tìm PT | `TrainerDirectoryScreen.tsx`; `GET /api/v1/coaching/trainers`, tìm theo tên | Thay UI tìm kiếm bằng QR và profile preview |
| PT tìm Student | `StudentLookupScreen.tsx`; `GET /api/v1/coaching/students/lookup`, email chính xác | Thay UI email bằng QR; không mở danh bạ Student |
| Gửi hai chiều | `POST /api/v1/coaching/relationships/requests` và `/invitations`, `counterpartyId` + `commandKey` | Tái sử dụng, kiểm quyền độc lập với QR |
| Quyết định | `POST /api/v1/coaching/relationships/{relationshipId}/{accept|reject|cancel}` | Giữ version, receipts, audit và lifecycle B03 |
| QR backend | Chưa có trong read use case/controller đã kiểm tra | Bổ sung QR identity và authenticated minimal-profile resolver |
| QR Mobile | Có scheme `ai-fitness-coaching`; package manifest chưa khai báo camera, image picker hoặc QR renderer | Kiểm tra Expo SDK 57/native build/dependency trước triển khai |

Đây là kiểm tra source, không phải evidence regression. API tên/email đang tồn tại không bị ghi thành đã xóa. Rà consumer và deprecation tương thích khi triển khai; không xóa endpoint dùng chung chỉ vì Mobile đổi cách chọn người.

## 3. QR thật, dữ liệu và quyền

- Backend cấp mã công khai ngẫu nhiên khó đoán, gắn account và tư cách, ánh xạ bền vững trong PostgreSQL. Không chứa email, điện thoại, JWT, refresh token hoặc dữ liệu sức khỏe.
- QR mã hóa liên kết có version, scheme/route được kiểm soát và mã do backend cấp; không dùng bitmap trang trí, payload hằng hoặc hồ sơ mock. Chốt URI cụ thể cùng contract trước implementation.
- QR là định danh tìm người, không phải credential hoặc giấy cấp quyền. Resolver yêu cầu đăng nhập/capability phù hợp; cấm tự quét, sai tư cách và ID/role bị sửa trong link.
- Kiểm lại account/capability, Trainer eligibility/accepting-students và policy B03 lúc resolve, gửi và chấp nhận. QR cũ không duy trì quyền sau account lock/capability loss.
- Cho phép người dùng tạo lại mã sau xác nhận; backend vô hiệu mã cũ nguyên tử. Mở “Mã QR của tôi” không tự xoay mã mỗi lần để ảnh đã lưu tiếp tục dùng tới khi đổi mã hoặc account không khả dụng.
- Không có public/partial Student search. Mã không tồn tại, đã thay hoặc target không khả dụng dùng response che giấu phù hợp. Resolver có rate limit theo convention dự án; không ghi raw QR token/ảnh vào log hoặc analytics.
- Preview trả public DTO tối thiểu. Relationship ACTIVE vẫn chưa đủ đọc health data: cần effective period, sharing scope/level/time và authority hiện hành theo B03. Student vẫn sở hữu dữ liệu và lịch sử.
- Camera chỉ bật sau hành động người dùng và dừng khi background/rời màn. Ảnh được giải mã trên thiết bị, không tải lên backend/AI hoặc lưu lâu dài. Nếu nhiều QR hợp lệ trong ảnh, yêu cầu chọn rõ; không tự gửi theo kết quả đầu tiên.
- QR render có contrast, quiet zone và kích thước đọc được; tên/role nằm ngoài vùng mã. Chưa kiểm thử camera thật thì chưa tuyên bố nghiệm thu camera.

## 4. UI và navigation dự kiến

| Screen ID | Route dự kiến | Nội dung |
|---|---|---|
| SH-07 | `/(app)/coaching/qr` | Camera/chọn ảnh; target role từ entry context và được backend kiểm |
| SH-08 | `/(app)/coaching/my-qr` | QR thật theo tư cách, lưu ảnh, tạo lại mã sau xác nhận |
| SH-09 | `/(app)/coaching/qr-profile` | Profile tối thiểu từ resolver và CTA gửi yêu cầu/lời mời |

Đây là route thiết kế, chưa có runtime. ST-17 và TR-01/TR-02 mở các màn shared. Các entry cũ `/coaching/trainers` và `/coaching/invite` cần giữ tương thích/điều hướng sang QR khi triển khai; `/coaching/students/lookup` là API, không phải Mobile route. Link ngoài app đi qua auth/context/resolve trước preview, không tự gọi command. Logout/đổi account xóa preview và dữ liệu quét; không persist raw token trong cache lâu dài.

Trạng thái bắt buộc: xin/từ chối quyền camera và hướng dẫn cài đặt; chọn/hủy ảnh; đang giải mã; ảnh không có QR, sai hệ thống hoặc nhiều QR; đang resolve; offline/network error; không khả dụng, sai tư cách, tự quét; đang gửi/đã gửi; đã có yêu cầu/quan hệ theo backend; stale/409. CTA gửi chỉ bật sau resolve thành công. Account hai role thấy rõ tư cách hiện hành. Lỗi mạng chưa rõ kết quả giữ body/commandKey cho exact retry; 409 tải lại/review, không tự phát lại accept.

Tuân design tokens, tiếng Việt, target 44×44, dynamic type, screen-reader labels và thông báo trạng thái bằng chữ. Có chọn ảnh thay camera; QR không thay tên/vai trò dạng text.

## 5. Công việc Dev A, phụ thuộc và contract

| Thứ tự | Công việc | Đầu ra cần review |
|---|---|---|
| A-QR-01 | Chốt QR URI/version, DTO, lỗi, public fields và ranh giới B03 (**Đã chốt tài liệu thiết kế**) | [Contract thiết kế F12 Coaching QR](../../05-api/coaching-qr-contract.md); **reviewer kỹ thuật chưa được chỉ định**, phải gán ở issue/PR khi triển khai |
| A-QR-02 | Cấp/đọc/tạo lại mã và authenticated resolver (**Đã triển khai backend + OpenAPI + Flyway `V61__coaching_qr_identities.sql`**, chưa có Mobile, reviewer chưa chỉ định) | `CoachingQrController`, `CoachingQrService`, `JdbcCoachingQrStore`, `TrainerPublicPreviewQuery`, `V61__coaching_qr_identities.sql`, `contracts/openapi/openapi.yaml`, unit + PostgreSQL integration tests |
| A-QR-03 | QR renderer/lưu ảnh, camera, image picker/decoder; phụ thuộc 01/02 (**Đã triển khai Mobile SH-07/08/09 + PNG round-trip tests**, chưa chạy thiết bị thật) | `SH-07/08/09`, `coachingQrCodec.ts`, `expo-camera`/`expo-image-picker`/`expo-media-library` (SDK 57), giữ entry cũ tới khi xong A-QR-04 |
| A-QR-04 | Tái sử dụng B03 request/invitation/decision; phụ thuộc 02/03 (**Đã triển khai Mobile + HTTP B03 round-trip**) | `QrProfilePreviewScreen` (`requestTrainer`/`inviteStudent`, `commandKey` ổn định), deep link `c/v`, `CoachingHubScreen` + redirect route cũ, HTTP/Jest tests |
| A-QR-05 | Regression, QR round-trip, emulator và điện thoại thật; phụ thuộc 04 (**Tự động PASS, thiết bị thật NOT VERIFIED**) | [Báo cáo bằng chứng A-QR-05](phase-2-dev-a-coaching-qr-evidence.md): 48/48 backend + 858/858 mobile tests pass; còn mở gate camera/ảnh trên thiết bị thật |

Dev A chịu trách nhiệm end-to-end cho work package này, kể cả phần Coaching kế thừa B03; không chuyển toàn bộ B03/B08 sang Dev A. Thay contract/schema dùng chung vẫn cần owner/module review. Chưa gán deadline hoặc reviewer. Hoàn tất trước gate P2-M6; không đổi nghĩa/thứ tự bảy milestone AI P2-M0–P2-M6.

Contract thiết kế tại [F12 Coaching QR Contract](../../05-api/coaching-qr-contract.md) đã chốt URI `ai-fitness-coaching://coaching/qr?v=1&c=<publicCode>` (`publicCode` 24 bytes `SecureRandom` Base64URL không padding = 32 ký tự), bốn operation dự kiến (`GET /api/v1/coaching/qr/me`, `POST /api/v1/coaching/qr/me`, `POST /api/v1/coaching/qr/me/rotate`, `POST /api/v1/coaching/qr/resolve`), bảng mã lỗi/rate limit, thứ tự che giấu target, các field hồ sơ thật (kèm khoảng trống public projection cho avatar và chuyên môn PT, `TrainerVerificationStatus.VERIFIED`), tái sử dụng B03 + `coaching_command_receipts` và danh sách file dự kiến cho `A-QR-02`/`A-QR-03`. Executable OpenAPI, typed client và migration chỉ sửa cùng implementation sau checkpoint tài liệu này. Không mô tả endpoint dự kiến là đang hoạt động. Public QR identifier không thay `commandKey` của domain command; chỉ thêm forward migration cần thiết, không sửa hoặc ghi đè migration đã áp dụng.

## 6. Acceptance và evidence bắt buộc

| Test ID | Tiêu chí / evidence cần có |
|---|---|
| QR01 | Hai account thật: QR PT backend cấp → Student quét camera → đúng profile → gửi → PT thấy PENDING → PT chấp nhận; kiểm relationship/period và audit thật |
| QR02 | Chiều ngược: QR Student → PT quét → đúng profile → mời → Student chấp nhận; không friendship |
| QR03 | Lưu QR thật từ app rồi chọn chính ảnh đó trên app bên kia; decoder/resolve đúng account ở hai tư cách; mock scanner callback không đủ |
| QR04 | Payload không có PII/credential; sửa role/ID/version, mã ngoại lai, ảnh rỗng/nhiều mã, tự quét và QR đã tạo lại không gây sai profile hoặc mutation |
| QR05 | Account inactive, capability sai, PT không đủ eligibility/không nhận học viên bị chặn server; trạng thái đổi sau preview cũng được kiểm lại lúc gửi/accept |
| QR06 | Double callback/tap/exact retry không tạo trùng; commandKey đổi payload conflict; gửi/accept đồng thời giữ uniqueness và period non-overlap B03 |
| QR07 | Chỉ counterparty quyết định, chỉ initiator hủy PENDING; refresh incoming/outgoing đúng; rollback không báo thành công hoặc tạo history dở dang |
| QR08 | Quét/preview/PENDING không mở health/Goal/nutrition/chat/history; ACTIVE vẫn cần sharing; giữ fitness history khi chuyển period |
| QR09 | Camera denied, hủy ảnh, offline, mất response, stale/409, role/account switch, logout/background có states/retry/cache đúng; accessibility tiếng Việt |
| QR10 | Ghi thiết bị/OS/build, code revision, account test và kết quả mỗi chiều/camera/ảnh; kiểm điện thoại thật cho camera/round-trip/ảnh, emulator-only không đóng gate camera thật |

Backend: unit và PostgreSQL HTTP/transaction/concurrency/authorization regression B03. Mobile: typecheck, lint, flow/client tests và QR encode–decode round-trip. Contract: OpenAPI/UX validation. Fixture dùng cho automated tests, không thay QR/backend thật trong nghiệm thu. Không dùng tài khoản production hoặc thay dữ liệu thật của người dùng để tạo evidence.

Chỉ DONE sau evidence và review; D08, branch hoặc tài liệu này không chứng minh code hoàn thành. F12 không có AI Run, resultType hoặc Recommendation Apply.

## 7. Tài liệu liên quan

- [Báo cáo bằng chứng nghiệm thu A-QR-05](phase-2-dev-a-coaching-qr-evidence.md)
- [Contract thiết kế F12 Coaching QR (A-QR-01)](../../05-api/coaching-qr-contract.md)
- [Release scope và D08](../../00-project-overview/phase-2-release-scope.md)
- [Phase 2 implementation plan](../phase-2-implementation-plan.md)
- [B03 domain và lifecycle](03-coaching.md)
- [Data/authority matrix](../../08-ai/phase-2-feature-data-authority-matrix.md)
- [UI inventory](../../06-ui-ux/screen-inventory.md)
- [Student flows](../../06-ui-ux/student/student-flows.md) và [Trainer flows](../../06-ui-ux/trainer/trainer-flows.md)
