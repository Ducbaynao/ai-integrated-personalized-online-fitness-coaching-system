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

### 9.4. Giới Hạn Còn Lại & Các Bước Tiếp Theo (Không Tuyên Bố Hoàn Tất F04 / Phase 2)
F04-T2 đã hoàn thành khép lại tầng dữ liệu đo lường thể chất cho F04. Tuy nhiên, toàn bộ tính năng F04 vẫn còn các hạng mục tiếp theo:
1. **Progress Engine Domain Service:** Tổng hợp tín hiệu giữa Workout Adherence/Volume (F04-T1) và Thể chất (F04-T2), phát hiện các tín hiệu an toàn (như gián đoạn tập luyện $\ge 100$ ngày).
2. **FastAPI AI Progress Summarization Service:** Xây dựng context builder, prompt template và schema JSON output cho báo cáo tiến độ tuần/tháng.
3. **Mobile Client Progress UI:** Giao diện hiển thị tiến độ và công cụ review của HLV trên Mobile (Live GUI Smoke Test hiện tại là NOT RUN).
4. **Thẩm định Chuyên môn (D06 / OD-3):** Phê duyệt quy tắc phân loại minor vs significant và các ngưỡng cảnh báo an toàn từ Hội đồng Thể hình (gate chuyên môn còn mở).

