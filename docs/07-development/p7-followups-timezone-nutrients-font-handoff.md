# P7 follow-up: múi giờ Chat/Attention, nhãn chất dinh dưỡng, weight/italic của font — 2026-11-27

- **Checkout:** `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-followups-timezone-nutrients-font` (git worktree riêng từ `origin/main`).
- **Branch / base:** `fix/p7-followups-timezone-nutrients-font` @ `15aedd1fd8efb404c512d8ddd2be4ddbe9d541bf` (`git ls-remote` xác nhận đây vẫn là `main` mới nhất lúc bắt đầu và lúc commit).
- **Môi trường:** `apps/mobile/node_modules` là junction tới `p7-012-integration` (lockfile trùng hash `3046C5AA…`); không chạy `npm ci`, không đổi dependency (package `@expo-google-fonts/be-vietnam-pro` đã có sẵn các file italic).
- **Phạm vi:** chỉ presentation của Mobile. Không đổi timestamp lưu, payload, body/key, retry, quyền, navigation, timezone tài khoản, lịch tính review, quiet hours, Backend, OpenAPI, Flyway.
- **Chưa QA:** không browser/Metro/emulator/thiết bị/Backend/Docker. Mọi kết quả dưới đây là Jest/tsc/lint/audit tự động; **visual, Android, Web, bàn phím vẫn `NOT RUN`**, release gate không đóng.

## 1. Quyết định semantics

| Loại mốc | Ví dụ trong phạm vi | Hiển thị |
|---|---|---|
| Instant thông thường (không bối cảnh) | thời gian tin nhắn, thông báo, "cập nhật lúc", `detectedAt`/`occurredAt` của Attention | `Asia/Ho_Chi_Minh` qua `formatInstantVietnam` / `formatInstantDateVietnam`; không đọc múi giờ máy |
| Instant thuộc bản ghi đã lưu | lịch tập / lịch hẹn gửi trong Chat | múi giờ của bản ghi + nhãn + offset theo đúng thời điểm (DST) qua `formatRecordedScheduleTime`; bản ghi không có múi giờ → UTC kèm "múi giờ của bản ghi cũ chưa xác định" |
| Ngày/bối cảnh Attention đã lưu | `previousLocalDate`, `currentLocalDate`, múi giờ xác nhận, `dueDate`, khoảng dự kiến | giữ nguyên, không đổi thành instant; khoảng dự kiến vẫn hiện UTC có nhãn (signal không mang múi giờ riêng) |
| Thiếu / chỉ ngày / không có offset / không parse được | — | "Chưa rõ thời điểm"; không đoán giờ, không "Invalid Date" |

Chuẩn "instant hợp lệ" dùng chung: `parseExactInstant` (chuỗi có giờ và `Z`/offset). Trước đây `formatScheduleTime` sẽ ném `RangeError` với giá trị không hợp lệ; Chat nay đi qua `formatRecordedScheduleTime` nên không crash.

## 2. Inventory và nguyên nhân

### A. Thời gian
- `ChatScreens.tsx` (8 vị trí) và `AttentionReviewScreen.tsx` (2 vị trí) dùng `new Date(x).toLocaleString('vi-VN')` / `toLocaleDateString` → múi giờ thiết bị. Sau sửa: 0 vị trí trong hai file.
- **Còn ngoài phạm vi (ghi nhận, chưa sửa):** 22 vị trí ở 16 file vẫn dùng `toLocale*String` theo múi giờ thiết bị — `coaching/RelationshipDetailScreen`, `coaching/SharingPermissionsScreen`, `coaching/TrainerStudentGoalScreen`, `goal/components/GoalHistoryTab` (3), `GoalOverviewTab` (2), `GoalProposalsTab`, `measurements/MeasurementsScreen`, `CorrectMeasurementModal`, `progress/ProgressDashboardScreen`, `trainer/TrainerProfileScreen` (2), `workout-execution/WorkoutExecutionScreen`, `WorkoutHistoryScreen`, `workout-plan/StudentWorkoutPlansScreen`, `WorkoutPlanDetailScreen`, `WorkoutPlanHistoryScreen`, `AiWorkoutAdjustmentModal`. Mỗi chỗ cần xác định là instant thông thường hay mốc có bối cảnh trước khi đổi.
- `linkWindow` trong `ConversationScreen` tính cửa sổ ±7/+30 ngày bằng `setDate` theo giờ máy để gọi API; đó là tham số truy vấn, không phải hiển thị, nên giữ nguyên.

### B. Nhãn chất dinh dưỡng
- `NutritionScreen.nutrientLabel` trả **mã thô** cho code lạ và, vì dùng object literal, trả `Object` function cho code như `constructor`/`toString`. Callers: `NutritionScreen` (tóm tắt ngày) và `TrainerNutritionScreen`.
- Sau sửa: `nutrientCodeLabel` (`localization/nutritionLabels.ts`) — 4 mã hệ thống giữ "Năng lượng (kcal) / Protein (g) / Carb (g) / Chất béo (g)"; mã khác → "Chất dinh dưỡng khác". Không đoán, không dịch tên tuỳ chỉnh, không đổi `code`; mỗi mã vẫn là một dòng riêng (key theo `code`). Giá trị, `Chưa xác định`, "phần đã biết" và số 0 không đổi.
- `nutrientName` (màn Strategy, có `name` từ catalog) giữ nguyên hành vi: tên catalog nguyên văn, thiếu tên dùng fallback riêng; chỉ dùng chung hàm tra bảng an toàn với prototype.
- Giới hạn: nhiều mã lạ trong cùng ngày cùng hiện "Chất dinh dưỡng khác" (phân biệt bằng thứ tự và giá trị). Nếu cần phân biệt tên, Backend phải trả `name` cho summary — chưa làm.

### C. Weight / italic
Inventory trên `origin/main` bằng AST (không dùng con số "31 file" cũ):

| Nhóm | Trước | Sau |
|---|---|---|
| `fontWeight` cùng object với `...typography.*` | 133 | 0 |
| `fontWeight` trong style ghép mảng với `styles.X` có token | 13 (có điều kiện) + 2 | 0 |
| `fontWeight` lồng trong `Text` cha có token (`linkTextBold` ×3, `CorrectMeasurementModal` ×2, `StudentOnboardingScreen`, `GoalTargetsTab`, `GoalProposalDetailScreen`) | 8 | 0 |
| `fontStyle: 'italic'` trên token | 22 | 0 (dùng `regularItalic`/`mediumItalic`) |
| `fontWeight`/`fontStyle` trên chữ **không có** font app (chữ hệ thống) | — | 32 vị trí, 6 file, khóa trong test |

- **Nguyên nhân chung:** mỗi weight là một file riêng; `fontWeight`/`fontStyle` đặt chồng lên family Regular khiến nền tảng tự tạo bold/oblique giả thay vì dùng face thật. Đây là hành vi dự kiến của Android/Web theo cách đăng ký font hiện tại; **chưa xác nhận bằng thiết bị** (không QA).
- **Sửa:** chọn `fontFamily.*` tương ứng (`500→medium`, `600→semiBold`, `700|bold→bold`, `400|normal→regular`) và bỏ `fontWeight`/`fontStyle`; size, line height, màu, spacing giữ nguyên. Style có điều kiện (tab chọn/không chọn) dùng `cond ? fontFamily.bold : fontFamily.medium`.
- **Face mới:** chỉ `BeVietnamPro_400Regular_Italic` (chữ phụ/ghi chú) và `BeVietnamPro_500Medium_Italic` (caption) — đúng hai tổ hợp có caller. Không nạp các weight khác. Nguồn/license không đổi: `@expo-google-fonts/be-vietnam-pro@0.4.1`, SIL OFL 1.1 nhúng trong từng TTF.
- **Thay đổi hiển thị có chủ đích:** chỗ yêu cầu `500` trên family Regular nay dùng Medium thật (trước đó Android không giả lập vì <600); các tab/chip không chọn đã khai báo `500` hoặc `600` nay đúng weight đó.
- **Chữ hệ thống giữ nguyên** (không có token nên Android/Web dùng font hệ thống, có bold/italic thật): `themed-text.tsx` (code mẫu không được màn nào import), ký tự ✓/✕, `SessionGateScreen`, hai chỗ trong `FoodAnalysisScreen`, toàn bộ `AiWorkoutProposalModal`. Đổi chúng sang Be Vietnam Pro là thay đổi typography, không thuộc follow-up này.
- **Fallback loader:** `appFontAssets` thêm 2 khoá; `fontsReady = fontsLoaded || Boolean(fontError)` giữ nguyên (test cũ về loading gate/lỗi font vẫn đạt).

## 3. Acceptance

| Acceptance | Bằng chứng |
|---|---|
| Đổi múi giờ thiết bị không đổi giờ Chat/thông báo | `chatAttentionTimezone.test.tsx`: 5 múi giờ giả lập (UTC, Los_Angeles, Auckland, Tokyo, Ho_Chi_Minh), cùng instant → cây render giống nhau; biên ngày 16:59Z/17:00Z |
| Context Attention đã lưu không bị đọc sang ngày khác | cùng test: `previousLocalDate`/`currentLocalDate`, múi giờ xác nhận, khoảng dự kiến qua ngày DST Mỹ 2026-03-08 không đổi theo thiết bị |
| Instant / date-only / missing / invalid tách riêng | formatter + 4 màn Chat + Attention: "Chưa rõ thời điểm", không "Invalid Date/NaN" |
| DST của bản ghi | lịch hẹn New York: tháng 12 `UTC-05:00`, tháng 7 `UTC-04:00`, độc lập thiết bị |
| Nhãn nutrient: known/unknown, không gộp, null/0, đơn vị | `nutrientCodeLabels.test.tsx`: 8 dòng giữ thứ tự và giá trị (0, null, phần đã biết); 4 mã lạ → 4 dòng; mã `constructor`/`__proto__` an toàn |
| Font mapping trên asset thật | `vietnameseFonts.test.tsx` mở rộng 6 family: OS/2 weight + cờ italic, tên file `_Italic`, 146 nguyên âm có dấu, NFC/NFD, GPOS `mark`/`mkmk` |
| Không còn weight/italic giả lập | `fontFaceOverrides.test.ts` (AST, khóa 32 vị trí chữ hệ thống, mọi face đăng ký đều có caller) + `fontFaceCallers.test.tsx` và assertion style đã flatten trong `CurrentGoalScreen`, `AiKnowledgeScreen`, Chat/Attention, Nutrition |

Hạn chế: test font là kiểm tra nguồn/metadata/glyph, **không phải** visual shaping hay kiểm tra thiết bị. Harness múi giờ (`src/test-utils/deviceTimezone.ts`) định tuyến `Intl.DateTimeFormat` mặc định và `Date#toLocale*String`; nó không thay getter `getHours()` hay `moment()` local (không dùng trong các màn này). `process.env.TZ` trong sandbox Jest không đổi múi giờ thật nên không dùng.

## 4. File đổi

- **Mới:** `src/test-utils/deviceTimezone.ts`, `src/test-utils/textFaces.ts`; test `chatAttentionTimezone.test.tsx`, `nutrientCodeLabels.test.tsx`, `fontFaceOverrides.test.ts`, `fontFaceCallers.test.tsx`.
- **Thời gian:** `localization/instantDisplay.ts` (+`parseExactInstant`, `formatInstantVietnam`, `formatInstantDateVietnam`, `ORDINARY_INSTANT_TIMEZONE`), `features/workout-schedule/scheduleDisplay.ts` (+`formatRecordedScheduleTime`), `features/chat/ChatScreens.tsx`, `features/attention/AttentionReviewScreen.tsx`.
- **Nutrient:** `localization/nutritionLabels.ts` (+`nutrientCodeLabel`, `OTHER_NUTRIENT_LABEL`), `features/nutrition/NutritionScreen.tsx`.
- **Font:** `design-system/tokens/typography.ts`, `design-system/fonts.ts`, và 40 file caller (32 qua codemod AST theo token, còn lại sửa tay theo từng bối cảnh cha). Test cập nhật: `vietnameseFonts`, `CurrentGoalScreen`, `AiKnowledgeScreen` (chỉ thêm assertion).
- **Tài liệu:** `docs/06-ui-ux/design-tokens.md`, `component-specifications.md`, `.agent/skills/digital-fitness-ui/references/review-checklist.md`, handoff này; ghi chú đóng mục mở trong handoff P7-012 §6 và P7-016 §12.9.

## 5. Kiểm chứng đã chạy (tuần tự, `apps/mobile`)

Trước khi chạy: RAM trống ~1,9 GB, C: ~19 GB, không có Jest/tsc/Gradle khác. Log nằm ngoài Git (scratchpad phiên).

| Lệnh | Kết quả |
|---|---|
| **Trước khi sửa** — nutrient: `nutrientCodeLabels.test.tsx` với `nutrientLabel` cũ | exit 1; **3 fail / 3 pass** |
| **Trước khi sửa** — timezone: `chatAttentionTimezone.test.tsx` với `ChatScreens`/`AttentionReviewScreen` bản `HEAD` | exit 1; **10 fail / 6 pass** (6 pass là test formatter thuần) |
| **Trước khi sửa** — font: `fontFaceOverrides` + `fontFaceCallers` với toàn bộ source bản `HEAD` (47 file) | exit 1; **5 fail / 2 pass** |
| `npx jest --runInBand --findRelatedTests <47 file nguồn> + 5 suite font/timezone/nutrient` | exit 0; **72/72 suite, 880/880 test**, 183 s |
| `npx tsc --noEmit` | exit 0 |
| `npm run lint` | 0 lỗi, 12 cảnh báo **baseline** (`CurrentGoalScreen.test.tsx`, `coachingQrScreens.test.tsx`); không có cảnh báo mới |
| `npm run audit:ui-mixed` | 13 dòng, không đổi (`coach-alex`, `Barbell Bench Press`, "reps") |
| `node tools/validate-ux-docs.mjs` | "UX documentation validation passed (18 required files)." |
| `git diff --check` | sạch (chỉ cảnh báo LF/CRLF của Git trên Windows) |

Không chạy full suite, Backend, Docker, Metro, emulator hay browser. Các lần "trước khi sửa" được làm bằng cách tạm đặt bản `git show HEAD:<file>` vào working tree rồi khôi phục từ bản sao (đã đối chiếu `cmp`); không dùng stash/checkout.

## 6. SHA-256 (source/test chính)

```
46a684e186972c62432f0f0e122a09930d7b4437967be3c7a43b4179b409a652  apps/mobile/src/localization/instantDisplay.ts
b2b101cbe7ce42c39a8a7377615aaf35c7eaccb9075b00160fc4b773b4d1a476  apps/mobile/src/localization/nutritionLabels.ts
a16dd3296e33e5152b597139802f28d34be0a905970ba69eaf5703b697fe24fb  apps/mobile/src/features/workout-schedule/scheduleDisplay.ts
b7e1f6b9f6b3c0e5cc174b67fb2bae57025bfc341808f8fefd0e1b8e68bd1ec0  apps/mobile/src/features/chat/ChatScreens.tsx
ed1c0b3b37b7b121898975c3585595ba0c2e60e35156a90339141a88fe92093e  apps/mobile/src/features/attention/AttentionReviewScreen.tsx
fdbd2ebfc41fdca44f5472a41af1fbbe0729e381819b9a84f36e366548d0bd71  apps/mobile/src/features/nutrition/NutritionScreen.tsx
90719b5df2ffa2873babf21e1ebe5ab062080a2f8866376560485d00db0ca037  apps/mobile/src/design-system/fonts.ts
f90c9637673d135ecfb5e7129688282dc473bc47baee0a08aaafc19cd58b838f  apps/mobile/src/design-system/tokens/typography.ts
d5213480a5866609c4577eeb6563f12d24328e31b3045a151ccc0f1741b58e28  apps/mobile/src/test-utils/deviceTimezone.ts
6e7262ba6fdae3eab471b0adf65ebec500d892a0319d10759dbabfee3c59cc2e  apps/mobile/src/test-utils/textFaces.ts
06a5e3640f9db518c4743c6af5307a2f142e9b5100fb46c4f70d60b52e7e6f47  apps/mobile/src/__tests__/chatAttentionTimezone.test.tsx
11c4cc91be5870eaf6487c24fe89eb240eddba08c3558105f8bd6a2c823abc65  apps/mobile/src/__tests__/nutrientCodeLabels.test.tsx
d32e5cb9a4a42637671786654c5cb69f570ea149d107c2a736effe6e80ba9a35  apps/mobile/src/__tests__/fontFaceOverrides.test.ts
0275cff7b490d0f377a58e49b75983479ea3b70f41d330d09bb21d0101484623  apps/mobile/src/__tests__/fontFaceCallers.test.tsx
0ef07814b568bf329f419a404db0f0ca17e712fbd16b03e9e09167702357fb38  apps/mobile/src/__tests__/vietnameseFonts.test.tsx
```

## 7. Trạng thái và việc còn mở

- **Hoàn tất code follow-up** cho ba mục: timezone Chat/Attention, nhãn nutrient lạ, weight/italic của font.
- **Còn mở (không thuộc patch):** (a) 22 vị trí `toLocale*String` theo múi giờ thiết bị ở 16 file ngoài Chat/Attention (§2A); (b) quyết định có đưa chữ hệ thống (§2C) về Be Vietnam Pro hay không — thuộc P7-004 (Figma); (c) summary nutrient không có `name` nên mã lạ chỉ có nhãn chung; (d) QA visual/Android/Web/bàn phím, đặc biệt render italic và bold thật của Be Vietnam Pro, vẫn `NOT RUN`.
- **Git:** kết quả commit/PR/CI/merge được báo ngoài tài liệu; không tạo commit chỉ để ghi trạng thái CI.

## 8. Tiếp nối inventory thời gian ngoài Chat/Attention

Inventory lịch sử §2A/§7 đã được đối chiếu với `main@d9fde89e` trong [P7 time display consistency](p7-time-display-consistency-handoff.md). Handoff mới ghi riêng disposition cho 22 lời gọi Date và một lời gọi Number (23 lời gọi trên 21 dòng, 16 file), caller của helper và các quy ước giữ nguyên. Evidence/checks của #183 ở trên vẫn là lịch sử, không bị chạy lại để xác nhận #183.
