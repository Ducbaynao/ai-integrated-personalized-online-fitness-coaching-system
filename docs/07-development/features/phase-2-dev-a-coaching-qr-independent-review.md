# F12 Coaching QR — Báo cáo Review Độc lập Candidate Tích hợp

> **Trạng thái:** `REVIEW COMPLETED — CONDITIONAL GO FOR PR PREPARATION` (chưa cấp quyền commit/push/merge/PR, chưa đóng `F12`).  
> **Giới hạn tính độc lập:** Báo cáo này được thực hiện bởi cùng AI agent đã chuẩn bị candidate tích hợp và merge `AuthContext.tsx` trong `D:\Projects\fitness-qr-integration-candidate-20261126`. Kết quả kiểm chứng dựa trên đọc source trực tiếp, đối chiếu 3 chiều và chạy test độc lập, nhưng **không thay thế reviewer con người độc lập** theo quy trình merge của dự án.

---

## 1. Phạm vi, Nguồn so sánh & Manifest Candidate

- **Thời điểm review:** `2026-11-26 20:21 UTC+7`
- **Thư mục candidate:** `D:\Projects\fitness-qr-integration-candidate-20261126` *(bản sao kiểm thử, không phải branch đã tích hợp)*.
- **Checkout gốc (không sửa source/migration):** `D:\Projects\ai-integrated-personalized-online-fitness-coaching-system`, branch `feat/phase2-dev-a-coaching-qr`, base `HEAD` `7d7d6da`.
- **Main snapshot đã ghim (GitHub public REST API):** `f8e564dca39a27acdc5f7192e31dbb399d471216` (`2026-11-26T11:11:11Z`), gồm `300` file thay đổi so với `7d7d6da` (đã có `V61..V64`).
- **Xác minh checksum chính trong candidate:**
  - `services/backend/src/main/resources/db/migration/V65__coaching_qr_identities.sql`: `SHA-256 = ddd7a4b9d140472ef5172d5589d6dc0a1b45f91e889e248b90652e0b9a086237` (khớp **100%** với `V61__coaching_qr_identities.sql` ở checkout gốc — chỉ đổi tên file).
  - `V63__trainer_verify_permission.sql`: `50a47988e48806819f93bb860133bb3380dd4659b0e9a91c5e272c3e300b7b2a` (giữ nguyên từ `main`).
  - `V64__seed_fitness_goal_types.sql`: `fd20637793d691e36f442d7d1dc1a088c7f459e784935113b02f8a2ec7af932e` (giữ nguyên từ `main`).
  - `apps/mobile/src/features/auth/AuthContext.tsx` (sau merge): `1df7883586c2655101f883d1cacd2ec3e7e8875cc20314c166e763529dbb44f3`.
  - Manifest đầy đủ 65 file F12 gốc (tracked + untracked) đã được ghi nhận tại mục 5 của [phase-2-dev-a-coaching-qr-evidence.md](phase-2-dev-a-coaching-qr-evidence.md).

---

## 2. Findings xếp theo P1 / P2 / P3

> **P1:** Không phát hiện lỗi nghiêm trọng làm rò quyền health data, phá vỡ `sessionEpochRef`/`capabilityEpochRef`, xung đột schema `V61..V65`, hay cho phép tự gửi lời mời khi quét/deep link.

### P2 — Lỗi xác nhận được

1. **`pendingQrIntent` không được xóa khi `logout`, `handleSessionExpired` hoặc redirect sang onboarding (`AuthContext.tsx`, `(auth)/_layout.tsx`)**
   - **Vị trí:**
     - `D:\Projects\fitness-qr-integration-candidate-20261126\apps\mobile\src\features\coaching\pendingQrIntent.ts:10-24`
     - `D:\Projects\fitness-qr-integration-candidate-20261126\apps\mobile\src\app\(auth)\_layout.tsx:15-17` (`const pendingQr = home === '/(app)' ? getPendingCoachingQrIntent() : null;`)
     - `D:\Projects\fitness-qr-integration-candidate-20261126\apps\mobile\src\features\auth\AuthContext.tsx:94-110, 268-294`
   - **Điều kiện tái hiện:** User chưa đăng nhập mở deep link `ai-fitness-coaching://coaching/qr?v=1&c=<code1>` (set `pendingIntent` in-memory) → đăng nhập vào tài khoản chưa có profile (`home !== '/(app)'`, ví dụ `/(onboarding)/select`) hoặc hủy/hết phiên → sau đó user khác (User B) đăng nhập trên cùng tiến trình app.
   - **Tác động:** `pendingIntent` vẫn nằm trong bộ nhớ module và có thể tự điều hướng phiên của User B tới `/(app)/coaching/qr` với mã QR của lần mở link trước. (Không tự gửi lời mời vì vẫn qua `SH-09`, nhưng phát lại intent sai phiên).
   - **Evidence:** Kiểm tra trực tiếp 3 file trên trong candidate; `clearPendingCoachingQrIntent()` chỉ mới được gọi trong `CoachingQrScannerScreen.tsx` (`navigateToPreview`, `handleCancelDeepLink`).
   - **Hướng sửa & Regression test cần thêm:** Gọi `clearPendingCoachingQrIntent()` trong `AuthContext.logout` và `handleSessionExpired`, và khi `AuthLayout` redirect sang onboarding (`home !== '/(app)'`). Thêm unit test trong `coachingQrScreens.test.tsx` / `authSessionRace.test.tsx` xác nhận `logout` và onboarding redirect xóa `getPendingCoachingQrIntent()`.

### P2 — Rủi ro chưa kiểm chứng

2. **Chưa build/chạy lại standalone APK và backend runtime từ chính thư mục candidate (`f8e564dc` + `V65`)**
   - **Vị trí:** `D:\Projects\fitness-qr-integration-candidate-20261126` vs checkout gốc (`7d7d6da` + `V61`).
   - **Evidence:** Backend `:18080` đang chạy (`task-1119`) và `app-release.apk` trên Pixel 8 (`emulator-5554`) được build từ **checkout gốc**, không phải từ candidate. Ngoài ra, gate **camera điện thoại thật (`QR01–QR10`)** vẫn **`NOT VERIFIED`**.
   - **Tác động:** Runtime APK trên bản đã merge `main` (với `apiClient.ts` và `AuthContext.tsx` mới của `main`) chỉ mới được kiểm chứng qua Jest/Typecheck/Lint (`31/31` + `35/35`), chưa chạy end-to-end trên APK build từ candidate.

### P3 — Cải tiến tùy chọn

3. **`TrainerDirectoryScreen.tsx` được `main` cập nhật nhưng bị bypass bởi route QR của F12**
   - **Vị trí:** `apps/mobile/src/features/coaching/TrainerDirectoryScreen.tsx` (sửa trên `main` `f8e564dc`) vs `apps/mobile/src/app/(app)/coaching/trainers.tsx` (F12 redirect sang `/(app)/coaching/qr?targetRole=TRAINER`).
   - **Tác động:** Không gây lỗi build/runtime, nhưng `TrainerDirectoryScreen.tsx` không còn được mở từ Mobile navigation sau F12. Có thể giữ lại như file legacy hoặc dọn dẹp theo quyết định chủ dự án.

---

## 3. Kết luận theo từng trọng tâm

### 3.1. Kết luận về `AuthContext.tsx`

- **Đối chiếu 3 chiều (`main` `f8e564dc` vs F12 gốc vs candidate):**
  - Toàn bộ cơ chế bảo vệ phiên của `main` (`invalidateAuthSession`, `invalidateCapabilitySession`, `sessionEpochRef`, `capabilityEpochRef`, `userRef`, `statusRef`, `activeCapabilityRef`, `clearQueryCache()` trong `handleSessionExpired`, `restoreSession`, `login`, `logout`, và khi `refreshUser` đổi `userId`) đều **được giữ nguyên 100%**.
  - Diff giữa `main AuthContext.tsx` và `candidate AuthContext.tsx` **chỉ gồm 3 dòng** của F12: import `queryClient` và gọi `queryClient.removeQueries({ queryKey: ['coaching', 'qr'] })` trong `refreshUser` (dòng 341) và `setActiveCapability` (dòng 380).
  - Query key `qrPreview` đã tách theo `userId` (`coachingQueries.ts:16-17`) và tắt khi chưa có `userId` (`enabled: Boolean(userId) && Boolean(publicCode)`).
  - Chạy bộ test race của `main` trên candidate (`authFlow.test.ts`, `authMessages.test.ts`, `authNativeStorageRace.test.tsx`, `authProviderCapabilities.test.tsx`, `authSessionRace.test.tsx`): **5/5 suites, 35/35 tests PASS**.
- **Đánh giá:** **ĐẠT** cho phần merge `AuthContext.tsx` (với 1 finding P2 về xóa `pendingQrIntent` khi logout/onboarding).

### 3.2. Kết luận về Migration QR (`V65__coaching_qr_identities.sql`)

- **Số version & nội dung:** Candidate dùng **`V65__coaching_qr_identities.sql`** (trống tại `main` `f8e564dc`), SHA-256 trùng khít `V61` gốc (chỉ đổi tên, không sửa SQL). Các migration `V61..V64` từ `main` giữ nguyên không đổi checksum.
- **Schema & ràng buộc:** Bảng `fitness.coaching_qr_codes` có `user_id` FK `REFERENCES users(id) ON DELETE CASCADE`, `uq_coaching_qr_public_code`, partial unique `uq_active_coaching_qr_owner_role ON (user_id, capability_role) WHERE status = 'ACTIVE'`, `payload_version = 1`, `version >= 1`, `coaching_qr_revocation_ck`, tương thích với `lockOwner`, `JdbcCoachingQrStore`, `coaching_command_receipts` (`QR_ROTATE`) và `AuditService`.
- **Đánh giá bằng chứng DB sạch & bản sao F06 (`:5435`):**
  - DB sạch (`digital_fitness_clean_test`) chạy `V1..V65` thành công, không trùng version.
  - Bản sao F06 (`digital_fitness_f06_clone`, restore từ `:5433`) nâng cấp từ `V61`/`V62` lên `V63..V65` thành công, row count (`users=14`, `meals=3`, `food_analysis_requests=0`) và digest (`meals_digest`, `flyway_1_62_digest`) khớp **100%**.
  - **Giới hạn bằng chứng:** Trong DB `:5433` gốc, `food_analysis_requests`/`food_analysis_draft_items`/`food_analysis_previews` hiện có `0` dòng nên digest chưa kiểm chứng dữ liệu draft F06 thực tế ngoài `meals` và `flyway_schema_history`.
  - **Xác nhận bắt buộc:** DB `:5434/digital_fitness_qr_test` vẫn đang ở `V61` QR và **không** được coi là đã chuyển sang lịch migration thống nhất `V1..V65`.

### 3.3. Kết luận tổng thể Candidate

- **Tests đã chạy trên candidate (không sửa thêm trong bước review):**
  - Backend QR + B03 regression: **50/50 tests PASS** (`task-1184`).
  - Auth & Session-race Jest: **5/5 suites, 35/35 tests PASS**.
  - Mobile QR + B03 Jest: **5/5 suites, 31/31 tests PASS**, `typecheck:mobile` **0 lỗi**, `lint:mobile` **0 errors** (10 warnings).
  - OpenAPI & AI Schema Contracts: **PASS** (OpenAPI valid, 11 schemas, 52 fixtures, 22/22 contract tests); UX Docs: **18/18 PASS**.
- **Kết luận sẵn sàng:** **CHƯA SẴN SÀNG TỰ ĐỘNG MERGE — SẴN SÀNG CHO BƯỚC TÍCH HỢP CÓ ĐIỀU KIỆN** sau khi:
  1. Bổ sung `clearPendingCoachingQrIntent()` ở `logout` / `handleSessionExpired` / onboarding redirect (P2 #1).
  2. Chủ dự án chốt thời điểm đồng bộ/rebase `feat/phase2-dev-a-coaching-qr` lên `main`, xác nhận lại số migration trống thực tế lúc mở PR (hiện tại là `V65`), và chỉ định **reviewer con người độc lập**.
  3. Chủ dự án quyết định xử lý gate **camera điện thoại thật (`QR01–QR10`)** trước hoặc ngay sau khi chuẩn bị bản APK từ nhánh đã hợp nhất.
