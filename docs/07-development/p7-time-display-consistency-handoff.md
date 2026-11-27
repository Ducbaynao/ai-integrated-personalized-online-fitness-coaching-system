# P7 — Nhất quán hiển thị thời gian Mobile — 27/11/2026

## 1. Phạm vi, nền và quyền

- Dev-B / Minh / GitHub `HawDinh`; implementation, self-review và tích hợp được người dùng ủy quyền trong task.
- Checkout riêng: `D:\codex-work\p7-time-display-consistency`; branch `codex/fix-p7-time-display-consistency`; clone trực tiếp remote `main@d9fde89e9a773ef1ee790d8bdb65305057013939` (#183).
- `git ls-remote origin refs/heads/main` lúc đầu và trước checkpoint vẫn cùng SHA; không có delta mới. Không kiểm lại CI hay chạy lại checks để xác nhận #183.
- Chỉ sửa presentation tại 15 file Mobile, thêm regression vào 10 suite hiện hữu và một test utility. Không đổi formatter nguồn, dữ liệu/payload, quyền, operation/session guards, body/key/retry, navigation, timezone mặc định/tài khoản, DST resolution, recurrence, quiet hours, backend, OpenAPI, Flyway hoặc AI schema.
- Nghiệp vụ liên quan: B03 Goal/Coaching/sharing (Student quyết định Goal/quyền chia sẻ), B04 Plan/version (authority theo period), B05 Actual Workout (Student), B09 Trainer verification (Admin quyết định; Trainer chỉ đọc); F03/P2-M4 chỉ đổi phần trình bày expiry. Không thay lifecycle hoặc command của các module. T02/T03/T05/T06/T11/T18/T23/T25/T27 là ràng buộc giữ nguyên; kiểm tra client không chứng minh lại các gate backend/Phase 2.
- Đã đọc core → Phase 2 release scope → data/authority matrix → implementation plan; Mobile/UI/testing, domain Goal/Workout/Measurement, actor flows/screens, UI states/accessibility và các handoff #180/#182/#183. [Expo SDK 57 reference](https://docs.expo.dev/versions/v57.0.0/) được đọc theo `apps/mobile/AGENTS.md`.

## 2. Đối chiếu inventory lịch sử

Lịch sử gọi “22 vị trí ở 16 file” không phải đơn vị đếm ổn định. Tại base thực tế, `git grep -n -E 'toLocale(Date|Time)?String' HEAD -- apps/mobile/src/features` trả **21 dòng, 23 lời gọi trong 16 file**: **22 Date calls ở 15 file** và **1 Number call ở Progress**. Hai lời gọi ngày/giờ measurement cùng dòng; hai ranh giới history chia sẻ cũng cùng dòng. Hai Date helper ở Workout có tổng cộng năm caller với semantics khác nhau. Sau sửa chỉ còn Number call; không còn Date locale call trong feature source. Không có vị trí cần quyết định nghiệp vụ mới hoặc CHƯA ĐỦ EVIDENCE bị tự gán timezone.

Đường dẫn dưới đây tương đối với `apps/mobile/src/features/`; số dòng **base → patch**. `VN` = `Asia/Ho_Chi_Minh`, dùng `formatInstantVietnam` (ngày + giờ) hoặc `formatInstantDateVietnam` (ngày). Với Date calls, chuỗi không phải exact instant (missing/invalid/date-only/offset-less) dùng nhãn chưa rõ; các nhánh tùy chọn hiện hữu vẫn giữ “—”, “Chưa rõ”, “Chưa có dữ liệu”, “từ đầu”/“hiện tại” hoặc ẩn dòng đúng semantics cũ.

| # | File:dòng base → patch / dữ liệu | Surface / actor | Semantics / nguồn context | Disposition và hiển thị đúng |
|---|---|---|---|---|
| 1 | `coaching/RelationshipDetailScreen.tsx:144→145`, `changedAt` | ST-17 / TR-03 lịch sử quan hệ | Instant của sự kiện; không timezone riêng | **FIX** → VN ngày/giờ; trước dùng thiết bị |
| 2 | `coaching/SharingPermissionsScreen.tsx:51→52`, `historyFrom` | ST-17 / TR-12 phạm vi chia sẻ | Instant ranh giới truy cập; không timezone riêng | **FIX** → VN; không đổi ranh giới/payload |
| 3 | cùng file `:51→52`, `historyUntil` | ST-17 / TR-12 | Instant ranh giới; không timezone riêng | **FIX** → VN; null vẫn “hiện tại” |
| 4 | cùng file `:52→53`, `validUntil` | ST-17 / TR-12 hết hiệu lực | Instant expiry; không timezone riêng | **FIX** → VN; chỉ presentation |
| 5 | `coaching/TrainerStudentGoalScreen.tsx:330→331`, `createdAt` | TR-03/TR-06 đề xuất Goal | Instant tạo proposal | **FIX** → ngày VN |
| 6 | `goal/components/GoalHistoryTab.tsx:284→285`, `effectiveFrom` | ST-02 phiên bản Goal | **Instant**, không phải LocalDate (GoalVersionResponse) | **FIX** → ngày VN |
| 7 | cùng file `:286→287`, `effectiveUntil` | ST-02 phiên bản Goal | Instant; null nghĩa đang có hiệu lực | **FIX** → ngày VN; null giữ nhãn đang mở |
| 8 | cùng file `:423→424`, `transitionedAt` | ST-02 chuyển tiếp Goal | Instant sự kiện chuyển journey | **FIX** → ngày VN; không đổi history |
| 9 | `goal/components/GoalOverviewTab.tsx:45→46`, `pausedAt` | ST-02 tổng quan | Instant tạm dừng | **FIX** → VN ngày/giờ |
| 10 | cùng file `:101→102`, `updatedAt` | ST-02 tổng quan | Instant cập nhật | **FIX** → ngày VN |
| 11 | `goal/components/GoalProposalsTab.tsx:313→314`, `createdAt` | ST-02/ST-03 danh sách đề xuất | Instant tạo | **FIX** → ngày VN |
| 12 | `measurements/MeasurementsScreen.tsx:189→190`, DateString `measuredAt` | ST-11 / Trainer đọc theo scope | Instant đo (MeasurementResponse); không timezone bản ghi | **FIX** → VN ngày/giờ; hợp nhất hai lời gọi cùng instant |
| 13 | cùng file `:189→190`, TimeString `measuredAt` | ST-11 / Trainer | cùng Instant với #12 | **FIX** → cùng formatter; không ghép ngày thiết bị với giờ khác context |
| 14 | `measurements/components/CorrectMeasurementModal.tsx:106→107`, `measuredAt` | ST-11 sửa số đo | Instant bản đo | **FIX** → VN như danh sách; không đổi measuredAt của correction |
| 15 | `progress/ProgressDashboardScreen.tsx:348→348`, `knownVolumeKg` | ST-09 / Trainer Progress | **Number**, khối lượng kg; không phải thời gian | **KEEP** → định dạng số; không đưa qua formatter Date |
| 16 | `trainer/TrainerProfileScreen.tsx:1052→1053`, `submittedAt` | Hồ sơ Trainer PENDING | Instant nộp hồ sơ | **FIX** → ngày VN; không thay quyết định Admin |
| 17 | cùng file `:1273→1274`, `verifiedAt` | Hồ sơ Trainer VERIFIED | Instant xác minh | **FIX** → ngày VN |
| 18 | `workout-execution/WorkoutExecutionScreen.tsx:78→663/666/687`, helper `formatDate` | ST-07/ST-08 current/detail | Caller actual-start/end là ordinary Instant; planned-start là frozen snapshot Instant **không có timezone** | **FIX** tách caller: actual → VN; planned → UTC có nhãn thiếu timezone, không lấy tài khoản/thiết bị |
| 19 | `workout-execution/WorkoutHistoryScreen.tsx:23→80/83`, helper `formatDate` | ST-08 lịch sử Student | actual-start vs planned-start; cùng giới hạn DTO #18 | **FIX** cùng semantics #18; không tải live occurrence để viết lại lịch sử |
| 20 | `workout-plan/StudentWorkoutPlansScreen.tsx:45→46`, `effectiveFrom` | ST-05 plan hub | Instant kích hoạt version; không phải ngày dự kiến session | **FIX** → VN ngày/giờ |
| 21 | `workout-plan/WorkoutPlanDetailScreen.tsx:127→128`, `effectiveFrom` | ST-06 chi tiết Plan | Instant hiệu lực version | **FIX** → VN ngày/giờ |
| 22 | `workout-plan/WorkoutPlanHistoryScreen.tsx:64→65`, `effectiveFrom` | ST-06 lịch sử Plan | Instant hiệu lực version | **FIX** → VN ngày/giờ |
| 23 | `workout-plan/components/AiWorkoutAdjustmentModal.tsx:1820→1821`, `expiresAt` | ST-16 / TR-11 F03 | Instant expiry; không phải performed/planned local clock | **FIX** → VN ngày/giờ; không đổi phép kiểm expiry |

### Bằng chứng kiểu dữ liệu / caller

- `modules/goal/adapter/in/web/dto/GoalVersionResponse.java`: `effectiveFrom/effectiveUntil` là `Instant`; `startDate/targetDate/resumeDate` là `LocalDate`. Các LocalDate không đưa qua Date/UTC; regression CurrentGoal giữ nguyên hai ngày khác nhau.
- `modules/measurement/adapter/in/web/dto/MeasurementResponse.java`: `measuredAt` là `Instant`, không mang timezone. Màn list và correction dùng cùng helper, kiểm riêng cả hai phần cây render.
- `apps/mobile/src/types/workoutExecution.ts:29` (`WorkoutExecutionSummary`) và `modules/workout/adapter/in/web/WorkoutExecutionDtos.java:77` chứa frozen `plannedStartAt`, `originalPlannedStartAt`, `plannedEndAt`, actual start/end; không có timezone. `user.timezone` là trạng thái tài khoản hiện tại, không phải nguồn của frozen history. Dùng fallback đã có `formatRecordedScheduleTime(value, null)`; nhãn “Giờ chuẩn quốc tế (UTC) (UTC+00:00) (múi giờ của bản ghi cũ chưa xác định)”. Không suy timezone từ offset `Z` của instant.
- Caller helper #18: actual start/end và planned start; #19: actual start và planned start. Missing vẫn “Chưa có dữ liệu”; invalid/date-only/local không offset là “Chưa rõ thời điểm”.
- Goal/Plan version, proposal, profile, sharing và event DTO/contract là Instant không có saved timezone riêng; dùng quyết định ordinary VN đã duyệt, không đặt lại default business timezone.

## 3. Các semantics giữ nguyên có chủ đích

| Caller / formatter liên quan | Disposition / evidence |
|---|---|
| `ProgressDashboardScreen` ảnh `takenAt` / `AiKnowledgeScreen` `completedAt` / `formatContextFreeInstant` | **KEEP UTC có nhãn** theo #182 §12.4; không áp ordinary VN vào hai trường đã duyệt ngoại lệ |
| `scheduleDisplay.formatScheduleTime`, `formatTrainerScheduleTime`, `formatRecordedScheduleTime`; `proposalPresentation.formatProposalInstant` | **KEEP** timezone bản ghi/proposal, nhãn + offset theo instant (DST); thiếu/invalid timezone dùng UTC có giải thích. Tái sử dụng fallback, không đổi helper |
| Goal `startDate/targetDate/resumeDate`, date input; Attention `previousLocalDate/currentLocalDate/dueDate` | **KEEP date-only**; không parse UTC hoặc gắn offset |
| Schedule form local date-time và giờ trong ngày; timezone picker | **KEEP local/context đã xác nhận**; không đổi payload, chọn offset/DST, quiet hours hay recurrence |
| Workout `ElapsedTime`, set `durationSeconds/restSeconds`, session estimated minutes | **KEEP duration**; tiếp tục phép trừ epoch / số giây, không format như timestamp |
| `continuity.daysSinceLastWorkout`, relative-time/cửa sổ truy vấn (kể cả `ConversationScreen.linkWindow`) | **KEEP** khoảng thời gian/query semantics; không biến thành lịch ngày hoặc thời điểm trình bày |
| Chat/Attention, font, nutrient fallback, mapper lỗi | **KEEP** code đã merge; không sửa hoặc kiểm lại milestone cũ |

## 4. Regression và tự review

- Thêm **33 regression cases** trong 10 suite hiện hữu, utility `src/test-utils/timeDisplayRegression.ts` tái sử dụng `simulateDeviceTimezone` đã có.
- Cùng dữ liệu render thực tế trên UTC, Los Angeles, Auckland, Ho Chi Minh: kiểm nội dung cụ thể và toàn bộ text giống nhau giữa các thiết bị; `finally` unmount/restore ngay cả khi assertion fail.
- Boundary `16:59Z` / `17:00Z` → `23:59` ngày trước / `00:00` ngày sau VN; null/rỗng/invalid/date-only/local thiếu offset; tabs versions/transitions, hồ sơ PENDING/VERIFIED; list/correction đo được kiểm riêng.
- B05 actual và planned được kiểm riêng trong history/detail; timezone tài khoản New York không đổi frozen planned snapshot. Ca trước/sau DST Mỹ `2026-03-08T06:59Z/07:00Z`: actual VN `13:59/14:00`, planned UTC `06:59/07:00`, không nhận DST của tài khoản.
- Bảo toàn assertions cũ về zero/null, dữ liệu, quyền, payload, version, body/key/retry, race/session và navigation. Không sửa/gỡ assertion cũ.
- Tự review diff xác nhận chỉ presentation + test/docs; không thêm dependency hoặc thay helper semantics. Lượt test đầu có lỗi utility đọc React Text/circular JSON và expectation sai branch/nhãn timezone; đã sửa harness/assertion theo cây render thật trước evidence regression cuối. Đây không phải lỗi production hoặc bằng chứng runtime.

## 5. Lệnh và evidence đã chạy

Cwd `apps/mobile`, trừ khi ghi khác. Log ngoài Git: `D:\codex-work\p7-time-display-evidence\`. Mobile `node_modules` là junction đọc dependency tại `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-012-integration\apps\mobile\node_modules`; lockfile hai nơi cùng SHA-256 `3046C5AADA3BF9DED88E6064B9A20800C3BE65AEB58AFEA245C50728124F55F5`. Không `npm ci` hoặc sửa dependency chung.

| Lệnh / bước | Kết quả / log |
|---|---|
| Regression cuối trên source gốc: `node node_modules/jest/bin/jest.js --runInBand --testTimeout=30000 --testNamePattern="time display:" --runTestsByPath <10 suite>` | **FAIL đúng lỗi: 31 fail / 2 pass / 33 selected**, 157 skipped; `final-regression-before-fix.log`. Tạm ghi source từ `git show HEAD:<path>` trong checkout riêng, backup bytes và restore 15/15 file trong `finally` đã đối chiếu byte-for-byte; không reset/stash/checkout |
| `npx jest --runInBand --testTimeout=30000 --findRelatedTests <15 file source>` | **16/16 suite, 244/244 test PASS**, `focused-final.log`; gồm 33 regression mới, session/upload races, mutation/authority/navigation suites liên quan |
| `npx tsc --noEmit` | **PASS (exit 0)**; `typecheck.log` |
| `npm run lint` | **PASS (exit 0), 0 lỗi / 12 cảnh báo baseline**, không cảnh báo mới; `lint-1536.log` (lượt 1024 MB OOM lưu `lint.log`) |
| `npm run audit:ui-mixed` | **PASS (exit 0), 13 dòng baseline** (`coach-alex`, bài tập catalog và “reps”), chỉ số dòng đổi theo import; `audit-ui-mixed.log` |
| Repo root: `node tools/validate-ux-docs.mjs`; `git diff --check` | **PASS**, 18 required files / diff sạch; `ux-docs.log`, `diff-check.log` |

Trước kiểm tra nặng: D còn ~17,3 GB, physical free ~2,9 GB / virtual free ~3,2 GB; Jest/tsc/lint chạy tuần tự với heap 1024 MB cho Jest/tsc. Lint 1024 MB chạm heap (exit 134, `lint.log`); kiểm lại free physical ~2,6 GB / virtual ~3,0 GB rồi tăng heap lint lên 1536 MB. Không dừng process khác hoặc xóa cache/node_modules/worktree. Không chạy local full suite; full suite ở CI checkpoint.

## 6. Checkpoint, tích hợp và điểm dừng

- Git status ban đầu sạch, không có pre-existing diff trong clone mới. Source checkout cũ, đặc biệt `b06-phase1-schedule-appointment-closure`, ignored backlog và evidence khác được giữ nguyên.
- File đổi: 15 source theo bảng; 10 `src/__tests__/*.test.tsx` theo tên surface (GoalHistoryTab, GoalProposalsTab, CurrentGoalScreen, TrainerStudentGoalScreen, MeasurementsScreen, TrainerProfileScreen, coachingScreens, studentWorkoutPlanScreens, workoutExecutionScreens, AiWorkoutAdjustmentModal); test utility mới; `component-specifications.md`; bổ sung link follow-up trong handoff #183; handoff này.
- SHA-256 file cuối lưu ngoài Git ở `final-file-hashes.json`; commit head là hash chuẩn của toàn bộ checkpoint. PR/head/merge SHA và CI đúng SHA được ghi trong PR và báo cuối sau khi có kết quả; không tạo commit chỉ để ghi trạng thái CI.
- Đã nhường tài nguyên kiểm tra nặng lúc 10:48 ngày 27/11/2026 (Asia/Bangkok) sau focused tests/typecheck/lint/audit và UX docs, trước commit/push/PR/CI; không còn Jest/tsc/lint của task chạy. Trạng thái Git cuối được kiểm sau commit/push; không xóa branch.
- **Hoàn tất code task khi các checks checkpoint đạt; QA visual/Android/Web/thiết bị/bàn phím và release vẫn NOT RUN.** Không mở browser, Metro, emulator, điện thoại, backend, Docker, Figma hoặc stack QC trong task này. CI tự động không thay thế QA runtime.
