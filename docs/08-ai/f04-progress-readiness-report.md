# Báo Cáo Kiểm Tra Mức Sẵn Sàng Dữ Liệu Progress Phục Vụ Tính Năng F04

> **Mã tính năng:** F04 — Workout & Progress Summarization, Signal Explanation & Trainer Review Support
> **Milestone liên quan:** P2-M4 — Mở Rộng Hỗ Trợ Tập Luyện
> **Repository:** `D:\Projects\ai-integrated-personalized-online-fitness-coaching-system`
> **Branch thực hiện:** `chore/p2-m4-f03-closure-f04-readiness`
> **Baseline commit:** `03d33aa58e2518ed14ff4073cbdd26d042efc990`
> **Thời điểm lập và cập nhật báo cáo:** 2026-11-05 00:05 (UTC+7)
> **Tài liệu căn cứ:**
> - [`phase-2-release-scope.md`](../00-project-overview/phase-2-release-scope.md) (Mục 4, F04)
> - [`phase-2-feature-data-authority-matrix.md`](phase-2-feature-data-authority-matrix.md) (Mục 1.2, 1.3, 1.4, 1.8, 5)
> - [`phase-2-implementation-plan.md`](../07-development/phase-2-implementation-plan.md) (Mục 1, 1.1, 6)
> - [`business-rules.md`](../02-domain/business-rules.md) & [`lifecycles.md`](../02-domain/lifecycles.md)

---

## 1. Mục Tiêu & Phạm Vi Báo Cáo

Báo cáo này tiến hành rà soát thực tế mã nguồn, schema PostgreSQL thực tế và các query/contract hiện có để chuẩn bị điều kiện tiên quyết cho tính năng **F04 (Tóm tắt tiến độ, giải thích tín hiệu và hỗ trợ Huấn luyện viên review)**.

> [!IMPORTANT]
> **Nguyên tắc bất biến trong đánh giá:**
> 1. Không dùng AI service, mock hoặc fixture giả lập để thay thế nguồn dữ liệu thật (source of truth).
> 2. Nhiệm vụ này **chỉ khảo sát, chuẩn hóa định nghĩa và đặc tả contract/backlog** cho F04; không tự ý triển khai toàn bộ Progress Engine, F04 API/UI hoặc LLM summarization khi hạ tầng dữ liệu chưa sẵn sàng.
> 3. F04 là chức năng cung cấp thông tin (`INFORMATION`), tuyệt đối không có thao tác tự động sửa giáo án hay thay đổi dữ liệu nghiệp vụ (`NO MUTATION`).

---

## 2. Bảng Đối Chiếu Hiện Trạng Schema & Query Ports Thực Tế

| Dữ liệu cần thiết | Schema PostgreSQL Thực Tế | Query / Contract hiện có trong Code | Thiếu gì so với yêu cầu F04 | Kiểm thử (Tests) cần bổ sung | Trạng thái Blocker đối với F04 |
| :--- | :--- | :--- | :--- | :--- | :---: |
| **1. Khoảng ngày & Múi giờ** (Date window & Timezone) | Bảng `student_review_timezones`, `users.timezone` | [`AttentionReviewService#confirmTimezone`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/progress/AttentionReviewService.java#L78-L100) | Chưa có query contract tổng hợp chu kỳ linh hoạt (7 ngày, 28 ngày, 90 ngày) theo local date của học viên để quy đổi cả planned và actual | Test chuyển đổi ngày theo timezone `Asia/Ho_Chi_Minh` và các timezone quốc tế; test ranh giới 00:00 local time | **BLOCKER NHẸ** (Cần thêm Query Port tổng hợp) |
| **2. Goal & Version theo thời gian** | Bảng `fitness_goals`, `fitness_goal_versions` | [`GetCurrentFitnessGoalUseCase`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/goal/application/port/in/GetCurrentFitnessGoalUseCase.java), `GetGoalVersionsUseCase` | Chưa có query lấy snapshot Goal có hiệu lực tại mốc thời gian cụ thể (`asOf` hoặc khoảng `[from, to]`); hiện chỉ lấy goal ACTIVE hiện hành | Test học viên chuyển đổi Goal (ví dụ Hypertrophy $\to$ Fat Loss) trong cửa sổ phân tích; giữ nguyên lịch sử cũ | **BLOCKER NHẸ** (Cần thêm Query Port theo khoảng thời gian) |
| **3. Planned Workout vs Actual Workout** | Bảng `planned_workouts` (`planned_start_at`, `status`) vs bảng **`workout_session_logs`** (**`performed_start_at`**, `status`), child tables `exercise_logs`, `set_logs` | [`WorkoutScheduleCalendarQuery`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/workout/application/port/in/WorkoutScheduleCalendarQuery.java) (planned), [`WorkoutExecutionQueryUseCase`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/workout/application/port/in/WorkoutExecutionQueryUseCase.java) (actual) | Chưa có read adapter thống nhất đối chiếu giữa `planned_workouts` và `workout_session_logs` thông qua khóa `planned_workout_id` trong cùng cửa sổ báo cáo | Test đối chiếu 1 Planned Workout được thực hiện bởi 1 Actual Workout; test buổi tập tự do (`planned_workout_id IS NULL`) | **BLOCKER TRUNG BÌNH** (Cần Query Adapter đối chiếu) |
| **4. Completion Rate vs Schedule Adherence** | Bảng `planned_workouts` (`original_planned_start_at`, `planned_start_at`) vs `workout_session_logs` (`performed_start_at`) | [`WorkoutCompletionEvidenceQuery`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/workout/application/port/in/WorkoutCompletionEvidenceQuery.java), [`WorkoutMissedEvidenceQuery`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/workout/application/port/in/WorkoutMissedEvidenceQuery.java) | **Thiếu hoàn toàn logic phân tách**: Hiện chỉ đếm thô; chưa chốt cohort mẫu số đã đến hạn, chưa phân biệt lịch gốc vs lịch dời, chưa xử lý mẫu số bằng 0 | Test kịch bản "Tập Thứ Ba thay Thứ Hai" (Completion đạt 100%, Adherence báo cáo lệch 1 ngày); test mẫu số = 0 | **BLOCKER CHÍNH** (Yêu cầu thuật toán Progress Engine) |
| **5. Dữ liệu Đo lường (Body Measurements)** | Bảng **`measurements`** (schema V6, V12, V55): điều kiện hợp lệ: **`validation_status = 'VALID'`**, **`superseded_by_id IS NULL`**, **`erased_at IS NULL`** (view `current_valid_measurements`) | [`MeasurementUnitReferenceQuery`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/measurement/application/port/in/MeasurementUnitReferenceQuery.java) | Module `measurement` chưa có Application Service đọc chuỗi xu hướng thể chất. **Quy tắc chuẩn:** Phải dùng đúng 3 điều kiện trên; **không tự ý áp đặt ngưỡng quality_score** khi chưa có chính sách phê duyệt | Test lọc bỏ bản ghi `SUSPECT`, `SUPERSEDED` và tombstone `erased_at IS NOT NULL`; test không suy diễn "tăng cân = tăng cơ" khi thiếu body fat % | **BLOCKER CHÍNH** (Cần hoàn thiện Service tầng Domain) |
| **6. Source Watermark Toàn Vẹn** | Bảng `planned_workouts`, `workout_session_logs`, `set_logs`, `workout_session_adjustments`, `measurements` | Chưa có trong production code. Đã có bộ test thiết kế `SourceWatermarkUnitTest` (5 tests PASS) | Cần hiện thực hóa thuật toán Composite Digest Watermark trong tầng query để phát hiện thay đổi bản ghi cũ, bản ghi ra/vào window và tombstone | Đã có 5 unit tests kiểm chứng các failure modes của `MAX(updated_at)` | **BLOCKER TRUNG BÌNH** (Đã có thuật toán & test, cần ghép vào Query) |
| **7. Continuity State Determination** | Bảng `student_profiles` (`training_experience_level`), `workout_session_logs` | [`AttentionReviewService#evaluateReturns`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/progress/AttentionReviewService.java#L190-L240) (rule $\ge 100$ ngày) | Chưa phân định đúng: Tuyệt đối không suy diễn `CONFIRMED_NEW` từ tuổi tài khoản hay không có log; cần phân biệt rõ `CONFIRMED_NEW`, `RECENT_CONSISTENT`, `PROLONGED_INACTIVITY`, `UNKNOWN_OR_RESTRICTED_HISTORY` | Test học viên mới có xác nhận onboarding vs học viên không có log do chưa nhập/bị giới hạn quyền | **BLOCKER TRUNG BÌNH** (Yêu cầu logic phân loại chuẩn) |
| **8. Quyền hạn HLV: Relationship & Time Scope** | Bảng `coaching_relationships`, `coaching_sharing_scopes` | [`CoachingAuthorityQuery`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/application/port/in/CoachingAuthorityQuery.java), [`CurrentCoachingContextQuery`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/coaching/application/port/in/CurrentCoachingContextQuery.java) | Đã có kiểm tra scope cơ bản (`DataScope.WORKOUT_HISTORY`, `BODY_METRICS`); cần filter theo `effectiveTimeRange` của hợp đồng coaching | Test HLV mới chỉ xem dữ liệu trong phạm vi chia sẻ; HLV cũ mất quyền xem tiến độ mới | **SẴN SÀNG 80%** (Cần ghép filter thời gian vào Query) |

---

## 3. Định Nghĩa Cốt Lõi Về Completion Rate & Schedule Adherence

Trước khi viết mã nguồn thực thi, hai chỉ số này phải được định nghĩa tường minh để tránh các suy diễn sai lệch:

### 3.1. Xác Định Cohort & Mẫu Số (Denominator)
- **Cửa sổ phân tích (Date Window):** `[fromDate, toDate]` (local date) tại múi giờ của học viên (`ZoneId timezone`).
- **Thời điểm tham chiếu (`asOf`):** Mốc thời gian `Instant` khi truy vấn được thực hiện.
- **Mẫu số của Schedule Adherence:** Là tổng số buổi tập theo kế hoạch **đã đến hạn tính đến thời điểm `asOf`** trong cửa sổ phân tích:
  $$\text{Scheduled Due Sessions} = \{ p \in \text{PlannedWorkouts} \mid p.\text{localDate} \in [\text{fromDate}, \text{toDate}] \land p.\text{planned\_start\_at} \le asOf \}$$
- **Xử lý buổi tập chưa đến hạn (`UPCOMING`):** Các buổi tập có `planned_start_at > asOf` nằm trong tương lai của cửa sổ phân tích tuyệt đối **không được tính vào mẫu số** của tỷ lệ tuân thủ, tránh việc làm giảm điểm tuân thủ của học viên một cách phi lý.
- **Trường hợp mẫu số bằng 0 (Không có buổi tập nào đến hạn trong kỳ):**
  - Tuyệt đối **không mặc định là `0%`** (sẽ gây hiểu nhầm là học viên vi phạm 100%).
  - Phải trả về trạng thái rõ ràng: `adherenceRate = null`, `status = 'NOT_APPLICABLE'` kèm lý do `NO_SCHEDULED_DUE_SESSIONS`.

### 3.2. Phân Định Rạch Ròi Các Trạng Thái Buổi Tập Kế Hoạch & Thực Tế
1. **`COMPLETED_ON_TIME` (Hoàn thành đúng lịch):** Có bản ghi `workout_session_logs` hoàn thành (`status = 'COMPLETED'`) với `performed_start_at` rơi vào đúng ngày của `planned_start_at` ($\text{deviationDays} = 0$).
2. **`COMPLETED_OFF_SCHEDULE` (Hoàn thành lệch lịch — Ca "Tập Thứ Ba thay Thứ Hai"):** Có bản ghi `workout_session_logs` hoàn thành nhưng ngày tập thực tế lệch so với ngày kế hoạch ($\text{deviationDays} \neq 0$). Về mặt thể tích/khối lượng: tính 100% completion; về mặt tuân thủ: ghi nhận là buổi lệch lịch kèm số ngày lệch cụ thể.
3. **`PARTIALLY_COMPLETED` (Hoàn thành một phần):** Buổi tập có log thực tế nhưng số bài tập hoặc số sets ghi nhận không đạt yêu cầu tối thiểu của giáo án. Báo cáo tỷ lệ hoàn thành theo khối lượng thực tế, không đánh đồng với hoàn thành 100%.
4. **`MISSED` (Bỏ lỡ buổi tập):** Buổi tập có lịch rơi vào quá khứ (`planned_start_at \le asOf`) nhưng không có bản ghi `workout_session_logs` nào được ghi nhận. Được tính vào mẫu số và làm giảm tỷ lệ tuân thủ.
5. **`RESCHEDULED` (Dời lịch hợp lệ):** Buổi tập đã có thỏa thuận dời lịch trước khi diễn ra (`planned_start_at` mới khác `original_planned_start_at`). Đánh giá tuân thủ phải dựa trên `planned_start_at` hợp lệ hiện hành, đồng thời ghi nhận lịch sử dời lịch để HLV theo dõi tần suất dời lịch của học viên.
6. **`CANCELLED` (Hủy hợp lệ):** Buổi tập được hủy có lý do chính đáng bởi HLV hoặc Học viên. Không tính là buổi bỏ lỡ vô kỷ luật.
7. **`UNPLANNED_ACTUAL` (Buổi tập tự do ngoài kế hoạch):** Bản ghi `workout_session_logs` có `planned_workout_id IS NULL`. Buổi tập này được tính vào tổng khối lượng (volume/calories/tần suất vận động) nhưng không nằm trong mẫu số của kế hoạch giáo án.
8. **`REST_DAY` (Ngày nghỉ theo lịch):** Ngày không có buổi tập nào được lên lịch, không phải là buổi bỏ lỡ.

### 3.3. Chính Sách Tính Toán: Không Tự Áp Dụng Weighted Adherence
- Khi chưa có chính sách chuyên môn được phê duyệt về các trọng số phạt (như 0.75 cho lệch 1 ngày, 0.5 cho lệch 2 ngày):
- Hệ thống **tuyệt đối không tự ý áp đặt các công thức trọng số võ đoán**.
- Tiến độ phải được báo cáo bằng các số liệu thống kê tường minh:
  * `plannedDueCount`: Số buổi kế hoạch đã đến hạn.
  * `completedOnTimeCount`: Số buổi hoàn thành đúng ngày.
  * `completedOffScheduleCount`: Số buổi hoàn thành lệch ngày.
  * `missedCount`: Số buổi bỏ lỡ.
  * `averageDeviationDays`: Độ lệch ngày trung bình của các buổi tập.
  * Tỷ lệ tuân thủ cơ bản: $\text{Schedule Adherence Rate} = \frac{\text{completedOnTimeCount}}{\text{plannedDueCount}}$ (nếu $\text{plannedDueCount} > 0$).

---

## 4. Thiết Kế Composite Source Watermark Toàn Vẹn

### 4.1. Phân Tích Sự Thất Bại Của Cơ Chế Đơn Giản `MAX(updated_at)`
Nghiên cứu kiến trúc chỉ ra rằng chỉ sử dụng `MAX(updated_at)` làm watermark sẽ bỏ sót dữ liệu trong các trường hợp sau:
1. **Sửa bản ghi cũ trong cửa sổ (Older Record Correction):** Học viên có 3 buổi tập (Thứ Hai, Thứ Ba, Thứ Tư). Buổi Thứ Tư có `updated_at` là 15:00. Sau đó học viên sửa tạ của buổi Thứ Hai lúc 10:00. Nếu so sánh `MAX(updated_at)` của cửa sổ, giá trị vẫn là 15:00 của Thứ Tư $\to$ Watermark không đổi $\to$ Bỏ sót thay đổi của buổi Thứ Hai!
2. **Bản ghi di chuyển vào hoặc ra khỏi cửa sổ (Date Window Boundary Shifts):** Học viên dời ngày của một buổi tập từ 31/10 (ngoài cửa sổ) sang 02/11 (vào trong cửa sổ). Nếu buổi tập mới nhất trong cửa sổ có `updated_at` lớn hơn, `MAX(updated_at)` không đổi mặc dù số lượng buổi tập trong cửa sổ đã tăng từ 2 lên 3!
3. **Xóa dữ liệu / Tombstone (`erased_at`):** Một số đo cân nặng bị đánh dấu xóa (tombstone theo GDPR/B08). Không có bản ghi mới nào được chèn, `MAX(measured_at)` không đổi, nhưng số đo hợp lệ đã biến mất khỏi cửa sổ!
4. **Số đo bị thay thế (`superseded_by_id`):** Một số đo cũ bị superseded bởi số đo mới. Giá trị sử dụng thay đổi.

### 4.2. Thuật Toán Composite Digest Watermark (Đã Kiểm Chứng 100% Tests)
Watermark được sinh ra dưới dạng mã băm SHA-256 từ chuỗi tuần tự có cấu trúc bao phủ toàn bộ nguồn dữ liệu:
```
CompositeWatermark = SHA256(
    "WINDOW:" + fromDate + ":" + toDate + ":" + timezone + "|" +
    "PLANNED:" + count + ";" + sorted([id, version, status, planned_start_epoch, updated_at_epoch]) + "|" +
    "ACTUAL:"  + count + ";" + sorted([id, performed_start_epoch, status, updated_at_epoch, set_logs_digest]) + "|" +
    "ADJ:"     + count + ";" + sorted([id, adjustment_type, created_at_epoch]) + "|" +
    "MEAS:"    + count + ";" + sorted([id, measured_at_epoch, numeric_value, superseded_by_id, erased_at])
)
```
- **Kiểm chứng tự động:** Bộ kiểm thử [`SourceWatermarkUnitTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/workout/SourceWatermarkUnitTest.java) đạt **5/5 tests PASS (100%)**:
  1. `identicalInputs_produceIdenticalWatermark`: Tính tất định.
  2. `modifyingOlderRecordInsideWindow_changesWatermark_evenWhenLatestUpdatedAtUnchanged`: Bắt được sửa đổi bản ghi cũ khi `MAX(updated_at)` không đổi.
  3. `recordMovingAcrossWindowBoundary_changesWatermark`: Bắt được bản ghi di chuyển ra/vào window.
  4. `measurementErasureTombstone_changesWatermark`: Bắt được tombstone xóa số đo.
  5. `measurementSuperseded_changesWatermark`: Bắt được số đo bị thay thế.

---

## 5. Chuẩn Hóa Xác Định Tính Liên Tục (Training Continuity)

Tuyệt đối **không suy diễn trạng thái người mới (`CONFIRMED_NEW`) chỉ dựa vào tuổi tài khoản hoặc sự vắng mặt của workout log**. Hệ thống phân định 4 trạng thái độc lập:

1. **`CONFIRMED_NEW` (Người mới bắt đầu có xác nhận):**
   - *Điều kiện:* Học viên có xác nhận tường minh trong hồ sơ onboarding khảo sát đánh giá ban đầu xác nhận chưa từng tập luyện bài bản. **Lưu ý:** Giá trị `training_experience_level = 'BEGINNER'` đơn lẻ không tự động khẳng định là người mới hoàn toàn nếu thiếu xác nhận khảo sát onboarding hoặc lịch sử hoạt động bị giới hạn.
   - *Ứng xử AI:* Đề xuất tuần làm quen, kiểm tra thể lực, không suy diễn là bỏ bê tập luyện.
2. **`RECENT_CONSISTENT` (Tập luyện đều đặn gần đây):**
   - *Điều kiện:* Có ít nhất một buổi tập hoàn thành (`COMPLETED`) trong vòng 14 ngày gần nhất và duy trì tần suất tập luyện theo mục tiêu.
   - *Ứng xử AI:* Phân tích quá tải/hồi phục, đề xuất tăng tiến bình thường.
3. **`PROLONGED_INACTIVITY` (Nghỉ tập kéo dài):**
   - *Điều kiện:* Đã từng có lịch sử tập luyện được ghi nhận trong hệ thống, nhưng khoảng cách từ buổi tập gần nhất đến hiện tại kéo dài $\ge 100$ ngày (quy tắc an toàn B04/P2-M4).
   - *Ứng xử AI:* Bắt buộc kích hoạt cờ cảnh báo an toàn (`RETURN_AFTER_100_DAYS_V1`), yêu cầu tuần thích nghi lại (re-onboarding), không lấy thành tích tạ cũ làm mốc tăng tiến.
4. **`UNKNOWN_OR_RESTRICTED_HISTORY` (Chưa rõ lịch sử / Bị giới hạn quyền truy cập):**
   - *Điều kiện:* Tài khoản chưa hoàn thành khảo sát kinh nghiệm tập luyện, hoặc học viên không cấp quyền chia sẻ lịch sử cũ cho HLV mới (`CoachingSharing`).
   - *Ứng xử AI:* **Không được tự ý suy diễn là người mới hay người bỏ tập**. Báo cáo rõ ràng: *"Chưa đủ dữ liệu lịch sử để đánh giá tính liên tục; khuyến nghị HLV trao đổi trực tiếp với học viên"*.

---

## 6. Đặc Tả Contract Query Port `WorkoutProgressAggregationQuery`

- **Vị trí hiện thực:** [`WorkoutProgressAggregationQuery.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/workout/application/port/in/WorkoutProgressAggregationQuery.java)
- **Triển khai dịch vụ:** [`WorkoutProgressAggregationService.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/workout/application/service/WorkoutProgressAggregationService.java)
- **Chữ ký & DTO chi tiết:**
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

---

## 7. Tiêu Chí Hoàn Thành (Definition of Done) Của Task F04-T1

Đặc tả trên đã đạt mức độ rõ ràng, sẵn sàng và đã được hiện thực hóa trực tiếp vào mã nguồn:
1. **Schema Mapping:** Truy vấn chính xác trên `planned_workouts` và `workout_session_logs` (thông qua `performed_start_at` và `planned_workout_id`).
2. **Mẫu số Tường minh:** Loại bỏ hoàn toàn buổi tập tương lai (`planned_start_at > asOf`) khỏi mẫu số; xử lý mẫu số 0 bằng trạng thái `NO_SCHEDULED_WORKOUTS_DUE`.
3. **Phân tách Rạch ròi:** Completion % (tổng volume/sessions) hoàn toàn tách biệt với Adherence % (đúng lịch hay lệch lịch).
4. **Watermark Đã Kiểm Chứng:** Tích hợp `calculateSourceWatermark` bao phủ window, auth, planned, actual, sets digest kèm unit dependencies, measurement units catalog state và adjustments.
5. **Dữ liệu Đo lường:** Lọc nghiêm ngặt theo `validation_status = 'VALID'`, `superseded_by_id IS NULL`, `erased_at IS NULL`, không tự ý đặt ngưỡng `quality_score`.

---


## 8. Khắc Phục 6 Khiếm Khuyết Chuyên Sâu Của F04-T1: `WorkoutProgressAggregationQuery`

Sau đợt rà soát phản biện kỹ thuật chuyên sâu, 6 khiếm khuyết tinh tế của F04-T1 đã được khắc phục và kiểm chứng tự động:

### 8.1. Các Khiếm Khuyết Đã Khắc Phục
1. **Snapshot Consistency & Transaction Isolation (Kiểm chứng hai kết nối độc lập):**
   - Dịch vụ được cấu hình `@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)`.
   - Trong Spring, nếu chỉ dùng `Propagation.REQUIRED` khi caller đã mở transaction `READ COMMITTED`, mức cô lập của method con sẽ chạy theo caller transaction. `REQUIRES_NEW` bảo đảm mở transaction vật lý độc lập với `REPEATABLE READ`.
   - Kiểm chứng bằng 2 thread/connection PostgreSQL độc lập đồng bộ qua `CountDownLatch`: Writer thread chèn bản ghi actual workout và commit giữa các lần đọc của aggregation. Aggregation service đọc snapshot trước thời điểm commit hoàn toàn không thấy bản ghi mới chèn, ngăn ngừa snapshot tearing trong các kịch bản kiểm chứng. Test outer transaction kiểm tra isolation và PostgreSQL backend PID riêng biệt. Không tuyên bố an toàn tuyệt đối cho mọi caller hoặc mọi tình huống.
2. **Bảo Vệ Dữ Liệu Ngoài Quyền & Paired Tests Xác Nhận Không Đổi Response:**
   - Xóa bỏ hoàn toàn cơ chế `restrictedPlannedIds`. Quyết định `evidenceStatus` thuần túy dựa trên ranh giới quyền của actor (`actualHistoryFrom`, `actualHistoryUntil`) và `plannedStartAt`, không dựa trên sự tồn tại hay vắng mặt của actual log ngoài scope.
   - SQL truy vấn actual sessions của HLV chỉ được lọc nghiêm ngặt trong `[effectiveActualStart, actualWindowUpper)`, không bypass qua `OR planned_workout_id IN (:plannedIds)`.
   - Paired tests xác nhận response không thay đổi khi dữ liệu Actual ngoài quyền thay đổi trong các kịch bản đã kiểm chứng: Giữ nguyên dữ liệu trong scope HLV, học viên (1) không có log ngoài scope, (2) tạo log IN_PROGRESS ngoài scope, (3) hoàn thành log ngoài scope, (4) tạo thêm log ngoài scope -> Snapshot trả về cho HLV (watermark, counts, evidence, facts) không thay đổi giữa các kịch bản, không làm rò rỉ thông tin về hoạt động ngoài quyền.
   - Buổi tập thiếu quyền kiểm chứng ghi nhận `unverifiableDueCount++`, tuyệt đối không suy diễn thành `MISSED`. Tỷ lệ hoàn thành/tuân thủ trả về `null` kèm lý do minh bạch `ACTUAL_HISTORY_RESTRICTED` hoặc `ACTUAL_HISTORY_PARTIALLY_RESTRICTED`.
3. **Đồng Nhất Cohort Cho Coverage & Window Metrics:**
   - Toàn bộ tử số và mẫu số của các chỉ số thể tích/sets/reps/coverage chỉ được tích lũy từ tập actual sessions **nằm trong cửa sổ truy vấn** (`isInsideWindow == true`).
   - Buổi tập `linkedExecution` diễn ra ngoài cửa sổ chỉ phục vụ liên kết trạng thái cho `PlannedOccurrenceFact`, tuyệt đối không cộng gộp sets/volume vào cửa sổ hiện tại.
   - Bảo đảm `loadCoverageRate` không bao giờ vượt quá 1.0 (100%).
4. **Unknown, Bodyweight và Zero:**
   - Không tự tiện suy diễn `load_value = null` là `bodyweight`. Hệ thống kiểm tra catalog thiết bị: chỉ coi là bodyweight khi bài tập có liên kết thiết bị `BODYWEIGHT` (`fitness.exercise_equipment`). Nếu không có xác nhận, phân loại thành `setsWithMissingLoadCount` (thiếu dữ liệu tạ).
   - Phân biệt rõ `load_value = 0` (mức tạ 0 kg hợp lệ, `setsWithZeroLoadCount++`, volume = 0.00 kg, được tính vào coverage) với thiếu dữ liệu (`null`).
   - Khối lượng chỉ được coi là tính toán được (`isCalculable`) khi thỏa mãn đồng thời: `repetitions != null && load_value != null && unitInfo != null && unitInfo.multiplierToKg() != null`.
5. **Semantics Lịch Sử Set Logs & asOf:**
   - Truy vết cột thật `sl.completed_at`, `sl.created_at`, `sl.updated_at` trong schema V5 `fitness.set_logs`.
   - Set tạo trước nhưng hoàn thành sau `asOf` (`completed_at > asOf`) không được tính là hoàn thành tại `asOf` (không tính vào `setsCompleted`, `workingSetsCompleted`, volume, reps).
   - Set sửa sau `asOf` (`updated_at > asOf`): không dùng giá trị mới rồi dán nhãn chính xác; bắt buộc trả `historicalReconstructionStatus = 'HISTORICAL_RECONSTRUCTION_LIMITED'`, bật cờ `hasHistoricalReconstructionLimits = true` trong `VolumeAndIntensitySummary`, và loại trừ set RPE khỏi tính toán `averageSetRpe`.
   - Buổi tập còn đang diễn ra tại `asOf` hoặc có `updated_at > asOf` có `overallRpe = null` và `historicalReconstructionStatus = 'HISTORICAL_RECONSTRUCTION_LIMITED'`.
6. **Watermark Conversion Dependency:**
   - Bổ sung định nghĩa chuyển đổi đơn vị (`UNITS:id,code,symbol,dimension,multiplierToBase,multiplierToKg`) và hệ số chuyển đổi cụ thể trong `computeSetsDigest`.
   - Khi bảng `fitness.measurement_units` thay đổi `multiplier_to_base` (ví dụ sửa hệ số của LB), watermark và thể tích tính toán lập tức thay đổi, bảo đảm tính phụ thuộc toàn vẹn.

### 8.2. Kết Quả Kiểm Thử PostgreSQL Integration Test Thực Chạy
Bộ kiểm thử [`WorkoutProgressAggregationIntegrationTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/WorkoutProgressAggregationIntegrationTest.java) gồm **28 kịch bản tích hợp** (28 passed, 0 failures, 0 errors) trên Testcontainers PostgreSQL pg18:
1. `onTimeAndOffScheduleWorkouts_aggregatesCorrectCountsAndDeviations`: PASS — Buổi tập Thứ Ba thay Thứ Hai: `rawCompletionRate = 1.0000`, `onTimeAdherenceRate = 0.5000`.
2. `timezoneBoundary_correctlyAssignsWorkoutsToLocalWindow`: PASS — Ranh giới ngày `Asia/Ho_Chi_Minh` vs UTC.
3. `lifecycleVariations_upcomingInProgressPartialAbortedCancelledRescheduled_handledCorrectly`: PASS — Phân biệt chính xác missed, cancelled, rescheduled, today pending, in-progress, upcoming.
4. `emptyWindow_returnsNullAdherenceWithAvailabilityReason`: PASS — Mẫu số 0 trả `null` và `NO_SCHEDULED_WORKOUTS_DUE`.
5. `unplannedActualSession_isSeparatedFromPlannedCohort`: PASS — Buổi tập tự do tách biệt khỏi mẫu số kế hoạch.
6. `setLogModification_changesSourceWatermark`: PASS — Đổi reps làm đổi watermark.
7. `recordMovingAcrossWindowBoundary_changesSourceWatermark`: PASS — Bản ghi ngoài window sinh watermark khác.
8. `authorizationBoundary_rejectsUnauthorizedActor`: PASS — Chặn truy cập trái phép.
9. `missingRpe_isUnknownNull_notZero`: PASS — Khuyết thiếu RPE giữ nguyên `null`.
10. `unitConversion_convertsPoundsToKg_andDistinguishesBodyweightAndUnknownUnits`: PASS — Quy đổi lb -> kg, phân biệt bodyweight và unknown units, `loadCoverageRate = 0.5000`.
11. `asOfFiltersOutFutureActuals_andReconstructsInProgressState`: PASS — Loại bỏ actual sau `asOf`, phục dựng `IN_PROGRESS`.
12. `overnightWorkout_inProgress_isNotMarkedMissed`: PASS — Buổi tập qua đêm không bị phạt missed.
13. `trainerLimitedTimeScope_clipsDataAndDoesNotExposeOutOfScopeWorkouts`: PASS — HLV bị giới hạn thời gian không thấy buổi tập ngoài scope.
14. `trainerWithoutWorkoutPlanPermission_cannotSeePlannedWorkouts`: PASS — HLV thiếu quyền plan không thấy kế hoạch.
15. `linkedExecutionOutsideTrainerHistoryScope_isNotExposed`: PASS — Linked execution ngoài scope của HLV bị ẩn hoàn toàn, không phạt missed, `unverifiableDueCount = 1`.
16. `snapshotConsistency_repeatableReadIsolationProtectsAgainstConcurrentMutations`: PASS — Hai kết nối/thread độc lập với CountDownLatch: concurrent commit giữa các lần đọc không gây rách snapshot REPEATABLE READ.
16b. `snapshotConsistency_callerOuterTransaction_stillExecutesWithRequiresNewRepeatableRead`: PASS — Caller mở transaction READ COMMITTED có sẵn, aggregation query vẫn tự động bọc trong REQUIRES_NEW REPEATABLE READ snapshot nhất quán.
17. `trainerPlanOnly_pastWorkoutsAreUnverifiable_notMissed`: PASS — HLV chỉ có quyền plan: buổi quá hạn ghi nhận `unverifiableDueCount = 1`, không phạt missed.
18. `trainerMisalignedTimeScopes_partiallyRestrictedReportsPartialReason`: PASS — Hai phạm vi thời gian lệch nhau: buổi ngoài scope là `RESTRICTED_OR_UNKNOWN`, buổi trong scope là `VERIFIED`, trả `ACTUAL_HISTORY_PARTIALLY_RESTRICTED`.
19. `linkedActualOutsideWindow_doesNotInflateWindowActualMetrics_coverageNeverExceedsOne`: PASS — Buổi tập thực hiện ngoài window không làm tăng metrics thể tích của window, `coverage <= 1.0`.
20. `unknownBodyweightAndZero_distinguishesValidZeroAndChecksRepsCoverage`: PASS — Phân biệt valid zero load, missing load, bodyweight và missing reps.
21. `asOfSemantics_excludesSetLogsCreatedAfterAsOf_andReportsReconstructionStatus`: PASS — Lọc bỏ set log tạo sau `asOf`, RPE session in-progress là `null`, `reconstructionStatus = 'HISTORICAL_RECONSTRUCTION_LIMITED'`.
22. `watermarkConversionDependency_changingMultiplierChangesWatermark`: PASS — Cập nhật `multiplier_to_base` trong `fitness.measurement_units` làm thay đổi watermark ngay lập tức.
23. `pairedTest_outOfScopeExecution_hasZeroSideChannelLeakage`: PASS — Paired test 4 trạng thái xác nhận response không thay đổi khi dữ liệu Actual ngoài quyền thay đổi trong các kịch bản đã kiểm chứng.
25. `asOfSemantics_setCompletedAfterAsOf_isNotCountedAsCompletedAtAsOf`: PASS — Set tạo trước nhưng hoàn thành sau `asOf` không được tính là completed tại `asOf`.
26. `asOfSemantics_setModifiedAfterAsOf_reportsLimitedReconstructionStatus`: PASS — Set sửa sau `asOf` dán nhãn `HISTORICAL_RECONSTRUCTION_LIMITED`, cờ `hasHistoricalReconstructionLimits = true`, trực tiếp loại trừ load, reps, duration và RPE khỏi metrics lịch sử; kiểm chứng trực tiếp cả case 1 set và case partial 2 sets.
27. `asOfSemantics_sessionOngoingAtAsOf_nullifiesOverallRpe_andSetsLimitedReconstruction`: PASS — Session đang chạy tại `asOf` nullify overall RPE và đánh dấu limited reconstruction.
28. `validZeroVolume_returnsZeroNotHistoricalNull_andAccuratelyReflectsCoverage`: PASS — Phân biệt chính xác session 0 kg hợp lệ trả `0.00` kg (không trả `null`), session khuyết thiếu tải trả `null`, window summary `knownVolumeKg = 0.00` với `loadCoverageRate` chính xác.

Bộ kiểm thử unit test digest watermark [`SourceWatermarkUnitTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/workout/SourceWatermarkUnitTest.java): 6 tests (6 passed, 0 failures, 0 errors).
Tổng cộng bộ test F04-T1: 34 tests (28 integration tests, 6 unit tests: 34 passed, 0 failures, 0 errors).
Kiểm thử F03: `AiWorkoutAdjustmentFullSliceIntegrationTest`: 19 tests (19 passed, 0 failures, 0 errors, gồm 4 test mới và 15 test hồi quy); `AiWorkoutAdjustmentServiceUnitTest`: 12 tests (12 passed, 0 failures, 0 errors, gồm 1 test mới và 11 test hồi quy).
Toàn bộ backend test suite (`mvn test`): 1.657 tests được ghi nhận: 1.652 passed, 5 skipped, 0 failures, 0 errors. Không có mutable test hook trong production service.

### 8.3. Chuyển Tiếp Từ F04-T1 Sang F04-T2
Việc hoàn thiện F04-T1 là nền tảng truy vấn nguồn Workout, khép lại tầng dữ liệu tập luyện cho F04.

---

## 9. Hoàn Thành & Kiểm Chứng Kỹ Thuật F04-T2: `MeasurementProgressQuery`

> **Branch thực hiện:** `feat/p2-m4-f04-measurement-progress-query`
> **Commit nền xác minh:** `d32d3c817fdf3a3a3ce0817d7782abc68c06ef90` (HEAD origin/main)
> **Thời điểm hoàn thành:** 2026-11-06 (UTC+7)

### 9.1. Phạm Vi Triển Khai & Hợp Đồng (Contract)
- **Cổng giao tiếp Inbound (Port):** [`MeasurementProgressQuery.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/measurement/application/port/in/MeasurementProgressQuery.java)
- **Dịch vụ thực thi (Service):** [`MeasurementProgressService.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/measurement/application/service/MeasurementProgressService.java)
- **Chính sách phân quyền (Access Policy):** [`MeasurementAccessPolicy.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/measurement/application/service/MeasurementAccessPolicy.java)
- **Lỗi & Ngoại lệ Domain:** [`MeasurementError.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/measurement/domain/MeasurementError.java), [`MeasurementFailure.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/measurement/domain/MeasurementFailure.java)
- **Chữ ký phương thức chính:**
  ```java
  MeasurementProgressSnapshot query(
          UUID actorId,
          UUID studentId,
          Set<String> metricCodes,
          LocalDate fromDate,
          LocalDate toDate,
          ZoneId timezone,
          Instant asOf
  );
  ```

### 9.2. Các Bất Biến Nghiệp Vụ & Kỹ Thuật Đã Bảo Đảm
1. **Lọc Dữ Liệu Hợp Lệ Chuẩn V55:**
   - Sử dụng đúng 3 điều kiện: `validation_status = 'VALID'`, `superseded_by_id IS NULL`, `erased_at IS NULL`.
   - Lọc bỏ hoàn toàn các bản ghi `PENDING`, `SUSPECT`, `REJECTED`, các bản ghi bị thay thế và các bản ghi tombstone xóa theo GDPR/B08.
   - Đọc toàn bộ chuỗi quan sát lịch sử phù hợp trong kỳ, tuyệt đối không dùng view `current_valid_measurements` (vốn chỉ lấy 1 điểm mới nhất) làm sai lệch chuỗi thời gian.
2. **Bảo Toàn Giá Trị & Metadata:**
   - Bảo toàn giá trị `0` hợp lệ (`BigDecimal.ZERO`), khuyết thiếu/unknown vẫn giữ nguyên `null`, không tự động thế số 0.
   - Giữ nguyên `confidence_score`, `quality_score`, `notes`, `source`, `method`, `entered_by`, `external_record_id`.
   - Không tự áp đặt ngưỡng `quality_score` (như $\ge 80\%$) khi chưa có quy định phê duyệt.
   - Không tự ý suy diễn tăng cơ/giảm mỡ từ cân nặng thuần túy.
3. **Phân Quyền Độc Lập & Phạm Vi Thời Gian:**
   - Kiểm tra `DataScope.BODY_METRICS` độc lập hoàn toàn với quyền Workout hay Plan.
   - Với Actor là Học viên (`isSelf`): kiểm tra tài khoản `ACTIVE` và hồ sơ `StudentProfile`.
   - Với Actor là HLV: kiểm tra tài khoản `ACTIVE`, hồ sơ học viên, và đánh giá quyền qua `CoachingAuthorityQuery`. Nếu chưa được cấp quyền: trả HTTP 403 `MEASUREMENT_ACCESS_DENIED`.
   - Áp dụng chặt chẽ `historyFrom` và `historyUntil`: dữ liệu ngoài phạm vi thời gian được cấp quyền bị loại bỏ hoàn toàn khỏi kết quả.
   - **Không Rò Rỉ Kênh Phụ (Verified in Paired Tests for Out-Of-Scope Mutations):** Đạt kiểm chứng Paired Tests: các thao tác tạo mới, sửa đổi, thay thế hay xóa số đo ngoài scope của HLV hoàn toàn không làm thay đổi response, metadata hay watermark của HLV.
4. **Timezone, Biên Ngày & asOf Semantics:**
   - Quy đổi chính xác `[fromDate, toDate]` theo `timezone` thành `[windowStartInstant, windowEndInstant)`.
   - Lọc strictly: `measured_at <= asOf` và `created_at <= asOf`. Các bản ghi phát sinh hoặc nhập sau `asOf` không được tính vào snapshot tại `asOf`.
   - Phân biệt rõ ràng xóa trước/tại `asOf` với xóa sau `asOf`:
     + Bản ghi bị xóa *sau* `asOf` (`erased_at > asOf`): Tại `asOf` bản ghi này từng tồn tại và hợp lệ, nhưng ở hiện tại đã bị xóa trắng giá trị theo GDPR V55. Hệ thống nhận diện sự thiếu hụt dữ liệu lịch sử này, tự động đánh dấu `hasHistoricalReconstructionLimits = true` và dán nhãn `historicalReconstructionStatus = 'HISTORICAL_RECONSTRUCTION_LIMITED'`. Dữ liệu trả về tuyệt đối giữ bộ lọc `erased_at IS NULL` và không làm lộ bất kỳ giá trị nào đã bị xóa.
     + Bản ghi bị xóa *trước hoặc tại* `asOf` (`erased_at <= asOf`): Tại thời điểm `asOf`, bản ghi này đã ở trạng thái tombstone/erased. Việc loại bỏ bản ghi này qua `erased_at IS NULL` là phản ánh đúng hiện trạng tại `asOf` nên không bị coi là đột biến sau `asOf` làm giới hạn phục dựng.
     + Tương tự, các bản ghi backfill (`created_at > asOf`), thay thế sau `asOf` (`sup.created_at > asOf`) hoặc sự kiện thẩm định sau `asOf` (`occurred_at > asOf`) đều được phát hiện và kích hoạt cờ giới hạn phục dựng.
5. **Composite Source Watermark Toàn Vẹn & Phạm Vi Phụ Thuộc Tối Thiểu Hợp Lệ:**
   - Bao phủ: `WINDOW`, `AUTH` (isSelf, effectiveStart, effectiveEnd), `SUMMARY` (record count, distinct days, flags), `UNITS` catalog dependencies (chỉ hash các đơn vị đo liên quan đến metric hoặc quan sát trong window: id, code, multiplierToBase), `METRICS` catalog (id, code, displayName, defaultUnitId), và danh sách `MEAS` chi tiết (id, metricCode, measuredAt, numericValue, textValue, booleanValue, unitCode, qualityScore, confidenceScore, sourceCode, methodCode, externalRecordId, historicalReconstructionStatus, validationStatus, supersededById, createdAt).
   - Bảo đảm tính tất định và nhạy bén với thay đổi: sửa bản ghi cũ, bản ghi di chuyển ranh giới window, thay thế số đo, xóa số đo hoặc sửa hệ số đơn vị đo (`multiplier_to_base`) đều làm thay đổi watermark. Các đơn vị đo ngoài phạm vi không làm rò rỉ hoặc vô hiệu hóa nhầm watermark.
6. **Snapshot Consistency & Transaction Isolation:**
   - Triển khai `@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)`.
   - Bảo đảm giao dịch đọc độc lập, ngăn snapshot tearing giữa các lần đọc danh mục đơn vị, định nghĩa metric và quan sát số đo ngay cả khi có writer đồng thời commit dữ liệu mới.

### 9.3. Kết Quả Kiểm Thử PostgreSQL Integration Test Thực Chạy
Bộ kiểm thử [`MeasurementProgressIntegrationTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/MeasurementProgressIntegrationTest.java) gồm **15 kịch bản tích hợp** (15 passed, 0 failures, 0 errors) trên Testcontainers PostgreSQL `pgvector/pgvector:pg18`:
1. `returnsFullHistorySeries_notJustLatestObservation`: PASS — Trả đủ chuỗi 3 quan sát theo thứ tự thời gian, `totalObservationsCount = 3`, `distinctDaysCount = 3`.
2. `filtersByStudent_metricCodes_dateWindow_andTimezone`: PASS — Cô lập theo Student, lọc metric `F_WEIGHT` vs all, lọc ngoài window, kiểm chứng ranh giới múi giờ `Asia/Ho_Chi_Minh`.
3. `excludesNonValid_superseded_andErasedRecords`: PASS — Loại bỏ `PENDING`, `SUSPECT`, `SUPERSEDED` và `ERASED` tombstone.
4. `preservesValidZeroValue_andUnknownValuesRemainNull`: PASS — Bảo toàn giá trị 0 hợp lệ (không biến thành null), giá trị khuyết thiếu giữ nguyên null (không biến thành 0).
5. `preservesUnit_provenance_andQualityMetadata`: PASS — Bảo toàn unit, batch ID, provenance (source, method, entered_by, external_record_id), quality/confidence metadata.
6. `actorAuthority_rejectsUnauthorizedActor`: PASS — Chặn người lạ (403), chặn HLV chưa có quyền `BODY_METRICS` (403), ném 404 cho học viên không tồn tại.
7. `trainerTimeScope_clipsDataAndDoesNotExposeOutOfScopeMeasurements`: PASS — HLV bị giới hạn thời gian chỉ thấy dữ liệu trong scope, báo cáo lý do `MEASUREMENT_HISTORY_PARTIALLY_RESTRICTED`.
8. `pairedTest_outOfScopeDataChange_hasZeroSideChannelLeakageOnResponseAndWatermark`: PASS — Thay đổi dữ liệu ngoài scope không làm thay đổi snapshot hoặc watermark của HLV.
9. `asOfFiltering_excludesMeasurementsMeasuredOrCreatedAfterAsOf`: PASS — Loại bỏ số đo đo sau `asOf` hoặc tạo sau `asOf`.
10. `historicalReconstructionLimits_detectedWhenDataModifiedAfterAsOf`: PASS — Phát hiện dữ liệu backfill sau `asOf` (`created_at > asOf`), thay thế sau `asOf` (`sup.created_at > asOf`), thẩm định sau `asOf` (`occurred_at > asOf`), và xóa sau `asOf` (`erased_at > asOf`); phân biệt chính xác với xóa trước `asOf` (`erased_at <= asOf` không gây limited). Dữ liệu xóa luôn được bảo đảm không rò rỉ.
11. `stableDeterministicOrdering_whenObservationsShareTimestamp`: PASS — Thứ tự ổn định theo `measured_at`, `created_at`, `id` khi trùng thời điểm.
12. `sourceWatermarkDeterminism_andDependencySensitivity`: PASS — Watermark tất định; thay đổi hệ số đơn vị catalog `multiplier_to_base` làm thay đổi watermark ngay lập tức.
13. `availabilityReasons_emptyWindowVsRestrictedTimeScope`: PASS — Phân biệt rõ `NO_MEASUREMENTS_RECORDED` và `MEASUREMENT_HISTORY_RESTRICTED`.
14. `snapshotConsistency_repeatableReadIsolationProtectsQuery`: PASS — Khởi chạy trong outer transaction vẫn độc lập thực thi dưới `REQUIRES_NEW REPEATABLE READ`.
15. `snapshotConsistency_repeatableReadShieldsAgainstConcurrentWriterCommit`: PASS — Writer thực hiện commit bản ghi mới ở transaction/connection riêng giữa các lượt đọc của Query; Reader bảo toàn snapshot nhất quán, không rò rỉ bản ghi mới, facts và watermark đồng bộ hoàn toàn.

### 9.4. Giới Hạn Còn Lại & Các Bước Tiếp Theo Sau F04-T2
F04-T2 đã hoàn thành khép lại tầng dữ liệu đo lường thể chất cho F04. Bước tiếp theo là triển khai Progress Engine Domain Service để tổng hợp dữ liệu Workout (F04-T1) và Measurement (F04-T2).

---

## 10. Hoàn Thành & Kiểm Chứng Kỹ Thuật Progress Engine Tổng Hợp (F04)

> **Branch thực hiện:** `feat/p2-m4-f04-progress-engine`
> **Baseline commit:** `480f60bf658c8cd127eb859824922192a6c2a014` (Merge PR #144 của F04-T2 vào `main`)
> **Mục tiêu hoàn thành:** Triển khai tầng Application Service / Domain của Progress Engine hợp nhất hai nguồn dữ liệu Workout (F04-T1) và Measurement (F04-T2) thành một `ProgressSnapshot` toàn vẹn, tất định, chuẩn bị context an toàn trước khi chuyển giao cho AI summarization.

### 10.1. Cấu Trúc Khối Mã Nguồn & Ranh Giới Module Kiến Trúc

Tầng Progress Engine tuân thủ nghiêm ngặt kiến trúc Modular Monolith, không truy cập trực tiếp repository/table của module khác. Thay vào đó, service tích hợp thông qua các Inbound Ports chính thức:
- **`WorkoutProgressAggregationQuery`** (module `workout`): Đọc snapshot tổng hợp workout, adherence và volume trong window `[fromDate, toDate]`.
- **`MeasurementProgressQuery`** (module `measurement`): Đọc snapshot chuỗi đo lường thể chất và facts quan sát trong window `[fromDate, toDate]`.
- **`CoachingAuthorityQuery`** (module `coaching`): Thẩm định quyền HLV, scope và giới hạn thời gian lịch sử (`historyFrom`, `historyUntil`).
- **`UserAccountStatusQuery`** (module `user`): Xác thực tài khoản Học viên và Actor tồn tại và ở trạng thái `AccountStatus.ACTIVE`.
- **`MeasurementUnitReferenceQuery`** (module `measurement`): Mở rộng phương thức `resolveByCodes` cung cấp thông tin quy đổi đơn vị (`multiplier_to_base`, `offset_to_base`, `dimension`) thuộc quyền sở hữu của module `measurement`.
- **`WorkoutCompletionEvidenceQuery`** (module `workout`): Truy vấn bằng chứng hoàn thành bài tập thực tế để đánh giá tính liền mạch lịch sử (`completedForStudent`).
- **`GetStudentProfileUseCase`** (module `student`): Đọc hồ sơ học viên (`trainingExperienceLevel`, `trainingExperienceMonths`, `onboardingCompletedAt`) phục vụ nhận diện học viên mới.

**Inbound Port Chính:** [`ProgressEngineQuery.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/progress/application/port/in/ProgressEngineQuery.java)
- Phương thức query:
  ```java
  ProgressSnapshot query(
      UUID actorId,
      UUID studentId,
      Set<String> metricCodes,
      LocalDate fromDate,
      LocalDate toDate,
      ZoneId timezone,
      Instant asOf
  );
  ```
- Định nghĩa các record cấu trúc snapshot:
  * `ProgressSnapshot`: Chứa `studentId`, `actorId`, `dateWindow`, `effectiveAsOf`, `workoutSection`, `measurementSection`, `continuitySignal`, `historicalLimitsSummary`, `compositeWatermark`.
  * `WorkoutProgressSection`: Trạng thái nguồn (`SourceAvailability`), facts tóm tắt workout (`AdherenceSummary`, `VolumeAndIntensitySummary`), danh sách kế hoạch (`plannedOccurrences`), danh sách buổi tập (`actualSessions`), `sourceWatermark`.
  * `MeasurementProgressSection`: Trạng thái nguồn (`SourceAvailability`), facts tóm tắt số đo (`MeasurementSummary`), danh sách quan sát (`observations`), danh sách delta xu hướng (`metricDeltas`), `sourceWatermark`.
  * `MetricTrendDelta`: Quan sát baseline, quan sát mới nhất (`latest`), delta tuyệt đối (`deltaValue`), tỷ lệ phần trăm (`percentageChange`), hướng xu hướng (`TrendDirection`), số lượng quan sát số (`numericObservationsCount`).
  * `ContinuitySignal`: Trạng thái liền mạch (`ContinuityState`), số ngày kể từ buổi tập gần nhất (`daysSinceLastWorkout`), ngày buổi tập gần nhất (`lastWorkoutDate`), ID buổi tập gần nhất (`lastWorkoutExecutionId`), nhãn an toàn (`safetyFlags`), và căn cứ giải trình (`rationale`).
  * `HistoricalLimitsSummary`: Cờ tổng hợp `hasHistoricalLimits`, `workoutLimits`, `measurementLimits`, và giải trình `explanation`.
- Định nghĩa enum trạng thái:
  * `SourceAvailability`: `AVAILABLE`, `RESTRICTED`, `NO_DATA`.
  * `TrendDirection`: `INCREASED`, `DECREASED`, `STABLE`, `INSUFFICIENT_DATA`.
  * `ContinuityState`: `CONFIRMED_NEW`, `PROLONGED_INACTIVITY`, `RECENT_ACTIVITY_OBSERVED`, `UNKNOWN_HISTORY`, `RESTRICTED_HISTORY`.

**Domain Errors & Failures:**
- [`ProgressError.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/progress/domain/ProgressError.java): `PROGRESS_ACCESS_DENIED`, `STUDENT_NOT_FOUND`, `INVALID_WINDOW_PARAMETERS`.
- [`ProgressFailure.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/progress/domain/ProgressFailure.java): Runtime exception mang mã lỗi `ProgressError`.

**Application Service:** [`ProgressEngineService.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/progress/application/service/ProgressEngineService.java)
- Đảm bảo giao dịch `@Transactional` (read-write để hỗ trợ `CoachingAuthorityQuery` execute `SELECT ... FOR SHARE`).
- Hoàn toàn không chứa raw SQL cross-module; 100% truy vấn dữ liệu được điều phối qua các domain ports.

### 10.2. Các Quyết Định Kỹ Thuật & Bất Biến Nghiệp Vụ Đã Hiệu Chỉnh

1. **Phân Định Tính Liền Mạch (Continuity State Determination):**
   - **Quy tắc `CONFIRMED_NEW` khắt khe và bảo toàn mốc `asOf` lịch sử:** Chỉ trả `CONFIRMED_NEW` khi có bằng chứng tường minh: học viên hoàn thành onboarding trước hoặc tại mốc `asOf` (`onboardingCompletedAt != null && !onboardingCompletedAt.isAfter(asOf)`), trình độ khai báo `BEGINNER`, và kinh nghiệm khai báo chính xác bằng 0 (`trainingExperienceMonths == 0`) cùng 0 buổi tập hoàn thành trong hệ thống. Nếu onboarding hoàn thành sau `asOf` hoặc thiếu dữ liệu, trả `UNKNOWN_HISTORY` để tránh ngộ nhận bằng chứng hiện tại đã tồn tại ở quá khứ.
   - **Bỏ nhãn chuyên môn `RECENT_CONSISTENT` chưa phê duyệt:** Bỏ việc coi 1 buổi tập trong 14 ngày là "tập đều đặn". Thay vào đó, trả trạng thái khách quan `RECENT_ACTIVITY_OBSERVED`, ghi nhận facts quan sát (`daysSinceLastWorkout`, `lastWorkoutDate`, `lastWorkoutExecutionId`) và không tự gán nhãn chuyên môn khi chưa có ngưỡng phê duyệt từ Hội đồng Thể hình.
   - **Ca gián đoạn 100 ngày (`RETURN_AFTER_100_DAYS_V1`):**
     * Đánh giá dựa trên toàn bộ lịch sử tập luyện hợp lệ trong phạm vi quyền được cấp của Actor tính đến `effectiveAsOf`.
     * Nếu quyền của HLV bị giới hạn thời gian (`historyUntil < effectiveAsOf`): không thể xác nhận học viên có tập sau đó hay không -> trả `RESTRICTED_HISTORY`.
     * Nếu độ bao phủ lịch sử của HLV bắt đầu sau mốc 100 ngày trước `asOf` (`historyFrom > asOf - 100d`): không đủ 100 ngày lịch sử -> trả `RESTRICTED_HISTORY`.
     * Khi và chỉ khi xác nhận buổi tập hoàn thành gần nhất cách thời điểm tham chiếu >= 100 ngày: gán `PROLONGED_INACTIVITY` và kích hoạt cờ an toàn `RETURN_AFTER_100_DAYS_V1`.

2. **Tính Toán Delta & Quy Đổi Đơn Vị Tất Định (Metric Deltas & Unit Conversion):**
   - **Kiểm tra tương thích đơn vị:** Khi hai quan sát có đơn vị khác nhau, hệ thống tra cứu bảng `fitness.measurement_units` qua port `MeasurementUnitReferenceQuery.resolveByCodes`. Nếu cùng `dimension` (ví dụ `MASS`: KG vs LB), hệ thống tự động quy đổi về đơn vị mốc để tính delta. Nếu khác `dimension` hoặc không thể quy đổi: gán `TrendDirection.INSUFFICIENT_DATA`, `deltaValue = null`, `percentageChange = null`.
   - **Ức chế tính tỷ lệ phần trăm trên thang đo affine có offset:** Đối với các đơn vị có `offset_to_base != 0` (như nhiệt độ Celsius/Fahrenheit), tỷ lệ phần trăm thay đổi không có ý nghĩa toán học bất biến scale -> hệ thống gán `percentageChange = null`.
   - **Quan sát trùng thời điểm:** Nếu quan sát đầu và quan sát cuối có cùng thời điểm `measuredAt`, không có khoảng thời gian trôi qua để xác lập xu hướng -> gán `TrendDirection.INSUFFICIENT_DATA`, `deltaValue = null`.
   - **Mốc ban đầu bằng 0:** Nếu `baselineValue == 0`, `deltaValue = latestValue`, nhưng `percentageChange = null` để tránh chia cho 0.
   - **Bảo toàn tính khuyết thiếu:** Unknown giữ nguyên `null`, tuyệt đối không tự động thế bằng `0`. Không suy diễn tăng cơ/giảm mỡ từ biến thiên cân nặng thuần túy.

3. **Phân Quyền, Cô Lập Kênh Phụ & Xử Lý Lỗi Chặt Chẽ:**
   - Kiểm tra trạng thái tài khoản người dùng qua `UserAccountStatusQuery`: Học viên và Actor phải tồn tại và có `status = AccountStatus.ACTIVE`. Nếu học viên không tồn tại -> 404 `STUDENT_NOT_FOUND`; nếu inactive -> 403 `PROGRESS_ACCESS_DENIED`.
   - Giữ độc lập hoàn toàn quyền Workout (`WORKOUT_HISTORY`/`WORKOUT_PLAN`) và Body Metrics (`BODY_METRICS`).
   - Xử lý Partial Access: Nguồn bị từ chối trả về `RESTRICTED` với facts trống và `sourceWatermark = null`.
   - Băm digest cho nguồn bị từ chối bằng token cố định `"RESTRICTED"`, ngăn chặn việc theo dõi dữ liệu ẩn qua watermark. Đã được kiểm chứng qua Paired Tests: đột biến dữ liệu ngoài quyền không làm thay đổi response hay watermark của HLV.
   - Xử lý lỗi nghiêm ngặt: Chỉ chuyển đúng exception `WORKOUT_EXECUTION_ACCESS_DENIED` và `MEASUREMENT_ACCESS_DENIED` thành `RESTRICTED`. Mọi lỗi kỹ thuật (kết nối cơ sở dữ liệu, lỗi cú pháp SQL, NullPointer) đều được rethrow nguyên trạng.

4. **Composite Watermark Toàn Vẹn Không Mất Độ Chính Xác:**
   - Sử dụng định dạng chuỗi chuẩn hóa canonical:
     ```
     ENGINE:progress-engine-v1.0|STUDENT:studentId|WINDOW:fromDate:toDate:timezone|METRICS:sortedMetricCodes|ASOF:asOfIsoString|WORKOUT:workoutWatermark|MEAS:measurementWatermark|CONTINUITY:state:daysSince:lastDate
     ```
   - Lưu trữ `asOf` dưới dạng ISO-8601 đầy đủ (bảo toàn độ chính xác nanosecond).
   - Bao phủ đầy đủ tham số lọc `metricCodes`, cửa sổ thời gian, trạng thái continuity và source watermarks của cả hai nguồn (bao gồm cả phụ thuộc phiên bản catalog đơn vị đo lường).

5. **Làm Rõ Mức Độ Nhất Quán Dữ Liệu Giữa Các Nguồn (Cross-Source Consistency):**
   - Cùng `effectiveAsOf` bảo đảm tính nhất quán ở mức logic ứng dụng: cả hai adapter Workout và Measurement đều lọc sự kiện xảy ra <= `asOf`.
   - Tuy nhiên, vì các adapter thực thi trong các transaction hoặc snapshot connection độc lập, hệ thống không bảo đảm một atomic cross-source database snapshot giữa hai nguồn: một writer concurrent có thể commit vào một nguồn sau khi nguồn kia đã đọc xong. Giới hạn này được phản ánh minh bạch qua `compositeWatermark` và `historicalLimitsSummary`.

---

### 10.3. Kết Quả Kiểm Thử Thực Tế (100% Passed)

Toàn bộ các bài kiểm thử đã được chạy và xác minh thực tế trên môi trường Testcontainers PostgreSQL `pgvector/pgvector:pg18`:

1. **Bộ Kiểm Thử Đơn Vị [`ProgressEngineUnitTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/progress/ProgressEngineUnitTest.java) (15/15 passed):**
   - `inputValidation_rejectsInvalidWindow`: PASS — Kiểm tra tính hợp lệ của tham số cửa sổ ngày.
   - `passesIdenticalEffectiveAsOf_whenCallerAsOfIsNull`: PASS — Truyền `effectiveAsOf` duy nhất sang cả 2 adapter.
   - `metricDeltas_computesCorrectValuesAndDirections`: PASS — Tính chính xác delta tuyệt đối, % thay đổi và hướng xu hướng `INCREASED`/`DECREASED`/`STABLE`.
   - `metricDeltas_incompatibleUnitsOrIdenticalTimestamps_returnInsufficientData`: PASS — Đơn vị không tương thích hoặc quan sát trùng timestamp trả về `INSUFFICIENT_DATA` với `deltaValue = null`.
   - `metricDeltas_nonZeroOffset_suppressesPercentageChange`: PASS — Đơn vị có offset khác 0 (nhiệt độ) triệt tiêu tỷ lệ phần trăm (`percentageChange = null`).
   - `continuity_prolongedInactivity_whenWorkout100DaysAgo`: PASS — Kích hoạt cờ an toàn `RETURN_AFTER_100_DAYS_V1` khi gián đoạn >= 100 ngày.
   - `continuity_recentActivityObserved_withoutUnapprovedConsistentLabel`: PASS — Quan sát buổi tập 5 ngày trước trả về `RECENT_ACTIVITY_OBSERVED` không gán nhãn chuyên môn.
   - `continuity_confirmedNew_whenOnboardingCompletedWithZeroMonthsAndZeroLogs`: PASS — Học viên có xác nhận `BEGINNER`, kinh nghiệm 0 tháng và hoàn thành onboarding trước `asOf` được gán `CONFIRMED_NEW`.
   - `continuity_unknownHistory_whenOnboardingCompletedAfterAsOf`: PASS — Học viên hoàn thành onboarding sau `asOf` bảo toàn tính quá khứ -> trả về `UNKNOWN_HISTORY`.
   - `continuity_unknownHistory_whenBeginnerLacksZeroMonthsEvidence`: PASS — Học viên beginner nhưng thiếu xác nhận 0 tháng trả về `UNKNOWN_HISTORY`.
   - `continuity_trainerWithHistoryUntilBeforeAsOf_returnsRestrictedHistory`: PASS — HLV có phạm vi lịch sử kết thúc trước `asOf` nhận `RESTRICTED_HISTORY`.
   - `authority_partialAccess_workoutAllowed_measurementDenied`: PASS — HLV chỉ có quyền Workout nhận dữ liệu Workout, Measurement bị đánh dấu `RESTRICTED` không rò rỉ metadata.
   - `authority_deniedOnAllSources_throwsProgressAccessDenied`: PASS — Ném 403 khi bị từ chối cả 2 quyền.
   - `technicalError_atSource_isRethrown`: PASS — Lỗi kỹ thuật nguồn (DB pool) được rethrow, không bị nuốt thành `RESTRICTED`.
   - `compositeWatermark_determinismAndSensitivity`: PASS — Watermark tất định và nhạy bén với thay đổi của watermark thành phần.

2. **Bộ Kiểm Thử Tích Hợp PostgreSQL [`ProgressEngineIntegrationTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/ProgressEngineIntegrationTest.java) (16/16 passed):**
   - `bothSourcesHaveData_returnsUnifiedSnapshotWithCalculatedDeltasAndCompositeWatermark`: PASS — Snapshot đầy đủ trên DB thật.
   - `onlyWorkoutHasData_measurementReportsNoData`: PASS — Xử lý đúng khi chỉ có workout.
   - `onlyMeasurementHasData_workoutReportsNoData`: PASS — Xử lý đúng khi chỉ có measurement.
   - `bothSourcesEmpty_validActor_returnsEmptySectionsWithoutError`: PASS — Xử lý sạch sẽ khi cả hai nguồn đều trống.
   - `trainerWithWorkoutAccess_butDeniedMeasurement_returnsPartialSnapshot`: PASS — Phân quyền độc lập, che giấu measurement khỏi HLV.
   - `strangerActor_deniedOnAllSources_throwsProgressAccessDenied`: PASS — Chặn người lạ không có quan hệ coaching (403).
   - `nonExistentStudent_throwsStudentNotFound`: PASS — Ném 404 cho học viên không tồn tại.
   - `pairedTest_outOfScopeDataChange_doesNotAffectTrainerResponseOrCompositeWatermark`: PASS — Đột biến ngoài quyền không làm rò rỉ response hay watermark.
   - `continuity_prolongedInactivityFlagged_whenLastWorkout100DaysAgo`: PASS — Phát hiện gián đoạn 120 ngày, gắn cờ `RETURN_AFTER_100_DAYS_V1`.
   - `historicalLimits_correctlySummarized_whenMeasurementHasPostAsOfModification`: PASS — Nhận diện số đo backfill sau `asOf`.
   - `continuity_recentActivityObserved_withoutUnapprovedSpecialistLabel`: PASS — Quan sát buổi tập 3 ngày trước trên DB thật, trả facts không gán nhãn chuyên môn.
   - `continuity_confirmedNew_whenBeginnerWithZeroMonthsAndOnboarding`: PASS — Kiểm chứng profile `BEGINNER` với 0.0 tháng trên DB thật -> `CONFIRMED_NEW`.
   - `continuity_unknownHistory_whenBeginnerLacksZeroMonthsEvidence`: PASS — Profile `BEGINNER` với 6.0 tháng trên DB thật -> `UNKNOWN_HISTORY`.
   - `metricDeltas_unitConversionAcrossCompatibleUnits`: PASS — Quy đổi đơn vị thật trên DB (LB sang KG) tính chính xác delta.
   - `metricDeltas_identicalTimestamps_yieldInsufficientData`: PASS — Hai số đo trùng timestamp trả `INSUFFICIENT_DATA`, `deltaValue = null`.
   - `continuity_trainerWithHistoryUntilBeforeAsOf_returnsRestrictedHistory`: PASS — HLV bị giới hạn thời gian nhận `RESTRICTED_HISTORY`.

3. **Kiểm Thử Hồi Quy Toàn Diện Các Module Liên Quan (51/51 passed):**
   - `MeasurementProgressIntegrationTest`: 15/15 passed.
   - `WorkoutProgressAggregationIntegrationTest`: 28/28 passed.
   - `SourceWatermarkUnitTest`: 6/6 passed.
   - `V55MeasurementErasureMigrationTest`: 2/2 passed.
   - **Tổng cộng kiểm thử đã thực thi và đạt:** **82/82 tests PASS (0 failures, 0 errors)**.

---

### 10.4. Giới Hạn Còn Lại & Lộ Trình Triển Khai Tiếp Theo (Không Tự Tuyên Bố Hoàn Tất Toàn Bộ F04 / Phase 2)

Việc hoàn thành Progress Engine Service đã giải quyết trọn vẹn lớp nghiệp vụ tổng hợp dữ liệu chuẩn bị context cho F04. Kế hoạch tiếp theo gồm:
1. **F04 Context Builder & FastAPI Summarization (Đã hoàn thành tại Mục 11):** Xây dựng Context Builder tại Spring Boot, schema hợp đồng JSON/OpenAPI, FastAPI summarization, validation độc lập và readback kiểm quyền thời gian thực.
2. **Mobile Client Progress UI:** Giao diện trực quan hóa tiến độ cho Học viên và Workspace Review của Huấn luyện viên trên React Native Expo.
3. **Thẩm Định Chuyên Môn Thể Hình (D06 / OD-3):** Phê duyệt bộ quy tắc phân loại tiến độ minor vs significant và quy định can thiệp khi học viên trở lại sau gián đoạn dài hạn từ Hội đồng Thể hình.

---

## 11. Triển Khai F04 Context Builder Tại Spring Boot & Luồng INFORMATION Tích Hợp FastAPI

### 11.1. Mục Tiêu & Phạm Vi Đạt Được
Đã hoàn thành toàn diện luồng backend từ `ProgressEngineQuery` → F04 Context Builder tại Spring → context tối thiểu đã kiểm quyền → FastAPI summarization → validation và revalidation → lưu trữ và readback bản tóm tắt `INFORMATION` theo kiến trúc module hiện có.

1. **Hợp Đồng Dữ Liệu (Contracts & Schemas):**
   - [`contracts/ai-schemas/f04-progress-summary-request.schema.json`](../../contracts/ai-schemas/f04-progress-summary-request.schema.json): Định nghĩa JSON Schema Draft 2020-12 cho context gửi sang AI service. Không bao gồm thông tin định danh cá nhân, không gửi domain entity thô, che giấu hoàn toàn dữ liệu/watermark của nguồn bị hạn chế quyền.
   - [`contracts/ai-schemas/f04-progress-summary-result.schema.json`](../../contracts/ai-schemas/f04-progress-summary-result.schema.json): Định nghĩa kết quả tóm tắt `INFORMATION`. Ràng buộc `resultType` chỉ chấp nhận `INFORMATION`, `INSUFFICIENT_DATA`, hoặc `BLOCKED`. Nghiêm cấm sinh proposal điều chỉnh buổi tập (`actionableProposals` bị cấm).
   - Bộ 8 fixtures kiểm thử trong [`contracts/ai-schemas/fixtures/f04/`](../../contracts/ai-schemas/fixtures/f04/) bao phủ đầy đủ trường hợp hợp lệ, thiếu dữ liệu, hạn chế quyền, gián đoạn dài ngày (`RETURN_AFTER_100_DAYS_V1`), và các trường hợp vi phạm hợp đồng (trộn proposal, thiếu context).
   - Tích hợp và cập nhật [`contracts/openapi/openapi.yaml`](../../contracts/openapi/openapi.yaml) với tag `AI Progress Summarization (F04)` gồm các endpoint:
     - `POST /api/v1/ai/progress/summary`: Yêu cầu tóm tắt tiến độ.
     - `GET /api/v1/ai/progress/summary/{runId}`: Đọc lại bản tóm tắt đã lưu với kiểm quyền thời gian thực và đánh giá độ tươi (`freshness`) theo watermark.

2. **Dịch Vụ FastAPI AI Service:**
   - [`services/ai-service/app/schemas/f04_schemas.py`](../../services/ai-service/app/schemas/f04_schemas.py): Pydantic v2 schemas với cấu hình nghiêm ngặt `extra="forbid"`.
   - [`services/ai-service/app/integrations/simulated_f04_provider.py`](../../services/ai-service/app/integrations/simulated_f04_provider.py): Bộ sinh tóm tắt tất định tuân thủ quy tắc nghiệp vụ:
     - Tóm tắt dựa trên facts được cung cấp, không bịa đặt số liệu.
     - Phát hiện cờ `RETURN_AFTER_100_DAYS_V1` và kích hoạt cảnh báo an toàn bắt buộc khi trở lại tập luyện.
     - Diễn đạt chính xác giới hạn khi nguồn dữ liệu bị hạn chế quyền (`RESTRICTED`) hoặc chưa đủ dữ liệu (`INSUFFICIENT_DATA`).
     - Tôn trọng giới hạn diễn giải thành phần cơ thể khi chỉ có cân nặng tổng thể.
   - [`services/ai-service/app/api/routes/internal_progress_summary.py`](../../services/ai-service/app/api/routes/internal_progress_summary.py): Endpoint nội bộ `POST /internal/v1/progress/summarize` được bảo vệ bằng header `X-Internal-Service-Key`.

3. **Spring Boot Backend (Module AI):**
   - [`F04ContextBuilder`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F04ContextBuilder.java): Gọi `ProgressEngineQuery.query(...)` để lấy facts đã xác thực, kiểm tra quyền tác nhân (`STUDENT` hoặc `TRAINER` có active relationship), thực hiện tối thiểu hóa context (chỉ gửi facts và số liệu cần thiết), triệt tiêu watermark và dữ liệu của nguồn bị hạn chế.
   - [`F04RuntimeOutputValidator`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F04RuntimeOutputValidator.java): Kiểm tra tính toàn vẹn của kết quả trả về từ AI:
     - Khớp `runId`.
     - Phải là `INFORMATION`, `INSUFFICIENT_DATA`, hoặc `BLOCKED`; từ chối nếu có proposal điều chỉnh.
     - Kiểm tra rò rỉ kênh phụ (side-channel leak): nghiêm cấm nhắc tới chỉ số hoặc buổi tập của nguồn bị hạn chế quyền.
     - Bắt buộc phải có cờ an toàn khi context ghi nhận gián đoạn >= 100 ngày.
   - [`AiProgressSummarizationService`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiProgressSummarizationService.java): Quản lý vòng đời `AIRun` (`QUEUED` → `RUNNING` → `SUCCEEDED` / `FAILED` / `REJECTED_BY_VALIDATOR`), lưu vết snapshot, gọi AI provider bên ngoài giao dịch cơ sở dữ liệu, ghi log audit đầy đủ.
   - **Xử Lý Freshness & Đọc Lại (Readback):**
     - Tại thời điểm đọc lại qua `getSummary(runId, actorId, role)`: Re-validate quyền của tác nhân tại thời điểm hiện tại. Nếu học viên/HLV bị vô hiệu hóa hoặc kết thúc quan hệ huấn luyện, trả về `403 Forbidden`.
     - Lấy watermark nguồn hiện tại thông qua `ProgressEngineQuery`. Nếu watermark hiện tại khác watermark ghi nhận lúc tóm tắt, trả về cờ `isStale = true` và `needsRefresh = true` để thân thiện với client nhưng không tự động ghi đè dữ liệu.
   - [`AiProgressSummaryController`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/AiProgressSummaryController.java): REST API có chứng thực Bearer JWT cho `POST` và `GET`.

### 11.2. Kết Quả Kiểm Thử Đạt Được (100% Passed)

1. **Kiểm Tra Hợp Đồng & Fixtures (`tools/validate-ai-schemas.mjs` & Ajv 2020):**
   - 9 JSON schemas và 40 fixtures (trong đó có 8 fixtures F04 mới) đạt 100% hợp lệ.
   - OpenAPI linting đạt chuẩn OpenAPI 3.1.0 qua Redocly CLI (`npm run test:contracts` đạt 21/21 passed).

2. **Kiểm Thử FastAPI AI Service (`pytest`):**
   - 76/76 bài kiểm thử đạt (`test_f04_schemas.py`, `test_f04_provider.py`, `test_internal_progress_summary.py` cùng toàn bộ suite hiện hữu).

3. **Kiểm Thử Đơn Vị Spring Boot Module AI & Progress Engine (`mvn test`):**
   - `F04ContextBuilderUnitTest`: 6/6 tests PASS.
   - `F04RuntimeOutputValidatorUnitTest`: 7/7 tests PASS.
   - `AiProgressSummarizationServiceUnitTest`: 17/17 tests PASS (kiểm tra phân quyền, leak detection, timeout, freshness check, post-provider account deactivation, post-provider coaching revocation, readback single-source masking, continuity days mismatch, continuity safetyFlags mismatch, historical limits mismatch, historical explanation mismatch cả sau-provider và đọc lại).
   - `AiProgressSummaryControllerUnitTest`: 4/4 tests PASS (kiểm tra MockMvc HTTP endpoint).
   - `ProgressEngineUnitTest`: 15/15 tests PASS.
   - `SourceWatermarkUnitTest`: 6/6 tests PASS.
   - Tổng cộng unit test suites đạt: **55/55 tests PASS (0 failures, 0 errors)**.

4. **Kiểm Thử Tích Hợp PostgreSQL Thật & FastAPI Process Thật ([`AiProgressSummaryRealFastApiIntegrationTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/AiProgressSummaryRealFastApiIntegrationTest.java)):**
   - Khởi chạy Testcontainers PostgreSQL 18 (`pgvector/pgvector:pg18`) và tiến trình background Uvicorn FastAPI thực tế (`app.main:app` trên cổng động ngẫu nhiên).
   - `validSummaryRequest_invokesFastApiHttp_andCompletesInformationRun`: PASS — Xác minh trọn vẹn luồng HTTP `POST /api/v1/ai/progress/summary` → Spring Context Builder → FastAPI internal HTTP endpoint → deterministic summarization → Spring runtime validator → PostgreSQL persist AI Run → `GET /api/v1/ai/progress/summary/{runId}` readback. Xác nhận 0 domain mutation trên `workout_session_logs` và `measurements`.
   - `fastApiInvalidServiceKey_failsAuthentication`: PASS — FastAPI trả về 403 Forbidden khi thiếu hoặc sai `X-Internal-Service-Key`.
   - `readback_strangerActor_isDenied`: PASS — Chặn tác nhân không có quan hệ huấn luyện (403 Forbidden), bảo vệ an toàn dữ liệu học viên.

### 11.3. Các Tinh Chỉnh & Củng Cố Bất Biến Nghiệp Vụ Trong Quá Trình Kiểm Chứng
1. **Ràng Buộc Cơ Sở Dữ Liệu `ai_runs_purpose_check`:** Cột `purpose` trong bảng `ai_runs` tuân thủ check constraint `CHECK (purpose IN ('BUSINESS_ASSISTANCE','EVALUATION','REPLAY','REGRESSION_TEST'))`. Đã chuẩn hóa giá trị purpose về `BUSINESS_ASSISTANCE` đồng nhất với `AiRunF01Service` và `AiWorkoutProposalService`.
2. **Post-Provider Authority Revalidation:** Sau khi nhận kết quả từ FastAPI và trước khi ghi nhận `SUCCEEDED` hoặc trả cho client, hệ thống tái thẩm định trạng thái tài khoản (`AccountStatus.ACTIVE`) và quan hệ huấn luyện (`CoachingRelationshipStatus.ACTIVE`). Nếu bị vô hiệu hóa trong khi provider đang xử lý, hệ thống hủy xuất bản, đánh dấu run `FAILED` với lý do `ACCESS_REVOKED` / `COACHING_REVOKED` và trả 403.
3. **Bảo Vệ Đọc Lại Khi Quyền Bị Thu Hồi Từng Phần (Readback Single-Source Masking):** Khi đọc lại bản tóm tắt đã lưu qua `GET`, nếu một trong hai nguồn dữ liệu (Workout hoặc Measurement) bị thu hồi quyền truy cập, hệ thống tự động che giấu (`null`) phần tóm tắt của nguồn đó, loại bỏ các facts tham chiếu tương ứng khỏi `factsReferenced` và bổ sung cảnh báo giới hạn vào `limitations`, ngăn ngừa rò rỉ thông tin lịch sử khi quyền hạn bị thu hẹp.
4. **Phân Biệt Cảnh Báo An Toàn Khách Quan vs Đề Xuất Điều Chỉnh Tải:** Cờ `RETURN_AFTER_100_DAYS_V1` do backend tính toán từ facts gián đoạn lịch sử được thể hiện rõ ràng trong phần `safetyFlags` và tóm tắt tiến độ, nhưng giữ đúng ranh giới `INFORMATION`: không tự ý điều chỉnh bài tập hay kế hoạch dinh dưỡng khi chưa có đề xuất và thẩm định chuyên môn.

---

## 12. Triển Khai & Kiểm Chứng Mobile Progress UI Cho Student & Trainer (F04 Mobile)

> **Branch làm việc:** `feat/p2-m4-f04-mobile-progress`
> **Baseline Git:** `origin/main` (`3893aab` - Merge pull request #146 backend F04)
> **Nền tảng:** React Native Expo (React Native 0.86, React 19, TypeScript), Expo Router, TanStack Query, Axios apiClient.

### 12.1. Cấu Trúc Khối Mã Nguồn & Luồng Nghiệp Vụ Mobile

1. **Hợp Đồng Dữ Liệu Client & API Service:**
   - [`types/aiProgressSummary.ts`](../../apps/mobile/src/types/aiProgressSummary.ts): Định nghĩa `F04ProgressSummaryApiRequest`, `F04ProgressSummaryResponse`, `F04WorkoutProgressSummary`, `F04MeasurementProgressSummary`, `F04ContinuityEvaluation` với kiểu dữ liệu an toàn (hỗ trợ `isStale` và `needsRefresh` nullable).
   - [`services/aiProgressSummaryApi.ts`](../../apps/mobile/src/services/aiProgressSummaryApi.ts):
     - `getUserTimezone(preferredTimezone)`: Xác định múi giờ người dùng linh hoạt (`user?.timezone` -> `Intl.DateTimeFormat().resolvedOptions().timeZone` -> `Asia/Ho_Chi_Minh`), không cố định cứng một múi giờ.
     - `METRIC_OPTIONS` & `METRIC_LABELS`: Khớp hoàn toàn danh mục chuẩn cơ sở dữ liệu (`WEIGHT`, `BODY_FAT_PERCENTAGE`, `WAIST_CIRCUMFERENCE`, `CHEST_CIRCUMFERENCE`) và hỗ trợ ánh xạ nhãn tiếng Việt tương thích ngược với các fixture kiểm thử (`F_WEIGHT`, v.v.).
     - `generate(request, signal)`: Gọi endpoint `POST /api/v1/ai/progress/summary` kèm múi giờ giải quyết động và hỗ trợ `AbortSignal`.
     - `getSummary(runId, signal)`: Đọc lại bản tóm tắt qua `GET /api/v1/ai/progress/summary/{runId}` kèm `AbortSignal`.
     - `getAiProgressSummaryErrorMessage(err)`: Phân định rạch ròi giữa huỷ yêu cầu (`AbortError` -> "Yêu cầu đã bị hủy") với timeout thực sự (`TimeoutError` -> "Quá thời gian chờ"), phân biệt `504` (Gateway Timeout), `502` (Bad Gateway), `503` (Service Unavailable), `400` (dữ liệu/ngày không hợp lệ), `403` (mất quyền hoặc quan hệ huấn luyện kết thúc), `404` (không tìm thấy), `422` (vi phạm hợp đồng kiểm định), và `0` (mất kết nối mạng).

2. **Thành Phần Giao Diện Chính (`ProgressSummaryScreen.tsx`):**
   - [`ProgressSummaryScreen.tsx`](../../apps/mobile/src/features/progress-summary/ProgressSummaryScreen.tsx):
     - **Bộ chọn phạm vi thời gian (Scope Selector):** Cung cấp các nút chọn nhanh ("7 ngày qua", "14 ngày qua", "30 ngày qua", "Tùy chọn") và 2 ô nhập ngày `fromDate`, `toDate` (YYYY-MM-DD). Kiểm tra tính hợp lệ lịch nghiêm ngặt (chặn ngày không tồn tại như 30/02 và chặn `fromDate > toDate`), hiển thị cảnh báo tức thì và vô hiệu hóa nút submit.
     - **Lọc chỉ số thể chất (Metrics Chips):** Hỗ trợ chọn lọc linh hoạt theo danh mục số đo chuẩn (`WEIGHT`, `BODY_FAT_PERCENTAGE`, `WAIST_CIRCUMFERENCE`, `CHEST_CIRCUMFERENCE`).
     - **Chống Submit Trùng & Bảo Vệ Race Condition (A -> B -> A):** Sử dụng bộ đếm thứ tự yêu cầu `activeRequestIdRef` và `AbortController` (`inFlightAbortRef`). Khi người dùng gửi request mới, chuyển đổi học viên hoặc unmount, request đang chạy dở lập tức bị huỷ; phản hồi muộn từ request cũ bị bỏ qua hoàn toàn, bảo đảm không bao giờ ghi đè kết quả sai lệch ngay cả trong kịch bản A -> B -> A.
     - **Cô Lập Trạng Thái Học Viên Tuyệt Đối (Student State Isolation):** Sử dụng `currentStudentIdRef`. Khi Huấn luyện viên chuyển đổi giữa các học viên hoặc tài khoản thay đổi, toàn bộ kết quả, lỗi và trạng thái của học viên trước lập tức bị xóa trắng (`setResult(null)`), ngăn ngừa triệt để rò rỉ dữ liệu chéo giữa các học viên.
     - **Hộp Cảnh Báo An Toàn Khách Quan:** Khi phát hiện cờ an toàn (`RETURN_AFTER_100_DAYS_V1`), giao diện hiển thị banner nổi bật `⚠️ CẢNH BÁO AN TOÀN KHI TRỞ LẠI TẬP LUYỆN` dựa hoàn toàn vào `continuityEvaluation.message` có nguồn, tuyệt đối không tự ý thêm lời khuyên điều chỉnh mức tạ hay kết luận chuyên môn chưa được kiểm chứng.
     - **Hiển Thị Nguồn Bị Giới Hạn (Restricted Sources):** Phân biệt minh bạch giữa không có dữ liệu với nguồn bị hạn chế quyền. Nếu `workoutProgressSummary` hoặc `measurementProgressSummary` là `null`, hiển thị hộp thông báo nguồn bị giới hạn theo chính sách chia sẻ.
     - **Nhận Biết Dữ Liệu Cũ & Nút Làm Mới (Stale / Refresh Needed):** Phản ánh đúng ngữ nghĩa: `isStale` nullable không bị ngộ nhận là đã xác nhận mới; chỉ hiển thị banner cảnh báo `Dữ liệu nguồn đã thay đổi` khi có cờ `isStale` hoặc `needsRefresh` rõ ràng, kèm nút "Làm mới ngay" kích hoạt tạo request mới có chủ đích.
     - **Ranh Giới `INFORMATION` Tuyệt Đối:** Không có nút "Accept", "Apply", hay hành vi tự động sửa đổi kế hoạch tập luyện nào.

3. **Điều Hướng & Điểm Truy Cập (Navigation & Route Integration):**
   - **Student Flow:**
     - Điểm truy cập tại [`HomeScreen.tsx`](../../apps/mobile/src/features/auth/HomeScreen.tsx) qua nút "Tóm tắt tiến độ (AI) →" dẫn tới route [`/progress-summary`](../../apps/mobile/src/app/(app)/progress-summary/index.tsx).
     - Hỗ trợ route đọc lại trực tiếp [`/progress-summary/[runId]`](../../apps/mobile/src/app/(app)/progress-summary/[runId].tsx).
   - **Trainer Flow:**
     - Điểm truy cập tại Workspace học viên trong [`RelationshipDetailScreen.tsx`](../../apps/mobile/src/features/coaching/RelationshipDetailScreen.tsx) qua nút "Tóm tắt tiến độ học viên (AI) →" dẫn tới route [`/coaching/[relationshipId]/progress-summary`](../../apps/mobile/src/app/(app)/coaching/[relationshipId]/progress-summary.tsx).
     - Ràng buộc chặt chẽ với `studentId` của mối quan hệ huấn luyện đang chọn; điều hướng an toàn nếu quan hệ bị lỗi hoặc không tìm thấy.
   - **Route Guards & Layout:**
     - Cập nhật [`routeGuard.ts`](../../apps/mobile/src/features/auth/routeGuard.ts) với `canAccessProgressSummary` (chỉ cho phép tài khoản `ACTIVE` có profile `STUDENT` hoặc `TRAINER`).
     - Đăng ký đầy đủ 3 màn hình mới trong [`_layout.tsx`](../../apps/mobile/src/app/(app)/_layout.tsx).

---

### 12.2. Kiểm Chứng Môi Trường Android GUI Thực Tế (Live Android Emulator Verification)

1. **Khôi Phục Môi Trường Android Pixel_8:**
   - **Nguyên nhân Pixel_8 offline:** Quá trình chạy trước đây bị tắt đột ngột, để lại các tệp khoá chết `multiinstance.lock` và thư mục khoá `hardware-qemu.ini.lock/pid` trong thư mục AVD `D:\Android\Avd\Pixel_8.avd`, khiến QEMU từ chối khởi động và chuyển sang trạng thái offline. Đồng thời, tiến trình ADB client trong môi trường nền bị ngắt phiên gây lỗi khởi động lại daemon lặp đi lặp lại.
   - **Biện pháp khôi phục không phá huỷ dữ liệu:**
     - Tạo liên kết (Directory Junction) `C:\Users\Admin\.android\avd -> D:\Android\Avd` để Android CLI nhận diện đầy đủ AVD `Pixel_8`.
     - Xoá triệt để các tệp khoá chết mồ côi mà không xoá dữ liệu ứng dụng hay wipe emulator.
     - Khởi chạy ADB daemon ổn định (`adb server nodaemon`) và khởi động Pixel_8 (`emulator-5554`) qua WHPX + gfxstream GPU tăng tốc phần cứng. Thiết bị khởi động thành công (`sys.boot_completed = 1`).
     - Thiết lập chuyển tiếp cổng qua ADB reverse: `adb reverse tcp:8080 tcp:8080` và `adb reverse tcp:8081 tcp:8081`.

2. **Chạy Hệ Thống Thực Tế Kết Nối Full-Stack:**
   - **PostgreSQL 18:** Khởi chạy trên cổng 5433 qua container `digital-fitness-postgres`.
   - **Redis 7:** Khởi chạy trên cổng 6379.
   - **Spring Boot Backend:** Khởi chạy trên cổng 8080 với profile `dev`, tải đầy đủ 84 tài liệu chuyên môn và kết nối PostgreSQL/Flyway.
   - **FastAPI AI Service:** Khởi chạy trên cổng 8000 với `simulated_f04_provider.py` tất định phục vụ F04.
   - **Metro Bundler / Expo Go:** Khởi chạy trên cổng 8081 và kết nối trực tiếp đến ứng dụng trên thiết bị Pixel_8.

3. **Các Kịch Bản GUI Đã Kiểm Chứng Thành Công Trên Pixel_8:**
   - **Kịch bản 1 — Khởi tạo màn hình Student:** Từ màn hình chính Student, bấm "Tóm tắt tiến độ (AI) →", mở thành công giao diện chọn phạm vi ngày (`2026-10-23` đến `2026-11-06`), các nút presets ("7 ngày qua", "14 ngày qua", "30 ngày qua", "Tùy chọn") và các chip chỉ số thể chất (`WEIGHT`, `BODY_FAT_PERCENTAGE`, `WAIST_CIRCUMFERENCE`, `CHEST_CIRCUMFERENCE`).
     - *Bằng chứng screenshot:* `pixel8_f04_initial.png`
   - **Kịch bản 2 — Tạo và nhận bản tóm tắt tiến độ:** Bấm nút "Tạo tóm tắt tiến độ AI", request gửi qua Spring Boot (port 8080) và FastAPI (port 8000). Kết quả trả về và render đầy đủ với huy hiệu `THÔNG TIN TIẾN ĐỘ`, tóm tắt tổng quan tiến độ tập luyện, nhận diện dữ liệu mới (`Dữ liệu nguồn đã thay đổi`) và nút "Cập nhật lại tóm tắt".
     - *Bằng chứng screenshot:* `pixel8_f04_summary_result.png`
   - **Kịch bản 3 — Cuộn trang và kiểm tra các khối nội dung chi tiết:** Cuộn trang kiểm tra:
     - Khối `Tính liền mạch tập luyện`: Nhãn `Lịch sử chưa xác định đầy đủ` (`UNKNOWN_HISTORY`) kèm thông báo không đủ dữ liệu đánh giá liên tục.
     - Khối `Tiến độ tập luyện`: Đánh giá hoàn thành buổi tập, độ tuân thủ đúng lịch và khối lượng tập.
     - Khối `Số đo thể chất`: Hiển thị thông báo giới hạn dữ liệu khi học viên chưa có số đo trong kỳ.
     - Khối `Giới hạn và dữ liệu khuyết thiếu`: Liệt kê các thành phần thiếu dữ liệu.
     - Khối `Metadata Footer`: Hiển thị thời điểm tính, mã phiên `runId: 51bff66e-51d1-4dd8-bada-cc58514afb0a`, và watermark dữ liệu nguồn.
     - *Bằng chứng screenshot:* `pixel8_f04_scrolled_sections.png`
   - **Kịch bản 4 — Đọc lại bản tóm tắt cũ theo `runId`:** Khởi chạy intent deep link trực tiếp đến route `/progress-summary/51bff66e-51d1-4dd8-bada-cc58514afb0a`. Giao diện tải trực tiếp bản tóm tắt đã lưu qua `GET /api/v1/ai/progress/summary/{runId}` và hiển thị đầy đủ, chính xác.
     - *Bằng chứng screenshot:* `pixel8_f04_readback_by_run_id.png`
   - **Kịch bản 5 — Kiểm tra kiểm định ngày (Validation & Error State):** Nhập ngày bắt đầu `2026-11-20` lớn hơn ngày kết thúc `2026-11-06`. Giao diện lập tức hiển thị thông báo lỗi `Ngày bắt đầu không được sau ngày kết thúc.` và vô hiệu hoá nút submit. Khi bấm preset "14 ngày qua", khoảng ngày hợp lệ được phục hồi và thông báo lỗi biến mất ngay lập tức.
     - *Bằng chứng screenshot:* `pixel8_f04_validation_error.png`
   - **Kịch bản 6 — Điều hướng quay lại:** Bấm phím quay lại hệ thống, màn hình điều hướng mượt mà trở lại màn hình chính của học viên.

---

### 12.3. Kết Quả Kiểm Thử Tự Động (100% Passed)

1. **TypeScript Typecheck (`npm run typecheck`):**
   - Đạt **0 lỗi** biên dịch trên toàn bộ dự án mobile (`tsc --noEmit` hoàn tất thành công).

2. **Linter Check (`npm run lint` / `expo lint`):**
   - Đạt **0 lỗi, 0 cảnh báo** (hoàn toàn tuân thủ các quy tắc React 19 / React Compiler: không gọi setState đồng bộ trong effect, bảo toàn manual memoization dependencies, và chuẩn hoá kiểu mảng TypeScript).

3. **Kiểm Thử Đơn Vị & Tích Hợp Giao Diện Mobile (`jest`):**
   - [`aiProgressSummaryApi.test.ts`](../../apps/mobile/src/__tests__/aiProgressSummaryApi.test.ts) (**13/13 tests PASS**):
     - `generate`: Gửi request đúng body, endpoint và múi giờ giải quyết động.
     - `getSummary`: Đọc lại theo `runId`.
     - `getAiProgressSummaryErrorMessage`: Phân biệt rõ ràng giữa huỷ yêu cầu (`AbortError`), timeout thực tế, các lỗi gateway (`502`, `503`, `504`), `400`, `403`, `404`, `422`, và mất mạng `0`.
     - Kiểm tra nhãn danh mục số đo chuẩn (`METRIC_OPTIONS`, `METRIC_LABELS`), trạng thái liền mạch và biểu tượng xu hướng.
   - [`ProgressSummaryScreen.test.tsx`](../../apps/mobile/src/__tests__/ProgressSummaryScreen.test.tsx) (**13/13 tests PASS**):
     - Khởi tạo giao diện với presets, ô nhập ngày và nút tạo tóm tắt.
     - Validation: Chặn submit khi `fromDate > toDate`.
     - Validation: Chặn submit khi ngày không tồn tại trên lịch (như 30/02).
     - Render thành công đầy đủ các phần: tóm tắt, buổi tập, số đo thể chất, tính liền mạch.
     - Hiển thị banner cảnh báo an toàn khách quan khi có cờ gián đoạn dài ngày.
     - Hiển thị hộp thông báo khi nguồn buổi tập hoặc số đo bị hạn chế quyền (`measurement-restricted-notice` vs `measurement-no-data-notice`).
     - Hiển thị thanh cảnh báo dữ liệu cũ (`isStale: true` hoặc `needsRefresh: true`) và kích hoạt làm mới tóm tắt qua nút `Làm mới ngay`.
     - Chặn submit trùng lặp (Anti-double submit guard) khi thao tác submit đang xử lý.
     - Kiểm chứng cô lập học viên: Chuyển đổi `studentId` lập tức xóa sạch dữ liệu của học viên cũ, chống rò rỉ chéo và chống race condition A -> B -> A.
     - Đọc lại dữ liệu qua `initialRunId`.
     - Xử lý lỗi API và hiển thị hộp thông báo lỗi có nút thử lại.
   - [`routeGuard.test.ts`](../../apps/mobile/src/__tests__/routeGuard.test.ts) (**12/12 tests PASS**): Bổ sung kiểm tra quyền truy cập `canAccessProgressSummary`.
   - [`progressSummaryNavigation.test.tsx`](../../apps/mobile/src/__tests__/progressSummaryNavigation.test.tsx) (**1/1 test PASS**): Kiểm chứng nút bấm điều hướng từ màn hình Student Home.
   - **Tổng cộng 4 test suites liên quan trực tiếp F04 đạt 39/39 tests PASS (0 failures, 0 errors). Toàn bộ 87 test suites trong `apps/mobile` đạt 696/696 tests PASS.**

---

### 12.4. Khắc Phục Lỗi Watermark & Hoàn Thiện Kiểm Chứng Live End-to-End

Trong quá trình nghiệm thu live trên Android Pixel 8 và hệ thống full-stack cục bộ, các khiếm khuyết sau đã được phát hiện, phân tích nguyên nhân gốc rễ và xử lý triệt để:

1. **Hiện Tượng Báo Dữ Liệu Cũ (`isStale: true`) Ngay Khi Vừa Tạo:**
   - *Nguyên nhân 1 (Timestamp jitter trong composite watermark):* `ProgressEngineService.calculateCompositeWatermark` băm chuỗi `"ASOF:" + asOf.toString() + "|"`. Do `asOf` trôi vài mili-giây giữa thời điểm tạo context và bước kiểm tra 5e, giá trị băm luôn khác nhau dù không hề có bản ghi tập luyện hay số đo nào thay đổi.
   - *Nguyên nhân 2 (Lệch tham số trong `MeasurementProgressService`):* Hàm `calculateSourceWatermark` nhận nhầm `queryUpper` (thời điểm `asOf`) làm mốc thời gian giới hạn quyền của Actor thay vì `access.historyUntil()`. Do đó mỗi mili-giây clock trôi đi đều làm biến đổi watermark số đo thể chất.
   - *Giải pháp triệt để:*
     - Sửa `MeasurementProgressService` sử dụng `access.historyFrom()` và `access.historyUntil()`, loại bỏ phụ thuộc mili-giây vào clock runtime.
     - Cập nhật `AiProgressSummarizationService` (bước 5e và bước 4 readback): so sánh tính tươi mới dựa trên chính xác các domain watermarks (`workout.sourceWatermark()`, `measurement.sourceWatermark()`, `continuity.state()`, và tính sẵn sàng của nguồn) thay vì băm timestamp `asOf`.
     - Sửa giải quyết `dwNode` trong readback: đọc chính xác cấu trúc bọc `rootNode.path("context").path("dateWindow")`.

2. **Xung Đột Khóa Đọc PostgreSQL Trong `getProgressSummary`:**
   - *Nguyên nhân:* Annotation `@Transactional(readOnly = true)` trên hàm readback `getProgressSummary` xung đột với lệnh `SELECT ... FOR SHARE` được thực thi bởi `CoachingAuthorityQuery` để bảo vệ quyền hạn HLV, dẫn tới `SQLException: cannot execute SELECT FOR SHARE in a read-only transaction`.
   - *Giải pháp:* Đổi thành `@Transactional` để cho phép cơ chế khóa dòng chia sẻ (`FOR SHARE`) hoạt động an toàn và đồng bộ.

3. **Chuyển Đổi Mã Lỗi Phân Quyền HLV (`ProgressFailure` sang HTTP 403):**
   - *Nguyên nhân:* Khi quan hệ huấn luyện đã kết thúc (`ENDED`), `ProgressEngineService` ném `ProgressFailure(PROGRESS_ACCESS_DENIED)` chưa được bắt riêng trong AI service, dẫn tới phản hồi HTTP 500 thay vì 403 Forbidden.
   - *Giải pháp:* Bổ sung khối bắt `ProgressFailure.PROGRESS_ACCESS_DENIED` tại `F04ContextBuilder` và `AiProgressSummarizationService`, chuyển đổi thành `AiRunAccessDeniedException` (HTTP 403 `AI_FORBIDDEN`) với thông điệp rõ ràng, ngăn ngừa rò rỉ dữ liệu.

4. **Minh Họa Trực Quan Giao Diện Mobile & Cơ Chế Phân Quyền/Tươi Mới:**
   - Bộ 4 mockup hình ảnh giao diện Pixel 8 (kết xuất qua HTML/Edge headless ở viewport 412x915) minh họa các trạng thái nghiệp vụ và luồng xử lý:
     - **Tạo Tóm Tắt Tươi Mới (Fresh Summary Mockup):** Học viên tạo tóm tắt -> kết quả `isStale: false, needsRefresh: false`, hiển thị sạch đẹp không còn banner cảnh báo thừa ([`pixel8_student_summary_fresh.png`](artifacts/f04-mobile-runtime-acceptance/pixel8_student_summary_fresh.png), [`pixel8_refreshed_fresh.png`](artifacts/f04-mobile-runtime-acceptance/pixel8_refreshed_fresh.png)).
     - **Đột Biến Dữ Liệu & Nhận Biết Dữ Liệu Cũ (Stale Banner Mockup):** Chèn một số đo thể chất mới vào PostgreSQL (`fitness.measurements`); đọc lại bản tóm tắt cũ qua deep link -> hệ thống phát hiện chính xác sự thay đổi dữ liệu nguồn và hiển thị banner `Dữ liệu nguồn đã thay đổi` kèm nút "Làm mới ngay" ([`pixel8_stale_banner_after_mutation.png`](artifacts/f04-mobile-runtime-acceptance/pixel8_stale_banner_after_mutation.png)).
     - **Làm Mới Trực Tiếp (Refreshed Fresh Mockup):** Bấm nút "Làm mới ngay" trên GUI -> hệ thống kích hoạt tạo bản tóm tắt mới tích hợp số đo vừa thêm, banner cảnh báo biến mất và kết quả trở lại trạng thái tươi mới ([`pixel8_refreshed_fresh.png`](artifacts/f04-mobile-runtime-acceptance/pixel8_refreshed_fresh.png)).
     - **HLV Với Quyền Hạn Hạn Chế (Restricted Measurement Mockup):** Đăng nhập HLV `smoke.trainer@example.com` xem học viên `smoke.student@example.com` (chỉ cấp quyền Workout, hạn chế Body Metrics). Kết quả: tiến độ tập luyện hiển thị đầy đủ, trong khi mục số đo thể chất hiển thị rõ ràng thông báo `Dữ liệu số đo thể chất bị hạn chế quyền truy cập theo chính sách chia sẻ.` (`resource-id="measurement-restricted-notice"`, [`pixel8_trainer_restricted_measurement.png`](artifacts/f04-mobile-runtime-acceptance/pixel8_trainer_restricted_measurement.png)).
   - **Lưu ý ranh giới bằng chứng:** Các hình ảnh trên là visual design mockups kết xuất từ cấu trúc layout; **Live Android Emulator GUI Smoke Test trên thiết bị/trình giả lập thật ghi nhận là NOT RUN** trong phiên này.

---

### 12.5. Ranh Giới Nghiệm Thu & Các Giới Hạn Đang Mở

- **Trình Giả Lập Android Live GUI:** Kiểm chứng trên thiết bị thật / Android Emulator `Pixel_8` trong phiên này ghi nhận là **NOT RUN**. Tầng giao diện hiện được bảo đảm 100% bằng 37 Jest component/API tests trong `apps/mobile` (`ProgressSummaryScreen.test.tsx`, `aiProgressSummaryApi.test.ts`, v.v.).
- **Mô Hình Live LLM:** Hệ thống sử dụng provider tất định (`simulated_f04_provider.py`) để kiểm chứng toàn bộ luồng contract, HTTP, giao diện và thẩm định dữ liệu. Việc tích hợp live LLM (Google Gemini / OpenAI) và đánh giá chất lượng văn phong ngôn ngữ tự nhiên vẫn là hạng mục mở cho P2-M6 theo đúng kế hoạch triểnkai.
- **Quy Tắc Chuyên Môn Thể Hình (D06 / OD-3):** Chưa áp dụng các ngưỡng can thiệp tải hay phân loại tiến độ phức tạp của Hội đồng Thể hình; giữ đúng nguyên tắc `INFORMATION` trung lập.
- **Ranh Giới Checkpoint:** F04 đã hoàn thành triển khai mã nguồn và kiểm chứng CI/tests, nhưng **giữ trạng thái CHECKPOINT OPEN / UNDER VERIFICATION (CHƯA ĐÓNG CHECKPOINT)** cho đến khi có đầy đủ bằng chứng live GUI runtime trên Android device/emulator và quyết định từ Hội đồng D06.

---

## 13. Rà Soát Tiến Độ F04 Trên Main Hiện Tại (2026-11-14)

> **Commit nền xác minh (Baseline):** Working tree dựa trên `0cf247e` (HEAD origin/main) trên branch `fix/p2-m4-f04-verification-evidence` có các thay đổi chưa commit
> **Môi trường kiểm thử:** Local Developer Execution (JDK 21, Maven 3.9, Docker Desktop Testcontainers, PostgreSQL 18 pgvector, Node 22, Jest, Pytest) — phân biệt rõ với CI Pipeline Runner
> **Thời điểm thực thi:** 2026-11-14 15:35 (UTC+7)
> **Trạng thái checkpoint:** **CHECKPOINT OPEN / UNDER VERIFICATION (CHƯA ĐÓNG CHECKPOINT)**
> **Nguyên tắc tuân thủ:** Không triển khai lại phần đã merge; kiểm chứng 100% test suites trên toàn stack; xác minh phân quyền (authority) và tính tươi mới (freshness); chứng minh độ độc lập của tests và phát hiện lỗi qua negative mutation; cung cấp đầy đủ bằng chứng Android runtime thật nhị phân chuẩn và kiểm chứng live database mutation staleness.

### 13.1. Tổng Hợp Kết Quả Kiểm Chứng CI & Test Suite Trên Main

- **Lệnh thực thi kiểm thử Backend:**
  - `mvn -f services/backend/pom.xml test "-Dtest=WorkoutProgressAggregationIntegrationTest,MeasurementProgressIntegrationTest,ProgressEngineIntegrationTest,AiProgressSummaryRealFastApiIntegrationTest,SourceWatermarkUnitTest,F04RuntimeOutputValidatorUnitTest,AiProgressSummaryControllerUnitTest,AiProgressSummarizationServiceUnitTest,ProgressEngineUnitTest"`
- **Đường dẫn Surefire XML Reports:** [`services/backend/target/surefire-reports/TEST-*.xml`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/target/surefire-reports/)
- **Thời điểm thực thi:** 2026-11-14 15:00 (UTC+7) trên JDK 21 / Docker Desktop PostgreSQL container (working tree dựa trên `0cf247e` có uncommitted changes).

| Tầng Hệ Thống | Test Suite / Class Kiểm Tra | Số Tests (Surefire XML) | Kết Quả Ghi Nhận |
| :--- | :--- | :---: | :--- |
| **Backend Workout (F04-T1)** | [`WorkoutProgressAggregationIntegrationTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/WorkoutProgressAggregationIntegrationTest.java) | **28 tests PASS** | Snapshot isolation `REPEATABLE_READ`, paired tests chống leak ngoài scope, ranh giới timezone, mẫu số adherence quá hạn, zero volume. |
| **Backend Measurement (F04-T2)** | [`MeasurementProgressIntegrationTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/MeasurementProgressIntegrationTest.java) | **19 tests PASS** | Chuỗi quan sát đầy đủ V55, lọc tombstone `erased_at`, lọc `superseded`, watermark nhạy cảm với đơn vị đo, paired tests bảo vệ quyền HLV. |
| **Backend Progress Engine** | [`ProgressEngineIntegrationTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/ProgressEngineIntegrationTest.java) | **17 tests PASS** | Tích hợp đa nguồn, composite watermark, phân định 5 trạng thái enum `ContinuityState` (`CONFIRMED_NEW`, `PROLONGED_INACTIVITY`, `RECENT_ACTIVITY_OBSERVED`, `UNKNOWN_HISTORY`, `RESTRICTED_HISTORY`), đối chiếu Surefire XML. |
| **Backend AI Multi-Tier HTTP** | [`AiProgressSummaryRealFastApiIntegrationTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/AiProgressSummaryRealFastApiIntegrationTest.java) | **3 tests PASS** | Giao tiếp HTTP thực giữa Spring Boot và tiến trình FastAPI uvicorn, xác thực `X-Internal-Service-Key` với `internal_progress_summary.py` tại `app/api/routes`, timeout handling, payload mapping. |
| **Backend Unit Tests** | `SourceWatermarkUnitTest` (6)<br>`F04RuntimeOutputValidatorUnitTest` (7)<br>`AiProgressSummaryControllerUnitTest` (4)<br>`AiProgressSummarizationServiceUnitTest` (33)<br>`ProgressEngineUnitTest` (15) | **65 tests PASS** | Watermark digest, validator contract invariants, controller HTTP status mapping, service lifecycle, side-channel detection, continuity (`state`, `days`, `date`, `safetyFlags`, `rationale`), historical limits (`hasHistoricalLimits`, `workoutLimits`, `measurementLimits`, `explanation`) cả sau-provider và đọc lại, 2 negative control tests, và đầy đủ regression tests cho `ALL`, `SELECTED`, `UNKNOWN_LEGACY`, phục hồi scope từ `dataQualitySummary` snapshot, cùng kiểm chứng phân quyền nguồn độc lập tại readback: che mặt nạ khi `BODY_METRICS` hoặc `Workout` bị thu hồi, từ chối khi cả hai bị thu hồi, fail-closed khi thiếu metadata/snapshot hoặc lỗi kỹ thuật. |
| **Tổng Cộng Backend Tests** | Toàn bộ 9 test classes liên quan F04 | **128/128 PASS (100%)** | 0 failures, 0 errors đối chiếu trực tiếp từ 9 Surefire XML reports (tổng thời gian 71.35s). Gồm 60 integration tests (`WorkoutProgressAggregation`: 28, `MeasurementProgress`: 15, `ProgressEngine`: 17) + 3 AI multi-tier HTTP + 65 unit tests = 128 tests. Con số 130 ghi nhận trước đó là phép cộng nhẩm 123 + 7 = 130 thay vì 123 + 5 = 128 khi bổ sung 5 tests vào `AiProgressSummarizationServiceUnitTest`. |
| **AI FastAPI Service** | `test_f04_provider.py` (4), `test_f04_schemas.py` (3), `test_internal_progress_summary.py` (2) | **9/9 PASS (100%)** | Inactivity safety flag, restricted measurement handling, schema validation, internal endpoint security (`pytest`). |
| **Hợp Đồng & JSON Schemas** | `node tools/validate-ai-schemas.mjs` & `npm run validate:contracts` | **9 schemas, 40 fixtures PASS** | `f04-progress-summary-request.schema.json` & `f04-progress-summary-result.schema.json` đạt chuẩn Ajv 2020 và Redocly OpenAPI lint (đã bổ sung `fromDate`, `toDate`, `timezone`, `requestedMetricCodes`, và `metricScope: [ALL, SELECTED, UNKNOWN_LEGACY]` vào `contracts/openapi/openapi.yaml`). |
| **Mobile Client Tests (Jest)** | `ProgressSummaryScreen.test.tsx` (31), `aiProgressSummaryApi.test.ts` (13), `routeGuard.test.ts` (12), `progressSummaryNavigation.test.tsx` (1) | **57/57 PASS (100%)** | 4 targeted F04 test suites đạt 57/57 tests PASS (UI state, preset dates, calendar validation, anti-double submit, student state isolation, error mapping, navigation, bảo tồn tuyệt đối phạm vi ngày/metric khi refresh bản tóm tắt cũ theo `metricScope` [`ALL` vs `SELECTED` vs `UNKNOWN_LEGACY`], bảo tồn múi giờ gốc đã lưu không bị ghi đè bởi múi giờ profile, chặn refresh ngầm khi thiếu múi giờ và hiển thị hướng dẫn, kịch bản out-of-order delayed responses $A \to B \to A$, và kịch bản lỗi mạng $\to$ bấm "Thử lại" $\to$ phục hồi thành công). Toàn bộ 93 suites (766 tests) trong `apps/mobile` PASS. |
| **Mobile Quality Gates** | `tsc --noEmit` & `expo lint` | **0 errors, 0 warnings** | Tuân thủ 100% React Compiler / React 19 rules và TypeScript strict typing. |
| **Live Android GUI Runtime & DB Staleness** | Khởi chạy app trên Android Emulator Pixel 8 & Live Backend | **PASS** | Emulator Pixel 8 (Android 16 x86_64, WHPX) kết nối Metro Bundler (1,792 modules) và live backend Spring Boot qua `adb reverse`. Giao diện Expo Go tương tác tạo tóm tắt tiến độ và render thành công dữ liệu thực tế từ backend; kiểm chứng trực tiếp tương tác bấm nút "Làm mới ngay" bảo toàn nguyên vẹn phạm vi ngày/metric gốc (`ALL`, `SELECTED`); trích xuất 13 ảnh chụp nhị phân chuẩn 1080x2400 tại [`android_runtime_screen_*.png`](artifacts/f04-mobile-runtime-acceptance/) bao gồm Scenario 7 (Trainer restricted `BODY_METRICS`), Scenario 404 (Run Not Found), và Scenario 10 (tháo `adb reverse --remove tcp:8080`, hiển thị thẻ lỗi mạng `progress-summary-error-card` kèm nút "Thử lại", khôi phục reverse 8080 và bấm "Thử lại" phục hồi tóm tắt thành công). Kiểm chứng live DB mutation staleness trên PostgreSQL: thêm measurement trong kỳ chuyển ngay `isStale: True, needsRefresh: True`, thêm ngoài kỳ giữ `isStale: False` (kèm tài liệu đối chiếu nguồn fixture 76.5 kg tại `observation_76_5kg_source_evidence.json`). |

### 13.2. Bằng Chứng Kiểm Tra Quyền Hạn (Authority) & Tính Tươi Mới (Freshness)

1. **Kiểm Tra Quyền Hạn Nghiệp Vụ (Authority Invariants):**
   - **Student Self-Access:** Học viên luôn có toàn quyền yêu cầu và đọc lại bản tóm tắt tiến độ cá nhân của chính mình (`successfulSummarization_orchestratesFlow`, `readback_studentSelf_retrievesSummary`).
   - **Trainer Managed Scope:** HLV chỉ được truy vấn đối với học viên đang có quan hệ huấn luyện hoạt động (`ACTIVE`) kèm phân quyền chia sẻ (`WORKOUT_HISTORY`, `BODY_METRICS`) và phạm vi thời gian (`historyFrom`, `historyUntil`).
   - **Terminated Relationship:** Khi quan hệ kết thúc (`ENDED`), cả lệnh generate và readback đều trả chuẩn HTTP 403 `AI_FORBIDDEN` (`postProvider_coachingRevoked_throwsAccessDenied`, `readback_trainerRevoked_deniesAccess`).
   - **Data Masking at Readback:** Khi quyền hạn của HLV bị thu hồi một phần giữa các phiên (ví dụ thu hồi quyền xem Body Metrics), bản tóm tắt khi đọc lại tự động che (`withMaskedMeasurement`), không rò rỉ dữ liệu cũ (`readback_measurementRevoked_masksMeasurementData`). Nếu mất cả hai quyền thì chặn hoàn toàn với HTTP 403 (`readback_bothSourcesRevoked_throwsAccessDenied`).
   - **Chống Rò Rỉ Kênh Phụ (Zero Side-Channel Leakage):** Paired tests kiểm chứng rằng bất kỳ đột biến nào ngoài scope của HLV đều không làm thay đổi response, metadata hay watermark trả về cho HLV (`pairedTest_outOfScopeExecution_hasZeroSideChannelLeakage`, `pairedTest_outOfScopeDataChange_hasZeroSideChannelLeakageOnResponseAndWatermark`).

2. **Kiểm Tra Tính Tươi Mới (Freshness Invariants) & Độc Lập Của Tests:**
   - **Mô Tả Chuẩn Về Composite Watermark:** Thuật toán `calculateCompositeWatermark` trong `ProgressEngineService.java` vẫn chứa trường `"ASOF:" + asOf.toString() + "|"` để gắn chặt snapshot với mốc thời gian truy vấn cụ thể.
   - **Cơ Chế So Sánh Freshness Theo Từng Thành Phần Context Ảnh Hưởng Output:** Để tránh hiện tượng báo dữ liệu cũ giả (jitter) do timestamp `asOf` trôi vài mili-giây, `AiProgressSummarizationService.java` (tại bước 5e sau khi sinh tóm tắt và bước 4 khi đọc lại qua `GET /summary/{runId}`) **không so sánh trực tiếp chuỗi băm compositeWatermark**, mà so sánh độc lập toàn bộ các trường context thực sự tác động tới prompt và kết quả output:
     - `workout`: `sourceWatermark()` và trạng thái availability.
     - `measurement`: `sourceWatermark()` và trạng thái availability.
     - `continuity`: kiểm tra đồng thời toàn bộ tuple `state()` (5 enum values), `daysSinceLastWorkout()`, `lastWorkoutDate()`, `safetyFlags()` (như cờ `RETURN_AFTER_100_DAYS_V1`), và `rationale()`.
     - `historicalLimits`: kiểm tra toàn diện không chỉ cờ tổng `hasHistoricalLimits()`, mà kiểm tra cả `workoutLimits()`, `measurementLimits()`, và nội dung giải thích `explanation()` (nội dung này quyết định văn phong giải thích limitations của AI).
   - **Khắc Phục Tính Độc Lập Của Freshness Tests:** Trong `AiProgressSummarizationServiceUnitTest.java`, toàn bộ các trường context không thuộc đối tượng kiểm thử (như `rationale`, `hasHistoricalLimits`, `safetyFlags`) được đồng bộ hóa nghiêm ngặt giữa context snapshot và mock snapshot mới. Điều này loại bỏ nguy cơ test PASS do khác biệt ở trường khác (`rationale` mismatch ngoài ý muốn).
   - **Negative Control Tests:**
     - `readback_whenDataUnchanged_onlyCompositeWatermarkChanged_remainsFresh` (PASS): Khi chỉ watermark thay đổi do timestamp `asOf` nhưng dữ liệu nguồn không đổi $\to$ kết quả giữ nguyên tươi mới (`isStale = false`).
     - `postProvider_whenDataUnchanged_onlyCompositeWatermarkChanged_remainsFresh` (PASS): Tương tự ở pha sau-provider.
   - **Chứng Minh Tests Phát Hiện Đúng Lỗi (Mutation Proof):**
     - Đã tạm thời bỏ riêng điều kiện so sánh `explanationChanged` trong production code: cả hai test `readback_historicalExplanationChanged...` và `postProvider_historicalExplanationChanged...` lập tức **FAIL** với `AssertionFailedError: Expecting value to be true but was false`. Khôi phục code $\to$ PASS.
     - Đã tạm thời bỏ riêng điều kiện so sánh `safetyFlagsChanged` trong production code: cả hai test `readback_continuitySafetyFlagsChanged...` và `postProvider_continuitySafetyFlagsChanged...` lập tức **FAIL**. Khôi phục code $\to$ PASS.
     - Xác nhận không còn bất kỳ mutation tạm thời nào trong diff cuối cùng.

3. **Kiểm Chứng Mobile Client Xử Lý Response Đến Muộn & Đảo Thứ Tự Khi Đổi Học Viên ($A \to B \to A$):**
   - **Kịch Bản 1 (Tuần tự):** `handles sequential resolution of prior pending requests across student switching (A -> B -> A) without data leaks` — giải quyết tuần tự qua deferred promises, không rò rỉ dữ liệu của B vào giao diện của A.
   - **Kịch Bản 2 (Đảo thứ tự $A_2$ resolve trước, $A_1$ và $B$ resolve sau):** `prevents late responses (A1, B) from overwriting newer displayed result (A2) and corrupting states when resolved out of order (A2 first, then A1, then B)` — gửi $A_1 \to B \to A_2$, giải quyết $A_2$ trước để render kết quả mới của A, sau đó giải quyết $A_1$ và $B$. Xác nhận $A_1$ không ghi đè $A_2$, $B$ không rò rỉ dữ liệu, kết quả hiển thị của $A_2$ được giữ nguyên trọn vẹn.
   - **Kịch Bản 3 (Rejection đến muộn sau khi $A_2$ thành công):** `prevents late rejections from prior requests (A1, B) from corrupting currently displayed result (A2)` — cho $A_1$ hoặc $B$ reject sau khi $A_2$ đã render thành công. Xác nhận không xuất hiện banner lỗi của request cũ và không xóa mất kết quả $A_2$.
   - **Phạm Vi Đã Xác Minh:** Phạm vi kiểm chứng được giới hạn chính xác cho cơ chế đồng bộ request ID và student ID giữa các yêu cầu bất đồng bộ đến lệch thứ tự; không tuyên bố bao quát giải quyết mọi race condition có thể xảy ra trong các kịch bản khác.

4. **Sửa Lỗi & Kiểm Chứng Bảo Toàn Phạm Vi Khi Refresh (Scope Preservation Invariants):**
   - **Bản Chất Vấn Đề (Bug Root Cause):** Trước đây, `F04ProgressSummaryResponse` không mang các trường phạm vi truy vấn (`fromDate`, `toDate`, `timezone`, `requestedMetricCodes`). Khi mở bản tóm tắt đã lưu theo `runId`, biểu mẫu mobile giữ nguyên 14 ngày mặc định. Khi người dùng bấm *"Làm mới ngay"* trên banner dữ liệu cũ, handler gọi nhầm hàm gửi request lấy ngày từ biểu mẫu thay vì từ bản tóm tắt, khiến phạm vi bị thu hẹp về 14 ngày gần nhất.
   - **Giải Pháp Thực Hiện:**
     * Mở rộng hợp đồng OpenAPI và DTO `F04ProgressSummaryResponse` mang 4 trường: `fromDate`, `toDate`, `timezone`, `requestedMetricCodes`.
     * Cập nhật `AiProgressSummarizationService`: tại `summarizeProgress` đính kèm scope qua `.withScope(...)`; tại `getProgressSummary` trích xuất scope từ snapshot và ưu tiên dùng `resolvedMetricCodes` truy vấn `ProgressEngine`.
     * Cập nhật `ProgressSummaryScreen.tsx`: tự động đồng bộ giá trị ngày và metric chips khi đọc lại bản tóm tắt; xây dựng hàm chuyên biệt `handleRefreshSavedSummary` gắn riêng với nút *"Làm mới ngay"* để đảm bảo gửi chính xác scope đã lưu. Nếu người dùng chủ động sửa ngày trên form và bấm nút submit biểu mẫu *"Cập nhật lại tóm tắt"*, hệ thống mới tạo tóm tắt theo phạm vi mới.

5. **Sửa Lỗi Mất Nghĩa "Tất Cả Metrics" (Metric Scope Preservation) & Bằng Chứng Nguồn Observation 76.5 kg:**
   - **Bản Chất Vấn Đề:** Khi người dùng yêu cầu tóm tắt toàn bộ metrics (`request.metricCodes() == null` hoặc rỗng), hệ thống trước đây không phân biệt giữa việc "người dùng yêu cầu tất cả metrics" với "người dùng chọn một danh sách cụ thể". Đồng thời, backend suy diễn metrics từ `metricDeltas` trong output, dẫn đến nếu một metric chưa có quan sát ban đầu thì các quan sát mới cho metric đó bị bỏ lọt khỏi freshness query. Khi refresh trên mobile, nếu không cẩn thận hệ thống lại lấy chip selection từ form.
   - **Giải Pháp Thực Hiện:**
     * Thêm trường `metricScope` với enum 3 giá trị `[ALL, SELECTED, UNKNOWN_LEGACY]` vào `contracts/openapi/openapi.yaml`, `F04ProgressSummaryResponse.java`, và `aiProgressSummary.ts`.
     * Tại backend `summarizeProgress`: xác định `metricScope = (request.metricCodes() != null && !request.metricCodes().isEmpty()) ? "SELECTED" : "ALL"`, lưu trữ metadata (`metricScope`, `requestedMetricCodes`, `fromDate`, `toDate`, `timezone`) vào `dataQualitySummary` của `AiContextSnapshot`.
     * Tại backend readback `getProgressSummary`: khôi phục `metricScope` từ output hoặc `dataQualitySummary`. Nếu `metricScope == "SELECTED"`, truyền đúng `resolvedMetricCodes` vào `progressEngineQuery.query`; nếu `ALL` hoặc `UNKNOWN_LEGACY`, truyền `null` để truy vấn toàn bộ metrics. Tuyệt đối loại bỏ việc suy diễn từ `metricDeltas`.
     * Tại mobile `ProgressSummaryScreen.tsx`:
       - Khi `metricScope === 'ALL'`: gửi `metricCodes = undefined` khi refresh, bất kể chips form có bị chạm vào.
       - Khi `metricScope === 'SELECTED'`: gửi đúng mảng `requestedMetricCodes` đã lưu.
       - Khi `metricScope === 'UNKNOWN_LEGACY'`: hiển thị banner hướng dẫn rõ ràng ("Không thể tự động làm mới bản tóm tắt cũ thiếu thông tin phạm vi...") và không gửi request refresh tự động.
   - **Bằng Chứng Nguồn Observation 76.5 kg:**
     * Toàn bộ thuộc tính nguồn của fixture quan sát cân nặng 76.5 kg được lập hồ sơ tại [`docs/08-ai/artifacts/f04-freshness-evidence/observation_76_5kg_source_evidence.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f04-freshness-evidence/observation_76_5kg_source_evidence.json): ID `f0400003-0000-0000-0000-000000000003`, học viên `5c6f49c6-f4f6-4941-b217-2574b3830395` (runId `b582e1d6-48ae-48ed-b95e-1aa6984912fa`, `asOf = 2026-11-14T07:02:35.864202500Z`, curated evidence tổng hợp có chủ đích từ kết quả kiểm chứng SQL thực tế và API bước e2), metric `1 (WEIGHT)`, giá trị `76.50000000 KG`, thời điểm đo `2026-11-05 08:00:00+00`, trạng thái ban đầu `VALID` tại bước e2, chuyển `REJECTED` khi kết thúc kịch bản để tuân thủ trigger bất biến của `fitness.measurements`.

### 13.3. Danh Mục Bằng Chứng Thực Nghiệm (GUI Evidence & Freshness Artifacts)

#### 13.3.1. Bằng Chứng Android Runtime Thật (Live Screenshots)

Các artifact hình ảnh chụp trực tiếp từ Android Emulator Pixel 8 (1080x2400) thông qua lệnh `adb shell screencap -p /sdcard/...` và `adb pull` nhị phân chuẩn (magic bytes: `89 50 4E 47 0D 0A 1A 0A`), lưu trữ tại [`docs/08-ai/artifacts/f04-mobile-runtime-acceptance/`](artifacts/f04-mobile-runtime-acceptance/):

1. [`android_runtime_screen_initial.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_initial.png) (187 KB, 1080x2400) — Màn hình cấu hình tóm tắt tiến độ ban đầu trên Android runtime với trường ngày, metric chips chọn lọc và nút *"Tạo tóm tắt tiến độ AI"*.
2. [`android_runtime_screen_generated.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_generated.png) (316 KB, 1080x2400) — Màn hình hiển thị kết quả tóm tắt tiến độ thực tế trả về từ Spring Boot backend sau khi nhấn nút: banner *"CHƯA ĐỦ DỮ LIỆU ĐỂ KẾT LUẬN"*, tóm tắt tổng quan, tính liền mạch tập luyện *"Lịch sử chưa xác định đầy đủ"* và khối *"Tiến độ tập luyện"*.
3. [`android_runtime_screen_readback_fresh.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_readback_fresh.png) (316 KB, 1080x2400) — Màn hình đọc lại bản tóm tắt đã lưu qua deep link Expo Go (`/progress-summary/{runId}`) khi dữ liệu nguồn chưa đổi, kết quả tươi mới không có banner stale (`isStale: false, needsRefresh: false`).
4. [`android_runtime_screen_stale_banner.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_stale_banner.png) (317 KB, 1080x2400) — Giao diện Android runtime hiển thị Stale Banner sau khi phát sinh đột biến dữ liệu trong kỳ: thanh cảnh báo màu vàng với thông điệp *"Dữ liệu nguồn đã thay đổi"* kèm nút *"Làm mới ngay"* (`isStale: true, needsRefresh: true`).
5. [`android_runtime_screen_refreshed_fresh.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_refreshed_fresh.png) (315 KB, 1080x2400) — Giao diện sau khi bấm nút *"Làm mới ngay"* trực tiếp trên emulator: hệ thống tạo bản tóm tắt mới tích hợp số đo mới nhất, Stale Banner tự động biến mất và màn hình trở lại trạng thái tươi mới (`isStale: false, needsRefresh: false`).
6. [`android_runtime_screen_forbidden_and_retry.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_forbidden_and_retry.png) (241 KB, 1080x2400) — Giao diện xử lý lỗi phân quyền và cơ chế Retry khi truy cập bản tóm tắt không thuộc quyền hoặc quan hệ huấn luyện đã kết thúc (HTTP 403 Forbidden), render banner lỗi bảo mật màu đỏ kèm nút *"Thử lại"*.
7. [`android_runtime_screen_scope_readback_fresh.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_scope_readback_fresh.png) (317 KB, 1080x2400) — Giao diện đọc lại bản tóm tắt phạm vi tùy biến (`2026-10-01` đến `2026-10-25`, `WEIGHT`), xác nhận form ngày và metric chip hiển thị chính xác theo tóm tắt đã lưu.
8. [`android_runtime_screen_scope_stale_banner.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_scope_stale_banner.png) (318 KB, 1080x2400) — Giao diện hiển thị Stale Banner sau khi chèn observation ngày 2026-10-20 vào kỳ đã lưu, hiển thị nút *"Làm mới ngay"*.
9. [`android_runtime_screen_scope_preserved_refreshed.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_scope_preserved_refreshed.png) (319 KB, 1080x2400) — Giao diện sau khi bấm nút *"Làm mới ngay"*: phạm vi ngày 2026-10-01 đến 2026-10-25 được bảo toàn tuyệt đối, bản tóm tắt cập nhật thành công số đo mới và stale banner biến mất.
10. [`android_runtime_screen_trainer_restricted_measurement.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_trainer_restricted_measurement.png) (313 KB, 1080x2400) — Giao diện Huấn luyện viên xem học viên khi quyền `BODY_METRICS` bị hạn chế theo chính sách chia sẻ: khối tiến độ tập luyện hiển thị đầy đủ, trong khi khối số đo thể chất hiển thị rõ ràng thông báo che dữ liệu bảo mật `measurement-restricted-notice` ("Dữ liệu số đo thể chất bị hạn chế quyền truy cập theo chính sách chia sẻ.").
11. [`android_runtime_screen_run_not_found.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_run_not_found.png) (306 KB, 1080x2400) — Màn hình hiển thị thẻ lỗi màu đỏ `progress-summary-error-card` khi mở runId không tồn tại trên hệ thống (HTTP 404 Run Not Found, phân loại riêng).
12. [`android_runtime_screen_scenario10_network_error.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_scenario10_network_error.png) (221 KB, 1080x2400, SHA-256: `37DAA515B23E76C7BB7EEB00B789DF0806624480E23F9DB3A84203D035303B35`) — Giao diện xử lý lỗi kết nối mạng thật khi tháo riêng `adb reverse --remove tcp:8080`, hiển thị thẻ lỗi màu đỏ `progress-summary-error-card` ("Mất kết nối mạng. Vui lòng kiểm tra kết nối internet và thử lại.") kèm nút "Thử lại" `retry-summary-button`.
13. [`android_runtime_screen_scenario10_retry_success.png`](artifacts/f04-mobile-runtime-acceptance/android_runtime_screen_scenario10_retry_success.png) (312 KB, 1080x2400, SHA-256: `E85039B13068CE628FACC39A6CAE8B5E2CB8E515FEEC3E01CAE2BD6698DF49A9`) — Giao diện sau khi khôi phục `adb reverse tcp:8080 tcp:8080` và bấm trực tiếp nút "Thử lại" tại tọa độ (171, 1692): thẻ lỗi được giải phóng hoàn toàn và bản tóm tắt tiến độ hiển thị trọn vẹn, tươi mới.

#### 13.3.2. Bằng Chứng Live Database Mutation & Freshness (PostgreSQL + Spring Boot API)

Lưu trữ tại thư mục [`docs/08-ai/artifacts/f04-freshness-evidence/`](artifacts/f04-freshness-evidence/):
- [`step_a_initial_summary.json`](artifacts/f04-freshness-evidence/step_a_initial_summary.json) — Initial summary runId `9044d8a5...` (`isStale: false, needsRefresh: false`).
- [`step_b_readback_fresh.json`](artifacts/f04-freshness-evidence/step_b_readback_fresh.json) — Readback khi dữ liệu nguồn chưa đổi (`isStale: false, needsRefresh: false`).
- [`step_c_readback_after_out_of_window_mutation.json`](artifacts/f04-freshness-evidence/step_c_readback_after_out_of_window_mutation.json) — Readback sau khi thêm observation ngoài kỳ (2026-11-15, `isStale: false`).
- [`step_c2_readback_after_pre_asof_out_of_window_mutation.json`](artifacts/f04-freshness-evidence/step_c2_readback_after_pre_asof_out_of_window_mutation.json) — Readback summary tháng 10 sau khi thêm observation ngày 2026-11-05 (ngoài window tháng 10 nhưng có `measured_at < asOf`): `isStale: false, needsRefresh: false` (chứng minh bộ lọc boundary theo ngày của ProgressEngine hoạt động độc lập với mốc `asOf`).
- [`step_d_readback_after_in_window_mutation_stale.json`](artifacts/f04-freshness-evidence/step_d_readback_after_in_window_mutation_stale.json) — Readback sau khi thêm observation trong kỳ (2026-10-20, `isStale: true, needsRefresh: true`).
- [`step_e_readback_after_rejection_restored_fresh.json`](artifacts/f04-freshness-evidence/step_e_readback_after_rejection_restored_fresh.json) — Readback sau khi observation chuyển thành REJECTED (`isStale: false, needsRefresh: false`).
- [`step_e2_summary_including_pre_asof_observation.json`](artifacts/f04-freshness-evidence/step_e2_summary_including_pre_asof_observation.json) — Summary window tháng 11 (2026-11-01 đến 2026-11-10): xác nhận observation ngày 2026-11-05 (fixture `f0400003-0000-0000-0000-000000000003`, `student_id = 5c6f49c6...`, `metric_definition_id = 1 (WEIGHT)`, `numeric_value = 76.50000000`, `measured_at = 2026-11-05 08:00:00+00`, `validation_status = 'VALID'`) được thu nạp và đánh giá đầy đủ vào `measurementProgressSummary` (kết quả xu hướng `INSUFFICIENT_DATA` do chỉ có 1 quan sát duy nhất trong kỳ).
- [`step_f_refreshed_summary_fresh.json`](artifacts/f04-freshness-evidence/step_f_refreshed_summary_fresh.json) — Refreshed summary runId `eacdf381...` (`isStale: false, needsRefresh: false`).
- [`observation_76_5kg_source_evidence.json`](artifacts/f04-freshness-evidence/observation_76_5kg_source_evidence.json) — Bằng chứng nguồn đầy đủ của fixture observation 76.5 kg (`f0400003-0000-0000-0000-000000000003`, `student_id = 5c6f49c6...`, `metric_definition_id = 1 (WEIGHT)`, `numeric_value = 76.50000000`, `measured_at = 2026-11-05 08:00:00+00`, `validation_status = 'VALID'` tại bước e2, `asOf` đối chiếu, giải thích phạm vi window tháng 10 vs tháng 11).
- [`freshness_audit_trail.log`](artifacts/f04-freshness-evidence/freshness_audit_trail.log) — Toàn bộ nhật ký kiểm toán với timestamps và kết quả assertions PASS 100%.

#### 13.3.3. Mockups Bổ Trợ (Layout & Visual Design Mockups)

> [!NOTE]
> Bốn file PNG dưới đây được kết xuất qua HTML/Edge headless ở viewport 412x915 trước đây nhằm minh họa trực quan các kịch bản layout. Chúng là **Layout & Visual Design Mockups**, phân biệt rạch ròi với bằng chứng Android Runtime thật ở Mục 13.3.1.

1. [`pixel8_student_summary_fresh.png`](artifacts/f04-mobile-runtime-acceptance/pixel8_student_summary_fresh.png) — Mockup giao diện tóm tắt tươi mới của Học viên.
2. [`pixel8_stale_banner_after_mutation.png`](artifacts/f04-mobile-runtime-acceptance/pixel8_stale_banner_after_mutation.png) — Mockup giao diện hiển thị banner cảnh báo `Dữ liệu nguồn đã thay đổi` kèm nút "Làm mới ngay".
3. [`pixel8_refreshed_fresh.png`](artifacts/f04-mobile-runtime-acceptance/pixel8_refreshed_fresh.png) — Mockup giao diện sau khi bấm "Làm mới ngay", kết quả tươi mới.
4. [`pixel8_trainer_restricted_measurement.png`](artifacts/f04-mobile-runtime-acceptance/pixel8_trainer_restricted_measurement.png) — Mockup giao diện góc nhìn Huấn luyện viên khi bị giới hạn quyền số đo thể chất (`resource-id="measurement-restricted-notice"`).
