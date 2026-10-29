# Báo cáo Rà soát Điều kiện Tiên quyết & Triển khai Lát cắt Toàn diện F02 (P2-M3)

**Dự án:** AI-Integrated Personalized Online Fitness Coaching System  
**Ngày thực hiện:** 2026-10-27  
**Branch hiện hành:** `feat/p2-m3-f02-workout-proposal-20261027`  
**Baseline commit:** `a0f2f2a` (Merge PR #113 on top of PR #114 `2857f4d`)  
**Phạm vi:** P2-M3 (F02: Đề xuất giáo án tập luyện 1 tuần - 1-Week Workout Plan Proposal)  
**Tài liệu phê duyệt căn cứ:**
- [Phase 2 Release Scope (D02, D03, F02)](../00-project-overview/phase-2-release-scope.md)
- [Phase 2 Feature Data and Authority Matrix (Mục 1.6 - 1.8, Mục 3)](phase-2-feature-data-authority-matrix.md)
- [Phase 2 Implementation Plan (P2-M3, T05 - T09)](../07-development/phase-2-implementation-plan.md)

---

## 1. Bảng Đối soát Điều kiện Tiên quyết và Khoảng cách Hiện trạng (Readiness & Gap Matrix)

| Yêu cầu F02 | Phần đã có | Bằng chứng code/test | Phần thiếu trước đợt này | Trạng thái sau đợt này |
| :--- | :--- | :--- | :--- | :--- |
| **1. Mô hình dữ liệu & Authority DB**<br>- Lưu recommendation (`status: PENDING`, payload jsonb)<br>- Phân quyền quyết định (`STUDENT` vs `TRAINER`)<br>- Guard DB chống can thiệp | - Bảng `fitness.ai_recommendations`<br>- Bảng `fitness.ai_recommendation_applications`<br>- Trigger `trg_validate_ai_recommendation_authority`<br>- Trigger `trg_validate_ai_recommendation_application` | - `V9__ai_and_knowledge.sql:160-189`<br>- `V16__domain_completeness_and_invariants.sql:368-383`<br>- `AiWorkoutProposalPersistenceIntegrationTest.java` | - Thiếu Entity/Record mapping trong Spring AI module<br>- Thiếu Repository port/adapter cho recommendation | **ĐÃ HOÀN TẤT**<br>- Tạo `AiRecommendation`, `AiRecommendationApplication`, `AiRecommendationStatus`, `AiDecisionAuthority`<br>- Triển khai `JdbcAiRecommendationRepository` với `findByIdForUpdate` và `saveApplication` |
| **2. Workout Domain Command & Persistence**<br>- Tạo draft plan (`createDraft`)<br>- Kích hoạt plan (`activate`)<br>- Idempotency receipts & versioning | - `WorkoutPlanCommandUseCase`<br>- `WorkoutPlanCommandService`<br>- `WorkoutPlanPersistencePort`<br>- Bảng `fitness.workout_plans`, `workout_plan_versions`, `workout_plan_sessions`, `planned_session_exercises` | - `WorkoutPlanCommandService.java:40-113`<br>- `WorkoutPlanPersistenceIntegrationTest.java`<br>- Khóa ngoại `workout_plan_versions.source_ai_recommendation_id` trong `V9:226` | Không thiếu trong Workout module | **ĐÃ TÍCH HỢP**<br>- Tích hợp gọi `createDraft` và `activate` trực tiếp từ transaction Accept-and-Apply |
| **3. Phân định quyền huấn luyện F02**<br>- `SELF_DIRECTED`: Student yêu cầu & Student quyết định<br>- `HUMAN_COACH`: Trainer yêu cầu cho học viên & Trainer quyết định (Student chỉ xem khi shared)<br>- Admin: Không có quyền huấn luyện | - `CurrentCoachingContextQuery`<br>- Trigger PostgreSQL chặn vi phạm quyền tại database-level | - `CurrentCoachingContextQuery.java`<br>- `V16:368-383` (raise exception if mismatch)<br>- `AiWorkoutProposalFullSliceIntegrationTest.java`<br>- `AiWorkoutProposalRealFastApiIntegrationTest.java` | Thiếu Service layer guardrail ném `AiRunAccessDeniedException` (403) trước DB trigger | **ĐÃ HOÀN TẤT & HÀN GẮN SÂU SẮC**<br>- Enforce quyền nghiêm ngặt tại thời điểm Accept & Reject:<br>  + Kiểm tra coaching context hiện hành<br>  + Kiểm tra chuyển dịch chế độ (chuyển sang HUMAN_COACH tước quyền tự quyết của Student)<br>  + Kiểm tra tính liên tục của Coaching Period<br>  + Chặn replay dữ liệu đối với actor đã bị thu hồi quyền |
| **4. AI Service Endpoint & Provider**<br>- Internal endpoint sinh đề xuất giáo án 1 tuần<br>- Output cấu trúc đúng định dạng (plan, sessions, prescriptions, usedData, missingData)<br>- Hỗ trợ lựa chọn 3 vs 4 ngày/tuần | - FastAPI application structure<br>- Auth guard bằng `x-internal-service-key`<br>- Settings & logging | - `services/ai-service/app/main.py`<br>- `services/ai-service/app/core/security.py`<br>- `services/ai-service/tests/test_f02_provider.py` | - Thiếu route `/internal/v1/workout-proposals/generate`<br>- Thiếu `SimulatedF02WorkoutProvider`<br>- Thiếu xử lý lịch 4 ngày khi người dùng yêu cầu | **ĐÃ HOÀN TẤT**<br>- Tạo `f02_schemas.py`<br>- Tạo `SimulatedF02WorkoutProvider` ($0.00 spend) hỗ trợ linh hoạt 3 buổi hoặc 4 buổi/tuần<br>- Tạo router `internal_workout_proposal.py`<br>- 3/3 tests pass |
| **5. Runtime Validation**<br>- Kiểm tra cấu trúc JSON schema<br>- Đối soát `exerciseVariationId` tồn tại và ACTIVE<br>- Kiểm tra giới hạn an toàn (sets, reps, restSeconds, duration) | - `ExerciseReferenceQuery` trong exercise/workout module<br>- Ajv schema validator trong tools | - `ExerciseReferenceQuery.java`<br>- `tools/validate-ai-schemas.mjs`<br>- `F02RuntimeOutputValidatorUnitTest.java` | - Thiếu `contracts/ai-schemas/f02-workout-proposal.schema.json`<br>- Thiếu validator component trong Spring AI module | **ĐÃ HOÀN TẤT**<br>- Định nghĩa JSON Schema Draft 2020-12<br>- Tạo `F02RuntimeOutputValidator` kiểm tra qua `ExerciseReferenceQuery`<br>- 4/4 tests pass |
| **6. Atomic Accept-and-Apply Transaction, Concurrency & Rollback**<br>- Khóa hàng `ai_recommendations` (`SELECT ... FOR UPDATE`)<br>- Concurrency double-accept: tạo đúng 1 plan nghiệp vụ<br>- Rollback toàn vẹn khi domain command/audit thất bại<br>- Idempotency replay khớp payload trả receipt; khác payload trả 409 | - Cấu trúc transaction Spring `@Transactional`<br>- Cơ chế idempotency receipt trong Workout domain | - `AiWorkoutProposalService.java`<br>- `AiWorkoutProposalFullSliceIntegrationTest.java`<br>- `AiWorkoutProposalRealFastApiIntegrationTest.java` | - Chưa kiểm chứng luồng tranh chấp đồng thời (concurrency race)<br>- Chưa kiểm chứng rollback khi có lỗi xung đột plan hoặc quyền hạn | **ĐÃ HOÀN TẤT & HÀN GẮN ĐẦY ĐỦ**<br>- Khóa `findByIdForUpdate` đảm bảo 2 request accept đồng thời chỉ tạo đúng 1 kế hoạch nghiệp vụ (luồng 2 nhận `replayed: true`)<br>- Rollback toàn vẹn khi tồn tại active plan (`ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS`), giữ recommendation ở `PENDING`<br>- Đối soát actorId và decisionNote khớp tuyệt đối khi cùng commandKey (khác tham số trả 409)<br>- Chặn replay đối với caller đã mất quyền |
| **7. API & Client Contracts**<br>- `POST /ai/workout-proposals`<br>- `GET /ai/recommendations/{id}`<br>- `POST /ai/recommendations/{id}/accept`<br>- `POST /ai/recommendations/{id}/reject` | - Cấu trúc OpenAPI 3.0 trong `contracts/openapi/openapi.yaml` | - `contracts/openapi/openapi.yaml`<br>- `AiWorkoutProposalControllerUnitTest.java` | Thiếu định nghĩa path và schema của 4 endpoints trên trong OpenAPI | **ĐÃ HOÀN TẤT**<br>- Bổ sung đầy đủ 4 endpoints và component schemas vào `openapi.yaml`<br>- `npm run validate:contracts` Redocly 0 lỗi |
| **8. Mobile UI Slice & Consistency**<br>- Form tùy chọn 3 vs 4 ngày/tuần<br>- Ghi chú ưu tiên: cảnh báo không thay thế dữ liệu y tế<br>- Độ tự tin mô hình: ghi rõ tự đánh giá, chưa hiệu chuẩn lâm sàng<br>- Nút chấp thuận/từ chối phân quyền theo vai trò | - Màn hình `StudentWorkoutPlansScreen.tsx`<br>- Màn hình `TrainerProgramScreen.tsx`<br>- Design tokens semantic colors | - `apps/mobile/src/features/workout-plan/components/AiWorkoutProposalModal.tsx`<br>- `apps/mobile/src/__tests__/AiWorkoutProposalModal.test.tsx` | - Chưa có modal đề xuất AI F02 trên Mobile<br>- Cần gắn nhãn cảnh báo phi y tế và tự đánh giá độ tin cậy | **ĐÃ HOÀN TẤT**<br>- Tạo `types/aiWorkoutProposal.ts`<br>- Tạo `services/aiWorkoutProposalApi.ts`<br>- Tạo component `AiWorkoutProposalModal.tsx` với disclaimer y tế rõ ràng và nhãn tự đánh giá độ tin cậy<br>- Gắn entry points cho Student & Trainer<br>- 30/30 tests pass |

---

## 2. Chi tiết Lát cắt Triển khai Toàn diện F02

### 2.1. Hợp đồng & Schema (Contracts)
- **JSON Schema:** `contracts/ai-schemas/f02-workout-proposal.schema.json`
  - Đảm bảo tuân thủ Draft 2020-12.
  - Bắt buộc `recommendationType = "WORKOUT_PLAN_PROPOSAL"`.
  - Giới hạn `durationWeeks = 1` theo quyết định phê duyệt D02.
  - Cấu trúc đầy đủ các buổi tập (`sessions`) và danh mục bài tập (`prescriptions`).
- **OpenAPI 3.0 Contract:** `contracts/openapi/openapi.yaml`
  - `POST /ai/workout-proposals`: Sinh đề xuất giáo án 1 tuần ở trạng thái `PENDING`.
  - `GET /ai/recommendations/{id}`: Xem chi tiết recommendation kèm payload cấu trúc.
  - `POST /ai/recommendations/{id}/accept`: Atomic Accept-and-Apply chuyển sang `APPLIED` và kích hoạt giáo án.
  - `POST /ai/recommendations/{id}/reject`: Từ chối proposal chuyển sang `REJECTED`.
  - Các schemas được khai báo đầy đủ trong `components/schemas`.
- **Fixtures & Công cụ kiểm chứng:**
  - `contracts/ai-schemas/fixtures/f02/valid-workout-proposal.json`
  - `contracts/ai-schemas/fixtures/f02/invalid-workout-proposal-duration-weeks.json`
  - Chạy tự động qua `npm run validate:contracts` (Redocly 0 error, Ajv 6 schemas + 22 fixtures).

### 2.2. Dịch vụ AI (FastAPI / Python)
- `services/ai-service/app/schemas/f02_schemas.py`: Pydantic models `F02WorkoutProposalRequest`, `F02WorkoutProposalResponse`, `WorkoutPlanPayloadSchema`.
- `services/ai-service/app/integrations/simulated_f02_provider.py`:
  - Deterministic mock provider trả về giáo án 1 tuần hợp lệ:
    + 3 buổi/tuần: Day 1 Upper Strength, Day 2 Lower Foundations, Day 3 Conditioning.
    + 4 buổi/tuần: Day 1 Upper Push, Day 2 Lower Foundations, Day 3 Upper Pull, Day 4 Lower & Conditioning.
  - Không gọi OpenAI trả phí, chi phí **$0.00**.
- `services/ai-service/app/api/routes/internal_workout_proposal.py`: Internal route `POST /internal/v1/workout-proposals/generate` bảo vệ bởi `X-Internal-Service-Key`.
- `services/ai-service/tests/test_f02_provider.py`: 3/3 tests pass (bao gồm kiểm thử chọn lịch 4 ngày).

### 2.3. Spring Boot Backend & Database Hardening
- **Domain:**
  - `AiRecommendation`, `AiRecommendationApplication`, `AiRecommendationStatus`, `AiDecisionAuthority`.
- **Persistence:**
  - `AiRecommendationRepository` & `JdbcAiRecommendationRepository` (`save`, `findById`, `findByIdForUpdate`, `updateDecision`, `saveApplication`, `findApplicationByRecommendationId`).
- **Context Builder & Client:**
  - `F02ContextBuilder`: Tổng hợp context từ `CurrentCoachingContextQuery`, `GetStudentProfileUseCase`, `GetCurrentFitnessGoalUseCase`.
  - `F02AiServiceClient`: Giao diện port gọi AI.
  - `HttpF02AiServiceClient`: Client HTTP giao tiếp qua REST tới FastAPI service.
  - `SimulatedF02AiServiceClient`: Client mô phỏng trong tiến trình phục vụ unit test nhanh.
- **Validation:**
  - `F02RuntimeOutputValidator`: Kiểm tra format JSON, D02 scope (`durationWeeks == 1`), sets/reps bounds, và khóa kiểm tra tồn tại của variation IDs qua `ExerciseReferenceQuery.lockAuthoringReferences`.
- **Nghiệp vụ Service (AiWorkoutProposalService):**
  - **generateProposal**: Kiểm tra quyền khởi tạo (`SELF_DIRECTED` -> Student, `HUMAN_COACH` -> Trainer), ghi nhận `AiRun`, gọi provider, validate output qua validator, lưu `AiRecommendation` ở trạng thái `PENDING`, ghi nhận audit log.
  - **acceptRecommendation**: Thực thi hoàn toàn trong transaction duy nhất `@Transactional`:
    1. Khóa bi-directional `AiRecommendation` (`FOR UPDATE`).
    2. Xác minh quyền của actor đối với chế độ huấn luyện hiện hành (chặn trường hợp chuyển từ `SELF_DIRECTED` sang `HUMAN_COACH` trước khi bấm chấp thuận).
    3. Kiểm tra tính liên tục của `coachingPeriodId` (chặn áp dụng đề xuất cũ vào period mới).
    4. Kiểm tra Idempotent Replay: kiểm tra quyền trước khi trả receipt; đối soát cả `commandKey`, `actorId`, và `decisionNote`. Nếu trùng `commandKey` nhưng khác tham số -> ném 409 `IDEMPOTENCY_CONFLICT`.
    5. Kiểm tra hạn sử dụng `expires_at` (quá 7 ngày ném 409 conflict).
    6. Parse payload thành `WorkoutSessionTemplate`s.
    7. Gọi `WorkoutPlanCommandUseCase.createDraft`.
    8. Gọi `WorkoutPlanCommandUseCase.activate`. Nếu đã tồn tại kế hoạch active -> ném `WorkoutPlanFailure` (`ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS`) và toàn bộ transaction rollback sạch sẽ.
    9. Cập nhật trạng thái recommendation sang `APPLIED`.
    10. Lưu bản ghi `AiRecommendationApplication` chứa đầy đủ metadata đối soát.
    11. Ghi nhận audit log JSON hợp lệ `AI_RECOMMENDATION_APPLIED`.
  - **rejectRecommendation**: Chuyển trạng thái sang `REJECTED`, ghi note và audit log JSON hợp lệ `AI_RECOMMENDATION_REJECTED`.
- **REST Controller:**
  - `AiWorkoutProposalController`: Xử lý 4 REST endpoints (`/api/v1/ai` và `/ai`), tích hợp `@ExceptionHandler(WorkoutPlanFailure.class)` để trả mã lỗi 409 và thông điệp chuẩn xác khi có xung đột nghiệp vụ Workout.

### 2.4. React Native Expo Mobile Client
- **Types & Service:**
  - `apps/mobile/src/types/aiWorkoutProposal.ts`: Khai báo đầy đủ các kiểu dữ liệu tương thích backend và OpenAPI.
  - `apps/mobile/src/services/aiWorkoutProposalApi.ts`: Triển khai các hàm gọi API kèm bộ ánh xạ thông điệp lỗi trực quan (`getWorkoutProposalErrorMessage`) cho các trường hợp: đã có kế hoạch active, đề xuất hết hạn, đã được áp dụng, xung đột thao tác, không có thẩm quyền.
- **Component & Tích hợp:**
  - `apps/mobile/src/features/workout-plan/components/AiWorkoutProposalModal.tsx`:
    + Form tùy chọn số ngày (3 hoặc 4 buổi) và ghi chú ưu tiên.
    + Ghi chú kèm disclaimer: "Văn bản tự do chỉ mang tính tham khảo sở thích cá nhân, không thay thế dữ liệu y tế hoặc chẩn đoán chấn thương đã xác thực."
    + Hiển thị độ tự tin từ mô hình: "Độ tự tin từ mô hình (tự đánh giá, chưa qua kiểm chứng lâm sàng): XX%" tránh ngộ nhận là xác suất lâm sàng đã hiệu chuẩn.
    + Xem chi tiết giáo án 1 tuần, căn cứ đề xuất (`reasoning`), dữ liệu còn thiếu (`missingData`), giới hạn (`limitations`), danh sách buổi tập & bài tập.
    + Phân quyền nút "Chấp thuận & Áp dụng" theo vai trò học viên / huấn luyện viên (backend enforce nghiêm ngặt độc lập với UI).
    + Banner thông báo lỗi, trạng thái thành công kèm ID kế hoạch.
  - Tích hợp vào `StudentWorkoutPlansScreen.tsx` (nút "Đề xuất giáo án 1 tuần (AI)").
  - Tích hợp vào `TrainerProgramScreen.tsx` (nút "Đề xuất giáo án 1 tuần (AI)" khi huấn luyện viên có quyền quản lý chương trình).

---

## 3. Kết quả Kiểm chứng Tự động Đa Tầng (Multi-Tier Automated Verification)

### 3.1. Hợp đồng & Schemas
- Lệnh: `npm run validate:contracts`
- Kết quả: **PASS**
  - Redocly OpenAPI lint: 0 lỗi.
  - Ajv Draft 2020-12: 6 schemas, 22 contract fixtures hợp lệ.

### 3.2. AI Service (FastAPI)
- Lệnh: `services/ai-service/.venv/Scripts/python.exe -m pytest services/ai-service/tests/test_f02_provider.py`
- Kết quả: **3/3 PASS**
  - `test_simulated_f02_workout_provider_returns_valid_proposal`: PASS
  - `test_simulated_f02_workout_provider_handles_four_days_selection`: PASS
  - `test_internal_workout_proposal_endpoint_requires_auth`: PASS

### 3.3. Spring Boot Backend Tests (PostgreSQL Testcontainers)
Toàn bộ 36 ca kiểm thử backend đều chạy thành công trên PostgreSQL Testcontainers với Flyway migrations:

| Test Class | Số ca | Kết quả | Chi tiết kiểm chứng |
| :--- | :---: | :---: | :--- |
| `AiWorkoutProposalRealFastApiIntegrationTest` | 4 | **PASS (4/4)** | **Đa tầng thực tế (Real HTTP)**:<br>1. Client -> Spring -> `HttpF02AiServiceClient` -> Real Uvicorn FastAPI Process -> Validator -> DB -> Accept -> Workout Plan ACTIVE aggregateVersion=1.<br>2. Form chọn 4 ngày -> FastAPI sinh 4 buổi -> Database lưu đúng 4 bản ghi `workout_plan_sessions`.<br>3. `HUMAN_COACH`: Trainer sinh đề xuất -> Student bị chặn 403 -> Trainer chấp thuận thành công APPLIED.<br>4. Rejection: Student từ chối đề xuất -> chuyển REJECTED -> không tạo bất kỳ plan nào trong DB. |
| `AiWorkoutProposalFullSliceIntegrationTest` | 11 | **PASS (11/11)** | 1. `SELF_DIRECTED`: Tạo proposal -> PENDING -> Accept -> APPLIED -> Workout Plan ACTIVE aggregateVersion=1.<br>2. `HUMAN_COACH`: Phân quyền chấp thuận thuộc về huấn luyện viên phụ trách.<br>3. `Reject`: Từ chối -> REJECTED -> không tạo plan.<br>4. `Idempotency`: Replay cùng key & payload trả receipt; khác payload trả 409 conflict; key mới trên proposal đã áp dụng trả 409.<br>5. `Expired`: Đề xuất quá hạn 7 ngày ném 409 conflict.<br>6. `Active Plan Conflict`: Đã có active plan -> ném `ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS` và rollback toàn vẹn (proposal giữ PENDING, không lưu application).<br>7. **Concurrent Double-Accept (Race Condition)**: 2 luồng đồng thời gọi accept cùng commandKey -> Thread 1 thực thi tạo plan (`replayed=false`), Thread 2 chờ khóa và replay (`replayed=true`) -> chỉ tạo đúng 1 plan trong DB.<br>8. **Period Transition**: Coaching period chuyển giao trước khi accept -> ném `AiRunLifecycleConflictException`.<br>9. **Mode Transition**: Đổi chế độ từ SELF_DIRECTED sang HUMAN_COACH -> student bị chặn thẩm quyền.<br>10. **Revoked Authority Replay**: Actor đã bị tước quyền không thể replay dữ liệu đã áp dụng trước đó.<br>11. **Audit Failure Rollback**: Giả lập lỗi audit `AI_RECOMMENDATION_APPLIED` qua trigger PostgreSQL -> toàn bộ transaction rollback hoàn toàn (không tạo plan, recommendation vẫn PENDING, không có application receipt). |
| `AiWorkoutProposalPersistenceIntegrationTest` | 4 | **PASS (4/4)** | 1. Lưu & đọc PENDING.<br>2. Cập nhật APPLIED & lưu Application.<br>3. Trigger DB chặn sai lệch quyền quyết định.<br>4. Trigger DB chặn EVALUATION run tạo recommendation nghiệp vụ. |
| `AiWorkoutProposalServiceUnitTest` | 9 | **PASS (9/9)** | Quyền quyết định Student/Trainer, chặn 403, validation reject, replay trùng key, conflict khác payload, reject reason, ngoại lệ khi audit ghi log thất bại. |
| `AiWorkoutProposalControllerUnitTest` | 4 | **PASS (4/4)** | MockMvc 4 endpoints: POST workout-proposals, GET detail, POST accept, POST reject. |
| `F02RuntimeOutputValidatorUnitTest` | 4 | **PASS (4/4)** | Schema hợp lệ, kiểm tra catalog qua `lockAuthoringReferences`, từ chối >1 tuần (D02), xử lý JSON hỏng. |
| **Tổng cộng Backend** | **36** | **PASS (36/36)** | **100% PASS** |

### 3.4. React Native Mobile Tests (Jest / TypeScript)
- Lệnh kiểm tra kiểu: `npm --prefix apps/mobile run typecheck`
  - Kết quả: **0 lỗi** (`tsc --noEmit` exit code 0).
- Lệnh chạy test:
  `npm --prefix apps/mobile test -- --watchAll=false src/__tests__/AiWorkoutProposalModal.test.tsx src/__tests__/aiWorkoutProposalApi.test.ts src/__tests__/studentWorkoutPlanScreens.test.tsx`

| Test Suite | Số ca | Kết quả | Nội dung kiểm chứng |
| :--- | :---: | :---: | :--- |
| `aiWorkoutProposalApi.test.ts` | 11 | **PASS (11/11)** | Gọi đúng các endpoint API, ánh xạ chuẩn xác các mã lỗi (conflict active plan, expired, replay mismatch, 403, 404, network error). |
| `AiWorkoutProposalModal.test.tsx` | 4 | **PASS (4/4)** | Hiển thị form tùy chọn ngày tập/ghi chú (kèm cảnh báo phi y tế và tự đánh giá độ tin cậy), sinh đề xuất và hiển thị chi tiết (căn cứ, dữ liệu thiếu, giới hạn, buổi tập), chấp thuận & áp dụng gọi callback, từ chối đề xuất, hiển thị banner lỗi khi xung đột. |
| `studentWorkoutPlanScreens.test.tsx` | 15 | **PASS (15/15)** | Toàn bộ các luồng màn hình học viên kế thừa đều an toàn, không hồi quy khi tích hợp nút Đề xuất AI. |
| **Tổng cộng Mobile** | **30** | **PASS (30/30)** | **100% PASS** |

### 3.5. Đối Soát Trạng Thái Kiểm Chứng Giao Diện Thực Tế Trên Thiết Bị (Mobile Runtime Status)

#### 3.5.1. Trạng thái tại thời điểm nghiệm thu lát cắt ban đầu (2026-10-27, commit `a0f2f2a`)
- **Trạng thái ghi nhận:** `NOT RUN / BLOCKED (Headless Environment Limit)`.
- **Lý do:** Tại thời điểm hoàn thiện code ban đầu, môi trường thực thi là máy chủ dòng lệnh chưa khởi chạy GUI giả lập Android Emulator / Expo Go. Luồng UI khi đó được kiểm chứng thông qua 30 test cases rendering component và 4 ca HTTP đa tầng.

#### 3.5.2. Phiên diễn tập Mobile Runtime bổ sung (Thực hiện trong quá trình hoàn thiện PR #116 trên baseline commit `7954b4e` / `d8d8062`)
- **Môi trường & Kiến trúc kết nối:** Android Emulator (`Pixel_8` AVD, API 34) kết nối với toàn bộ local services:
  $$	ext{Mobile App (:8081)} \longrightarrow 	ext{Spring Boot (:8080)} \longrightarrow 	ext{FastAPI (:8000)} \longrightarrow 	ext{PostgreSQL (:5433)}$$
- **Đối tượng tham gia & Bối cảnh:**
  - Huấn luyện viên: `Coach Alex Smoke` (`1cb982ef-b7e6-493c-86f9-fe4994f8ad93`)
  - Học viên: `Smoke Student` (`5c6f49c6-f4f6-4941-b217-2574b3830395`)
  - Kỳ huấn luyện: `bdeccc87-beaf-43df-b475-807be044ed0b` (Chế độ `HUMAN_COACH`)
  - Kế hoạch tạo mới & kích hoạt: Plan ID `bb2a6813-6fae-400a-bfbf-52938143cb74`
- **Kết quả kiểm chứng:** **PASS (Trong phạm vi được chứng minh: Trainer / HUMAN_COACH, cấu hình 4 buổi/tuần, Accept-and-Apply tạo Workout Plan ACTIVE)**.
- **Bằng chứng Artifacts (Đã đối soát và lưu trữ tại `docs/08-ai/artifacts/f02-mobile-runtime-validation/`):**
  1. [`mobile_f02_01_trainer_program_screen.png`](./artifacts/f02-mobile-runtime-validation/mobile_f02_01_trainer_program_screen.png): Màn hình Quản lý chương trình của Trainer với học viên Smoke Student, hiển thị nút "Đề xuất giáo án AI (F02)".
  2. [`mobile_f02_02_modal_open.png`](./artifacts/f02-mobile-runtime-validation/mobile_f02_02_modal_open.png): Mở modal F02, chọn cấu hình 4 buổi/tuần, hiển thị ghi chú mục tiêu và khuyến cáo phi y tế.
  3. [`mobile_f02_03_proposal_generated.png`](./artifacts/f02-mobile-runtime-validation/mobile_f02_03_proposal_generated.png): Đề xuất được sinh thành công (trạng thái `PENDING`), hiển thị nhãn độ tin cậy tự đánh giá từ mô hình, căn cứ đề xuất, dữ liệu thiếu và giới hạn.
  4. [`mobile_f02_04_sessions_and_actions.png`](./artifacts/f02-mobile-runtime-validation/mobile_f02_04_sessions_and_actions.png): Cuộn xem chi tiết 4 buổi tập với bài tập/prescriptions và các nút "Từ chối" / "Duyệt & Áp dụng giáo án".
  5. [`mobile_f02_05_plan_applied_active.png`](./artifacts/f02-mobile-runtime-validation/mobile_f02_05_plan_applied_active.png): Bấm duyệt thành công, transaction Accept-and-Apply kích hoạt giáo án `bb2a6813-6fae-400a-bfbf-52938143cb74` và ứng dụng tự động điều hướng sang màn hình xem giáo án đang hoạt động (`ACTIVE`).
  6. [`mobile_f02_06_trainer_program_with_active_plan.png`](./artifacts/f02-mobile-runtime-validation/mobile_f02_06_trainer_program_with_active_plan.png): Màn hình Trainer Program cập nhật hiển thị giáo án vừa áp dụng trong danh mục "Giáo án đang áp dụng".
  7. [`mobile_f02_07_applied_receipt_modal.png`](./artifacts/f02-mobile-runtime-validation/mobile_f02_07_applied_receipt_modal.png): Mở lại modal trên recommendation này, hiển thị badge "Trạng thái: APPLIED" kèm mã biên nhận kế hoạch.

#### 3.5.3. Ranh giới Phân định Kỹ thuật & Giới hạn Kết luận (Boundary Constraints)
1. **Mở lại Modal vs. Idempotent Command Replay ở Backend:** Việc mở lại modal và hiển thị badge `APPLIED` là do client truy vấn lại trạng thái hiện hành từ server; đây **không** phải là bằng chứng trực tiếp chứng minh cơ chế replay idempotent của command. Khả năng replay idempotent (gửi lại command với cùng `idempotencyKey` nhận lại biên nhận mà không tạo trùng kế hoạch) và xử lý tranh chấp song song (2 request accept đồng thời) được bảo đảm và chứng minh độc lập qua các ca kiểm thử tích hợp chuyên biệt trong `AiWorkoutProposalFullSliceIntegrationTest` (bên cạnh các ca kiểm tra thẩm quyền theo chế độ, chuyển dịch kỳ huấn luyện và transactional rollback khi audit thất bại).
2. **Dữ liệu phân quyền và Exercise Catalog:** Quyền `WORKOUT_PLAN:MANAGE` và 4 biến thể bài tập (`a0000000-0000-4000-8000-000000000001` .. `0004`) được nạp vào cơ sở dữ liệu dưới dạng **test fixtures** nhằm phục vụ diễn tập; đây **không** phải là bằng chứng cho thấy danh mục bài tập thực tế đã sẵn sàng trên môi trường sản xuất. Trước khi nạp fixtures, hệ thống đã chặn thành công đề xuất với lỗi `AiOutputValidationException: WORKOUT_PLAN_EXERCISE_UNAVAILABLE`, chứng minh output validator hoạt động nghiêm ngặt.
3. **Giới hạn không suy rộng:** Kết quả đạt được chỉ chứng minh tính đúng đắn cho ca tương tác: Trainer/HUMAN_COACH, đề xuất 4 buổi, Accept-and-Apply. Tuyệt đối **không suy rộng** sang:
   - Học viên tự quản (`Student / SELF_DIRECTED`) trên Mobile runtime thực tế.
   - Luồng thao tác Từ chối (`REJECT`) hoặc xử lý xung đột (`CONFLICT`) trên Mobile runtime thực tế.
   - Provider AI thật (phiên chạy sử dụng `SimulatedF02WorkoutProvider` với chi phí $0.00).
   - Mức độ sẵn sàng phát hành production.
4. **Phạm vi còn lại của P2-M3:** Lát cắt F02 hiện tại tập trung vào chu trình trọn vẹn từ đề xuất đến phê duyệt nguyên khối. Chức năng chỉnh sửa từng bài tập/sets/reps ngay trên bản thảo trước khi duyệt, và nghiệp vụ lưu trữ/thay thế giáo án đang hoạt động (`ACTIVE`) phức tạp thuộc về các lát cắt tiếp theo của Milestone P2-M3. Báo cáo này **không tuyên bố hoàn tất toàn bộ Milestone P2-M3**.

---

## 4. Kiểm soát Chi phí & An toàn Git

1. **Chi phí AI:**
   - Sử dụng `SimulatedF02WorkoutProvider` trên FastAPI và PostgreSQL Testcontainers fixtures.
   - Số tiền đã chi: **0.00 USD** (Hoàn toàn không gọi API trả phí bên ngoài).

2. **Tuân thủ Git:**
   - Thực hiện trên branch: `feat/p2-m3-f02-workout-proposal-20261027`.
   - Baseline đối chiếu: commit `a0f2f2a`.
   - Không commit, push, create PR, merge, hoặc chuyển branch.
   - Toàn bộ thay đổi nằm trong working tree để người dùng rà soát.

---

## 5. Dự thảo Thông điệp Commit & Mô tả PR (Đề xuất)

### Commit Message Đề xuất:
```text
feat(ai,workout,mobile): implement F02 1-week workout plan proposal slice

- Add OpenAPI schemas and contracts for /ai/workout-proposals and recommendation accept/reject
- Add JSON Schema Draft 2020-12 for 1-week workout proposal and Ajv validation
- Implement FastAPI internal route /internal/v1/workout-proposals/generate with SimulatedF02WorkoutProvider (3/4 days split, $0.00 spend)
- Implement Spring Boot HttpF02AiServiceClient and F02RuntimeOutputValidator
- Implement atomic Accept-and-Apply transaction with findByIdForUpdate, authority guards, mode transition checks, and idempotency replay
- Add React Native Expo AiWorkoutProposalModal with 3/4 days selection, medical disclaimers, self-assessment confidence labels, and error banner
- Add 36 backend unit/integration tests (including real FastAPI process multi-tier tests, concurrency race tests, and transactional audit failure rollback) and 30 mobile tests
```

### Nội dung PR Description Đề xuất:
```markdown
## Summary
Triển khai hoàn chỉnh lát cắt F02 (Đề xuất giáo án tập luyện 1 tuần - P2-M3) xuyên suốt các tầng kiến trúc: OpenAPI Contract -> AI Schema -> FastAPI AI Service -> Spring Boot Backend -> PostgreSQL Domain Store -> React Native Expo Mobile Client.

## Key Changes
1. **Contracts & Schemas:**
   - Bổ sung 4 endpoints `/ai/workout-proposals`, `/ai/recommendations/{id}`, `/accept`, `/reject` trong `openapi.yaml`.
   - Định nghĩa JSON schema Draft 2020-12 (`f02-workout-proposal.schema.json`) giới hạn nghiêm ngặt `durationWeeks = 1` theo D02.
2. **AI Service (FastAPI):**
   - Triển khai internal endpoint `/internal/v1/workout-proposals/generate` bảo vệ bằng `X-Internal-Service-Key`.
   - Cung cấp `SimulatedF02WorkoutProvider` hỗ trợ linh hoạt cấu hình 3 hoặc 4 buổi tập/tuần với chi phí $0.00.
3. **Spring Boot Backend:**
   - Triển khai `AiWorkoutProposalService` với transaction nguyên tử Accept-and-Apply (`findByIdForUpdate`).
   - Kiểm soát chặt chẽ thẩm quyền quyết định: `SELF_DIRECTED` thuộc về Student, `HUMAN_COACH` thuộc về Trainer phụ trách.
   - Chặn các lỗ hổng chuyển dịch chế độ (mode transition), kết thúc kỳ huấn luyện (coaching period transition), và thu hồi quyền replay.
   - Xử lý xung đột kế hoạch active (`ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS`) trả 409 và rollback toàn vẹn.
4. **Mobile Client:**
   - Thêm modal `AiWorkoutProposalModal.tsx` trên cả Student và Trainer screens.
   - Cung cấp cảnh báo phi y tế cho trường văn bản tự do và gắn nhãn "Độ tự tin từ mô hình (tự đánh giá, chưa qua kiểm chứng lâm sàng)".
5. **Kiểm chứng:**
   - 36/36 Backend tests PASS (bao gồm `AiWorkoutProposalRealFastApiIntegrationTest` chạy qua HTTP tới tiến trình FastAPI Uvicorn thật và rollback khi audit thất bại).
   - 30/30 Mobile tests PASS và 0 lỗi TypeScript.
   - Không phát sinh chi phí OpenAI ($0.00 spend).
```
