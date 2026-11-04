# Phase 2 Progress Checkpoint & Technical Verification State (2026-11-04)

> **Mục tiêu:** Đối chiếu hiện trạng hệ thống, đính chính các thông tin chưa chính xác, hoàn thiện kiểm chứng kỹ thuật F03 (multi-action values, rollback đa tầng, replay idempotency), xác lập thiết kế chuẩn cho Progress Engine & Watermark phục vụ F04  
> **Milestone trọng tâm:** P2-M4 — Mở Rộng Hỗ Trợ Tập Luyện  
> **Repository:** `D:\Projects\ai-integrated-personalized-online-fitness-coaching-system`  
> **Branch làm việc:** `chore/p2-m4-f03-closure-f04-readiness`  
> **Commit nền xác minh (Baseline commit):** `03d33aa58e2518ed14ff4073cbdd26d042efc990` (origin/main)  
> **Thời điểm đối chiếu & kiểm chứng:** 2026-11-05 00:05 (UTC+7)  

---

## 1. Đính Chính & Chuẩn Hóa Các Nhận Định Kỹ Thuật

Qua quá trình rà soát đối chiếu trực tiếp với mã nguồn và tài liệu Phase 2 đã phê duyệt, các nhận định kỹ thuật được đính chính như sau:

1. **Phân Định Target Scope vs Tiêu Chí Phân Loại Ý Nghĩa Thay Đổi (Significance):**
   - *Đính chính:* Không đồng nhất võ đoán `ONE_MATERIALIZED_OCCURRENCE == MINOR` và `TEMPLATE_OR_FUTURE_OCCURRENCES == SIGNIFICANT`.
   - *Hiện trạng:* Người dùng (Student/Trainer) chủ động lựa chọn `TargetScope` trên giao diện Mobile theo nhu cầu áp dụng (1 buổi tập cụ thể hay toàn bộ lịch trình tương lai).
   - *Chính sách chuyên môn:* Tiêu chí phân loại tự động minor vs significant (ví dụ: thay đổi bài phụ vs bài chính, thay đổi thể tích ngắn hạn vs chiến lược) hiện **chưa có tiêu chí được phê duyệt chính thức** từ Hội đồng Thể hình Chuyên môn D06.
   - *Ngưỡng 20%:* Tuyệt đối **không tự ý áp đặt hay coi ngưỡng 20% volume** là đã được phê duyệt. Ngưỡng này chỉ là đề xuất thảo luận kỹ thuật (UNAPPROVED PROPOSAL) đang chờ Hội đồng D06.

2. **Khả Năng Sửa Proposal, Revalidation, Diff và Chia Sẻ Nháp Trainer:**
   - *Đính chính:* Trong thiết kế đã duyệt của Phase 2 (Release Scope V1.0, D03, D04), luồng Trainer review/chỉnh sửa nháp proposal trước khi apply và chia sẻ nháp với Student là một phần thuộc phạm vi thiết kế.
   - *Hiện trạng:* Backend `AiWorkoutAdjustmentController` hiện tại mới chỉ cung cấp 4 endpoints (`POST /proposals`, `GET /{id}`, `POST /{id}/accept`, `POST /{id}/reject`), chưa có endpoint `PATCH/PUT` để chỉnh sửa và chưa có endpoint chia sẻ nháp.
   - *Kết luận:* Đây là **Implementation Gap của Phase 2**, không được ngộ nhận hay tự coi là "ngoài scope". Luồng lớn này được đưa vào Backlog kỹ thuật mở để triển khai đồng bộ tiếp theo, không tự ý thêm endpoint vội vàng trong phiên rà soát này.

3. **Chuẩn Hóa Schema & Thuộc Tính Thực Tế Cho F04:**
   - *Actual Workout:* Bảng thật trong schema PostgreSQL là **`fitness.workout_session_logs`**, thời gian bắt đầu thực tế là cột timestamptz **`performed_start_at`**, trạng thái là enum `status actual_workout_status` ('IN_PROGRESS', 'COMPLETED', 'PARTIALLY_COMPLETED', 'ABORTED'), child tables là `exercise_logs`, `set_logs`.
   - *Measurement:* Bảng thật là **`fitness.measurements`** (schema V6, V12, V55). Thuộc tính kiểm thực chuẩn là cột **`validation_status = 'VALID'`** (enum `measurement_validation_status`), **`superseded_by_id IS NULL`** và **`erased_at IS NULL`** (theo view `current_valid_measurements` trong V55).
   - *Ngưỡng Quality Score:* **Tuyệt đối không tự ý áp đặt ngưỡng `quality_score`** (ví dụ $\ge 80\%$) khi chưa có chính sách chuyên môn phê duyệt. Chỉ lọc đúng 3 điều kiện trạng thái hợp lệ trên, lưu giữ `quality_score` nếu có nhưng không tùy tiện loại trừ quan sát thực tế.

4. **Định Nghĩa Completion vs Adherence:**
   - *Mẫu số (Denominator):* Chỉ tính các buổi tập kế hoạch **đã đến hạn** (`planned_start_at <= asOf`). Buổi tập tương lai (`planned_start_at > asOf`) là `UPCOMING`, không tính vào mẫu số quá khứ.
   - *Trường hợp mẫu số = 0:* Trả về trạng thái tường minh **`NOT_APPLICABLE`** (`adherenceRate = null`), tuyệt đối không mặc định là `0%`.
   - *Không tự áp dụng weighted adherence:* Không tự ý gán các trọng số phạt (như 0.75 hay 0.5) khi chưa có policy; báo cáo rõ số buổi đúng ngày, số buổi lệch ngày (`deviationDays`), và số buổi bỏ lỡ (`missedCount`).
   - *Phân biệt lịch gốc vs lịch đã đổi hợp lệ:* Lịch dời hợp lệ (`RESCHEDULED`) có `planned_start_at` mới khác `original_planned_start_at`, đánh giá tuân thủ dựa trên lịch mới.

5. **Xác Định Tính Liên Tục (Training Continuity):**
   - Tuyệt đối **không suy diễn `CONFIRMED_NEW` từ tuổi tài khoản hoặc sự vắng mặt của workout log**.
   - Phân định rõ 4 trạng thái:
     1. `CONFIRMED_NEW`: Có xác nhận tường minh trong hồ sơ onboarding (`training_experience_level = 'BEGINNER'`).
     2. `RECENT_CONSISTENT`: Có log tập luyện hoàn thành trong 14 ngày gần nhất.
     3. `PROLONGED_INACTIVITY`: Từng có lịch sử tập nhưng gián đoạn $\ge 100$ ngày (rule B04/P2-M4).
     4. `UNKNOWN_OR_RESTRICTED_HISTORY`: Chưa có dữ liệu xác nhận hoặc bị giới hạn chia sẻ dữ liệu $\to$ chưa rõ lịch sử, không tự ý suy diễn là người mới hay người bỏ tập.

6. **Nguồn Gốc Khác Biệt Metadata Môi Trường & Trạng Thái Live GUI:**
   - *Đính chính:* Khác biệt giữa thông tin môi trường ghi trong text báo cáo Mobile (`Expo 54 / RN 0.81`) và file cấu hình `package.json` (`Expo ~57.0.24 / RN 0.86.3`) được kết luận chính xác là **"chưa xác minh" (unverified discrepancy)** do thiếu artifact log build của phiên trước; không võ đoán nguyên nhân.
   - *Trạng thái GUI:* Do Android Emulator và Docker physical container không chạy trong phiên này, kịch bản Live GUI Smoke test trên thiết bị giả lập được ghi nhận trung thực là **NOT RUN** (không ghi NOT RUN thành PASS).

---

## 2. Các Thay Đổi Mã Nguồn & Kiểm Thử Mới Thực Chạy Trong Phiên (2026-11-04)

### 2.1. Khắc Phục Lỗi Bỏ Sót Lệnh Trong `VOLUME_ADJUSTMENT` & Kiểm Chứng Toàn Diện Giá Trị
- **Mã nguồn điều chỉnh:** [`AiWorkoutAdjustmentService.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiWorkoutAdjustmentService.java#L520-L645).
- **Vấn đề phát hiện:** `WorkoutPlanCommandService.adjustOccurrence` chỉ nhận đúng 1 `AdjustmentType` (`SETS`, `REPS`, hoặc `DURATION`). Cấu trúc `if/else if` cũ chỉ thực thi nhánh sau và bỏ rơi hoàn toàn lệnh `sets`.
- **Giải pháp:** Cấu trúc lại bộ xử lý thành danh sách `OccurrenceSubAction` tuần tự. Service thực thi tuần tự các lệnh `adjustOccurrence` với `subCommandKey` xác định (`${commandKey}:adj:${changeId}:${subSuffix}`) và version tăng tuần tự ($V \to V+1 \to V+2$), bọc trong cùng Spring `@Transactional`.
- **Kiểm thử tự động mới:**
  1. *Unit Test:* `AiWorkoutAdjustmentServiceUnitTest#acceptAdjustment_occurrenceScope_volumeAdjustment_bothSetsAndReps_executesBothCommandsSequentially` — **PASS (1.8s)**.
  2. *PostgreSQL Integration Test (Sets + Reps):* `AiWorkoutAdjustmentFullSliceIntegrationTest#volumeAdjustment_occurrenceScope_withBothSetsAndReps_appliesBothAdjustmentsToDatabase` — **PASS (26.8s)** trên Testcontainers PostgreSQL. Kiểm tra chi tiết giá trị: `SETS` (`targetSets`: 3 $\to$ 2), `REPS` (`targetRepsMin/Max`: 8-12 $\to$ 6-10), version $1 \to 3$, replay `replayed = true` không sinh thêm bản ghi trùng.
  3. *PostgreSQL Integration Test (Sets + Duration):* `AiWorkoutAdjustmentFullSliceIntegrationTest#volumeAdjustment_occurrenceScope_withBothSetsAndDuration_appliesBothValuesAndVerifiesReceipt` — **PASS (26.8s)** trên Testcontainers PostgreSQL. Kiểm tra chi tiết giá trị: `SETS` (`targetSets`: 3 $\to$ 2), `DURATION` (`durationSeconds`: 60 $\to$ 45), version $1 \to 3$, replay `replayed = true` không sinh thêm bản ghi trùng.

### 2.2. Kiểm Chứng Database Rollback Thực Tế Trên PostgreSQL (Không Dùng Mock-Only)
- **Kiểm thử Rollback khi Sub-command Thứ Hai Thất Bại:**
  - Test: `AiWorkoutAdjustmentFullSliceIntegrationTest#volumeAdjustment_whenSecondSubCommandFails_rollsBackFirstCommandAndDatabaseCompletely` — **PASS (26.8s)**.
  - Kịch bản: Spy `workoutPlanCommands.adjustOccurrence`: Command 1 (SETS) chạy thật ghi vào PostgreSQL; Command 2 (REPS) ném `RuntimeException`.
  - Kết quả kiểm chứng PostgreSQL: Command 1 bị rollback hoàn toàn, occurrence version về 1, adjustments count = 0, command receipts = 0, domain audit = 0, recommendation = PENDING, application receipts = 0.
- **Kiểm thử Rollback Riêng Cho Audit Cuối `AI_RECOMMENDATION_APPLIED`:**
  - Test: `AiWorkoutAdjustmentFullSliceIntegrationTest#acceptAdjustment_whenFinalRecommendationAuditFails_rollsBackDomainChangesAndDomainAudits` — **PASS (26.8s)**.
  - Kịch bản: Spy `auditService.recordAudit`: Cho phép domain audit `PLANNED_WORKOUT_ADJUSTED` chạy thật và ghi vào database; chỉ ném exception khi gọi audit cuối `AI_RECOMMENDATION_APPLIED` tại step 6.
  - Kết quả kiểm chứng PostgreSQL: Cả domain mutation, domain command receipt và domain audit `PLANNED_WORKOUT_ADJUSTED` đều bị rollback về 0; recommendation status giữ PENDING, application receipts = 0.
  - **Khẳng định:** Phân biệt rõ rệt test lỗi audit domain đầu tiên với test lỗi audit ứng dụng cuối cùng, chứng minh tính nguyên tử toàn diện của giao dịch.

### 2.3. Kiểm Chứng Thuật Toán Composite Source Watermark
- **Test Suite Mới:** [`SourceWatermarkUnitTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/workout/SourceWatermarkUnitTest.java) — **5/5 tests PASS (0.18s)**.
- **Chứng minh:**
  1. Tính tất định: Dữ liệu giống nhau sinh watermark giống nhau.
  2. Bắt được sửa đổi bản ghi cũ trong window khi `MAX(updated_at)` không đổi.
  3. Bắt được bản ghi di chuyển ra/vào ranh giới window.
  4. Bắt được tombstone xóa dữ liệu đo lường (`erased_at`).
  5. Bắt được số đo bị superseded (`superseded_by_id`).

### 2.4. Tổng Hợp Kết Quả Thực Chạy Toàn Bộ Bộ Kiểm Thử
- **Backend Full Slice Integration Suite:** `AiWorkoutAdjustmentFullSliceIntegrationTest` 19 tests (19 passed, 0 failures, 0 errors, gồm 4 test mới: 2 test giá trị sets+reps và sets+duration, 2 test atomic rollback trên PostgreSQL) trên Testcontainers PostgreSQL pg18.
- **Backend Service Unit Suite:** `AiWorkoutAdjustmentServiceUnitTest` 12 tests (12 passed, 0 failures, 0 errors, gồm 1 test mới cho chuỗi lệnh tuần tự).
- **Source Watermark Unit Suite:** `SourceWatermarkUnitTest` 6 tests (6 passed, 0 failures, 0 errors).
- **Toàn bộ Backend Test Suite (`mvn test`):** 1.657 tests được ghi nhận: 1.652 passed, 5 skipped, 0 failures, 0 errors.
- **Mobile Client Test Suite:** 41/41 tests PASS (16 modal tests, 16 api tests, 9 screen tests).
- **Mobile TypeScript Typecheck:** 0 lỗi compilation.
- **AI Service Pytest Suite:** 67/67 tests PASS trong virtual environment.
- **AI Schemas & Fixtures:** 7/7 schema files, 32/32 fixtures PASS với Ajv 2020.

---


### 2.5. Khắc Phục Hoàn Chỉnh F04-T1: WorkoutProgressAggregationQuery (2026-11-05)
- **Mã nguồn triển khai:**
  * Cổng giao tiếp Inbound: [`WorkoutProgressAggregationQuery.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/workout/application/port/in/WorkoutProgressAggregationQuery.java).
  * Dịch vụ thực thi: [`WorkoutProgressAggregationService.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/workout/application/service/WorkoutProgressAggregationService.java).
- **Các khiếm khuyết kỹ thuật đã khắc phục triệt để:**
  1. *Snapshot consistency & Transaction isolation:* Áp dụng `@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)`. Ngăn chặn hiện tượng caller mở transaction READ COMMITTED lấn át isolation của method con. Kiểm chứng bằng hai thread/connection PostgreSQL độc lập đồng bộ qua CountDownLatch: transaction ghi commit giữa các lần đọc không gây rách snapshot trong kịch bản kiểm thử. Test outer transaction kiểm tra isolation và PostgreSQL backend PID riêng biệt. Không tuyên bố an toàn tuyệt đối cho mọi caller hoặc mọi tình huống.
  2. *Bảo vệ dữ liệu ngoài quyền & Paired tests:* Xóa bỏ hoàn toàn `restrictedPlannedIds`. Quyết định `evidenceStatus` thuần túy dựa trên phạm vi được cấp quyền của actor và thời điểm kế hoạch; SQL truy vấn actual sessions của HLV chỉ lọc nghiêm ngặt trong scope được cấp quyền. Paired tests xác nhận response không thay đổi khi dữ liệu Actual ngoài quyền thay đổi trong các kịch bản đã kiểm chứng, không làm rò rỉ thông tin về hoạt động ngoài quyền.
  3. *Đồng nhất cohort cho volume & coverage:* Mọi counters của window chỉ tính trên sessions nằm trong date window; linked execution ngoài window chỉ phục vụ linkage, không làm tăng counters của window, bảo đảm `loadCoverageRate <= 1.0`.
  4. *Unknown, bodyweight và zero:* Phân biệt chính xác bodyweight (xác nhận qua equipment catalog) với missing load (`setsWithMissingLoadCount`); phân biệt zero load hợp lệ (`setsWithZeroLoadCount`, 0 kg) với `null`. Volume chỉ tính khi có đủ reps, load và đơn vị hợp lệ.
  5. *Semantics lịch sử của set logs & asOf:* Truy vết `completed_at`, `created_at`, `updated_at`. Set hoàn thành sau `asOf` (`completed_at > asOf`) không được tính là hoàn thành tại `asOf`. Set sửa sau `asOf` (`updated_at > asOf`) dán nhãn `HISTORICAL_RECONSTRUCTION_LIMITED`, bật cờ `hasHistoricalReconstructionLimits = true` trong `VolumeAndIntensitySummary`, và loại trừ set RPE khỏi tính toán. Buổi tập đang diễn ra tại `asOf` nullify overall RPE.
  6. *Watermark conversion dependency:* Watermark bao phủ định nghĩa chuyển đổi đơn vị (`UNITS:`), hệ số chuyển đổi và dấu thời gian `completed_at` trong set digest; khi bảng `fitness.measurement_units` thay đổi hệ số, watermark và thể tích tính toán thay đổi ngay lập tức.
- **Kiểm thử tự động thực chạy:**
  * Suite F04-T1: [`WorkoutProgressAggregationIntegrationTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/WorkoutProgressAggregationIntegrationTest.java): 28 tests (28 passed, 0 failures, 0 errors) trên Testcontainers PostgreSQL pg18 (bao gồm Test 16 với 2 kết nối/barrier độc lập qua spy policy, Test 16b caller outer transaction với kết nối/isolation độc lập, Test 26 trực tiếp kiểm tra load/reps/duration khi set sửa sau asOf, và Test 28 cho valid zero volume 0.00 kg vs missing load null).
  * Suite Watermark Unit: [`SourceWatermarkUnitTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/workout/SourceWatermarkUnitTest.java): 6 tests (6 passed, 0 failures, 0 errors).
  * Kiểm thử F03: `AiWorkoutAdjustmentFullSliceIntegrationTest` (19 tests: 19 passed, 0 failures, 0 errors, gồm 4 test mới và 15 test hồi quy), `AiWorkoutAdjustmentServiceUnitTest` (12 tests: 12 passed, 0 failures, 0 errors, gồm 1 test mới và 11 test hồi quy).
  * Toàn bộ backend test suite (`mvn test`): 1.657 tests được ghi nhận: 1.652 passed, 5 skipped, 0 failures, 0 errors. Không tồn tại bất kỳ mutable test hook nào trong mã nguồn production.
## 3. Khoảng Trống Nghiệm Thu Kỹ Thuật Còn Lại Của F03

Mặc dù toàn bộ logic nghiệp vụ cốt lõi, quyền hạn, transaction và concurrency đã đạt 100% kiểm thử tự động, F03 vẫn **chưa thể tuyên bố khép hoàn toàn (Full Closure)** do các khoảng trống sau:

1. **Implementation Gaps:**
   - Thiếu API endpoint và giao diện Mobile cho Trainer chỉnh sửa proposal (`PATCH /workout-adjustments/{id}`) trước khi phê duyệt.
   - Thiếu API endpoint và giao diện chia sẻ nháp proposal giữa Trainer và Student.
2. **Evidence Gaps:**
   - Live GUI Smoke test trên Android Emulator cho bản build Expo 57 / RN 0.86 hiện tại đang ở trạng thái **NOT RUN**.
3. **Approval Gates:**
   - Hội đồng Thể hình Chuyên môn D06 chưa ký biên bản phê duyệt chính thức danh mục bài tập tương đương và tiêu chí phân loại minor/significant (ngưỡng 20% volume là unapproved proposal).
   - Chưa đo lường chi phí và độ trễ với LLM thật (live tokens).

---

## 4. Đặc Tả Contract & Tiêu Chí Hoàn Thành Cho `WorkoutProgressAggregationQuery` (F04)

### 4.1. Contract Interface Hiện Thực
```java
package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public interface WorkoutProgressAggregationQuery {

    WorkoutProgressSnapshot aggregate(
            UUID actorId,
            UUID studentId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    );

    record WorkoutProgressSnapshot(
            UUID studentId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf,
            AdherenceSummary adherence,
            VolumeAndIntensitySummary volumeAndIntensity,
            List<PlannedOccurrenceFact> plannedOccurrences,
            List<ActualSessionFact> actualSessions,
            String sourceWatermark
    ) {}

    record AdherenceSummary(
            int scheduledDueCount,
            int completedDueCount,
            int completedOnTimeCount,
            int completedOffScheduleCount,
            int missedCount,
            int todayPendingCount,
            int inProgressCount,
            int partiallyCompletedCount,
            int abortedCount,
            int rescheduledCount,
            int cancelledCount,
            int upcomingCount,
            int unplannedCompletedCount,
            int unverifiableDueCount,
            BigDecimal rawCompletionRate,
            BigDecimal onTimeAdherenceRate,
            String availabilityReason
    ) {}

    record VolumeAndIntensitySummary(
            BigDecimal knownVolumeKg,
            int totalSetsCompleted,
            int workingSetsCompleted,
            int warmupSetsCompleted,
            int bodyweightSetsCompleted,
            int setsWithMissingLoadCount,
            int setsWithZeroLoadCount,
            int setsWithMissingRepsCount,
            int setsWithUnknownUnitCount,
            int setsWithCalculableVolumeCount,
            BigDecimal loadCoverageRate,
            boolean hasIncompleteVolumeCoverage,
            int totalRepsCompleted,
            Integer totalSetDurationSeconds,
            Integer totalSessionElapsedSeconds,
            BigDecimal averageSessionRpe,
            BigDecimal averageSetRpe
    ) {}

    record PlannedOccurrenceFact(
            UUID occurrenceId,
            UUID planSessionId,
            UUID coachingPeriodId,
            Instant plannedStartAt,
            LocalDate plannedLocalDate,
            Instant originalPlannedStartAt,
            LocalDate originalPlannedLocalDate,
            String status,
            UUID rescheduledFromId,
            UUID scheduleChangeId,
            long version,
            UUID linkedExecutionId,
            String linkedExecutionStatus,
            Instant linkedPerformedStartAt,
            LocalDate linkedPerformedLocalDate,
            Integer scheduleDeviationDays,
            String evidenceStatus // VERIFIED, RESTRICTED_OR_UNKNOWN, UPCOMING
    ) {}

    record ActualSessionFact(
            UUID executionId,
            UUID plannedWorkoutId,
            UUID coachingPeriodId,
            Instant performedStartAt,
            LocalDate performedLocalDate,
            Instant performedEndAt,
            String status,
            BigDecimal overallRpe,
            BigDecimal knownVolumeKg,
            int setsCompleted,
            int workingSetsCompleted,
            int bodyweightSetsCompleted,
            int repsCompleted,
            Integer setDurationSeconds,
            Integer sessionElapsedSeconds,
            boolean isUnplanned,
            String historicalReconstructionStatus // HISTORICAL_SNAPSHOT_EXACT, HISTORICAL_RECONSTRUCTION_LIMITED
    ) {}
}
```

### 4.2. Tiêu Chí Hoàn Thành (Definition of Done) Sẵn Sàng Triển Khai
1. Map chính xác trên `planned_workouts` và `workout_session_logs` (`performed_start_at`, `planned_workout_id`).
2. Mẫu số adherence chỉ tính các buổi `planned_start_at <= asOf`; mẫu số 0 trả `NOT_APPLICABLE`.
3. Completion Rate tính trên tổng volume/sessions hoàn thành, độc lập với Adherence Rate.
4. Composite watermark tích hợp thuật toán SHA-256 từ `SourceWatermarkUnitTest`.
5. Đọc `measurements` lọc nghiêm ngặt `validation_status = 'VALID'`, `superseded_by_id IS NULL`, `erased_at IS NULL`, không áp đặt ngưỡng quality score khi chưa có policy.

### 3.4. Ranh Giới Nghiệm Thu F04: F04 Chưa Hoàn Thành
- Task F04-T1 hoàn thành việc thiết lập nguồn dữ liệu Workout nhất quán, chuẩn hóa mẫu số adherence và watermark của module Workout.
- **Tuyệt đối không tuyên bố F04 đã hoàn tất.** F04 còn các phần việc lớn đang mở:
  1. Module Measurement progress query & watermark (F04-T2, không bao gồm Nutrition, Admin UI hay data ingestion).
  2. Progress Engine tổng thể kết hợp đa nguồn (F04 không lấy Nutrition mặc định).
  3. FastAPI AI Summarization service & prompt evaluation.
  4. Giao diện Mobile Client F04 (giữ trạng thái Live GUI Smoke Test là NOT RUN trên emulator hiện tại).
  5. Hội đồng Thể hình Chuyên môn D06 / OD-3 phê duyệt các quy tắc và ngưỡng cảnh báo an toàn (gate chuyên môn còn mở).
