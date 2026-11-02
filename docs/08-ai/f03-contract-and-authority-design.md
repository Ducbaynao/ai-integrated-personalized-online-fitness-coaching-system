# Thiết Kế Contract & Mô Hình Quyền Hạn F03 — Đề Xuất Thay Bài Tập & Điều Chỉnh Tải (Milestone P2-M4)

> **Mã tính năng:** F03 — AI Workout Adjustment Proposal (Thay bài tập, điều chỉnh mức tạ và khối lượng tập luyện)
> **Milestone phụ trách:** P2-M4 — Mở Rộng Hỗ Trợ Tập Luyện
> **Repository:** `D:\Projects\ai-integrated-personalized-online-fitness-coaching-system`
> **Branch thực hiện:** `feat/p2-m4-f03-contract-package-20261101`
> **Baseline commit xác minh:** `b471e25c024447ead5abc08783a8c044a7cfb021` (origin/main, tích hợp hotfix PR #124 `a90ede2`)
> **Tài liệu căn cứ:**
> - [`phase-2-release-scope.md`](../00-project-overview/phase-2-release-scope.md) (Mục 5, 6, 7)
> - [`phase-2-feature-data-authority-matrix.md`](phase-2-feature-data-authority-matrix.md) (Mục 1.6, 1.7, 1.8, 4)
> - [`phase-2-implementation-plan.md`](../07-development/phase-2-implementation-plan.md) (Mục 6, 9, 11.1, 12.3)
> - [`acceptance-criteria.md`](../01-requirements/acceptance-criteria.md) (Mục Workout & Scheduling AC #7; AI & Governance AC #1, #2)
> - [`business-rules.md`](../02-domain/business-rules.md) (Mục Workout Programming BR #6, #7, #8)
> - [`recommendation-lifecycle.md`](recommendation-lifecycle.md)
> - Hồ sơ kiểm chứng P2-M3 tại PR [#125](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/pull/125)

---

## 1. Kết Quả Review PR #125 & Phân Định Trạng Thái Nghiệm Thu P2-M3

### 1.1. Tình trạng Pull Request & CI Checks
- **Pull Request:** [#125](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/pull/125) (`test/p2-m3-f02-gui-acceptance-20261031` $\to$ `main`).
- **Trạng thái PR:** Open, Draft mode (đúng quy định đóng gói để review, không tự tiện merge vào `main`).
- **Review comments hiện có:** 0 review comments mở.
- **Commit hash:** `80941cdefc1dba7bf7e41e5d25bc2f243b60bb0f`.
- **Kết quả CI/CD trên commit:**
  - AI Service Check: **SUCCESS**
  - Database Migrations Check: **SUCCESS**
  - Client Admin Web Check: **SUCCESS**
  - Client Mobile Check: **SUCCESS**
  - Contracts & Docs Validation: **SUCCESS**
  - Backend Check: **SUCCESS**

### 1.2. Đánh giá tính xác thực của hồ sơ P2-M3
Hồ sơ kiểm chứng P2-M3 đã được chuẩn hóa với 76 liên kết tương đối (relative links), kiểm tra tự động đạt 0 broken links. Phân định rạch ròi 3 cấp độ:
1. **Kiểm chứng kỹ thuật (Technical Verification):** **ĐẠT (PASS)** trên toàn bộ 4 tầng (Mobile GUI trên Pixel 8 Emulator API 34 $\to$ Spring Boot 4.1.1 $\to$ FastAPI AI Service $\to$ PostgreSQL 18.6). 44 backend tests và 13 mobile component tests chạy thực tế tại phiên 31/10/2026.
2. **Đủ điều kiện review (Review Readiness):** **ĐẠT (READY)**. Hồ sơ đầy đủ 17 ảnh screenshot độ phân giải gốc, bảng đối soát database snapshot, audit log và application receipt.
3. **Quyết định nghiệm thu Milestone (Milestone Sign-off):** **CHƯA NGHIỆM THU CHÍNH THỨC**. Đây là thẩm quyền của Project Owner và Tech Lead. Hồ sơ PR #125 là căn cứ kỹ thuật để cấp có thẩm quyền phê duyệt nghiệm thu P2-M3, không tự nhận là đã được nghiệm thu toàn bộ.

### 1.3. Làm rõ sai khác giữa hai mã SHA baseline được báo cáo
- SHA `b471e2586bf12ca87ee64df089e504c356e2e505`: Đây là chuỗi ký tự bị nhầm lẫn trong báo cáo tóm tắt trước đó (lẫn lộn đoạn giữa `...86bf...`).
- SHA `b471e25c024447ead5abc08783a8c044a7cfb021`: Đây là **commit SHA thực tế duy nhất** của merge commit PR #124 trên `origin/main` (xác nhận qua lệnh `git rev-parse origin/main`). Toàn bộ công việc thiết kế và triển khai contract F03 được xây dựng chuẩn xác từ baseline này.

---

## 2. Bối Cảnh, Mục Tiêu & Ranh Giới Nghiệp Vụ Của F03

### 2.1. Mục tiêu tính năng F03
F03 cung cấp khả năng trợ lý AI giúp người có thẩm quyền giáo án:
1. **Thay bài tập tương đương (Exercise Substitution):** Thay một bài tập không thể thực hiện (do thiếu thiết bị, máy bận, hạn chế chấn thương / đau mỏi) bằng một biến thể bài tập tương thích về nhóm cơ, chuyển động và thiết bị có sẵn.
2. **Điều chỉnh mức tạ (Load Adjustment):** Tăng hoặc giảm mức tạ (target load) dựa trên lịch sử thực tế (actual sets, reps, load, RPE) và trạng thái hồi phục/gián đoạn.
3. **Điều chỉnh số hiệp / số lần lặp (Volume Adjustment):** Tăng/giảm số sets hoặc khoảng reps/thời lượng của một bài tập để phù hợp với năng lực hiện tại hoặc phân phối khối lượng tập luyện.

### 2.2. Bất biến nghiệp vụ bắt buộc (Invariants)
1. **Không can thiệp Actual Workout:** Buổi tập đã hoàn thành (`COMPLETED`) hoặc đang thực hiện (`IN_PROGRESS`) là **bất biến tuyệt đối**. F03 chỉ được phép tác động lên các bài tập của buổi tập dự kiến trong tương lai (`PLANNED`). Vi phạm điều này sẽ bị chặn ngay tại domain guard `PLANNED_WORKOUT_ADJUSTMENTS_SEALED`.
2. **Quyền hạn theo Coaching Mode:**
   - Trong `SELF_DIRECTED`: Học viên có toàn quyền yêu cầu, chỉnh sửa bản thảo và chấp thuận áp dụng đề xuất.
   - Trong `HUMAN_COACH`: Huấn luyện viên (PT) được phân công sở hữu toàn quyền. Học viên **bị chặn tuyệt đối** (`403 AI_FORBIDDEN`), không được tự áp dụng đề xuất làm lệch giáo án của PT.
3. **Nguyên tắc "Không có dữ liệu $\to$ Không tăng tải":**
   - Chỉ được đề xuất tăng tải (`LOAD_INCREASE` hoặc `VOLUME_INCREASE`) khi có **đầy đủ dữ liệu Actual Workout (sets, reps, load, RPE)** đạt tiêu chuẩn chất lượng trong cửa sổ dữ liệu (coverage window).
   - Nếu thiếu RPE, dữ liệu gián đoạn hoặc học viên mới $\to$ **TUYỆT ĐỐI KHÔNG TỰ TIỆN ĐỀ XUẤT TĂNG TẢI**. Hệ thống chỉ cho phép thay bài tương đương hoặc đề xuất giảm tải/reset an toàn.
4. **Phân loại Minor vs Significant Change (AC #7 & BR #6, #7):**
   - **Minor Change (Thay đổi nhỏ trong một buổi cụ thể - `ONE_MATERIALIZED_OCCURRENCE`):** Tác động lên duy nhất 1 buổi tập đã lên lịch (`plannedWorkoutId`). Ghi nhận vào lịch sử điều chỉnh buổi tập (`WorkoutSessionAdjustment`), **KHÔNG tạo phiên bản giáo án mới** (`no new plan version`).
   - **Significant Change (Thay đổi chiến lược - `TEMPLATE_OR_FUTURE_OCCURRENCES`):** Tác động lên mẫu giáo án (template) hoặc áp dụng cho toàn bộ các buổi tương lai. Bắt buộc tạo **phiên bản giáo án mới** (`publishVersion`), tăng `planVersionNumber` (ví dụ v1 $\to$ v2) và bảo vệ `aggregateVersion`.
5. **AI không có quyền áp dụng tự động:** AI chỉ sinh đề xuất (`PROPOSAL` $\to$ `AiRecommendation` ở trạng thái `PENDING`). Mọi thay đổi phải được con người phê duyệt tường minh (`Accept-and-Apply`) qua domain command của Spring Boot.

---

## 3. Thiết Kế Cấu Trúc Dữ Liệu Typed Change Set F03

Theo quy định tại `phase-2-feature-data-authority-matrix.md` mục 4: **Tuyệt đối không dùng object tự do (`suggestedActions: object`) cho dữ liệu domain cốt lõi.** Mọi đề xuất phải được định kiểu tường minh, null-safe, và có đơn vị đo rõ ràng.

### 3.1. Định danh mục tiêu (`targetRef`)
Để đảm bảo tính bền vững khi dữ liệu trong database thay đổi, `targetRef` sử dụng định danh UUID cụ thể, **không dựa vào chỉ số mảng UI (array index) không ổn định**:

```json
{
  "targetRef": {
    "scope": "ONE_MATERIALIZED_OCCURRENCE",
    "plannedWorkoutId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "plannedSessionExerciseId": "7c9e6679-7425-40de-944b-e07fc1f90ae7"
  }
}
```

- Khi `scope == ONE_MATERIALIZED_OCCURRENCE`: `plannedWorkoutId` và `plannedSessionExerciseId` là bắt buộc (khớp với khóa ngoại và tham số lệnh `AdjustOccurrenceCommand`).
- Khi `scope == TEMPLATE_OR_FUTURE_OCCURRENCES`: `planId`, `planVersionNumber`, và `exercisePrescriptionId` là bắt buộc.

### 3.2. Phân loại thay đổi (`changeType`) & Định dạng Before / After

#### A. Loại 1: `EXERCISE_SUBSTITUTION` (Thay bài tập)
- **Before:**
  ```json
  {
    "exerciseVariationId": "22222222-2222-4222-8222-222222222222",
    "exerciseName": "Barbell Back Squat",
    "movementPattern": "SQUAT",
    "equipmentType": "BARBELL"
  }
  ```
- **After:**
  ```json
  {
    "replacementVariationId": "33333333-3333-4333-8333-333333333333",
    "replacementExerciseName": "Goblet Squat",
    "movementPattern": "SQUAT",
    "equipmentType": "DUMBBELL",
    "substitutionCategory": "EQUIPMENT_UNAVAILABLE"
  }
  ```
- **Ràng buộc:** `replacementVariationId` bắt buộc phải là bài tập đang ở trạng thái `ACTIVE` trong Exercise Catalog. `movementPattern` phải tương thích hoặc giải phóng nhóm cơ bị hạn chế.

#### B. Loại 2: `LOAD_ADJUSTMENT` (Điều chỉnh mức tạ)
- **Before:**
  ```json
  {
    "targetLoad": 80.0,
    "loadUnit": "KG",
    "loadUnitId": 1
  }
  ```
- **After:**
  ```json
  {
    "targetLoad": 82.5,
    "loadUnit": "KG",
    "loadUnitId": 1,
    "direction": "INCREASE",
    "deltaLoad": 2.5,
    "deltaPercent": 3.125
  }
  ```
- **Ràng buộc:** `targetLoad >= 0`. Đơn vị đo `loadUnit` phải đồng nhất giữa Before và After. `deltaLoad` bắt buộc phải khớp với `after.targetLoad - before.targetLoad`. Nếu mức tạ trước đó bằng 0 hoặc null (ví dụ bài bodyweight), `deltaPercent` bắt buộc là `null` (tránh chia cho 0).

#### C. Loại 3: `VOLUME_ADJUSTMENT` (Điều chỉnh số hiệp / số lần lặp / thời lượng)
- **Bài tập tính theo reps (ví dụ Squat, Bench Press):**
  ```json
  {
    "before": { "targetSets": 3, "targetRepsMin": 8, "targetRepsMax": 10, "durationSeconds": null },
    "after": { "targetSets": 4, "targetRepsMin": 8, "targetRepsMax": 10, "durationSeconds": null, "deltaSets": 1, "deltaRepsMin": 0, "deltaRepsMax": 0, "deltaDurationSeconds": null }
  }
  ```
- **Bài tập tính theo thời lượng (ví dụ Plank, Static Hold):**
  ```json
  {
    "before": { "targetSets": 3, "targetRepsMin": null, "targetRepsMax": null, "durationSeconds": 45 },
    "after": { "targetSets": 3, "targetRepsMin": null, "targetRepsMax": null, "durationSeconds": 60, "deltaSets": 0, "deltaRepsMin": null, "deltaRepsMax": null, "deltaDurationSeconds": 15 }
  }
  ```
- **Ràng buộc:** Không ép buộc bài tính thời gian phải có reps. `targetSets >= 1`. `targetRepsMin <= targetRepsMax`. Các trường delta phải đồng nhất với giá trị after trừ before.

---

## 4. Thiết Kế Luồng Thẩm Quyền (Authority) & Giao Dịch Áp Dụng (Apply Flow)

### 4.1. Sơ đồ tuần tự xử lý xuyên suốt (End-to-End Sequence)

```
[Request API] -> [Context Builder & Pre-Rules] -> [AI FastAPI] -> [Output Validator]
      -> [AiRecommendation PENDING (rev 1)] -> [Draft Edit (rev 2)]
      -> [Accept-and-Apply Transaction] -> [Domain Command Routing]
           |-- Minor (1 buổi)      --> workoutPlanCommandUseCase.adjustOccurrence()
           |-- Significant (nhiều) --> workoutPlanCommandUseCase.publishVersion()
      -> [Receipt & Audit Log] -> [Commit APPLIED]
```

### 4.2. Ma trận thẩm quyền quyết định & Xử lý xung đột (Authority Matrix)

| Chủ thể yêu cầu | Chế độ huấn luyện | Quyền yêu cầu & Xem đề xuất | Quyền chỉnh sửa bản thảo (Draft Edit) | Quyền Chấp thuận & Áp dụng (Accept & Apply) | Hành vi khi vi phạm quyền |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **Học viên (Student)** | `SELF_DIRECTED` | **CÓ** | **CÓ** | **CÓ** | Hợp lệ theo quyền tự chủ kế hoạch tập luyện cá nhân. |
| **Học viên (Student)** | `HUMAN_COACH` | **CÓ (Chỉ gửi yêu cầu)** | **KHÔNG** | **KHÔNG (BỊ CHẶN)** | Trả về HTTP 403 `AI_FORBIDDEN`. Học viên chỉ có thể gửi request để PT xem xét, không được tự ý sửa giáo án do PT quản lý. |
| **Huấn luyện viên (Trainer)** | `HUMAN_COACH` | **CÓ (Cho học viên được gán)** | **CÓ** | **CÓ** | Huấn luyện viên có toàn quyền điều chỉnh giáo án trong thời hạn hợp đồng coaching hợp lệ (`CoachingPeriod`). |
| **Huấn luyện viên (Trainer)** | `SELF_DIRECTED` | **KHÔNG** | **KHÔNG** | **KHÔNG** | Trả về HTTP 403 `AI_FORBIDDEN`. PT không có quyền can thiệp vào tài khoản tự tập không có quan hệ huấn luyện. |
| **Admin nền tảng** | Bất kỳ | **KHÔNG** | **KHÔNG** | **KHÔNG** | Admin là thẩm quyền vận hành nền tảng, không có thẩm quyền chuyên môn thay người dùng. |

### 4.3. Đối soát mã nguồn & Khả năng domain thực tế
1. **Lệnh `adjustOccurrence`:** Đã tồn tại trong `WorkoutPlanCommandUseCase.java:31` và được triển khai đầy đủ tại `WorkoutPlanCommandService.java:197–230`. Lệnh này:
   - Nhận `AdjustOccurrenceCommand(plannedWorkoutId, actorId, expectedVersion, commandKey, type, plannedSessionExerciseId, replacementVariationId, beforeJson, afterJson, reason)`.
   - Kiểm tra `occurrence.executionStartedAt() != null` $\to$ ném ngay `PLANNED_WORKOUT_ADJUSTMENTS_SEALED` (HTTP 409).
   - Kiểm tra `occurrence.version() != expectedVersion` $\to$ ném `PLANNED_WORKOUT_VERSION_CONFLICT` (HTTP 409).
   - Lưu vào bảng `fitness.workout_session_adjustments` và tăng `planned_workouts.version` thêm 1.
   - Ghi nhận `Receipt` và `PLANNED_WORKOUT_ADJUSTED` audit event.
2. **Lệnh `publishVersion`:** Đã tồn tại trong `WorkoutPlanCommandUseCase.java:27` và `WorkoutPlanCommandService.java:140–185`. Lệnh này:
   - Nhận `PublishVersionCommand(planId, actorId, expectedVersion, commandKey, reason, summary, sessions)`.
   - Bảo vệ `plan.aggregateVersion() == expectedVersion` (HTTP 409 nếu conflict).
   - Tăng `planVersionNumber` (ví dụ v1 $\to$ v2), lưu vào `workout_plan_versions`.

---

## 5. Tách Biệt Chính Sách Chuyên Môn (Policy) Khỏi Contract Kỹ Thuật

Tuyệt đối không hardcode các giá trị heuristic thành quy tắc nghiệp vụ đã phê duyệt:
- **Ngưỡng volume $\ge 20\%$ cho significant change:** Là **UNAPPROVED PROPOSAL**. Không hardcode trong schema hay code logic.
- **Ràng buộc thiếu dữ liệu RPE:** Là **APPROVED INVARIANT**. Không có RPE / actual sets tin cậy thì cấm tăng tải (`allowLoadIncrease = false`).
- **Trần biên độ tăng tải an toàn ($\le 5\%$ upper body, $\le 10\%$ lower body):** Là **OPERATIONAL CANDIDATE**, chờ Hội đồng Chuyên môn Thể hình (D06) phê duyệt.

### Ma trận các quyết định còn thiếu (Open Decisions Matrix)

| STT | Câu hỏi cần quyết định | Các lựa chọn khả thi | Tác động kỹ thuật & nghiệp vụ | Thẩm quyền quyết định | Phần việc bị ảnh hưởng | Việc làm được ngay |
| :---: | :--- | :--- | :--- | :---: | :--- | :--- |
| **OD-1** | Định lượng volume bao nhiêu % được tính là Significant Change? | A. $\ge 15\%$<br>B. $\ge 20\%$<br>C. $\ge 2$ bài tập chính | Phân loại tự động giữa `adjustOccurrence` và `publishVersion`. | **Hội đồng D06 & PO** | Tự động phân loại scope khi người dùng chọn "Áp dụng cho các tuần tới". | Toàn bộ schema, Java DTOs, lệnh command routing, giao diện Before/After diff card. |
| **OD-2** | Thiếu RPE nhưng đủ số sets/reps hoàn thành có được tăng tải nhẹ? | A. Cấm tuyệt đối (Fail-closed)<br>B. Cho phép micro-loading $\le 2.5\%$ | An toàn y khoa vs tính liên tục của trải nghiệm. | **Hội đồng D06** | Pre-model rule trong Context Builder (`allowLoadIncrease`). | Xây dựng pipeline kiểm tra RPE, schema payload, các luồng thay bài tập do thiếu thiết bị. |
| **OD-3** | Quản lý danh mục bài tập thay thế ở đâu? | A. Code enum<br>B. Bảng quan hệ DB (`exercise_replacements`) do Admin quản lý<br>C. Tri thức RAG | Khả năng mở rộng bài tập thay thế tương đương. | **Tech Lead & D06** | Module Exercise Library query. | Schemas, command execution, UI diff card. |

---

## 6. Bảng Đối Chiếu Yêu Cầu $\to$ Contract $\to$ Validation $\to$ Tests $\to$ Runtime Gap

| Yêu cầu nghiệp vụ | JSON Schema / Model | Lớp Validation | Fixtures / Tests xác minh | Khoảng trống Runtime (Chưa làm ở đợt này) |
| :--- | :--- | :--- | :--- | :--- |
| **Thay bài tập 1 buổi cụ thể** | `changeType: EXERCISE_SUBSTITUTION`<br>`scope: ONE_MATERIALIZED_OCCURRENCE`<br>`targetRef: plannedWorkoutId, plannedSessionExerciseId` | JSON Schema + Pydantic + Java `TargetRef` constructor | `valid-exercise-substitution-occurrence.json`<br>`test_valid_exercise_substitution_occurrence`<br>`deserialize_validExerciseSubstitutionOccurrence` | Chưa nối endpoint gọi `workoutPlanCommandUseCase.adjustOccurrence` từ proposal controller. |
| **Thay bài tập toàn bộ các tuần tới** | `changeType: EXERCISE_SUBSTITUTION`<br>`scope: TEMPLATE_OR_FUTURE_OCCURRENCES`<br>`targetRef: planId, planVersionNumber, exercisePrescriptionId` | JSON Schema + Pydantic + Java `TargetRef` constructor | `valid-exercise-substitution-template.json`<br>`test_valid_exercise_substitution_template`<br>`deserialize_validExerciseSubstitutionTemplate` | Chưa viết mapper chuyển đổi `changes` sang `List<WorkoutSessionTemplate>` cho `publishVersion`. |
| **Điều chỉnh mức tạ có tải ngoài (KG/LBS)** | `changeType: LOAD_ADJUSTMENT`<br>`before/after.targetLoad >= 0`<br>`loadUnit: "KG" \| "LBS"`, `loadUnitId: int` | Schema check `minimum: 0` + Pydantic `validate_load_deltas` + Java `LoadAfter` | `valid-load-adjustment.json`<br>`test_valid_load_adjustment`<br>`deserialize_validLoadAdjustment` | Chưa viết `F03RuntimeOutputValidator` kiểm tra catalog active và an toàn tăng tải. |
| **Điều chỉnh bài tập tự trọng (Bodyweight)** | `changeType: LOAD_ADJUSTMENT`<br>`targetLoad: null`, `loadUnit: null`<br>`direction: MAINTAIN`, `deltaLoad: null` | Schema cho phép nullable + Pydantic zero check | `valid-load-adjustment-bodyweight.json`<br>`test_valid_load_adjustment_bodyweight`<br>`deserialize_validLoadAdjustmentBodyweight` | Chưa xử lý UI phân biệt bài có tạ vs không tạ. |
| **Điều chỉnh số hiệp / số lần lặp** | `changeType: VOLUME_ADJUSTMENT`<br>`targetSets >= 1`, `targetRepsMin <= targetRepsMax`<br>`durationSeconds: null` | Schema check `targetSets >= 1` + Pydantic `validate_reps` | `valid-volume-adjustment-reps.json`<br>`test_valid_volume_adjustment_reps`<br>`deserialize_validVolumeAdjustmentReps` | Chưa nối với Progress Engine để đánh giá khối lượng tập luyện tuần. |
| **Điều chỉnh thời lượng bài tập isometric** | `changeType: VOLUME_ADJUSTMENT`<br>`durationSeconds >= 0`<br>`targetRepsMin: null`, `targetRepsMax: null` | Schema nullable reps + Pydantic `validate_volume_deltas` | `valid-volume-adjustment-duration.json`<br>`test_valid_volume_adjustment_duration`<br>`deserialize_validVolumeAdjustmentDuration` | Chưa tích hợp timer đếm ngược trên Mobile. |
| **Phát hiện delta mâu thuẫn với before/after** | `after.deltaLoad != after.targetLoad - before.targetLoad` | Pydantic model validator `validate_load_deltas` | `test_contradictory_delta_load_fails` | Chỉ phát hiện ở application layer; JSON schema kiểm tra kiểu và định dạng. |
| **Chặn sửa buổi tập đã bắt đầu/xong** | `PLANNED_WORKOUT_ADJUSTMENTS_SEALED` | Domain guard trong `WorkoutPlanCommandService:215` | Test `T_F03_05` trong ma trận kế hoạch | Runtime apply service chưa gọi domain command. |
| **Chặn học viên trong HUMAN_COACH** | HTTP 403 `AI_FORBIDDEN` | Auth policy trong `AiWorkoutAdjustmentService` | Test `T_F03_04` trong ma trận kế hoạch | Controller endpoint F03 chưa được khởi tạo. |

---

## 7. Các Thành Phần Đã Triển Khai Trong Gói Contract Đầu Tiên

1. **JSON Schema (Draft 2020-12):**
   [`contracts/ai-schemas/f03-workout-adjustment.schema.json`](../../contracts/ai-schemas/f03-workout-adjustment.schema.json).
2. **Pydantic Models (Python 3.12 / FastAPI):**
   [`services/ai-service/app/schemas/f03_schemas.py`](../../services/ai-service/app/schemas/f03_schemas.py).
3. **Java DTOs & Records (Java 21 / Spring Boot 4):**
   - [`F03WorkoutAdjustmentPayload.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/dto/F03WorkoutAdjustmentPayload.java)
   - [`WorkoutAdjustmentApiRequest.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/dto/WorkoutAdjustmentApiRequest.java)
   - [`WorkoutAdjustmentApiResponse.java`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/dto/WorkoutAdjustmentApiResponse.java)
4. **Bộ Fixtures Hợp Lệ & Không Hợp Lệ (10 fixtures):**
   [`contracts/ai-schemas/fixtures/f03/`](../../contracts/ai-schemas/fixtures/f03/).
5. **Cập nhật Runner Kiểm Tra Hợp Đồng:**
   [`tools/validate-ai-schemas.mjs`](../../tools/validate-ai-schemas.mjs) (Ajv 2020 kiểm chứng đạt 32/32 fixtures).
6. **Bộ Kiểm Thử Tự Động Đã Chạy & Đạt 100% PASS:**
   - Python Pytest: [`services/ai-service/tests/test_f03_schemas.py`](../../services/ai-service/tests/test_f03_schemas.py) (11 tests PASS).
   - Java Maven: [`services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/dto/F03ContractValidationUnitTest.java`](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/dto/F03ContractValidationUnitTest.java) (7 tests PASS).

---

## 8. Đề Xuất Gói Implementation Tiếp Theo & Kết Luận

- **Trạng thái kết thúc phiên:** Gói contract đầu tiên của F03 đã hoàn thành và được kiểm chứng nhất quán trên cả 3 lớp (JSON Schema, Python Pydantic, Java DTOs).
- **Phạm vi giữ nguyên:** Chưa triển khai orchestration service, chưa gọi LLM provider, chưa tạo giao diện Mobile, chưa commit/push Git.
- **Bước implementation tiếp theo:**
  Triển khai **Giai đoạn 2 (Backend Context Builder & Command Routing)**:
  1. Viết `F03ContextBuilder` để gom dữ liệu buổi tập đích, bài tập cần thay, lịch sử RPE gần nhất và thiết bị khả dụng.
  2. Viết `F03RuntimeOutputValidator` kiểm tra bài tập thay thế tồn tại và đang `ACTIVE` trong catalog.
  3. Cài đặt service định tuyến lệnh `adjustOccurrence` cho Minor change và `publishVersion` cho Significant change.
