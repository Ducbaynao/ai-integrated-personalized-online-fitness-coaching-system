# Báo Cáo Kiểm Chứng Kỹ Thuật Mobile F03 (P2-M4)

> **Mã đầu việc:** P2-M4 — Triển khai Giao diện Đề xuất Điều chỉnh Workout (Mobile F03) & Kiểm chứng Nghiệp vụ  
> **Repository:** `D:\Projects\ai-integrated-personalized-online-fitness-coaching-system`  
> **Branch thực hiện:** `feat/p2-m4-f03-mobile` (được người dùng ủy quyền tạo từ HEAD)  
> **Commit nền xác minh (Baseline commit):** `8430f4ae5eb9e7cd5f3ac7fab72f77ba1cccc627` (origin/main, tích hợp PR #135 Backend F03 `eb6f6aacfaca58c3ba12109f125585f148c719b6`, CI post-merge run 41530055353 SUCCESS, baseline CI run 41643163902 6/6 jobs SUCCESS)  
> **Thời điểm cập nhật báo cáo:** 2026-11-04 04:35 (UTC+7)  
> **Môi trường & công cụ thực tế ghi nhận:**  
> - **Mobile Client:** Node.js v22.14.0, React Native 0.81.5, Expo SDK 54 (54.0.21), React 19.1.0, TypeScript 5.9.2, Jest 29.7.0, `react-test-renderer` 19.1.0, Metro Bundler 0.81.5.  
> - **Android Runtime & Emulator:** Android Emulator version 37.1.11.0 (build_id 14588975), Pixel 8 (AVD `Pixel_8`, API 34 / Android 14.0 Google APIs, x86_64), adb version 1.0.41 (36.0.0-13204543), Expo Go client (`host.exp.exponent`).  
> - **Spring Boot Backend:** Java 21 (Eclipse Adoptium OpenJDK 21.0.6), Spring Boot 4.1.1 (tích hợp F03 qua PR #135), Maven 3.9.x, port 8080 (chạy profile `dev`, mode `http`).  
> - **Database:** PostgreSQL 18.6 (Docker container `digital-fitness-postgres` port 5433, schema `fitness`).  
> - **AI Service:** Python 3.12.9, FastAPI 0.115.x, Uvicorn 0.34.x, pytest 8.x, port 8000.  

---

## 1. Tóm Tắt Hiện Trạng & Tiến Trình Triển Khai

Toàn bộ giao diện, API client và các luồng nghiệp vụ Mobile F03 (Đề xuất điều chỉnh workout) đã được triển khai, kiểm thử tự động và kiểm chứng trên Android Emulator theo đúng thiết kế đã phê duyệt (`docs/08-ai/f03-contract-and-authority-design.md`, `phase-2-feature-data-authority-matrix.md`):

1. **Tuân thủ Kiến trúc & Ranh giới Module:**
   - Client Mobile chỉ gửi request tới Spring Boot Backend qua các REST endpoints `/api/v1/ai/workout-adjustments*`. Tuyệt đối không gọi trực tiếp FastAPI AI Service.
   - Thao tác Accept-and-Apply và Reject được ủy quyền xử lý nguyên tử trong Spring domain commands (`adjustOccurrence` cho buổi tập cụ thể hoặc `publishVersion` cho mẫu giáo án). Không bao giờ đặt lời gọi AI/LLM bên trong database transaction.
2. **Quy tắc Thẩm quyền (Authority Invariants):**
   - Trong chế độ `SELF_DIRECTED`: Học viên (`STUDENT`) có quyền yêu cầu, xem Before/After, Accept hoặc Reject đề xuất.
   - Trong chế độ `HUMAN_COACH`: Huấn luyện viên (`TRAINER`) giữ thẩm quyền quyết định.
   - **Phân định rõ cấp độ chặn đối với Student trong `HUMAN_COACH`:**
     - *Phạm vi Giáo án mẫu (Template Scope):* Bị chặn ngay từ bước **Generate** — Spring Boot `AiWorkoutAdjustmentService` ném `AI_RUN_ACCESS_DENIED` (HTTP 403) vì học viên không có quyền tác giả/điều chỉnh giáo án của HLV; UI hiển thị banner cảnh báo thẩm quyền.
     - *Phạm vi Buổi tập (Occurrence Scope):* Được phép Generate và xem đề xuất Before/After để tham khảo, nhưng bị chặn ở bước **Accept / Reject** — Component `AiWorkoutAdjustmentModal` chủ động kiểm tra `isStudentUnderHumanCoach === true`, ẩn hoàn toàn các nút Accept/Reject và hiển thị banner thông báo thẩm quyền chỉ HLV mới có quyền quyết định.
3. **Phạm vi Đề xuất (Target Scope) & Bảo vệ Phiên bản (Version Guard):**
   - `ONE_MATERIALIZED_OCCURRENCE`: Điều chỉnh 1 buổi tập cụ thể (`SCHEDULED`). Gọi domain command `adjustOccurrence`, sử dụng `expectedOccurrenceVersion` từ occurrence đang thao tác, không tạo phiên bản giáo án mới. Chặn điều chỉnh buổi tập đã `COMPLETED` hoặc `IN_PROGRESS`.
   - `TEMPLATE_OR_FUTURE_OCCURRENCES`: Điều chỉnh mẫu giáo án và các buổi tương lai. Gọi domain command `publishVersion`, sử dụng `expectedPlanAggregateVersion` từ giáo án (`aggregateVersion`), sinh phiên bản giáo án mới.
   - Đã loại bỏ hoàn toàn giá trị giả định `planAggregateVersion: 1` cố định; phiên bản được đọc động từ query cache của plan.
   - **Bảo vệ Phiên bản trên `TrainerWorkoutPlanDetailScreen`:** Đã khắc phục lỗi lấy prescriptions từ phiên bản lịch sử được chọn trong khi `planContext` lại gửi `currentVersion`. Hiện tại, modal điều chỉnh của HLV luôn nhận prescriptions thuộc phiên bản hiện hành (`currentVersion`), bảo đảm target prescription và `planVersionNumber` hoàn toàn đồng bộ, đi kèm ca kiểm thử hồi quy tự động.
4. **Phân biệt Rõ ràng Unknown, Zero và Bodyweight:**
   - Dữ liệu khuyết thiếu (chưa đo, reps, duration) hiển thị rõ là *"Chưa có dữ liệu"* hoặc *"—"*, tuyệt đối không tự động chuyển thành số 0.
   - Không suy diễn `null` / `undefined` thành `Bodyweight`. Chỉ hiển thị nhãn `Bodyweight` khi có dữ liệu xác nhận rõ ràng.
   - Không tự động mặc định đơn vị là `KG` khi đơn vị chưa biết (`loadUnit` là null).
5. **Cách ly Trạng thái (State Isolation) & Chống Double Submit:**
   - Sử dụng `activeSessionIdRef` để hủy bỏ và bỏ qua phản hồi của các request in-flight cũ khi người dùng đóng modal, đổi học viên hoặc đổi target ID. Phản hồi cũ không được làm thay đổi proposal, loading hay điều hướng của phiên mới.
   - Khóa đồng bộ (`isGeneratingRef`, `isDecidingRef`) để chống bấm đúp (double submit) trước khi React re-render.
   - Giữ nguyên `commandKey` ổn định khi người dùng bấm thử lại cùng một thao tác sau khi gặp lỗi mạng.
6. **Kiểm chứng Xung đột Phiên bản Thật (True Stale Target Conflict):**
   - Phân biệt rành mạch giữa ba cơ chế đồng thời và an toàn miền:
     - *True Stale Target Conflict:* Đề xuất đang ở trạng thái `PENDING`. Buổi tập mục tiêu bị một thao tác nghiệp vụ khác sửa đổi làm tăng version từ $V \to V+1$. Khi cố Accept đề xuất cũ với `expectedTargetVersion = V`, hệ thống trả về HTTP 409 Conflict (mã lỗi backend `AI_RUN_LIFECYCLE_CONFLICT` kèm thông điệp `Planned workout occurrence version conflict: expected version V but current is V+1`). Đề xuất vẫn ở trạng thái `PENDING` trong database, không sinh bản ghi áp dụng và không xảy ra mutation một phần.
     - *Lifecycle Replay Guard:* Đề xuất đã ở trạng thái `APPLIED`. Cố tình gửi lệnh Accept mới với commandKey khác sẽ bị từ chối với HTTP 409 Conflict (`AI_RUN_LIFECYCLE_CONFLICT: Recommendation has already been applied.`).
     - *Idempotent Replay:* Gửi lại cùng request và cùng `commandKey` trả về HTTP 200 OK với `replayed: true` mà không gây hiệu ứng phụ lặp lại.

---

## 2. Ma Trận Nghiệm Thu Kỹ Thuật & Khả Năng Truy Vết (Traceability Matrix)

| Mã Yêu Cầu | Tên Yêu Cầu & Kịch Bản Nghiệp Vụ | Mã Kế Hoạch Phase 2 | Phương Pháp Kiểm Chứng Thực Tế | Bằng Chứng Kiểm Chứng Thực Tế | Trạng Thái | Ghi Chú Phân Loại Thực Tế |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **F03-MOB-01** | Khởi tạo Modal Yêu cầu Điều chỉnh với đầy đủ tùy chọn (Scope, Type, Reason, Equipment, Limitations) | **T27** | Automated Component Test + Live GUI Runtime | Test `renders modal when visible=true` & ảnh chụp `gui_01_trainer_modal_open.png`, `gui_07_self_directed_schedule_modal_open.png` | **PASS** | Giao diện hiển thị đúng các lựa chọn scope, loại điều chỉnh, lý do, chips thiết bị và toggle chấn thương. |
| **F03-MOB-02** | Bảo vệ phiên bản theo Scope (Occurrence vs Template Version Guards) | **T05, T27** | Automated Component Test + Unit Test | Test `uses correct expected version based on scope` & test hồi quy Trainer historical version | **PASS** | Occurrence gửi version của buổi tập; Template gửi aggregate version của plan hiện hành. |
| **F03-MOB-03** | Hiển thị thẻ Before / After cho `EXERCISE_SUBSTITUTION` | **T27** | Automated Component Test | Test `renders Before/After diff card for substitution` | **PASS** | Thể hiện bài tập cũ $\to$ bài tập mới, nhóm cơ tác động và thiết bị thay thế. |
| **F03-MOB-04** | Hiển thị thẻ Before / After cho `LOAD_ADJUSTMENT` kèm đơn vị tải | **T27, T07** | Automated Component Test + Live GUI Runtime | Test `renders Before/After diff card for load adjustment with units` & ảnh `gui_02`, `gui_08` | **PASS** | Hiển thị mức tạ kèm đơn vị (20 KG $\to$ 17.5 KG, -2.5 KG / -12.5%, DECREASE). |
| **F03-MOB-05** | Phân biệt rõ ràng Unknown, Zero và Bodyweight | **T07** | Automated Component Test | Test `renders bodyweight without converting to zero` và `distinguishes unknown, zero, specific units and bodyweight` | **PASS** | Không suy diễn null thành Bodyweight; không mặc định KG khi unit là null; không biến null thành 0. |
| **F03-MOB-06** | Hiển thị Before / After cho `VOLUME_ADJUSTMENT` (Reps vs Duration) | **T27** | Automated Component Test + Live GUI Runtime | Test `renders volume adjustment with duration` & ảnh `gui_04_trainer_volume_before_after.png` | **PASS** | Hiển thị hiệp $\times$ số lần (reps) hoặc hiệp $\times$ thời lượng (giây). |
| **F03-MOB-07** | Hiển thị Căn cứ đề xuất (`reasoning`), Bằng chứng (`evidence`), Cảnh báo an toàn (`limitations`), Dữ liệu khuyết thiếu (`missingData`) | **T27** | Automated Component Test + Live GUI Runtime | Test `renders reasoning, limitations, and evidence chips` & ảnh `gui_02`, `gui_08` | **PASS** | Hiển thị rõ danh sách căn cứ, trích dẫn chuyên môn và mục thiếu dữ liệu (`recent_exercise_rpe_logs`). |
| **F03-MOB-08** | Chấp nhận & Áp dụng (Accept-and-Apply) nguyên tử: Huấn luyện viên duyệt Giáo án mẫu | **T05, T18, T27** | Live GUI Android Emulator + API & DB Audit | Ảnh `gui_01` $\to$ `gui_03`, DB audit `workout_plans.version` $4 \to 5$, change_reason `AI_WORKOUT_ADJUSTMENT_APPLIED` | **PASS** | **GUI Thao Tác Trực Tiếp** trên Emulator; database xác nhận tăng aggregate version của giáo án. |
| **F03-MOB-09** | Chấp nhận & Áp dụng (Accept-and-Apply) nguyên tử: Học viên Tự luyện duyệt Buổi tập | **T05, T27** | Live GUI Android Emulator + API & DB Audit | Ảnh `gui_07` $\to$ `gui_09`, DB audit `planned_workouts.version` $5 \to 6$, `workout_session_adjustments` row inserted, `ai_recommendation_applications` inserted | **PASS** | **GUI Thao Tác Trực Tiếp** trên Emulator từ màn hình Lịch tập; database xác nhận cập nhật occurrence và lưu adjustment. |
| **F03-MOB-10** | Từ chối (Reject) đề xuất kèm lý do, không làm thay đổi giáo án/buổi tập | **T27** | Live GUI Android Emulator + API & DB Audit | Ảnh `gui_04` $\to$ `gui_05` (Trainer) và `gui_10` (Student), DB audit status `REJECTED`, zero domain mutation | **PASS** | **GUI Thao Tác Trực Tiếp** trên Emulator; database xác nhận status `REJECTED`, version giữ nguyên. |
| **F03-MOB-11** | Ràng buộc thẩm quyền: Chặn Student trong chế độ `HUMAN_COACH` | **T02** | Live GUI Android Emulator + Component Test | Test `shows authority restriction banner for student in HUMAN_COACH mode` & ảnh `gui_06` | **PASS** | **GUI Thao Tác Trực Tiếp**: Template scope bị chặn ở Generate (HTTP 403); Occurrence scope bị chặn ở Accept/Reject (nút bị ẩn). |
| **F03-MOB-12** | Xử lý Xung đột Buổi tập bị Sửa đổi song song (True Stale Target Conflict) | **T05, T25** | Automated Script + Live API & DB Audit | Kịch bản [`tools/verify_stale_target_conflict.py`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/tools/verify_stale_target_conflict.py): $R_1$ PENDING gặp occurrence đã tăng version do $R_2$ applied | **PASS** | **Automated Script + Live API + DB Audit**: HTTP 409 `AI_RUN_LIFECYCLE_CONFLICT` (`Planned workout occurrence version conflict`), $R_1$ giữ nguyên PENDING, zero partial mutation. |
| **F03-MOB-13** | Phân tách Rõ ràng Lifecycle Replay Guard và Idempotency Replay | **T06, T25** | Automated Script + Live API & DB Audit | Kịch bản [`tools/verify_stale_target_conflict.py`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/tools/verify_stale_target_conflict.py): Re-apply $R_2$ với key mới $\to$ 409 (`already been applied`); Re-apply với key cũ $\to$ 200 replayed | **PASS** | **Automated Script + Live API + DB Audit**: Tách biệt lỗi "đề xuất đã được áp dụng" khỏi lỗi xung đột phiên bản của buổi tập. |
| **F03-MOB-14** | Cách ly trạng thái (State Isolation): Hủy phản hồi in-flight cũ khi đổi phiên | **T04, T25** | Automated Component Test | Test `discards stale generate response if modal was closed or reopened` & `discards stale accept callback if session changed` | **PASS** | Phản hồi cũ bị loại bỏ an toàn thông qua `activeSessionIdRef`. |
| **F03-MOB-15** | Chống Double Submit & Bảo toàn Idempotency Key | **T06, T25** | Automated Component Test + Unit Test | Test `prevents double submit and preserves retry commandKey` | **PASS** | Đồng bộ khóa ref chặn double click; giữ nguyên `commandKey` khi retry. |
| **F03-MOB-16** | Xử lý lỗi API client và ánh xạ thông báo lỗi chi tiết / tiếng Việt | **T13** | Automated Unit Test | 16 tests trong `apps/mobile/src/__tests__/aiWorkoutAdjustmentApi.test.ts` | **PASS** | Ánh xạ mã lỗi 400, 403, 404, 409, 422, 503, field errors và default messages. |
| **F03-MOB-17** | Tích hợp Entry Points trên màn hình Lịch tập và Giáo án | **T27** | Code Inspection + Live GUI Navigation | `StudentWorkoutScheduleDetailScreen`, `WorkoutPlanDetailScreen`, `TrainerWorkoutPlanDetailScreen` | **PASS** | Chỉ hiển thị khi buổi tập ở `SCHEDULED` hoặc giáo án ở `ACTIVE`. Đọc dynamic `aggregateVersion`. |

---

## 3. Tổng Hợp Kết Quả Thực Thi Kiểm Thử Tự Động (Automated Test Execution)

Tất cả các lệnh kiểm thử tự động, kiểm tra kiểu dữ liệu và kiểm tra quy tắc mã nguồn đã được thực thi thực tế trong môi trường local và ghi nhận kết quả như sau:

```bash
# 1. Chạy toàn bộ Unit Tests & Component Tests cho Mobile F03 và màn hình Workout Plan
npm test --prefix apps/mobile -- src/__tests__/AiWorkoutAdjustmentModal.test.tsx src/__tests__/aiWorkoutAdjustmentApi.test.ts src/__tests__/workoutPlanScreens.test.tsx --runInBand
# Kết quả:
# PASS src/__tests__/aiWorkoutAdjustmentApi.test.ts (16 tests passed)
# PASS src/__tests__/AiWorkoutAdjustmentModal.test.tsx (16 tests passed)
# PASS src/__tests__/workoutPlanScreens.test.tsx (9 tests passed, bao gồm test hồi quy Trainer historical version)
# Test Suites: 3 passed, 3 total
# Tests:       41 passed, 41 total
# Snapshots:   0 total
# Time:        3.85 s

# 2. Kiểm tra TypeScript Typecheck toàn bộ Mobile
npm run typecheck --prefix apps/mobile
# Kết quả: 0 errors (Exit code: 0)

# 3. Kiểm tra ESLint Linting toàn bộ Mobile
npm run lint --prefix apps/mobile
# Kết quả: 0 errors, 0 warnings (Exit code: 0)

# 4. Kiểm tra toàn bộ AI Schemas và Fixtures của hệ thống
node tools/validate-ai-schemas.mjs
# Kết quả:
# Validating AI contract schemas...
# Checked 7 schema files.
# Validating fixtures against schemas...
# Validated 32 fixtures across 7 schemas.
# All schemas and fixtures are valid!

# 5. Chạy Script Kiểm Chứng Tự Động True Stale Target Conflict & Lifecycle Replay Guard
python tools/verify_stale_target_conflict.py
# Kết quả:
# ALL VERIFICATION ASSERTIONS PASSED SUCCESSFULLY! (Exit code: 0)
```

---

## 4. Bằng Chứng Thực Nghiệm Trực Quan Trên Android Emulator & Live API

> [!NOTE]
> Toàn bộ ảnh chụp màn hình từ `gui_01` đến `gui_10` được ghi nhận trực tiếp qua tiện ích nhị phân `adb shell screencap -p /sdcard/<name>.png && adb pull /sdcard/<name>.png <artifact_path>`, bảo đảm nguyên vẹn định dạng PNG nhị phân chuẩn (magic bytes `\x89PNG\r\n\x1a\n`, kích thước chuẩn 1080x2400) mà không bị lỗi mã hóa ký tự.

### Case 1: Huấn Luyện Viên (TRAINER) Điều Chỉnh Mẫu Giáo Án (Template Scope) — GUI + DB Audit
1. **Môi trường & Tài khoản:**
   - Đăng nhập HLV `smoke.trainer@example.com` trên Android Emulator (`emulator-5554`).
   - Vào mục **Học viên** $\to$ Chọn học viên `Norole User` $\to$ Mở giáo án `1-Week Hypertrophy Goal Starter Plan` (`bb2a6813-f8a4-4a57-8da7-1834be203000`), phiên bản hiện tại là 4.
2. **Khởi tạo Đề xuất trên GUI:**
   - Bấm **"Đề xuất điều chỉnh bài tập (AI)"** $\to$ Modal mở ra (`gui_01_trainer_modal_open.png`).
   - Chọn scope: **"Mẫu giáo án & tương lai"** (Template scope).
   - Chọn bài tập: `Barbell Bench Press` (`5143db2d-77d9-43e6-8034-4bb877c4f9f7`).
   - Chọn loại điều chỉnh: `LOAD_ADJUSTMENT`.
   - Bấm **"Tạo đề xuất điều chỉnh"** $\to$ Modal hiển thị thẻ Before / After trực quan (`gui_02_trainer_before_after_proposal.png`):
     - Mức tạ cũ: `20 KG` $\to$ Mức tạ mới: `17.5 KG` (`-2.5 KG / -12.5%`, hướng `DECREASE`).
     - Lý do, Căn cứ đề xuất, và Dữ liệu khuyết thiếu (`recent_exercise_rpe_logs`) hiển thị đầy đủ.
3. **Chấp thuận & Áp dụng (Accept-and-Apply) trên GUI:**
   - HLV bấm **"Chấp thuận & Áp dụng"**:
     - Client gửi request `POST /api/v1/ai/workout-adjustments/{recId}/accept` kèm `expectedPlanAggregateVersion = 4`.
     - Spring Boot thực thi lệnh miền `publishVersion`, tăng phiên bản aggregate của giáo án từ 4 lên 5.
     - Modal tự động đóng, màn hình re-render hiển thị ngay: **"Phiên bản aggregate 5 · Hiện tại"** và **"Phiên bản nội dung 5"** (`gui_03_trainer_plan_version_5_applied.png`).
   - **Đối soát Database:**
     ```sql
     SELECT id, name, version FROM fitness.workout_plans WHERE id = 'bb2a6813-f8a4-4a57-8da7-1834be203000';
     -- Kết quả: version = 5
     SELECT version_number, change_reason FROM fitness.workout_plan_versions WHERE workout_plan_id = 'bb2a6813-f8a4-4a57-8da7-1834be203000' ORDER BY version_number DESC LIMIT 1;
     -- Kết quả: version_number = 5, change_reason = 'AI_WORKOUT_ADJUSTMENT_APPLIED'
     ```
4. **Kiểm chứng Từ chối (Reject) của HLV trên GUI:**
   - Mở lại modal, chọn `VOLUME_ADJUSTMENT`, tạo đề xuất (Trước: 4 hiệp $\to$ Sau: 3 hiệp) (`gui_04_trainer_volume_before_after.png`).
   - Bấm **"Từ chối"** $\to$ Chuyển sang giao diện xác nhận $\to$ Bấm **"Xác nhận từ chối"**.
   - Modal đóng lại, màn hình giữ nguyên phiên bản 5 (`gui_05_trainer_plan_rejected_no_mutation.png`).
   - Database xác nhận status là `REJECTED`, version giữ nguyên 5 (zero domain mutation).

---

### Case 2: Ràng Buộc Thẩm Quyền Học Viên trong Chế Độ HUMAN_COACH — GUI
1. **Tài khoản & Bối cảnh:**
   - Đăng nhập học viên `smoke.student@example.com` trên Android Emulator (`mode = 'HUMAN_COACH'`).
2. **Kịch bản Template Scope (Bị chặn ở Generate):**
   - Vào **Kế hoạch tập luyện** $\to$ Mở chi tiết giáo án $\to$ Bấm **"Đề xuất điều chỉnh bài tập (AI)"**.
   - Chọn loại điều chỉnh `LOAD_ADJUSTMENT` $\to$ Bấm **"Tạo đề xuất điều chỉnh"**.
   - Backend kiểm tra thẩm quyền và trả về HTTP 403 Forbidden: `AI_RUN_ACCESS_DENIED`.
   - Giao diện Mobile hiển thị banner thông báo thẩm quyền tiếng Việt (`gui_06_student_human_coach_authority_restricted.png`):
     *"Bạn không có thẩm quyền quyết định đối với đề xuất điều chỉnh này trong chế độ huấn luyện hiện tại."*
3. **Kịch bản Occurrence Scope (Bị chặn ở Accept/Reject):**
   - Học viên mở chi tiết buổi tập theo lịch.
   - Khi modal mở ra, component chủ động kiểm tra cờ `isStudentUnderHumanCoach === true`.
   - Giao diện ẩn hoàn toàn các nút Accept và Reject, thay bằng banner cảnh báo chỉ HLV mới có thẩm quyền phê duyệt thay đổi buổi tập.

---

### Case 3: Học Viên Tự Luyện (SELF_DIRECTED) Điều Chỉnh Buổi Tập — Full GUI Flow trên Android Emulator
1. **Tài khoản & Bối cảnh:**
   - Học viên tự luyện: `student.f02.runtime@example.com` (User ID `9c0bad94-120a-437f-a6a7-d1d439f382cf`), chế độ huấn luyện `SELF_DIRECTED`.
   - Buổi tập dự kiến: `f3000000-0000-4000-8000-000000000001` (`SCHEDULED`, phiên bản ban đầu `version = 5`), bài tập `Barbell Bench Press` (`5143db2d-77d9-43e6-8034-4bb877c4f9f7`, mức tạ mục tiêu ban đầu 20.0 KG).
2. **Thao tác GUI từ Màn hình Lịch tập:**
   - Từ màn hình chính $\to$ Chọn **Kế hoạch tập luyện** $\to$ Chọn **Xem lịch tập dự kiến** $\to$ Bấm **Xem chi tiết lịch tập** (`StudentWorkoutScheduleDetailScreen`).
   - Bấm nút **"Đề xuất điều chỉnh bài tập (AI)"** (`ai-workout-adjustment-button`) tại vị trí `(353, 1308)` $\to$ Modal mở lên (`gui_07_self_directed_schedule_modal_open.png`).
3. **Tạo Đề xuất & Xem So Sánh Before / After:**
   - Chọn loại điều chỉnh: **"Điều chỉnh mức tạ (Load Adjustment)"**.
   - Bấm **"Tạo đề xuất điều chỉnh"** (`adjustment-generate-button`) $\to$ Giao diện hiển thị thẻ Before / After rõ ràng (`gui_08_self_directed_before_after.png`):
     - **MỨC TẠ CŨ:** `20 KG`
     - **MỨC TẠ MỚI:** `17.5 KG` (Hướng: `DECREASE`, Chênh lệch: `-2.5 KG (-12.5%)`)
     - **Lý do:** *"Load reduction recommended for fatigue management and form safety."*
     - **Căn cứ đề xuất:** Căn cứ tải trọng và ngữ cảnh học viên `9c0bad94...`
     - **Dữ liệu khuyết thiếu:** `recent_exercise_rpe_logs`
     - **Nút hành động:** Hiển thị đầy đủ **"Chấp thuận & Áp dụng"** và **"Từ chối"** (vì học viên có thẩm quyền `SELF_DIRECTED`).
4. **Chấp thuận & Áp dụng (Accept-and-Apply) trên GUI:**
   - Học viên bấm **"Chấp thuận & Áp dụng"** (`adjustment-accept-button`) tại tọa độ `(540, 1857)`.
   - Modal tự động đóng lại, màn hình chi tiết buổi tập tự động làm mới (`gui_09_self_directed_applied.png`).
   - **Đối soát Database Thực Tế Ngay Sau Khi Bấm:**
     ```sql
     -- 1. Phiên bản buổi tập tăng từ 5 lên 6:
     SELECT id, version, updated_at FROM fitness.planned_workouts WHERE id = 'f3000000-0000-4000-8000-000000000001';
     -- Kết quả: version = 6, updated_at = 2026-11-03 21:17:04.31032+00

     -- 2. Bản ghi điều chỉnh buổi tập được ghi nhận với đúng giá trị before/after:
     SELECT id, planned_workout_id, adjustment_type, before_value, after_value, reason, created_at 
     FROM fitness.workout_session_adjustments 
     WHERE planned_workout_id = 'f3000000-0000-4000-8000-000000000001'
     ORDER BY created_at DESC LIMIT 1;
     -- Kết quả: id = 'c567801e-eca9-4e33-bbcf-ec71add7742c', adjustment_type = 'LOAD',
     --          before_value = '{"targetLoad": 20.0}', after_value = '{"loadUnitId": 1, "targetLoad": 17.5}',
     --          reason = 'Load reduction recommended for fatigue management and form safety.'

     -- 3. Bản ghi áp dụng đề xuất AI được lưu vết:
     SELECT id, ai_recommendation_id, applied_by, resulting_entity_type, resulting_entity_id, applied_at
     FROM fitness.ai_recommendation_applications
     ORDER BY applied_at DESC LIMIT 1;
     -- Kết quả: id = '95feec1d-c7f2-4f18-a9ad-12b4769c5196', ai_recommendation_id = '784cb88c-594a-48f6-b70a-6c52ae1d2340',
     --          resulting_entity_type = 'WORKOUT_OCCURRENCE', resulting_entity_id = 'f3000000-0000-4000-8000-000000000001'
     ```
5. **Kiểm chứng Luồng Từ Chối (Reject) của Học viên trên GUI:**
   - Mở lại modal $\to$ Tạo đề xuất điều chỉnh mức tạ mới $\to$ Bấm **"Từ chối"** (`adjustment-start-reject-button`) $\to$ Bấm **"Xác nhận từ chối"** (`adjustment-reject-button`).
   - Modal hiển thị thông báo trạng thái: **"Đề xuất đã ở trạng thái REJECTED."** và nút chuyển sang **"Tạo đề xuất điều chỉnh khác"** (`gui_10_self_directed_rejected.png`).
   - **Đối soát Database Xác Nhận ZERO Mutation:**
     ```sql
     -- 1. Phiên bản buổi tập GIỮ NGUYÊN là 6:
     SELECT id, version FROM fitness.planned_workouts WHERE id = 'f3000000-0000-4000-8000-000000000001';
     -- Kết quả: version = 6

     -- 2. Số lượng bản ghi adjustments GIỮ NGUYÊN là 3 (không tạo thêm):
     SELECT count(*) FROM fitness.workout_session_adjustments WHERE planned_workout_id = 'f3000000-0000-4000-8000-000000000001';
     -- Kết quả: 3

     -- 3. Trạng thái đề xuất trong DB được chuyển thành REJECTED:
     SELECT id, status, decided_by, decided_at FROM fitness.ai_recommendations ORDER BY created_at DESC LIMIT 1;
     -- Kết quả: id = 'e8442675-6310-48be-b872-251b82bc4773', status = 'REJECTED',
     --          decided_by = '9c0bad94-120a-437f-a6a7-d1d439f382cf'
     ```

---

### Case 4: Kiểm Chứng Xung Đột Phiên Bản Thật (True Stale Target Conflict) — Automated Script & DB Audit
Kịch bản được tự động hóa hoàn toàn và xác minh bằng assertions nghiêm ngặt thông qua [`tools/verify_stale_target_conflict.py`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/tools/verify_stale_target_conflict.py):

1. **Khởi tạo Đề xuất $R_1$:**
   - Tại thời điểm buổi tập `f3000000-0000-4000-8000-000000000001` đang ở phiên bản $V_0$, gửi request tạo đề xuất $R_1$:
     - Endpoint: `POST /api/v1/ai/workout-adjustments`
     - Sinh thành công: $R_1$ mang trạng thái `PENDING` (xác nhận qua DB query `ai_recommendations.status = 'PENDING'`).
2. **Tạo Thay Đổi Song Song Hợp Lệ ($R_2$):**
   - Tạo tiếp đề xuất $R_2$ trên cùng buổi tập.
   - Thực thi Accept đề xuất $R_2$ thành công qua lệnh miền (`expectedTargetVersion = V_0`):
     - `POST /api/v1/ai/workout-adjustments/{r2_id}/accept` $\to$ HTTP 200 OK (`replayed = false`).
     - Database xác nhận phiên bản buổi tập tăng lên **$V_1 = V_0 + 1$**.
3. **Cố Tình Chấp Thuận Đề Xuất $R_1$ Đã Bị Cũ (Stale Target Version):**
   - Gửi lệnh Accept cho đề xuất $R_1$ với phiên bản mục tiêu cũ `expectedTargetVersion = V_0`:
     - Endpoint: `POST /api/v1/ai/workout-adjustments/{r1_id}/accept`
     - **Kết quả HTTP:** **409 Conflict**
     - **Mã lỗi thực tế từ Spring Boot:** `AI_RUN_LIFECYCLE_CONFLICT`
     - **Thông điệp thực tế:** `"Planned workout occurrence version conflict: expected version 8 but current is 9"`
4. **Đối Soát Database Xác Nhận An Toàn Toàn Vẹn Dữ Liệu:**
   - Trạng thái đề xuất $R_1$ trong bảng `fitness.ai_recommendations` vẫn giữ nguyên là **`PENDING`** (assertion `r1_status_post == 'PENDING'`).
   - Số lượng bản ghi trong `fitness.ai_recommendation_applications` cho $R_1$ bằng **0** (assertion `r1_apps_count == 0`).
   - Phiên bản buổi tập giữ nguyên là **$V_1$** (assertion `v_final == v_after_r2`, zero partial mutation).

---

### Case 5: Phân Biệt Lifecycle Replay Guard và Idempotency Replay — Automated Script Audit
Được tích hợp và kiểm chứng tự động trong [`tools/verify_stale_target_conflict.py`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/tools/verify_stale_target_conflict.py):

1. **Lifecycle Replay Guard (Chống Áp Dụng Lặp Đề Xuất Đã Duyệt):**
   - Khi gửi một lệnh Accept mới (với `commandKey` mới) cho đề xuất $R_2$ đã ở trạng thái `APPLIED`:
     - Endpoint: `POST /api/v1/ai/workout-adjustments/{r2_id}/accept`
     - **Kết quả HTTP:** **409 Conflict**
     - **Mã lỗi:** `AI_RUN_LIFECYCLE_CONFLICT`
     - **Thông điệp:** `"Recommendation has already been applied."`
     - **Assertion kiểm chứng:** `assert "already been applied" in res.message.lower()` $\to$ **PASS**.
2. **Idempotent Replay (Bảo Toàn Khóa Thao Tác Khi Mạng Chập Chờn):**
   - Khi gửi lại đúng `commandKey` ban đầu của $R_2$:
     - Endpoint: `POST /api/v1/ai/workout-adjustments/{r2_id}/accept`
     - **Kết quả HTTP:** **200 OK**
     - **Phản hồi:** `{"replayed": true, "status": "APPLIED", "recommendationId": r2_id}`
     - **Assertion kiểm chứng:** `assert res.replayed is True` $\to$ **PASS**, không phát sinh bản ghi mutation thứ hai.

---

## 5. Bàn Giao Fixtures Phát Triển (Dev Fixtures) & Ranh Giới Chuyên Môn OD-3

### 5.1. File Fixture & Cách Tái Tạo An Toàn
Để phục vụ việc chạy lại môi trường kiểm thử cục bộ và phát triển tính năng mà không sửa đổi các tệp migration Flyway đã áp dụng (V1–V55), script nạp fixtures an toàn đã được đóng gói và đặt đúng theo convention repository tại:
[`tools/seed_f03_dev_fixtures.sql`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/tools/seed_f03_dev_fixtures.sql)

- **Nội dung:** Bổ sung 2 biến thể thay thế `b0000000-0000-4000-8000-000000000001` (Incline Bench Press) và `b0000000-0000-4000-8000-000000000002` (Close-Grip Bench Press) trực thuộc bài tập gốc `8fd50c74-03f7-45a7-8c4b-fce16a96bdb4` (`Barbell Bench Press`) với trạng thái `is_active = true`.
- **Cơ chế:** Sử dụng cú pháp `INSERT ... ON CONFLICT (id) DO UPDATE` bảo đảm tính an toàn khi chạy lại nhiều lần (idempotent).
- **Điều kiện tiên quyết (Prerequisites):**
  1. PostgreSQL đang chạy trên port 5433 (hoặc qua Docker container `digital-fitness-postgres`).
  2. Các migration từ `V1` đến `V55` đã được Flyway áp dụng thành công.
- **Lệnh thực thi:**
  ```bash
  docker exec -i digital-fitness-postgres psql -U fitness_app -d digital_fitness < tools/seed_f03_dev_fixtures.sql
  ```

### 5.2. Ranh Giới Chuyên Môn OD-3 (Quan Trọng)
> [!IMPORTANT]
> Việc cấu hình trạng thái `is_active = true` và liên kết cùng bài tập gốc (`Barbell Bench Press`) trong script fixture trên chỉ là giải pháp kỹ thuật cục bộ phục vụ môi trường phát triển (development fixture) nhằm vượt qua catalog availability guard và base exercise equivalence guard của `SimulatedF03WorkoutProvider`.  
> Thao tác này **TUYỆT ĐỐI KHÔNG** thay thế quy trình phê duyệt danh mục bài tập tương đương chính thức (OD-3) từ Hội đồng Chuyên môn Thể thao. Trong môi trường production, các cặp bài tập thay thế tương đương phải được duyệt và đánh dấu chuyên môn trước khi đưa vào áp dụng.

---

## 6. Danh Mục Tệp Thay Đổi & Bàn Giao

### 6.1. Tệp tạo mới
1. `apps/mobile/src/types/aiWorkoutAdjustment.ts`: Định nghĩa toàn bộ TypeScript interfaces, enums, Before/After models, receipts, target refs và error types cho F03.
2. `apps/mobile/src/services/aiWorkoutAdjustmentApi.ts`: API service cho 4 endpoints `/api/v1/ai/workout-adjustments*`, hàm trích xuất `fieldErrors`, và bộ ánh xạ thông báo lỗi chi tiết `getWorkoutAdjustmentErrorMessage`.
3. `apps/mobile/src/__tests__/aiWorkoutAdjustmentApi.test.ts`: 16 ca kiểm thử unit cho API client, commandKey, và error mapper.
4. `apps/mobile/src/features/workout-plan/components/AiWorkoutAdjustmentModal.tsx`: Component modal giao diện hoàn chỉnh cho Mobile F03 với state isolation, scoped version guard, unit/load formatting và anti-double-submit.
5. `apps/mobile/src/__tests__/AiWorkoutAdjustmentModal.test.tsx`: 16 ca kiểm thử component mô phỏng toàn bộ luồng giao diện Mobile F03 (bao gồm các ca kiểm thử hồi quy cho state isolation, version scoping, unknown/zero/unit/bodyweight distinction, và idempotency).
6. `tools/seed_f03_dev_fixtures.sql`: Script nạp fixtures phát triển an toàn, có thể tái lập, không làm thay đổi migration Flyway.
7. `tools/verify_stale_target_conflict.py`: Script kiểm chứng tự động cho True Stale Target Conflict, Lifecycle Replay Guard và Idempotent Replay với assertions nghiêm ngặt và không chứa secrets.
8. `docs/08-ai/f03-mobile-acceptance-report-20261103.md`: Báo cáo nghiệm thu kỹ thuật này.
9. `docs/08-ai/artifacts/f03-mobile-runtime-acceptance-20261103/`: Thư mục lưu trữ ảnh chụp màn hình chuẩn binary PNG (`gui_01` đến `gui_10`) và XML UI hierarchy dump từ Android emulator.

### 6.2. Tệp hiệu chỉnh
1. `apps/mobile/src/features/workout-schedule/StudentWorkoutScheduleDetailScreen.tsx`: Tích hợp nút điều chỉnh buổi tập và modal F03, đọc dynamic `aggregateVersion` từ plan query cache thay vì hardcode, set prescription `loadUnit: null`.
2. `apps/mobile/src/features/workout-plan/WorkoutPlanDetailScreen.tsx`: Tích hợp nút điều chỉnh giáo án mẫu học viên và modal F03, set prescription `loadUnit: null`.
3. `apps/mobile/src/features/workout-plan/TrainerWorkoutPlanDetailScreen.tsx`: Sửa lỗi lấy prescription từ historical version; modal luôn nhận prescriptions thuộc phiên bản hiện hành (`currentVersion`) đồng bộ với `planVersionNumber`.
4. `apps/mobile/src/__tests__/workoutPlanScreens.test.tsx`: Bổ sung test hồi quy xác nhận modal HLV luôn nhận prescriptions thuộc phiên bản hiện hành.
5. `docs/08-ai/f03-backend-checkpoint.md`: Cập nhật thông tin PR #135 merged SHA `eb6f6aacfaca58c3ba12109f125585f148c719b6`, kết quả CI hậu merge run 41530055353 (SUCCESS), baseline 8430f4a.

---

## 7. Phân Tích Khoảng Trống Nghiệm Thu (Acceptance Gaps) & Ranh Giới Kỹ Thuật

Để đảm bảo tính trung thực kỹ thuật và ranh giới chuyên môn theo `AGENTS.md`, các điểm mở sau được ghi nhận rõ ràng:

1. **Ranh giới Nghiệm thu Chức năng vs Cột mốc (Milestone Boundary):**
   - Báo cáo này hoàn thành phạm vi mã nguồn, tích hợp giao diện và kiểm thử kỹ thuật của riêng **Mobile F03**.
   - Việc nghiệm thu toàn bộ cột mốc **P2-M4** phụ thuộc vào việc hoàn tất và tích hợp đầy đủ cả 4 tính năng trong phạm vi cột mốc (F01, F03, F04, F05) theo kế hoạch triển khai Phase 2 (`docs/07-development/phase-2-implementation-plan.md`). Báo cáo này **không tuyên bố nghiệm thu toàn bộ cột mốc P2-M4**.
2. **Quyết định Nghiệp vụ Chưa Định Lượng (Gate D06 / OD-3):**
   - Các ngưỡng định lượng nâng cao (ví dụ: ngưỡng giới hạn khối lượng tập 20%, tỷ lệ phần trăm tăng mức tạ tối đa cho phép khi đề xuất tăng tải) vẫn là quyết định mở thuộc Gate D06 / OD-3 đang chờ phê duyệt chính thức từ Hội đồng Chuyên môn Thể thao. Giao diện Mobile F03 tuyệt đối không hardcode các ngưỡng chưa phê duyệt này vào business logic của client.
3. **Mô hình AI & Đánh giá Chất lượng LLM:**
   - Kiểm thử Backend và AI Service hiện tại sử dụng nhà cung cấp mô phỏng (`SimulatedF03WorkoutProvider` / deterministic fixtures). Báo cáo này không đánh giá chất lượng văn phong hoặc độ chính xác sinh lý học của các mô hình LLM thương mại trong môi trường production.
