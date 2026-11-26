# F12 Coaching QR — Báo cáo bằng chứng nghiệm thu (A-QR-05)

> **Trạng thái nghiệm thu:** `PARTIAL / NOT DONE` — Đã đạt toàn bộ kiểm thử tự động (Backend MockMvc + PostgreSQL Testcontainers, Mobile Jest + PNG round-trip, OpenAPI/UX validation). **Gate thiết bị thật (`QR01–QR10` camera + native image picker/save) vẫn `NOT VERIFIED`** do chưa có điện thoại thật kết nối trong môi trường hiện tại.  
> **Reviewer kỹ thuật:** **Chưa được chỉ định** (chưa đủ điều kiện đóng PR/merge).

## 1. Thông tin môi trường & Revision

- **Ngày kiểm thử:** `2026-11-26` (UTC+7)
- **Branch:** `feat/phase2-dev-a-coaching-qr`
- **Base `HEAD` SHA:** `7d7d6dad14ddeb59a8c1b61c3fe2cdb8da2a5674` *(Lưu ý: working tree chưa commit; SHA này **không** đại diện cho toàn bộ code đang chạy).*
- **`git diff` SHA-256 (tracked changes tại thời điểm chạy full suite trước sửa `Asset.create`):** `0cc6a9f6a30c15c2e237ae2a32918293f41995a010e0dc3971f4a4a072907379` *(không bao gồm 27 file mới untracked; xem đầy đủ manifest + SHA-256 cả tracked & untracked tại mục 5).*
- **Xác nhận từ chủ dự án (26/11/2026):** Chủ dự án đã trực tiếp kiểm thử và xác nhận chức năng hoạt động trong môi trường kiểm thử cô lập hiện tại (`emulator-5554` + `:18080` + `:5434`). **Giới hạn xác nhận:** Xác nhận này chỉ ghi nhận trải nghiệm trong môi trường kiểm thử hiện tại, **không** suy diễn rằng đã kiểm thử toàn bộ `QR01–QR10`, cả hai chiều hay camera điện thoại thật; F12 **chưa** được đánh dấu hoàn tất.
- **Backend test runtime:** Spring Boot + MockMvc + PostgreSQL 18 Testcontainers (`pgvector/pgvector:pg18`), Flyway `V1`..`V61__coaching_qr_identities.sql` trên DB cô lập. Ngoài ra đã chạy kiểm chứng HTTP trực tiếp trên backend cô lập (`:18080`) + DB PostgreSQL cô lập (`:5434/digital_fitness_qr_test`): `GET /me` (`404 COACHING_QR_NOT_FOUND`) → `POST /me` (`201`, 32 ký tự) → `GET /me` lại (`200`, giữ nguyên mã) → `POST /me/rotate` (`200`, `version: 2`) → `POST /resolve` mã cũ (`404 COACHING_QR_NOT_FOUND`) → `POST /resolve` mã mới (`200`).
- **Bảo toàn môi trường đang chạy:** Database `localhost:5433/digital_fitness` (đã có `V61`/`V62` của F06 trong `flyway_schema_history`) và backend PID `6284` trên `:8080` được giữ nguyên trạng. Phương án hợp nhất khi merge F06 + F12: giữ nguyên `V61`/`V62` của F06 và đổi số `V61__coaching_qr_identities.sql` thành `V63__coaching_qr_identities.sql` (hoặc số trống kế tiếp) theo quyết định của chủ dự án/reviewer.
- **Mobile runtime:** React Native `0.86.3`, Expo `~57.0.24`, Jest `29.7.0`, TypeScript `tsc --noEmit`, `expo lint`.
- **Thiết bị / Emulator:** `emulator-5554` (`sdk_gphone64_x86_64`, Android 16) đã cài bản native Android (`com.anonymous.aifitnesscoaching`, `app-release.apk` ký bằng `debug.keystore`, nhúng `EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:18080/api/v1`) kết nối tới backend QR cô lập (`:18080`) và PostgreSQL cô lập (`:5434/digital_fitness_qr_test`). **Đã xác minh runtime trên Pixel 8 emulator:**
  - Đăng nhập `qr.student@example.com` (`QR Test Student`), mở `Huấn luyện cá nhân` → `Mã QR của tôi` (`SH-08`) → backend cấp `v1` (`KGACN5K4gH9bhOpuxTtoQN6Zg1qlK-bh`, `ACTIVE`), đóng và mở lại vẫn giữ nguyên `v1`.
  - Bấm `Lưu ảnh QR` → sửa lỗi runtime Expo SDK 57 (`MediaLibrary.saveToLibraryAsync` bị ném lỗi ở `expo-media-library ~57.0.6`, chuyển sang `MediaLibrary.Asset.create` với `granularPermissions: ['photo']`) → lưu PNG thành công (`Đã lưu ảnh mã QR vào thư viện ảnh của bạn.`).
  - Vào `Tìm huấn luyện viên qua QR` (`SH-07`) → `Chọn ảnh QR từ thư viện` → chọn đúng ảnh PNG vừa lưu trong Android Photo Picker → `Camera.scanFromURLAsync` giải mã trên thiết bị và gọi `POST /api/v1/coaching/qr/resolve` (`COACHING_QR_SELF_SCAN`).
  - Bấm `Tạo lại mã QR` → `Xác nhận đổi mã` → lên `v2` (`KwsWcYHZeGu6IZgB5fhhlgVdb-S9iCPZ`, `ACTIVE`), `v1` chuyển `REVOKED` trong DB và gọi `/resolve` với mã `v1` trả `404 COACHING_QR_NOT_FOUND` (mã `v2` trả `200`).
  - Sau khi rebuild `app-release.apk` (sau sửa 5 findings review): `pm clear` để ở trạng thái chưa đăng nhập, cold-start bằng `ai-fitness-coaching://coaching/qr?v=1&c=KwsWcYHZeGu6IZgB5fhhlgVdb-S9iCPZ` → chuyển màn hình đăng nhập → đăng nhập `qr.trainer@example.com` → tự động chuyển tiếp tới `qr-profile-preview-screen` (`SH-09`, `QR Test Student`, `Trạng thái hiện tại: Đang chờ`, không tự gửi lời mời); bấm `Quét mã khác` → `Chọn ảnh QR từ thư viện` → chọn ảnh PNG giải mã và mở `SH-09` thành công.
  - **Không có điện thoại thật kết nối**, nên gate quét camera trực tiếp hai chiều trên thiết bị thật (`QR01–QR10`) vẫn giữ **`NOT VERIFIED`**.

## 2. Kết quả kiểm thử tự động đã chạy

| Hạng mục | Phạm vi & Môi trường | Kết quả |
|---|---|---|
| Backend Coaching + QR Regression (sau sửa findings) | `./mvnw -B test "-Dtest=CoachingQrServiceUnitTest,CoachingQrIntegrationTest,CoachingErrorAdviceTest,CoachingMobileReadContractsIntegrationTest,CoachingLifecycleIntegrationTest,CoachingSharingIntegrationTest"` (Testcontainers `pgvector:pg18`) | **PASS — 50/50 tests** (`Failures: 0, Errors: 0, Skipped: 0`) |
| Mobile QR + B03 Regression (sau sửa findings) | `npm --prefix apps/mobile test -- coachingQrScreens.test.tsx coachingQrCodec.test.ts coachingScreens.test.tsx coachingApi.test.ts coachingMessages.test.ts coachingQueries.test.ts` + `typecheck:mobile` + `lint:mobile` | **PASS — 5/5 suites, 30/30 tests; 0 TS errors; 0 lint errors** |
| Mobile Full Jest Suite (trước sửa findings) | `npm --prefix apps/mobile test -- --runInBand --forceExit` | **PASS — 98/98 suites, 858/858 tests** |
| OpenAPI & AI Schema Contracts | `npm run validate:contracts` (`redocly lint` + Ajv) & `npm run test:contracts` | **PASS — OpenAPI valid, 21/21 contract tests** |
| UX Docs & Git Diff Check | `npm run validate:ux-docs` & `git diff --check` | **PASS — 18/18 UX docs, 0 diff errors** |

## 3. Ma trận trạng thái QR01–QR10 (phân tách theo tầng bằng chứng)

| Test ID | Backend / PostgreSQL (Testcontainers) | Mobile Unit / Jest + PNG Codec | Thiết bị thật (Camera + Native Gallery) | Kết luận hiện tại |
|---|---|---|---|---|
| **QR01** | **PASS** (`CoachingQrIntegrationTest`: PT QR → Student resolve → `POST /requests` → PT `INCOMING` → `accept` → `ACTIVE` + `HUMAN_COACH`) | **PASS** (`coachingQrScreens.test.tsx`: SH-09 gửi `requestTrainer`, `commandKey` ổn định) | **NOT VERIFIED** (chưa quét camera thật) | **NOT VERIFIED** |
| **QR02** | **PASS** (`CoachingQrIntegrationTest`: Student QR → PT resolve → `POST /invitations` → Student `reject`/`accept`) | **PASS** (`coachingQrScreens.test.tsx`: SH-09 gửi `inviteStudent`) | **NOT VERIFIED** (chưa quét camera thật) | **NOT VERIFIED** |
| **QR03** | **PASS** (`POST /qr/me` + `POST /qr/resolve` dùng `publicCode` 32 ký tự thật) | **PASS** (`coachingQrCodec.test.ts`: PNG thật encode → `pngjs` → `jsQR` decode đúng `qrUri`; Jest mock `MediaLibrary`/`ImagePicker`/`Camera.scanFromURLAsync`) | **NOT VERIFIED** (chưa lưu PNG vào gallery thật & chọn lại bằng `Camera.scanFromURLAsync` trên máy thật) | **NOT VERIFIED** |
| **QR04** | **PASS** (không PII/JWT, `rotate` vô hiệu mã cũ trả `404`, self-scan `400`, role mismatch `409`, audit không lộ `publicCode`) | **PASS** (`parseCoachingQrUri` chặn sai scheme/path/v!=1/code, scanner lock chống double callback, picker nhiều QR) | **NOT VERIFIED** (chưa test thực tế nhiều QR trước camera/ảnh thật) | **NOT VERIFIED** |
| **QR05** | **PASS** (account inactive / revoked role trong DB / `isAcceptingStudents=false` bị chặn `404`/`403` ngay cả sau preview) | **PASS** (mapping lỗi `403`/`404`, chặn PT chưa eligible ở SH-08) | **NOT VERIFIED** | **NOT VERIFIED** |
| **QR06** | **PASS** (6 luồng đồng thời `POST /qr/me` chỉ tạo 1 `ACTIVE`; `rotate` replay, payload conflict `409`, replay sau rotate kế tiếp `409`) | **PASS** (retry giữ nguyên `commandKey`, double-tap bị khóa, `409` refetch state mới) | **NOT VERIFIED** | **NOT VERIFIED** |
| **QR07** | **PASS** (chỉ counterparty `accept`/`reject`, initiator `cancel`, rollback B03 giữ nguyên) | **PASS** (`coachingScreens.test.tsx` B03 `RelationshipDetailScreen`) | **NOT VERIFIED** | **NOT VERIFIED** |
| **QR08** | **PASS** (`CoachingAuthorityQuery.evaluateCurrent` từ chối `WORKOUT_HISTORY` sau `resolve`, `PENDING`, và cả sau `ACTIVE` khi chưa cấp sharing) | **PASS** (SH-09 không tạo quan hệ/quyền tự động) | **NOT VERIFIED** | **NOT VERIFIED** |
| **QR09** | **PASS** (`429 RATE_LIMIT_EXCEEDED` + `Retry-After`) | **PASS** (offline, `Retry-After`, camera denied, hủy chọn ảnh, đổi capability giữa chừng, deep link `c/v`) | **NOT VERIFIED** (cần xác minh UX/a11y/quyền OS thực tế trên máy thật) | **NOT VERIFIED** |
| **QR10** | — | — | **NOT VERIFIED** (chưa có thiết bị thật kết nối để ghi model/OS/build/ảnh) | **NOT VERIFIED** |

## 4. Hướng dẫn đóng gate trên thiết bị thật (cần người dùng thực hiện)

1. **Build & chạy backend cô lập:** Khởi động PostgreSQL test sạch (Flyway `V1`..`V61`, **không** chạy trên DB có dữ liệu thật) và chạy `services/backend`.
2. **Build Expo Development Build (SDK 57):**
   - Trong `apps/mobile`, cấu hình `EXPO_PUBLIC_API_BASE_URL` trỏ tới backend test.
   - Tạo development build cho Android/iOS (`npx expo run:android` hoặc `npx expo run:ios`, vì `expo-camera` + `expo-media-library` cần native permissions).
3. **Chuẩn bị 2 tài khoản test cô lập** (chưa có quan hệ `PENDING`/`ACTIVE`/`PAUSED` với nhau):
   - Tài khoản A: `STUDENT` active + `student_profiles`.
   - Tài khoản B: `TRAINER` active + `VERIFIED` + `is_accepting_students = true`.
4. **Chạy kịch bản thực tế trên 2 máy (hoặc 1 máy + 1 màn hình):**
   - **Chiều 1 (QR01):** Máy B mở `Mã QR của tôi` (`SH-08`) → Máy A mở `Tìm huấn luyện viên qua QR` (`SH-07`) → cấp quyền camera → quét QR trên màn hình B → kiểm tra `SH-09` hiện đúng tên B, `VERIFIED`, chuyên môn → bấm `Gửi yêu cầu huấn luyện` → Máy B vào Coaching hub thấy `PENDING` đến và bấm `Chấp nhận` → kiểm tra cả 2 bên thành `ACTIVE`.
   - **Chiều 2 (QR02):** Tạo cặp C/D mới; Máy C (`STUDENT`) mở `SH-08`, Máy D (`TRAINER`) quét bằng camera → gửi lời mời → Máy C nhận và `Chấp nhận` / `Từ chối`.
   - **Lưu & chọn ảnh (QR03):** Trên `SH-08`, bấm `Lưu ảnh QR` → cấp quyền thư viện → gửi/mở ảnh PNG đó trên máy đối tác → ở `SH-07` bấm `Chọn ảnh QR từ thư viện` → xác nhận giải mã và mở đúng `SH-09`.
   - **Rotate & Deep link (QR04, QR09):** Bấm `Tạo lại mã QR` trên `SH-08` → quét lại ảnh cũ phải báo `404` (`COACHING_QR_NOT_FOUND`); mở deep link `ai-fitness-coaching://coaching/qr?v=1&c=<publicCode>` cả khi app đang mở và khi cold start (sau khi đăng nhập nếu chưa có phiên) → xác nhận chỉ mở `SH-09`, không tự gửi.
5. **Ghi nhận bằng chứng:** Chụp ảnh màn hình (đã che token/credential), ghi rõ model máy, OS, app build, commit/diff SHA và cập nhật bảng mục 3 thành `PASS` trước khi mời reviewer kỹ thuật duyệt merge.

## 5. Manifest & SHA-256 toàn bộ thay đổi F12 (Tracked + Untracked)

> Toàn bộ 38 file sửa (`M`) và 28 file mới (`??`) trong `git status --short` hiện tại đều thuộc phạm vi **F12 / D08** (không có thay đổi nghiệp vụ ngoài luồng lẫn vào). Bảng dưới ghi nhận SHA-256 của 65 file code/tài liệu tại thời điểm hoàn tất sửa findings `2026-11-26` (trước khi cập nhật chính file báo cáo này):

| Trạng thái | Đường dẫn | SHA-256 |
|---|---|---|
| `M` | `.agent/skills/fitness-goal-coaching/SKILL.md` | `e035256879e6c139b0cc0efbf02c0684626e7d64e4e5f533d226e633ae91262e` |
| `M` | `.agent/skills/fitness-mobile-client/SKILL.md` | `cac721f652408444b0ebd2ba2677499a79ce56de4038d43f3a3e46782175514a` |
| `M` | `AGENTS.md` | `39207d6a099ca4f1e64a8aca18dd0a42f8b7a0f0b863db5362e11e5679682d9f` |
| `M` | `apps/mobile/app.json` | `de3e26f512974135cff84e2c8d34e1d059ecb3ec7f946399059f510a2865b9e0` |
| `M` | `apps/mobile/package.json` | `87a8abb5678eeab599e1e4b1fc93d277b4585f2c2415d9ce989cc60f97880108` |
| `M` | `apps/mobile/package-lock.json` | `7cfc36865da64c3b0f5c4cad3c30a89e885f73ad7b3053079554d70b385221a1` |
| `??` | `apps/mobile/src/__tests__/coachingQrCodec.test.ts` | `212d2c52339cda9de860f4e3be5ab2f8656f4b71b4d74e195975b771e1700127` |
| `??` | `apps/mobile/src/__tests__/coachingQrScreens.test.tsx` | `f7566e8e29de73ec9406eba3ecf99bd09e2a335699a23f2cc7f663ec1e99d711` |
| `M` | `apps/mobile/src/app/(app)/_layout.tsx` | `584f1ac77b0e3c6e795708f8bbaeb5548116761ded66801f7d37cc605f0ada6b` |
| `M` | `apps/mobile/src/app/(app)/coaching/invite.tsx` | `38c9f2c9c1277b207d9ffdb5d9e8397d15fb989087b9d82880405502ddca1e03` |
| `??` | `apps/mobile/src/app/(app)/coaching/my-qr.tsx` | `f8fba969025be12450f113ee82801925c1091c90f9e59715e5c4a433f587047b` |
| `??` | `apps/mobile/src/app/(app)/coaching/qr.tsx` | `b540c1e03a8244f3ed92d67795594715afd820291af643a8b734af528742e421` |
| `??` | `apps/mobile/src/app/(app)/coaching/qr-profile.tsx` | `dfc1aa38dd83b8823a75bda7b20aff10468bbe50e2129db03f38f562bc450f43` |
| `M` | `apps/mobile/src/app/(app)/coaching/trainers.tsx` | `080bbdb5c3ca9a385dc94494c8a58129f0908e542965ea3b4d343a0d4a7ca451` |
| `M` | `apps/mobile/src/app/(auth)/_layout.tsx` | `d26dd9623b3f90a9ec254c2feb1da3bcac8823d2177588a5c2c9c011b892d583` |
| `M` | `apps/mobile/src/app/_layout.tsx` | `36737931d8bc94608c7c12eb23b2d4396ee01c4dccc8dfb207d3d2476466649a` |
| `M` | `apps/mobile/src/features/auth/AuthContext.tsx` | `34dac7552b50a0ae7aef4432b49148a62b758fb3ff733cd982e77d6ffe1c8e45` |
| `M` | `apps/mobile/src/features/coaching/CoachingHubScreen.tsx` | `c822a6609b1709f2dacb4cc1a246b3435f8b0a16d75b8a3b84318428b483a15e` |
| `M` | `apps/mobile/src/features/coaching/coachingMessages.ts` | `68163648be8c102bef799f364e83f4111f3a3b0a5dc6bf8c93e9ac7440700973` |
| `??` | `apps/mobile/src/features/coaching/coachingQrCodec.ts` | `aa3c3bdff387f40be1c4d20dccbc6dc489469d3d33798875d329f1165003ef7e` |
| `??` | `apps/mobile/src/features/coaching/CoachingQrScannerScreen.tsx` | `3d62bb3e45be5c3fa1074fe1e9774e57e310fdc40b9ab6e4006cb9f973a0c677` |
| `M` | `apps/mobile/src/features/coaching/coachingQueries.ts` | `ca46aaa01b973c5764e02db43ee643382aa2536672bf46d915133144a4554280` |
| `??` | `apps/mobile/src/features/coaching/components/CoachingQrMatrixView.tsx` | `578d132a641db6a5fd1088c1ad89aefcffafb2429ba59d1cc5542201f0cabef4` |
| `??` | `apps/mobile/src/features/coaching/MyCoachingQrScreen.tsx` | `401cb3be0f1b76e97eeb2e75c8487b6fd5c02aa372941cd8dfc2e20e38dffa95` |
| `??` | `apps/mobile/src/features/coaching/pendingQrIntent.ts` | `0f96e64bb82aea9315c339fd95dbb2048b6e1b3fd66739ee4948525d61f0fbf1` |
| `??` | `apps/mobile/src/features/coaching/QrProfilePreviewScreen.tsx` | `9769d7482e4441dbd8e5aa74ea331a2ec3a73862b12f1c82c206c526fbbf319d` |
| `M` | `apps/mobile/src/services/apiClient.ts` | `4314f51a4bec63b1a0d5d9225d7186d01345298d871afea7c2b3c6bf1db94e64` |
| `M` | `apps/mobile/src/services/coachingApi.ts` | `5a87810c9700682a55d545d3f182e91c111b67f7523dc1575af5af224402bb64` |
| `M` | `apps/mobile/src/types/auth.ts` | `058e64d1e161be803a9684b7ad31e562658224508696d12446002f1d9c0534c9` |
| `M` | `apps/mobile/src/types/coaching.ts` | `9cee411fbb8063acd9bd75849cc65b1b41f269947a63eca172fb759ea99398c1` |
| `M` | `contracts/openapi/openapi.yaml` | `9c13513584ab5e0aee2df86db8546a9423a75ce4f6f2acb5a2f032175e5959dd` |
| `M` | `docs/00-project-overview/phase-2-release-scope.md` | `62103fbf86c7cdfa1cf18379792b33e73fd37c53e287f2f33b4564bcd740d3ec` |
| `M` | `docs/00-project-overview/scope-and-roadmap.md` | `5feb7745d4611c3849eff7fc6dca7411725aab67f1ff49ff9d9eaaaca9086f8f` |
| `M` | `docs/01-requirements/acceptance-criteria.md` | `31833dfb37566c96968f21cc6615cf82fa2081f20a277af6c9102ff00841085d` |
| `??` | `docs/05-api/coaching-qr-contract.md` | `9ec3d286eae016704f549ff85c0ef5e53c071ac1eda97a819324a155850b359e` |
| `M` | `docs/05-api/README.md` | `3294c81632e201f31feafce6c7c9363e04169012175f3ddbd794e5b683c65ca2` |
| `M` | `docs/06-ui-ux/navigation-architecture.md` | `5a0c1faafee60840fd251d702de3e68bf582a680069bb92d8b002c34b92220fd` |
| `M` | `docs/06-ui-ux/references/traceability-matrix.md` | `dfa83b2e9609150385e501b02835b14f95f043c1b1dd13ed9d8ba23396e53433` |
| `M` | `docs/06-ui-ux/screen-inventory.md` | `4fa8d6361b5061cae6181d6d63f0e34de85d5e3583dcf278a87cf09bbd9ec6f2` |
| `M` | `docs/06-ui-ux/student/student-flows.md` | `a9f4e5b6ff310e1c3f2a38968e5caeabffe4f93f8e869a100ea058b0a80f8bf1` |
| `M` | `docs/06-ui-ux/student/student-screens.md` | `537a02060eb2a21fa55d5fb323e13c7094db1cea9b5bdadbcc4509c0364aaae8` |
| `M` | `docs/06-ui-ux/trainer/trainer-flows.md` | `9559771dcb4e95c3d293d372e2e683fbc8c93093f2eac8cd2ebb4ad0c9564692` |
| `M` | `docs/06-ui-ux/trainer/trainer-screens.md` | `4083688ec707207c327bc91f827b1fa0e187906b5e6bce013228531a001081c5` |
| `M` | `docs/07-development/features/03-coaching.md` | `10cb25ddac053354fe39449af0a32d02936a0be35a8f28ddd3a0d90218991987` |
| `??` | `docs/07-development/features/phase-2-dev-a-coaching-qr.md` | `0be32ff1747185115cd4bb556fb8375a804c7dc94ec4326d4780fd6fb03e6c0f` |
| `M` | `docs/07-development/phase-2-implementation-plan.md` | `e16627a0871d1044feb5011f49b83b2b0e850ff9c26c6fdfdb7f9e7ef39a2ced` |
| `M` | `docs/07-development/README.md` | `71e9e36d5a06b9f39c93b4e252b819cdc5aa91dd8d2b2b077df86da6275581c2` |
| `M` | `docs/08-ai/phase-2-feature-data-authority-matrix.md` | `77f2028d809b1e83df12a621408b66eb766cc88e2341299682b815b9d7921531` |
| `M` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/adapter/in/web/CoachingErrorAdvice.java` | `97816f2f22756824815d8a29fe36b86f0aed471b44b89690e4183e679e32ef38` |
| `??` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/adapter/in/web/CoachingQrController.java` | `d55ae92122c41e5f8074c9ef5977e1449e7d759276e3c4249012fe309e9bd47b` |
| `??` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/adapter/out/persistence/JdbcCoachingQrStore.java` | `eab966307ae97af23552c7a36d5fb9d70fcb4d8904dd6cd27bc2583fedb2e14d` |
| `M` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/adapter/out/persistence/JdbcCoachingStore.java` | `9024acc2588dccc1e699eecfae1efbcd40984323c43cb06907196c9f206cc39c` |
| `??` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/application/CoachingQrRateLimitException.java` | `3c08e9c27941c2dd73ce0ae24fe06567696d4c3c5570043e931b3ac82a58747a` |
| `??` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/application/port/in/CoachingQrUseCase.java` | `6dddea32df3f9ac8599daaf371dfea795ecc954a7e5aca2fabd5c4ded6fbf239` |
| `??` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/application/port/out/CoachingQrStore.java` | `f9468f57698ccbf13a58f9bc7ca378a3e2fc76a2f27e94abe5bbcf7e0694fe03` |
| `M` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/application/port/out/CoachingStore.java` | `16990a234aeb0473ac10f6e72b4c35ade369cf2859d887b8c832ded479d22c74` |
| `??` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/application/service/CoachingQrRateLimiter.java` | `98c83557dfcb39ceeee64e451491c8e8649adb48d91420bef721f7402bd1c2ab` |
| `??` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/application/service/CoachingQrService.java` | `2f24b7e3ff6fcbcf9cd2beb9decab17f8d16eca6d97e0c04373b29ca2ef59834` |
| `??` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/domain/CoachingQrState.java` | `fd23f13aea7c9a718992b7e312328d10f1252dec1ff42155e6e1546cfae28e89` |
| `??` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/trainer/adapter/out/persistence/JdbcTrainerPublicPreviewAdapter.java` | `269ddbadfb0d73e2a884705c969be77d0be02f81cc62b16d2d87cb0a2ea6a468` |
| `??` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/trainer/application/model/TrainerPublicPreview.java` | `e51a93fb5f6be5a8bc42a4eee07c2e8160da8190c4e873b63f45139b1269ddf8` |
| `??` | `services/backend/src/main/java/com/fitnesscoaching/platform/modules/trainer/application/port/in/TrainerPublicPreviewQuery.java` | `5e9c3b7a0685897759201bd34f4738d92c8b73649809e3e14fa2e29747c168e1` |
| `??` | `services/backend/src/main/resources/db/migration/V61__coaching_qr_identities.sql` | `ddd7a4b9d140472ef5172d5589d6dc0a1b45f91e889e248b90652e0b9a086237` |
| `??` | `services/backend/src/test/java/com/fitnesscoaching/platform/CoachingQrIntegrationTest.java` | `b18c96b5c235affe2f1c4eb445977e15bcba5588c10b18b8ec660552cb46aa3e` |
| `??` | `services/backend/src/test/java/com/fitnesscoaching/platform/modules/coaching/application/service/CoachingQrServiceUnitTest.java` | `bdfb564168d5c5486853c24e9d6a6bc4db6e22fa89a0d3707cf19a294680e53b` |

## 6. Phân tích xung đột & Phương án tích hợp Flyway migration (`main` đã tới `V64`)

- **Trạng thái live của nhánh đích `main` (xác minh qua GitHub public REST API ngày `2026-11-26 19:50 UTC+7`):**
  - Repository: `Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system` (public, `default_branch: main`). *(Lưu ý: GitHub MCP connector gặp `401 Bad credentials`, không có `gh` CLI, nên đã dùng GitHub public REST API chỉ đọc; không chạy `git fetch`/`pull`).*
  - **`main` live HEAD:** `f8e564dca39a27acdc5f7192e31dbb399d471216` (`2026-11-26T11:11:11Z`), đã tiến xa hơn lần kiểm tra trước (`fe25e441...`) và local `main` (`7d7d6da`).
  - **Open PRs:** `0` PR đang mở.
  - **F06 đã merge vào `main`:** PR `#165` (`2026-11-25T17:30:12Z`) và PR `#169` (`2026-11-25T17:44:03Z`) đã đưa `V61__f06_food_text_estimate_drafts.sql` và `V62__f06_account_erasure_minimization.sql` lên `main`.
  - **`V63` và `V64` cũng đã bị chiếm trên `main`:** PR `#172` (`2026-11-25T18:01:10Z`) đã merge `V63__trainer_verify_permission.sql`, và đến `2026-11-26T11:11:11Z` `main` đã có thêm `V64__seed_fitness_goal_types.sql` (`ae5419f3`).
  - **Local `main` (`7d7d6da`) hiện cũ hơn live `main` (`f8e564dc`).**

### 6.1. Bảng đối chiếu migration

| Nguồn / Môi trường | Commit / Ref & Thời điểm kiểm tra | Version & Script | Nội dung chính (đã đọc file thực tế) | Flyway Checksum / Git SHA / File SHA-256 |
|---|---|---|---|---|
| **Live `origin/main`** (GitHub public REST API) | `f8e564dca39a27acdc5f7192e31dbb399d471216` (`2026-11-26 19:50`) | `V61__f06_food_text_estimate_drafts.sql`, `V62__f06_account_erasure_minimization.sql`, `V63__trainer_verify_permission.sql`, `V64__seed_fitness_goal_types.sql` | `V61`: `food_analysis_draft_items`, `food_analysis_previews`, cột F06 trên `food_analysis_requests`; `V62`: tombstones & `nutrition_f06_erasure_redactions`; `V63`: seed `TRAINER_VERIFY` cho `ADMIN`; `V64`: seed fitness goal types | Git blob SHA: `1dcfd6e7` (`V61`), `684c847e` (`V62`), `954ca432` (`V63`), `ae5419f3` (`V64`) |
| Local `main` (ref cũ) | `7d7d6da` (`2026-11-26 02:34`) | `V60__ai_recommendation_draft_sharing.sql` | Thêm cột draft sharing vào `ai_recommendations` | Chưa có `V61..V64` |
| Branch `feat/phase2-dev-a-coaching-qr` | `7d7d6da` + working tree (`2026-11-26 02:34`) | `V61__coaching_qr_identities.sql` | Tạo `fitness.coaching_qr_codes` (FK chỉ tới `fitness.users`), độc lập F06/P7 | File SHA-256: `ddd7a4b9d140472ef5172d5589d6dc0a1b45f91e889e248b90652e0b9a086237` |
| DB gốc `localhost:5433/digital_fitness` (`digital-fitness-postgres`) | `fitness.flyway_schema_history` (`2026-11-26 02:16`) | `V61__f06_food_text_estimate_drafts.sql`, `V62__f06_account_erasure_minimization.sql` | Đã áp dụng thành công lúc `2026-11-25 22:50:34`; **chưa có** `V63` và `coaching_qr_codes` | Flyway CRC32: `-1320390269` (`V61`), `-1413701030` (`V62`) *(khác định nghĩa với SHA-256 file)* |
| DB QR cô lập `localhost:5434/digital_fitness_qr_test` (`digital-fitness-qr-isolated`) | `fitness.flyway_schema_history` (`2026-11-26 02:16`) | `V61__coaching_qr_identities.sql` | Đã áp dụng thành công lúc `2026-11-26 00:58:46` | Flyway CRC32: `539052078` |

### 6.2. Kết luận phương án tích hợp khuyến nghị (Kế hoạch — chưa thực thi)

- **Thứ tự & Version khuyến nghị:**
  - F06 (`V61`, `V62`), `P7-018` (`V63`) và `V64` (`V64__seed_fitness_goal_types.sql`) **đã ở trên `main`**.
  - Version trống kế tiếp trên live `main` (`f8e564dc`) tại thời điểm kiểm tra (`2026-11-26 19:50 UTC+7`) là **`V65` (`V65__coaching_qr_identities.sql`)**.
  - **Lưu ý quan trọng:** Số `V65` chỉ là **dự kiến tại thời điểm `2026-11-26 19:50`** và **chỉ được chốt chính thức tại thời điểm tích hợp** (sau khi cập nhật nhánh từ `main` và kiểm tra lại số trống lúc mở PR), vì nhánh/PR khác có thể tiếp tục dùng `V65` trước khi F12 merge. Tác vụ này không coi rebase/merge là đã được phép.
- **Danh sách file dự kiến sửa ở bước triển khai tích hợp sau:**
  1. Đổi tên `services/backend/src/main/resources/db/migration/V61__coaching_qr_identities.sql` → `V<tại_thời_điểm_tích_hợp>__coaching_qr_identities.sql` (dự kiến `V65__coaching_qr_identities.sql` nếu còn trống).
  2. Cập nhật tham chiếu tên file migration trong `docs/05-api/coaching-qr-contract.md`, `docs/07-development/features/phase-2-dev-a-coaching-qr.md`, và `docs/07-development/features/phase-2-dev-a-coaching-qr-evidence.md`.
- **Bảo toàn hai database hiện có & Xử lý nếu cần môi trường schema thống nhất:**
  - **Giữ nguyên** cả `localhost:5433/digital_fitness` và `localhost:5434/digital_fitness_qr_test` (không `DROP`, không `TRUNCATE`, không `flyway repair`, không sửa `flyway_schema_history`, không tắt validation).
  - Nếu cần môi trường schema thống nhất (`V1..V64` + QR) **mà không ảnh hưởng `:5434`**, tạo một **database/container mới song song** (ví dụ `digital_fitness_qr_unified` trên cổng riêng như `:5435`).
  - Nếu cần giữ dữ liệu kiểm thử từ `:5434/digital_fitness_qr_test` sang database mới song song, **chỉ chuyển dữ liệu theo từng bảng** theo thứ tự FK (tuyệt đối không copy toàn bộ schema hay `flyway_schema_history`):
    1. `fitness.users` (chỉ các tài khoản test cần giữ)
    2. `fitness.user_roles`, `fitness.student_profiles`, `fitness.trainer_profiles` (và `trainer_specialty_assignments` nếu có)
    3. `fitness.coaching_qr_codes` (giữ `id`, `user_id`, `capability_role`, `public_code`, `payload_version`, `status`, `version`, `issued_at`, `revoked_at`, `revoke_reason`)
    4. `fitness.coaching_relationships`, `fitness.coaching_periods`, `fitness.coaching_command_receipts` (gồm receipt `QR_ROTATE` và B03)
    5. `fitness.audit_logs` (các bản ghi `COACHING_QR_ISSUED`, `COACHING_QR_ROTATED` và transition B03 liên quan).

### 6.3. Kế hoạch kiểm thử an toàn & Backup / Recovery (chưa thực thi)

1. **Backup trước bất kỳ thao tác DB nào:** Chụp `pg_dump -Fc` (hoặc SQL dump kèm schema/data) cho `:5433/digital_fitness` (và `:5434` nếu cần giữ), kiểm tra kích thước/tính đọc được (`pg_restore --list`) và giữ bản backup ngoài container trước khi chạy nâng cấp.
2. **DB mới sạch:** Tạo DB mới trống, chạy đầy đủ `V1..V64` (từ `main`) + migration QR mới (dự kiến `V65`), xác minh `flyway_schema_history` khớp checksum và schema đầy đủ.
3. **Bản sao riêng của baseline F06 (`:5433`):** Khôi phục bản backup của `:5433` vào một DB **bản sao riêng** (không chạy trực tiếp lên `:5433` khi chưa kiểm chứng), chạy `V63`, `V64` (từ `main`) + migration QR mới, xác minh toàn bộ dữ liệu F06 (`food_analysis_requests`, `meals`, `flyway_schema_history`) còn nguyên vẹn.
4. **Regression & Contract checks:** Chạy `CoachingQrIntegrationTest` (bao gồm concurrency, rotation/replay, concealment, audit), `CoachingQrServiceUnitTest`, bộ B03 (`CoachingLifecycleIntegrationTest`, `CoachingSharingIntegrationTest`), `validate:contracts`, `test:contracts`, `validate:ux-docs`, và `git diff --check`.
5. **Recovery:** Vì PostgreSQL chạy DDL trong transaction per migration, nếu migration QR lỗi sẽ tự rollback; trường hợp cần quay lại trạng thái trước nâng cấp trên bản sao/DB đích, restore từ file `pg_dump -Fc` đã kiểm tra ở bước 1 (không dùng `flyway repair` để che xung đột).
6. **Quyết định còn cần chủ dự án chốt:**
   - Phê duyệt rebase/tích hợp F12 lên `main` (`f8e564dc`) và chốt số migration trống tại thời điểm mở PR (dự kiến `V65`, do `V63` đã bị PR `#172` dùng và `V64` đã bị `V64__seed_fitness_goal_types.sql` dùng).
   - Chỉ định reviewer kỹ thuật độc lập và quyết định xử lý gate camera điện thoại thật (`QR01–QR10`).

## 7. Kết quả kiểm thử Candidate tích hợp trong môi trường riêng (`2026-11-26 20:07 UTC+7`)

> **Lưu ý:** Thư mục `D:\Projects\fitness-qr-integration-candidate-20261126` chỉ là **bản sao candidate kiểm thử**, **không phải** branch đã tích hợp vào `main`. Checkout gốc `feat/phase2-dev-a-coaching-qr` (gồm source và `V61__coaching_qr_identities.sql`) được giữ nguyên; cả `:5433/digital_fitness` và `:5434/digital_fitness_qr_test` đều không bị chạy migration hay chỉnh sửa.

- **Main snapshot đã ghim:** `f8e564dca39a27acdc5f7192e31dbb399d471216` (`2026-11-26T11:11:11Z`, gồm `V61`..`V64`).
- **Candidate migration & conflict đã xử lý trong bản sao:**
  - Áp delta `38` file tracked + `28` file untracked từ checkout gốc; đổi riêng bản sao `V61__coaching_qr_identities.sql` → **`V65__coaching_qr_identities.sql`** (`SHA-256: ddd7a4b9d140472ef5172d5589d6dc0a1b45f91e889e248b90652e0b9a086237`).
  - **Xung đột duy nhất (`37/38` file áp sạch):** `apps/mobile/src/features/auth/AuthContext.tsx` (`SHA-256` sau merge: `1df7883586c2655101f883d1cacd2ec3e7e8875cc20314c166e763529dbb44f3`) — giữ nguyên cơ chế `invalidateAuthSession`, `invalidateCapabilitySession`, `sessionEpochRef`, `capabilityEpochRef`, `clearQueryCache()` của `main` và giữ lại `queryClient.removeQueries({ queryKey: ['coaching', 'qr'] })` của F12 trong `refreshUser` & `setActiveCapability`.
- **Kiểm chứng Backup (lưu ngoài repo, không đưa vào manifest công khai):**
  - Đã tạo `pg_dump -Fc` cho `:5433/digital_fitness` (`1,673,145 bytes`) và `:5434/digital_fitness_qr_test` (`1,128,324 bytes`), kiểm tra `pg_restore --list` thành công (`2,100` và `2,069` entries).
- **Kiểm chứng Migration trên PostgreSQL riêng `digital-fitness-qr-candidate-pg` (`:5435`, volume `digital_fitness_qr_candidate_pg18_data`):**
  - **A. Database sạch (`digital_fitness_clean_test`):** Flyway chạy sạch `V1`..`V65` (`installed_rank 61..65`, `success = t`, `V65` checksum `539052078`), không trùng version; bảng `fitness.coaching_qr_codes`, index `uq_active_coaching_qr_owner_role`, `uq_coaching_qr_public_code`, `idx_coaching_qr_user_role` và các constraint khớp đầy đủ.
  - **B. Bản sao F06 (`digital_fitness_f06_clone`, restore từ backup `:5433`):** Baseline trước nâng cấp ở `V61`/`V62` (`users=14`, `food_analysis_requests=0`, `food_analysis_draft_items=0`, `food_analysis_previews=0`, `meals=3`, `meals_digest=3d0aa1b3d14f712b3ac5ec1c623ca107`, `flyway_1_62_digest=c85b268f2b12db971cb1f1d505e75c10`). Sau khi chạy `V63..V65`, Flyway đạt `installed_rank 65` (`success = t`), toàn bộ row count và digest trước/sau khớp **100%**, và `fitness.coaching_qr_codes` được tạo sạch.
- **Kiểm thử tự động trên Candidate (`D:\Projects\fitness-qr-integration-candidate-20261126`):**
  - Backend QR + B03 regression (`CoachingQrServiceUnitTest`, `CoachingQrIntegrationTest`, `CoachingErrorAdviceTest`, `CoachingMobileReadContractsIntegrationTest`, `CoachingLifecycleIntegrationTest`, `CoachingSharingIntegrationTest`): **PASS — 50/50 tests** (`Failures: 0, Errors: 0, Skipped: 0`).
  - OpenAPI & AI Schema Contracts: **`validate:contracts` PASS** (OpenAPI valid, 11 schemas, 52 fixtures) & **`test:contracts` PASS (22/22 tests)**.
  - UX Docs: **`validate:ux-docs` PASS (18/18 files)**.
  - Mobile QR + B03 Jest, Typecheck & Lint: **5/5 suites, 31/31 tests PASS**, `typecheck:mobile` **0 lỗi**, `lint:mobile` **0 errors** (10 warnings).
- **Gate chưa đóng:** Điện thoại thật (`QR01–QR10` camera trực tiếp) và independent code review vẫn giữ **`NOT VERIFIED`**.

## 8. Khắc phục `pendingQrIntent`, kiểm thử F06 non-empty & runtime candidate (`Self-Verification`, `2026-11-26 20:40 UTC+7`)

> **Tuyên bố tính chất xác minh:** Phần này là **remediation & self-verification** sau báo cáo [phase-2-dev-a-coaching-qr-independent-review.md](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/07-development/features/phase-2-dev-a-coaching-qr-independent-review.md), **không thay thế independent approval** của reviewer độc lập. Báo cáo review độc lập trước đó được giữ nguyên làm lịch sử review.

### 8.1. Sửa finding P2 `pendingQrIntent` trong Candidate & Checkout gốc (TDD)

- **Thay đổi đã áp dụng:**
  - `apps/mobile/src/features/auth/AuthContext.tsx`: gọi `clearPendingCoachingQrIntent()` trong `handleSessionExpired` và `logout` (giữ riêng `AuthContext.tsx` của checkout gốc, không chép thay đổi từ `main` sang checkout gốc).
    - SHA-256 checkout gốc: `3E6FF0A4798E6715EEAB24D144F55A33168E661B4DA39FD3F4F0D23B4A5F820D`
    - SHA-256 candidate: `232FE1B150A81FB435F378D97288ACF0111B357BA7F6AC9BD35B2D0B9EB37236`
  - `apps/mobile/src/app/(auth)/_layout.tsx`: gọi `clearPendingCoachingQrIntent()` khi redirect vào onboarding (`home !== '/(app)'`).
    - SHA-256 checkout gốc & candidate: `3D1CA2521759DA1F08F1FC6494EDC6AA76D7690BB197C4939EE9F0EF44D9DC28`
  - `apps/mobile/src/__tests__/coachingQrScreens.test.tsx`: bổ sung regression test cho `AuthLayout` (clear khi `home !== '/(app)'`, giữ khi `home === '/(app)'`).
    - SHA-256 checkout gốc: `BD3C410F0F78C16E82613B5B7780FDE9694943D626B939922589A57B1556B57F`
    - SHA-256 candidate: `650B2B63E95C9E2564133B989AE88255425CEDB3A0F6BA9F2930F51ADB41DB4C`
  - `apps/mobile/src/__tests__/authSessionRace.test.tsx` (candidate): bổ sung test #7 xác minh `logout` và `handleSessionExpired` xóa `pendingQrIntent` (đã xác nhận FAIL trước sửa -> PASS sau sửa).
    - SHA-256 candidate: `D337022072B7A45F3D6C816F579884645A222986CAABFED1D830D823FCCDF715`
- **Kết quả kiểm thử tự động sau sửa:**
  - **Candidate:** Auth/session-race + QR/B03 Jest (`10/10 suites, 68/68 tests PASS`), `typecheck:mobile` (**0 errors**), `lint:mobile` (**0 errors, 12 warnings**).
  - **Checkout gốc:** Auth + QR/B03 Jest (`8/8 suites, 53/53 tests PASS`), `typecheck:mobile` (**0 errors**), `lint:mobile` (**0 errors, 7 warnings**).

### 8.2. Kiểm chứng nâng cấp DB F06 có dữ liệu (`digital_fitness_f06_nonempty_test` trên `:5435`)

- Khôi phục bản backup F06 (`V62`) của `:5433` vào DB riêng `digital_fitness_f06_nonempty_test`, chèn dữ liệu F06 thực (`food_analysis_requests=1` `CONFIRMED` `f06-nonempty-cmd-1`, `food_analysis_draft_items=1` `Pho bo tai`, `food_analysis_previews=1` `f06-preview-cmd-1`, `meals=3`).
- Chạy nâng cấp Flyway của candidate (`V63..V65`, `installed_rank=65`, `V65__coaching_qr_identities.sql` checksum `539052078`, `success=t`).
- **So sánh digest trước/sau nâng cấp (khớp 100%):**
  - `req_digest=5df8d2d0b6d621af2b94f6b7fad29f70` (`1` row)
  - `item_digest=eb6f715d9bb192db1d0a875de2d62745` (`1` row)
  - `preview_digest=447cf3947576465b2b8d3b6a54d0f00d` (`1` row)
  - `meals_digest=3d0aa1b3d14f712b3ac5ec1c623ca107` (`3` rows)
  - `coaching_qr_codes=0` (tạo sạch sau nâng cấp).

### 8.3. Kiểm chứng Runtime Candidate trên Pixel 8 (`emulator-5554`)

- **Backend candidate:** chạy tại `http://localhost:18085` kết nối `localhost:5435/digital_fitness_clean_test` (`/actuator/health` -> `200 UP`), song song và không ảnh hưởng `:8080/:5433` và `:18080/:5434`.
- **APK candidate:** build release x86_64 trỏ `http://10.0.2.2:18085/api/v1` với `applicationIdSuffix ".candidate"` (`com.anonymous.aifitnesscoaching.candidate`, cài song song với `com.anonymous.aifitnesscoaching` trên `emulator-5554`), **SHA-256:** `89D866E8DFFB3A8ACD1E35D84B654DAE9C5E6CD2E3A8BA38E61345C08D10AB9A`.
- **Luồng đã xác minh trên emulator (trong `com.anonymous.aifitnesscoaching.candidate`):**
  1. **Cold-start deep link khi chưa đăng nhập:** mở `ai-fitness-coaching://coaching/qr?v=1&c=qwNEEw7s57uoS5XXvNPJeZLtoYNRo4QH` -> vào màn hình Đăng nhập -> đăng nhập `cand.trainer@example.com` -> tự động điều hướng tới `SH-09` (`Hồ sơ từ mã QR`) hiển thị `Candidate Student` (`Học viên`) -> bấm `Gửi lời mời huấn luyện` -> hiển thị `"Đã gửi lời mời huấn luyện"` / `"Đang chờ đối tác phản hồi."`.
  2. **Đăng xuất & không replay intent cũ:** đăng xuất `cand.trainer@example.com` -> đăng nhập `cand.student@example.com` -> vào thẳng Trang chủ Học viên (`"Chào Candidate Student!"`), **không** replay `pendingQrIntent` sang `SH-09`; trong `Huấn luyện cá nhân` thấy lời mời đến từ `Candidate Trainer` ở trạng thái `Đang chờ`.
  3. **Mã QR của tôi & chọn ảnh từ thư viện:** mở `Mã QR của tôi` (`SH-08`) -> bấm `Lưu ảnh QR` -> thông báo `"Đã lưu ảnh mã QR vào thư viện ảnh của bạn."` -> mở `Quét QR tìm huấn luyện viên` (`SH-07`) -> bấm `Chọn ảnh QR từ thư viện` -> chọn ảnh PNG vừa lưu -> giải mã trên thiết bị và gọi `SH-09` hiển thị `"Bạn không thể tự quét mã QR của chính mình."`.
- **Lệnh khởi động / dừng môi trường kiểm thử:**
  - DB candidate `:5435`: container Docker `digital-fitness-qr-candidate-pg` (dừng khi xong: `docker stop digital-fitness-qr-candidate-pg`).
  - Backend QR `:18080` (`digital_fitness_qr_test` trên `:5434`) và Backend Candidate `:18085` (`digital_fitness_clean_test` trên `:5435`) đang được giữ chạy để người dùng tiếp tục kiểm thử.
- **Gate chưa đóng:** Điện thoại thật (`QR01–QR10` camera trực tiếp) và independent approval vẫn giữ **`NOT VERIFIED`**.

## 9. Tích hợp `origin/main` (`f8e564dc`) & giải quyết conflict PR #177 (`2026-11-26 21:24 UTC+7`)

- **Merge chưa commit (`git merge --no-commit --no-ff origin/main`):** tích hợp `origin/main` SHA `f8e564dca39a27acdc5f7192e31dbb399d471216` vào `feat/phase2-dev-a-coaching-qr` (`HEAD`: `ac0d761b19b056efe2fdf07c290d3b2c53c487c9`).
- **Xử lý conflict `apps/mobile/src/features/auth/AuthContext.tsx` (3 điểm):**
  1. `handleSessionExpired`: giữ `safeClearActiveCapability(() => sessionEpochRef.current === expiredEpoch)` của `main` kèm `clearPendingCoachingQrIntent()`.
  2. `logout`: đặt `clearQueryCache()`, `clearPendingCoachingQrIntent()` và `safeClearActiveCapability(...)` bên trong guard `sessionEpochRef.current === logoutEpoch` để callback logout cũ không xóa intent/state của phiên mới.
  3. `setActiveCapability`: kiểm tra guard epoch/status của `main` trước khi chạy `queryClient.removeQueries({ queryKey: ['coaching', 'qr'] })` và cập nhật `activeCapabilityRef.current = resolved`.
- **Giải quyết trùng Flyway migration:**
  - `origin/main` (`f8e564dc`) đã có `V61`–`V64` (`V64__seed_fitness_goal_types.sql` là bản mới nhất).
  - Giữ nguyên toàn bộ migration trên `main`, dời `V61__coaching_qr_identities.sql` → `V65__coaching_qr_identities.sql` (nội dung SQL giữ nguyên, `SHA-256: DDD7A4B9D140472EF5172D5589D6DC0A1B45F91E889E248B90652E0B9A086237`).
  - Các bản ghi lịch sử `V61` ở mục 1–6 được giữ nguyên; DB QR cô lập `:5434` vẫn giữ nguyên trên `V61`, không tự migrate.
- **Kiểm thử sau giải quyết conflict:**
  - Mobile Jest (`authSessionRace.test.tsx` gồm 2 test mới #7/#8 cho logout/hết phiên/race/đổi capability + Auth + QR + B03): **10/10 suites, 69/69 tests PASS**.
  - `typecheck:mobile`: **0 errors**; `lint:mobile`: **0 errors** (12 warnings `@typescript-eslint/no-require-imports` ở file test).
  - `validate:contracts` (OpenAPI + 11 schemas + 52 fixtures), `test:contracts` (**22/22 PASS**), `validate:ux-docs` (**18 files PASS**).
  - Backend QR + B03 (`CoachingQrServiceUnitTest`, `CoachingQrIntegrationTest`, `CoachingErrorAdviceTest`, `CoachingMobileReadContractsIntegrationTest`, `CoachingLifecycleIntegrationTest`, `CoachingSharingIntegrationTest` trên Testcontainers PostgreSQL 18 sạch `V1..V65`): **50/50 PASS** (`Failures: 0, Errors: 0, Skipped: 0`).
  - Backend Migration (`V62FoodErasureMigrationTest`, `V64GoalTypesSeedMigrationTest`): **4/4 PASS**.
- **Trạng thái chờ duyệt:** Merge đang ở trạng thái staged/unresolved-commit để chủ dự án review (chưa commit, chưa push; PR #177 trên GitHub vẫn báo conflict cho tới khi push commit merge). Gate thiết bị thật (`QR01–QR10`) và independent approval vẫn **`NOT VERIFIED`**.
