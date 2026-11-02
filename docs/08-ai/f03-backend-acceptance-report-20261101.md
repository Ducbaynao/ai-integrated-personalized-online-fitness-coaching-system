# Báo Cáo Kiểm Chứng & Bàn Giao Backend F03 (P2-M4): Đề Xuất Điều Chỉnh Giáo Án Tập Luyện (AI Workout Adjustment Proposal)

> **Mã đầu việc:** P2-M4 / F03 Backend Slice & Verification
> **Repository:** `D:\Projects\ai-integrated-personalized-online-fitness-coaching-system`
> **Branch làm việc:** `feat/p2-m4-f03-contract-package-20261101`
> **Base HEAD:** `b471e25c024447ead5abc08783a8c044a7cfb021` (Merge PR #124)
> **Ngày thực hiện:** 2026-11-03 (phiên tiếp nối từ 2026-11-01)
> **Trạng thái:** HOÀN THÀNH TOÀN DIỆN KIỂM CHỨNG BACKEND F03 (INTEGRATION TESTS 15/15 PASS, UNIT TESTS 30/30 PASS, LIVE API E2E 5/5 PASS, SCHEMA 32/32 PASS, PYTHON 67/67 PASS)
> **Tổng chi phí AI phát sinh:** **$0.00** (100% sử dụng Simulated F03 Provider).

---

## 1. Tổng Quan Phạm Vi Triển Khai Backend F03

Tính năng F03 bổ sung năng lực cho hệ thống hỗ trợ điều chỉnh thích ứng giáo án tập luyện (Workout Adjustment Proposal):
- **Phạm vi hỗ trợ (Scopes):**
  1. `ONE_MATERIALIZED_OCCURRENCE`: Điều chỉnh một buổi tập đã lên lịch cụ thể (`planned_workout`).
  2. `TEMPLATE_OR_FUTURE_OCCURRENCES`: Điều chỉnh bản mẫu giáo án (`workout_plan_versions`) cho toàn bộ các buổi tập tương lai.
- **Loại hình điều chỉnh (Change Types):**
  1. `LOAD_ADJUSTMENT`: Điều chỉnh mức tạ (tăng/giảm) có bảo vệ an toàn dựa trên tín hiệu liên tục tập luyện (`allowLoadIncrease`).
  2. `VOLUME_ADJUSTMENT`: Điều chỉnh số hiệp (sets), số lần lặp (reps), thời gian thực hiện (duration).
  3. `EXERCISE_SUBSTITUTION`: Thay thế biến thể bài tập tương đương (ràng buộc cùng bài tập gốc hoặc canonical reference theo OD-3).
- **Ma trận thẩm quyền (Authority Matrix):**
  - Chế độ `SELF_DIRECTED`: Học viên (`STUDENT`) yêu cầu và trực tiếp quyết định Chấp thuận (`Accept`) hoặc Từ chối (`Reject`).
  - Chế độ `HUMAN_COACH`: Huấn luyện viên phụ trách (`TRAINER`) có quyền yêu cầu và phê duyệt; Học viên bị chặn 403 Forbidden nếu cố tình duyệt.
  - Quản trị viên (`ADMIN`): Là thẩm quyền nền tảng, không có thẩm quyền huấn luyện.
- **Ranh giới giao dịch & Tính nguyên tử (Atomic Accept-and-Apply):**
  - Khóa hàng bi quan (`SELECT ... FOR UPDATE`) trên bảng `fitness.ai_recommendations`.
  - Kiểm tra trạng thái hiện hành của mục tiêu (occurrence status `SCHEDULED`, aggregate version, revision).
  - Tái kiểm định danh mục bài tập (Re-validation) trước khi thực thi domain command.
  - Gọi domain command tương ứng (`adjustOccurrence` hoặc `publishVersion`) trong cùng transaction boundary.
  - Cập nhật trạng thái recommendation thành `APPLIED` và ghi nhận application receipt cùng audit log bất biến.
  - Không có lời gọi mạng AI nào nằm bên trong apply transaction.
- **Bảo vệ Idempotency Replay chặt chẽ:**
  - Đối soát bộ nhận diện: `commandKey` + `actorId` + `appliedRevision` + `expectedTargetVersion` + `decisionNote`.
  - Không coi các trường tùy chọn là wildcard: phân biệt rõ ràng giữa giá trị `null` và giá trị cụ thể.
  - Replay thành công trả về `replayed: true` mà không chèn trùng mutation hay domain receipts.
  - Replay vẫn bắt buộc kiểm tra tính hợp lệ và thẩm quyền hiện hành của tài khoản người gọi.

---

## 2. Kết Quả Kiểm Thử Tự Động (Automated Test Suites)

### 2.1. Full-Slice Integration Test (Spring Boot + PostgreSQL Testcontainers)
- **File:** [`AiWorkoutAdjustmentFullSliceIntegrationTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/AiWorkoutAdjustmentFullSliceIntegrationTest.java)
- **Hạ tầng:** Docker PostgreSQL `pgvector/pgvector:pg18`, 53 Flyway migrations.
- **Kết quả:** **15 / 15 TESTS PASS (0 Failures, 0 Errors, 0 Skipped)**
- **Chi tiết 15 kịch bản đã kiểm chứng:**
  1. `generateAndAcceptOccurrenceAdjustment_fullSlice`: Sinh proposal buổi tập, chấp thuận, áp dụng domain command, ghi application receipt và idempotent replay.
  2. `generateAndRejectAdjustment_fullSlice`: Sinh proposal, từ chối, chuyển sang `REJECTED`, chặn accept trễ.
  3. `humanCoachMode_studentCannotAcceptAdjustment_forbidden`: Học viên cố duyệt proposal của Huấn luyện viên bị ném 403 `AiRunAccessDeniedException`.
  4. `generateAndAcceptTemplateAdjustment_fullSlice`: Điều chỉnh template bản mẫu xuất bản phiên bản giáo án mới (`workout_plan_versions`), tăng `aggregateVersion`.
  5. `occurrenceStarted_cannotAdjust_lifecycleConflict`: Buổi tập đã hoàn thành (`COMPLETED`) bị từ chối với xung đột vòng đời ("only SCHEDULED occurrences can be adjusted").
  6. `staleOccurrenceVersion_cannotAdjust_lifecycleConflict`: Occurrence bị sửa đổi đồng thời làm lệch version dẫn đến 409 `LifecycleConflictException`.
  7. `replayWithDifferingFingerprint_idempotencyConflict`: Cùng `commandKey` nhưng thay đổi `decisionNote` bị từ chối với 409 Idempotency conflict.
  8. `replayAfterAuthorityRevoked_forbidden`: Tài khoản học viên bị đình chỉ (`SUSPENDED`) bị chặn 403 khi cố replay.
  9. `rejectRecommendation_doesNotMutateWorkout`: Từ chối proposal không làm thay đổi trạng thái, version hay chèn bản ghi vào domain workout.
  10. `concurrentDoubleAccept_onlyOneSucceeds_otherFailsWithConflict`: **(Mới)** 2 luồng đồng thời gọi Accept trên cùng recommendation qua `CountDownLatch`; đúng 1 luồng thành công (`APPLIED`), luồng còn lại nhận 409 conflict.
  11. `concurrentAcceptAndReject_raceResolvesCleanly`: **(Mới)** 1 luồng Accept và 1 luồng Reject đua nhau; kết thúc sạch sẽ ở đúng một trạng thái cuối cùng, không có dirty write hay rò rỉ receipt.
  12. `twoRecommendationsTargetingSameOccurrence_secondAcceptRejectedDueToStaleVersion`: **(Mới)** 2 recommendations độc lập nhắm vào cùng 1 version occurrence; proposal 1 accept thành công làm tăng version, proposal 2 sau đó accept bị chặn bởi stale version guard.
  13. `attemptedOccurrence_cannotAdjust_lifecycleConflict`: **(Mới)** Buổi tập có trạng thái `ATTEMPTED` (đã bắt đầu tập nhưng dở dang/chưa xong) bị từ chối điều chỉnh (bảo vệ execution-started/in-progress guard).
  14. `exerciseSubstitution_replacementDeactivatedBeforeAccept_rejectedByRevalidation`: **(Mới)** Biến thể bài tập thay thế bị Admin vô hiệu hóa (`is_active = false`) trước lúc duyệt; revalidation bắt giữ và ném `AiOutputValidationException` (`WORKOUT_PLAN_EXERCISE_UNAVAILABLE`), giữ nguyên recommendation ở `PENDING`.
  15. `replayWithDifferingExpectedTargetVersion_idempotencyConflict`: **(Mới)** Replay với cùng `commandKey` nhưng sai lệch `expectedTargetVersion` bị ném 409 Idempotency conflict.

### 2.2. Unit Test Suites (Spring Boot Backend)
- **`AiWorkoutAdjustmentServiceUnitTest`:** **11 / 11 PASS** (Bảo vệ actor active, role authority, transition context, idempotency fingerprints).
- **`F03ContractValidationUnitTest`:** **7 / 7 PASS** (Xác thực DTO Bean Validation).
- **`F03ContextBuilderUnitTest`:** **4 / 4 PASS** (Xây dựng context snapshot, tính toán an toàn `allowLoadIncrease`).
- **`F03RuntimeOutputValidatorUnitTest`:** **8 / 8 PASS** (Kiểm tra JSON schema, giới hạn tải, phân định biến thể OD-3).
- **Tổng cộng Unit Tests:** **30 / 30 PASS**.

### 2.3. Schema & AI Contracts
- **Runner:** `node tools/validate-ai-schemas.mjs`
- **Kết quả:** **7 schemas, 32 fixtures PASS** (bao gồm 10 fixtures cho F03).

### 2.4. Python AI Service Tests
- **Runner:** `pytest services/ai-service/tests`
- **Kết quả:** **67 / 67 PASS** (bao gồm `test_f03_schemas.py` và `test_f03_provider.py`).

---

## 3. Bằng Chứng Kiểm Chứng Live API End-to-End

Các bài kiểm chứng live được thực hiện trên kiến trúc mạng thực tế:
**Client HTTP** $\rightarrow$ **Spring Boot Backend (:8080)** $\rightarrow$ **FastAPI AI Service (:8000)** $\rightarrow$ **PostgreSQL Container (:5433 `digital_fitness`)**

### Bảng Kết Quả Đối Soát 5 Ca Thực Tế

| Ca | Kịch bản kiểm thử | Actor & Mode | Kết quả HTTP | Run Status & Recommendation | Đối soát Cơ sở dữ liệu (PostgreSQL) | Đánh giá |
| :---: | :--- | :--- | :---: | :---: | :--- | :---: |
| **A** | **SELF_DIRECTED Occurrence Adjustment**<br>Generate $\rightarrow$ Read $\rightarrow$ Accept $\rightarrow$ Replay | Student (`student.f02.runtime`)<br>Chế độ `SELF_DIRECTED` | `POST generate`: 200 OK<br>`GET detail`: 200 OK<br>`POST accept`: 200 OK<br>`POST replay`: 200 OK | `PENDING` $\rightarrow$ `APPLIED`<br>Replay: `replayed: true`<br>Rec ID: `d11b8613...` | Occurrence `f3000000...0001`:<br>- Version: $2 \rightarrow 3$<br>- Thêm 1 bản ghi `workout_session_adjustments`<br>- Thêm 1 bản ghi `ai_recommendation_applications`<br>- Replay: Không chèn trùng bản ghi | **PASS** |
| **B** | **HUMAN_COACH Authority Validation**<br>Trainer đề xuất $\rightarrow$ Student duyệt (chặn) $\rightarrow$ Trainer duyệt | Trainer (`smoke.trainer`) & Student (`smoke.student`)<br>Chế độ `HUMAN_COACH` | `POST generate`: 200 OK<br>`POST student accept`: **403 Forbidden**<br>`POST trainer accept`: 200 OK | Rec ID: `2ced2706...`<br>Decision Authority: `TRAINER`<br>Status: `APPLIED` | Occurrence `f3000000...0002`:<br>- Student bị từ chối 403 khi cố duyệt<br>- Trainer duyệt thành công<br>- Ghi nhận application receipt cho Trainer | **PASS** |
| **C** | **Template Scope Adjustment**<br>Sinh đề xuất template $\rightarrow$ Duyệt $\rightarrow$ Xuất bản Plan Version mới | Student (`student.f02.runtime`)<br>Chế độ `SELF_DIRECTED` | `POST generate`: 200 OK<br>`POST accept`: 200 OK | Rec ID: `238817e2...`<br>Status: `APPLIED`<br>Entity: `WORKOUT_PLAN`<br>Plan ID: `7844c7a5...` | Bảng `workout_plans` & `workout_plan_versions`:<br>- Plan aggregate version: $2 \rightarrow 3$<br>- Tạo version mới `2d645607...` với `effective_until IS NULL`<br>- Version cũ được đóng `effective_until = now()` | **PASS** |
| **D** | **Reject Proposal**<br>Sinh đề xuất $\rightarrow$ Từ chối $\rightarrow$ Xác minh zero mutation | Student (`student.f02.runtime`)<br>Chế độ `SELF_DIRECTED` | `POST generate`: 200 OK<br>`POST reject`: 200 OK | Rec ID: `6c2725da...`<br>Status: `REJECTED`<br>Reason lưu đầy đủ | Occurrence `f3000000...0001`:<br>- Version giữ nguyên không đổi<br>- 0 bản ghi adjustment được chèn<br>- 0 application receipt | **PASS** |
| **E** | **Target Version Conflict Guard**<br>Sinh đề xuất $\rightarrow$ Tác động làm lệch version mục tiêu $\rightarrow$ Duyệt | Student (`student.f02.runtime`)<br>Chế độ `SELF_DIRECTED` | `POST generate`: 200 OK<br>`POST accept`: **409 Conflict** | Rec ID: `98e0694f...`<br>Status: **`PENDING`** (giữ nguyên, rollback sạch) | Occurrence `f3000000...0001`:<br>- Accept bị từ chối 409 Stale Version<br>- Recommendation giữ nguyên trạng thái PENDING<br>- Không có cập nhật một phần | **PASS** |

### Nhật Ký Gọi Mạng FastAPI
Tiến trình nền FastAPI (`task-2553`) ghi nhận các request nội bộ qua route:
`POST /internal/v1/workout-adjustments/generate` $\rightarrow$ `200 OK`
Chứng minh luồng tích hợp HTTP thực sự giữa Spring Boot Backend và FastAPI AI Service.

---

## 4. Các Lỗi Thực Tế Đã Sửa Trong Phiên

1. **Lỗi Coercion kiểu dữ liệu Jackson 3 trên StringNode (`decimalValue()`):**
   - *Triệu chứng:* Khi chấp thuận điều chỉnh template (Case C), server trả về lỗi `500 JsonNodeException: 'StringNode' method decimalValue() cannot coerce value "17.5" to java.math.BigDecimal`.
   - *Nguyên nhân:* FastAPI / Pydantic serialize số thực Decimal thành JSON string trong một số cấu trúc. Phương thức `.decimalValue()` của Jackson 3 từ chối ép kiểu tự động từ `StringNode`.
   - *Khắc phục:* Thay thế các lời gọi `.decimalValue()` bằng `new BigDecimal(node.asText())` tại 3 vị trí trong [`AiWorkoutAdjustmentService.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiWorkoutAdjustmentService.java).
2. **Lỗi Idempotency Semantics đối với Optional Fields:**
   - *Triệu chứng:* Kiểm tra idempotency coi giá trị `null` như wildcard khiến replay không phát hiện được sự thay đổi của `expectedTargetVersion` hoặc `expectedRevision`.
   - *Khắc phục:* Sử dụng `java.util.Objects.equals(req.expectedTargetVersion(), savedTargetVer)` và kiểm tra chặt chẽ `revMatches = (req.expectedRevision() != null) ? (req.expectedRevision() == savedRev) : (savedRev == 1)`.
3. **Lỗi Mock Replacement Variation trong Simulated Client:**
   - *Triệu chứng:* `SimulatedF03AiServiceClient` và `simulated_f03_provider.py` sinh `UUID.randomUUID()` cho `replacementVariationId` khi thay thế bài tập, dẫn đến việc `F03RuntimeOutputValidator` từ chối ngay lập tức do biến thể không tồn tại trong danh mục.
   - *Khắc phục:* Cập nhật simulated providers trả về biến thể anh em hợp lệ trong catalog (`b0000000-0000-4000-8000-000000000002` hoặc `b0000000-0000-4000-8000-000000000001`), đồng thời thiết lập mối quan hệ cùng bài tập gốc trong test fixtures.

---

## 5. Các Khoảng Trống Còn Lại & Giới Hạn Nghiệm Thu

1. **Chính sách Chuyên môn Định lượng (D06):**
   - Các ngưỡng phần trăm tăng tải tối đa, số tuần tích lũy RPE yêu cầu vẫn đang sử dụng chính sách cơ sở định lượng chờ hội đồng chuyên môn D06 thẩm duyệt chính thức.
2. **Phạm vi Release F03 Milestone P2-M4:**
   - Phiên này **chỉ nghiệm thu gói Backend, Contracts và AI Service**.
   - **Chưa triển khai giao diện Mobile F03** (Mobile Client UI, modals, forms).
   - Chưa kích hoạt pilot hay môi trường production.

---

## 6. Trạng Thái Git & Khuyến Nghị Tiếp Theo

- **Branch:** `feat/p2-m4-f03-contract-package-20261101`
- **Working Tree:** Toàn bộ các thay đổi Backend F03, contracts, DTOs, services, tests và báo cáo nằm trong working tree, bảo toàn nguyên vẹn mọi thay đổi của người dùng.
- **Khuyến nghị:**
  - Gói Backend F03 đã **ĐỦ ĐIỀU KIỆN** để người dùng review và tạo Pull Request cho Backend Slice trước khi bắt đầu triển khai Mobile F03.
