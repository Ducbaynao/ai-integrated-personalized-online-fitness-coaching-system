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
| **9. Chỉnh sửa bản thảo trước khi duyệt (Draft Proposal Customization & Provenance)**<br>- Cho phép actor thẩm quyền sửa draft khi PENDING<br>- Lưu version/revision, last_modified_by, is_customized<br>- Bảo toàn original_payload từ AI<br>- Optimistic locking qua expectedRevision<br>- Tái kiểm định bài tập/giới hạn khi Apply | - Bảng `fitness.ai_recommendations`<br>- `F02RuntimeOutputValidator`<br>- `WorkoutPlanCommandUseCase` | - `V51__ai_recommendation_draft_revisions.sql`<br>- `PUT /ai/recommendations/{id}/draft`<br>- `AiWorkoutProposalFullSliceIntegrationTest.java`<br>- `AiWorkoutProposalModal.test.tsx` | - Chưa có endpoint chỉnh draft F02<br>- Chưa theo dõi provenance và revision<br>- Chưa có optimistic lock trên recommendation | **ĐÃ HOÀN TẤT**<br>- Migration V51 bổ sung `revision`, `original_payload`, `is_customized`, `last_modified_by`<br>- Triển khai `updateDraft` trong Service & Repository<br>- Endpoint `PUT /ai/recommendations/{id}/draft`<br>- Giao diện Mobile cho phép chỉnh sửa buổi/bài tập, xem so sánh với bản AI gốc<br>- Kiểm soát tương tranh lạc quan qua `expectedRevision`<br>- Re-validate trước khi apply |

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

### 2.5. Triển khai Tinh chỉnh Bản thảo F02 & Bảo toàn Nguồn gốc AI (Draft Editing & Provenance)
- **Cơ sở dữ liệu (Migration V51):**
  - Tệp Flyway: `services/backend/src/main/resources/db/migration/V51__ai_recommendation_draft_revisions.sql`.
  - Bổ sung các cột vào `fitness.ai_recommendations`:
    + `revision integer NOT NULL DEFAULT 1`: Đánh số phiên bản bản thảo, khởi đầu từ 1 và tự động tăng khi chỉnh sửa.
    + `original_payload jsonb`: Lưu giữ nguyên vẹn nội dung sinh ra ban đầu từ AI service, không bao giờ bị ghi đè.
    + `is_customized boolean NOT NULL DEFAULT false`: Cờ đánh dấu bản thảo đã qua chỉnh sửa bởi con người.
    + `last_modified_by uuid REFERENCES fitness.users(id)`: Định danh người dùng thực hiện lần sửa đổi gần nhất.
  - Backfill an toàn: các bản ghi cũ tự động điền `original_payload = structured_payload`.
- **Spring Boot Backend:**
  - Endpoint mới: `PUT /ai/recommendations/{recommendationId}/draft` (OpenAPI operation `updateRecommendationDraft`).
  - Kiểm soát thẩm quyền: Chỉ actor có quyền quyết định hiện hành (`SELF_DIRECTED` -> Student, `HUMAN_COACH` -> Trainer phụ trách) mới được chỉnh sửa bản thảo.
  - Kiểm soát trạng thái & thời hạn: Chỉ cho phép chỉnh sửa khi trạng thái là `PENDING` và chưa hết hạn (`expiresAt`).
  - Khóa tương tranh lạc quan (Optimistic Locking): Bắt buộc `expectedRevision` trong request phải khớp chính xác với `rec.revision()`; nếu lệch (do phiên làm việc khác đã chỉnh sửa trước), hệ thống ném HTTP 409 `REVISION_CONFLICT`.
  - Kiểm định an toàn nghiêm ngặt: Payload chỉnh sửa phải vượt qua `F02RuntimeOutputValidator` (phạm vi 1 tuần theo D02, giới hạn an toàn sets/reps, và các biến thể bài tập đang `ACTIVE`).
  - Tăng số hiệu `newRevision = rec.revision() + 1`, cập nhật `is_customized = true`, và lưu vết `last_modified_by`.
  - Ghi nhận nhật ký kiểm toán bất biến: Action `AI_RECOMMENDATION_DRAFT_UPDATED` lưu trữ đầy đủ `beforeDataJson` và `afterDataJson`.
  - Tái kiểm định tại thời điểm Chấp thuận (Pre-apply Validation): `acceptRecommendation` gọi lại validator trong transaction trước khi kích hoạt kế hoạch, phòng ngừa trường hợp biến thể bài tập bị vô hiệu hóa giữa thời điểm lưu bản thảo và thời điểm duyệt.
  - Biên nhận áp dụng lưu vết `appliedRevision` và `isCustomized`.
- **React Native Expo Mobile Client:**
  - Bổ sung `UpdateRecommendationDraftRequest`, `UpdateRecommendationDraftResponse` trong `types/aiWorkoutProposal.ts`.
  - Bổ sung API `updateDraft` và hỗ trợ tham số `expectedRevision` trong `accept` tại `services/aiWorkoutProposalApi.ts`.
  - Cập nhật giao diện `AiWorkoutProposalModal.tsx`:
    + Hiển thị huy hiệu phiên bản (`v1`, `v2`...) và huy hiệu nguồn gốc ("Đề xuất AI gốc" vs "Đã tùy chỉnh").
    + Nút "Chỉnh sửa bản thảo" chuyển modal sang chế độ chỉnh sửa chi tiết: cho phép sửa tên kế hoạch, tóm tắt, tên buổi tập, focus, thời lượng, và các bài tập (sets, reps min/max, rest seconds).
    + Nút "Lưu bản thảo" gửi request cập nhật kèm `expectedRevision`.
    + Nút "So sánh với bản AI gốc" / "Ẩn bản AI gốc" cho phép đối chiếu trực quan bản tùy chỉnh với đề xuất ban đầu của AI.
    + Xử lý lỗi xung đột phiên bản `REVISION_CONFLICT` với thông điệp rõ ràng hướng dẫn tải lại bản thảo mới nhất.

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
Toàn bộ 42 ca kiểm thử backend đều chạy thành công trên PostgreSQL Testcontainers với Flyway migrations:

| Test Class | Số ca | Kết quả | Chi tiết kiểm chứng |
| :--- | :---: | :---: | :--- |
| `AiWorkoutProposalRealFastApiIntegrationTest` | 4 | **PASS (4/4)** | **Đa tầng thực tế (Real HTTP)**:<br>1. Client -> Spring -> `HttpF02AiServiceClient` -> Real Uvicorn FastAPI Process -> Validator -> DB -> Accept -> Workout Plan ACTIVE aggregateVersion=1.<br>2. Form chọn 4 ngày -> FastAPI sinh 4 buổi -> Database lưu đúng 4 bản ghi `workout_plan_sessions`.<br>3. `HUMAN_COACH`: Trainer sinh đề xuất -> Student bị chặn 403 -> Trainer chấp thuận thành công APPLIED.<br>4. Rejection: Student từ chối đề xuất -> chuyển REJECTED -> không tạo bất kỳ plan nào trong DB. |
| `AiWorkoutProposalFullSliceIntegrationTest` | 14 | **PASS (14/14)** | 1. `SELF_DIRECTED`: Tạo proposal -> PENDING -> Accept -> APPLIED -> Workout Plan ACTIVE aggregateVersion=1.<br>2. `HUMAN_COACH`: Phân quyền chấp thuận thuộc về huấn luyện viên phụ trách.<br>3. `Reject`: Từ chối -> REJECTED -> không tạo plan.<br>4. `Idempotency`: Replay cùng key & payload trả receipt; khác payload trả 409 conflict; key mới trên proposal đã áp dụng trả 409.<br>5. `Expired`: Đề xuất quá hạn 7 ngày ném 409 conflict.<br>6. `Active Plan Conflict`: Đã có active plan -> ném `ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS` và rollback toàn vẹn.<br>7. **Concurrent Double-Accept (Race Condition)**: 2 luồng đồng thời gọi accept cùng commandKey -> Thread 1 thực thi tạo plan, Thread 2 chờ khóa và replay -> chỉ tạo đúng 1 plan trong DB.<br>8. **Period Transition**: Coaching period chuyển giao trước khi accept -> ném `AiRunLifecycleConflictException`.<br>9. **Mode Transition**: Đổi chế độ từ SELF_DIRECTED sang HUMAN_COACH -> student bị chặn thẩm quyền.<br>10. **Revoked Authority Replay**: Actor đã bị tước quyền không thể replay dữ liệu đã áp dụng trước đó.<br>11. **Audit Failure Rollback**: Giả lập lỗi audit -> transaction rollback hoàn toàn.<br>12. **Edit Draft & Apply Edited Version**: Học viên chỉnh sửa buổi tập và bài tập -> tăng revision lên 2 -> `is_customized = true` -> accept với `expectedRevision = 2` áp dụng chính xác kế hoạch đã sửa vào store và ghi nhận receipt.<br>13. **Stale Revision Conflict on Edit**: Chỉnh sửa với revision cũ trả về 409 `REVISION_CONFLICT` và không ghi đè dữ liệu.<br>14. **Stale Revision on Accept Rollback**: Chấp thuận với revision cũ bị từ chối 409 conflict và rollback toàn vẹn (không tạo plan). |
| `AiWorkoutProposalPersistenceIntegrationTest` | 5 | **PASS (5/5)** | 1. Lưu & đọc PENDING.<br>2. Cập nhật APPLIED & lưu Application.<br>3. Trigger DB chặn sai lệch quyền quyết định.<br>4. Trigger DB chặn EVALUATION run tạo recommendation nghiệp vụ.<br>5. **Update Draft Persistence**: Lưu và truy xuất chính xác `revision`, `original_payload` bảo toàn nguồn gốc, cờ `is_customized` và `last_modified_by`. |
| `AiWorkoutProposalServiceUnitTest` | 14 | **PASS (14/14)** | Quyền quyết định Student/Trainer, chặn 403, validation reject, replay trùng key, conflict khác payload, reject reason, ngoại lệ khi audit ghi log thất bại, cập nhật draft thành công (revision 2), xung đột revision khi sửa draft (409), validator từ chối payload sửa hỏng, xung đột revision khi accept (409), validator từ chối khi apply nếu bài tập bị vô hiệu hóa. |
| `AiWorkoutProposalControllerUnitTest` | 5 | **PASS (5/5)** | MockMvc 5 endpoints: POST workout-proposals, GET detail, PUT draft (chỉnh sửa bản thảo v2), POST accept, POST reject. |
| `F02RuntimeOutputValidatorUnitTest` | 4 | **PASS (4/4)** | Schema hợp lệ, kiểm tra catalog qua `lockAuthoringReferences`, từ chối >1 tuần (D02), xử lý JSON hỏng. |
| **Tổng cộng Backend** | **42** | **PASS (42/42)** | **100% PASS** |

### 3.4. React Native Mobile Tests (Jest / TypeScript)
- Lệnh kiểm tra kiểu: `npm --prefix apps/mobile run typecheck`
  - Kết quả: **0 lỗi** (`tsc --noEmit` exit code 0).
- Toàn bộ test suite Mobile: 82/82 test suites PASS, 622/622 tests PASS (100% PASS).
- Lệnh chạy test tập trung F02:
  `npm --prefix apps/mobile test -- --watchAll=false src/__tests__/AiWorkoutProposalModal.test.tsx src/__tests__/aiWorkoutProposalApi.test.ts src/__tests__/studentWorkoutPlanScreens.test.tsx`

| Test Suite | Số ca | Kết quả | Nội dung kiểm chứng |
| :--- | :---: | :---: | :--- |
| `aiWorkoutProposalApi.test.ts` | 11 | **PASS (11/11)** | Gọi đúng các endpoint API (bao gồm `PUT /draft`), ánh xạ chuẩn xác các mã lỗi (conflict active plan, expired, replay mismatch, revision conflict, 403, 404, network error). |
| `AiWorkoutProposalModal.test.tsx` | 6 | **PASS (6/6)** | 1. Hiển thị form tùy chọn ngày tập/ghi chú (kèm cảnh báo phi y tế và tự đánh giá độ tin cậy), sinh đề xuất hiển thị chi tiết.<br>2. Chấp thuận & áp dụng với `expectedRevision` gọi callback chuyển màn hình.<br>3. Từ chối đề xuất cập nhật trạng thái REJECTED.<br>4. **Chỉnh sửa bản thảo**: Chuyển chế độ edit, sửa tên kế hoạch/buổi tập/hiệp/lần, lưu bản thảo thành công, cập nhật huy hiệu `v2` và nhãn `Đã tùy chỉnh`, bật xem so sánh bản gốc từ AI.<br>5. **Xử lý xung đột phiên bản**: Hiển thị banner lỗi khi sửa bản thảo bị xung đột revision.<br>6. Hiển thị banner lỗi khi học viên đã có active plan. |
| `studentWorkoutPlanScreens.test.tsx` | 15 | **PASS (15/15)** | Toàn bộ các luồng màn hình học viên kế thừa đều an toàn, không hồi quy khi tích hợp nút Đề xuất AI. |
| **Tổng cộng Mobile Focused** | **32** | **PASS (32/32)** | **100% PASS** |

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

#### 3.5.4. Trạng thái Kiểm chứng Giao diện Thực tế cho Tính năng Chỉnh sửa Bản thảo (Lịch sử Lát cắt PR #116)
- **Ghi chú phân định lát cắt:** Tại thời điểm nghiệm thu PR #116 (Slice 1), tính năng chỉnh sửa bản thảo chưa được triển khai nên chưa có runtime tương ứng.
- **Cập nhật lát cắt bổ sung (2026-10-29):** Phiên diễn tập Live Mobile GUI runtime hoàn chỉnh cho tính năng Chỉnh sửa Bản thảo (Draft Edit) đã được thực hiện thành công trên Android Emulator (`emulator-5554`) với 8 ảnh bằng chứng artifact mới, được trình bày chi tiết tại **Mục 4.3.D và 4.3.E**.

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

## 4. Lát cắt Bổ sung (2026-10-29): Chỉnh sửa Bản thảo Đề xuất (Draft Edit), Quản lý Revision & AI Provenance

### 4.1. Động lực & Yêu cầu Nghiệp vụ
Theo phản hồi thực tế và yêu cầu tinh chỉnh của Huấn luyện viên / Học viên trước khi kích hoạt kế hoạch:
- Cần cho phép actor có thẩm quyền chỉnh sửa các thông số buổi tập (tên bài, thời lượng, số hiệp, khoảng số lần reps, thời gian nghỉ) trực tiếp trên bản thảo đề xuất trước khi bấm Duyệt.
- Giữ vững các nguyên tắc cốt lõi:
  1. **Bảo tồn AI Provenance:** Lưu giữ nguyên vẹn đề xuất ban đầu của mô hình (`original_payload`) bên cạnh bản thảo sau chỉnh sửa (`structured_payload`). Không gán nhãn "AI xác nhận" cho các nội dung do con người sửa đổi (`is_customized = true`).
  2. **Quản lý Revision & Optimistic Locking:** Mỗi lần lưu bản thảo thành công sẽ tăng revision ($v1 \rightarrow v2 \rightarrow \dots$). Thao tác lưu hoặc duyệt yêu cầu đối soát `expectedRevision`; nếu phát hiện sửa đổi cạnh tranh thì trả lỗi 409 `Revision conflict` nhằm chống lost update.
  3. **Phân quyền Nghiêm ngặt:** Trong `HUMAN_COACH`, chỉ Huấn luyện viên phụ trách mới có quyền chỉnh sửa và duyệt bản thảo. Trong `SELF_DIRECTED`, chỉ Học viên mới có quyền. Admin không có quyền can thiệp chuyên môn.
  4. **Re-validation Trước Khi Áp dụng:** Payload sau chỉnh sửa phải được validate qua `F02RuntimeOutputValidator` và đối soát danh mục bài tập `ACTIVE`. Khi chấp thuận, payload được re-validate một lần nữa bên trong transaction boundary trước khi tạo `WorkoutPlan`.

### 4.2. Triển khai Kỹ thuật

| Tầng kiến trúc | Thành phần | Chi tiết triển khai |
| :--- | :--- | :--- |
| **Cơ sở dữ liệu** | `V51__ai_recommendation_draft_revisions.sql` | - Thêm các cột `revision` (int, default 1), `original_payload` (jsonb), `is_customized` (bool, default false), `last_modified_by` (uuid) trong bảng `fitness.ai_recommendations`.<br>- Backfill `original_payload = structured_payload` cho dữ liệu cũ. |
| **Hợp đồng API** | `contracts/openapi/openapi.yaml` | - Thêm endpoint `PUT /ai/recommendations/{recommendationId}/draft`.<br>- Thêm schema `UpdateRecommendationDraftRequest`, `UpdateRecommendationDraftResponse`.<br>- Cập nhật `AcceptRecommendationRequest` nhận `expectedRevision`. |
| **Backend Service** | `AiWorkoutProposalService.java` | - Triển khai `updateDraft(UpdateDraftCommand)`: khóa dòng `FOR UPDATE`, kiểm tra quyền hiện hành, kiểm tra revision, validate payload, tăng revision, lưu DB, ghi log audit `AI_RECOMMENDATION_DRAFT_UPDATED`.<br>- Nâng cấp `acceptRecommendation`: kiểm tra `expectedRevision`, re-validate payload, lưu metadata `appliedRevision` và `isCustomized` trong application receipt. |
| **Mobile Client** | `AiWorkoutProposalModal.tsx` | - Cung cấp chế độ Edit Form trực tiếp trên modal cho các bài tập, hiệp, reps, thời gian nghỉ.<br>- Gắn badge `v{revision}` và badge `Đã tùy chỉnh` / `Đề xuất AI gốc`.<br>- Nút chuyển đổi so sánh với bản AI gốc.<br>- Guard thẩm quyền: ẩn nút sửa và vô hiệu hóa nút duyệt khi actor không có thẩm quyền tương ứng với vai trò. |

### 4.3. Bằng chứng Kiểm chứng Đối soát Thực tế (Reconciled Verification Matrix)

> [!NOTE]
> Các con số dưới đây được đối soát trực tiếp từ output thực tế của các test runner (Surefire, Jest, Pytest), tách bạch hoàn toàn giữa kiểm thử thực thi mã nguồn tự động với kiểm định tĩnh (schemas/fixtures/typecheck) và diễn tập runtime.

#### A. Kiểm thử Mã nguồn Tự động (Automated Code Tests) — 85/85 PASS (100%)
1. **Backend Tests (PostgreSQL Testcontainers / Surefire): 49 tests PASS (49/49)**
   - `AiWorkoutProposalControllerUnitTest`: 5/5 PASS (bao gồm PUT `/draft`).
   - `AiWorkoutProposalServiceUnitTest`: 16/16 PASS (bổ sung các ca kiểm thử update draft, quyền hạn, stale revision conflict 409, validator reject, audit failure, idempotency replay, replay với revision xung đột ném 409, và rào chắn bắt buộc `expectedRevision` khi draft đã sửa `acceptRecommendation_whenDraftModifiedAndExpectedRevisionMissing_throwsConflict`).
   - `F02RuntimeOutputValidatorUnitTest`: 4/4 PASS (kiểm tra catalog variation ACTIVE, 1-week duration limit, JSON schema).
   - `AiWorkoutProposalPersistenceIntegrationTest`: 5/5 PASS (áp dụng migration V51, lưu/đọc revision, original_payload, customized flag, last_modified_by).
   - `AiWorkoutProposalFullSliceIntegrationTest`: 14/14 PASS (full slice: Student sửa draft -> lên v2 -> accept áp dụng đúng v2; stale revision 409; stale revision on accept rollback sạch sẽ; double accept concurrency race; active plan conflict; mode & period transitions; audit failure rollback).
   - `AiWorkoutProposalRealFastApiIntegrationTest`: 5/5 PASS (giao tiếp HTTP đa tầng giữa Spring Boot và tiến trình FastAPI Uvicorn thật, bao gồm kiểm tra trọn vẹn luồng sửa draft -> lưu v2 -> accept -> active plan trong DB -> idempotent replay).
2. **Mobile Client Tests (React Native / Jest): 33 tests PASS (33/33)**
   - `AiWorkoutProposalModal.test.tsx`: 7/7 PASS (sinh đề xuất, chỉnh sửa từng trường, lưu draft, tăng revision lên v2, toggle so sánh bản gốc, xử lý xung đột phiên bản, ẩn nút sửa và disable nút duyệt khi học viên xem đề xuất ở `HUMAN_COACH`).
   - `aiWorkoutProposalApi.test.ts`: 11/11 PASS (gọi API endpoints, ánh xạ mã lỗi và revision conflict).
   - `studentWorkoutPlanScreens.test.tsx`: 15/15 PASS (kiểm thử hồi quy toàn bộ màn hình kế hoạch tập luyện kế thừa của học viên).
3. **Python AI Service Tests (FastAPI / Pytest): 3 tests PASS (3/3)**
   - `test_simulated_f02_workout_provider_returns_valid_proposal`: PASS.
   - `test_simulated_f02_workout_provider_handles_four_days_selection`: PASS.
   - `test_internal_workout_proposal_endpoint_requires_auth`: PASS.
- **Tổng cộng Automated Code Tests:** 49 + 33 + 3 = 85 tests PASS (0 fail, 0 skipped).

#### B. Kiểm định Hợp đồng Tĩnh & Schemas (Static Validation)
- **OpenAPI 3.0 Lint:** `npm run validate:openapi` -> 0 lỗi (Redocly valid).
- **AI Schemas Validation:** `node tools/validate-ai-schemas.mjs` -> 6 schemas Draft 2020-12, 22 contract fixtures verified với Ajv.

#### C. Kiểm tra Kiểu Dữ liệu (Typecheck)
- **Mobile TypeScript:** `npm --prefix apps/mobile run typecheck` -> 0 lỗi (`tsc --noEmit` exit code 0).

#### D. Trạng thái Môi trường Local & Kiểm chứng Mobile GUI Runtime Thực tế
- **Cơ sở dữ liệu Cục bộ (`digital_fitness` port 5433):**
  - Đã sao lưu an toàn ra scratch directory (`digital_fitness_backup_pre_v50_v51.sql`, 3.9 MB).
  - Đã áp dụng migration Flyway thành công đến **V51** (`V51__ai_recommendation_draft_revisions.sql`).
- **Spring Boot Backend (port 8080):**
  - Đang chạy tiến trình `task-524` mang mã nguồn `feat/p2-m3-f02-draft-edit-20261029`.
  - Actuator `/readiness` đạt **HTTP 200 UP**.
  - Endpoint `PUT /api/v1/ai/recommendations/{id}/draft` và `POST /api/v1/ai/recommendations/{id}/accept` đã hoạt động với đầy đủ rào chắn `expectedRevision`.
- **FastAPI AI Service (port 8000):**
  - Hoạt động ổn định với `SimulatedF02WorkoutProvider` ($0.00 spend). Health check `curl.exe` trả về **HTTP 200 OK**.
- **Kiểm chứng Mobile GUI Runtime Thực tế trên Android Emulator (`emulator-5554`):**
  - Đã diễn tập trực tiếp trên Android Emulator Pixel 8 (`emulator-5554`), Android 14 API 34 qua ứng dụng Expo Go.
  - **Kiểm chứng phía Huấn luyện viên (`HUMAN_COACH`):**
    - Sử dụng tài khoản Huấn luyện viên Coach Alex Smoke (`smoke.trainer@example.com`, `user_id = 1cb982ef-b7e6-493c-86f9-fe4994f8ad93`) trong quan hệ huấn luyện `HUMAN_COACH` với Smoke Student (`5c6f49c6-f4f6-4941-b217-2574b3830395`).
    - Kiểm chứng thành công các ca:
      1. **Tạo đề xuất gốc v1:** Recommendation `d1b040dc-bd36-443e-857e-6c6ab61c3f92` ở trạng thái `PENDING`, thẩm quyền `TRAINER`, revision `1` ([`mobile_f02_draft_01_proposal_v1.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_01_proposal_v1.png)).
      2. **Chế độ chỉnh sửa (Draft Edit):** Mở form sửa từng buổi và bài tập ([`mobile_f02_draft_02_edit_mode.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_02_edit_mode.png)).
      3. **Chỉnh sửa trường dữ liệu:** Sửa số hiệp bài tập Day 3 từ 3 lên 4 ([`mobile_f02_draft_03_field_edited.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_03_field_edited.png)).
      4. **Lưu bản thảo & Tăng revision:** Cập nhật DB lên `revision = 2`, `is_customized = true`, UI hiển thị badge "v2" và "Đã tùy chỉnh" ([`mobile_f02_draft_04_saved_v2.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_04_saved_v2.png), [`mobile_f02_draft_05_customized_v2_comparison_available.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_05_customized_v2_comparison_available.png)).
      5. **So sánh với bản AI gốc:** Hiển thị song song bản AI ban đầu (`original_payload`) bên dưới bản sửa đổi ([`mobile_f02_draft_06_original_comparison_view.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_06_original_comparison_view.png)).
      6. **Ca D — Xung đột Kế hoạch Active & Rollback:** Táp chấp thuận v2 khi học viên đã có kế hoạch active (`bb2a6813-6fae-400a-bfbf-52938143cb74`), hệ thống trả lỗi 409 `ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS`, transaction rollback sạch sẽ (giữ nguyên recommendation ở `PENDING`, 0 hàng trong `ai_recommendation_applications`), UI hiển thị banner lỗi xử lý trung tính: *"Học viên đã có một kế hoạch tập luyện đang áp dụng. Hệ thống không cho phép áp dụng đồng thời nhiều kế hoạch tập luyện."* ([`mobile_f02_draft_07_active_plan_conflict_banner.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_07_active_plan_conflict_banner.png)).
      7. **Ca C — Từ chối đề xuất (Reject):** Nhập lý do và bấm Từ chối, recommendation chuyển sang `REJECTED`, DB lưu `decided_by` và `decision_note`, UI hiển thị `Trạng thái: REJECTED` và card "Đã từ chối đề xuất" ([`mobile_f02_draft_08_rejected_status.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_08_rejected_status.png)).
  - **Kiểm chứng phía Học viên (`SELF_DIRECTED`):**
    - Sử dụng tài khoản Học viên Student F02 Runtime (`student.f02.runtime@example.com`, `user_id = 9c0bad94-120a-437f-a6a7-d1d439f382cf`), hoàn tất onboarding và kỳ huấn luyện `SELF_DIRECTED` (`period_id = cd77ff7f-807f-4223-a2c8-6e74caf762df`) không có kế hoạch active.
    - Kiểm chứng thành công các ca:
      8. **Tạo đề xuất gốc v1 (Student):** Tại màn hình `StudentWorkoutPlansScreen`, bấm "Đề xuất giáo án 1 tuần (AI)", chọn 3 buổi/tuần và sinh đề xuất `ba92f34f-a2a1-4ab6-b293-5e4622e0ac03` (`PENDING`, `STUDENT`, `v1`) ([`mobile_f02_draft_09_student_proposal_v1.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_09_student_proposal_v1.png)).
      9. **Sửa & Lưu bản thảo v2 (Student):** Sửa bài tập 1 của Buổi 1 từ 3 thành 4 hiệp, lưu bản thảo thành công lên `v2`, badge "Đã tùy chỉnh" ([`mobile_f02_draft_10_student_saved_v2.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_10_student_saved_v2.png)).
      10. **So sánh với bản AI gốc (Student):** Hiển thị song song bản AI ban đầu chưa sửa với bản tùy chỉnh ([`mobile_f02_draft_11_student_comparison_view.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_11_student_comparison_view.png)).
      11. **Chấp thuận & Áp dụng thành công (Accept & Apply):** Bấm "Chấp thuận & Áp dụng", backend sinh kế hoạch `3e26acd8-ce5b-4409-acc3-eaff71f1755d` ở trạng thái `ACTIVE`. Mobile tự động điều hướng sang màn hình kế hoạch đang áp dụng, hiển thị Barbell Bench Press chuẩn xác `4 hiệp · 8–12 reps` ([`mobile_f02_draft_12_student_plan_active_applied.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_12_student_plan_active_applied.png)).

#### E. Ma trận Trạng thái Kiểm chứng Giao diện Thực tế (Mobile Runtime Status Matrix)

| Kịch bản | Thẩm quyền & Chế độ | Kết quả Live GUI Runtime | Chi tiết & Giới hạn | Bằng chứng Kiểm thử Tự động Thay thế |
| :--- | :--- | :---: | :--- | :--- |
| **Tạo đề xuất gốc v1** | Trainer / `HUMAN_COACH` | **PASS** | Chạy trên `emulator-5554` (Pixel 8), đề xuất v1 sinh thành công, badge "Đề xuất AI gốc". | `AiWorkoutProposalRealFastApiIntegrationTest`, `AiWorkoutProposalModal.test.tsx` |
| **Chỉnh sửa bản thảo (Draft Edit)** | Trainer / `HUMAN_COACH` | **PASS** | Mở form sửa buổi tập, sửa số hiệp từ 3 thành 4 trên Day 3. | `AiWorkoutProposalModal.test.tsx` (edit form tests) |
| **Lưu draft v2 & So sánh bản gốc** | Trainer / `HUMAN_COACH` | **PASS** | DB ghi nhận `revision=2`, `is_customized=true`, UI hiển thị badge "v2", nút so sánh mở song song bản gốc AI v1. | `AiWorkoutProposalServiceUnitTest`, `AiWorkoutProposalPersistenceIntegrationTest` |
| **Xung đột Kế hoạch Active & Rollback** | Trainer / `HUMAN_COACH` | **PASS** | Accept v2 gặp active plan sẵn có -> nhận 409 `ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS`, rollback sạch sẽ giữ `PENDING`, 0 application receipt, banner cảnh báo chuẩn xác. | `AiWorkoutProposalFullSliceIntegrationTest` (`acceptRecommendation_whenActivePlanAlreadyExists_rollsBackTransactionAndReturnsConflict`) |
| **Từ chối đề xuất (Reject)** | Trainer / `HUMAN_COACH` | **PASS** | Nhập lý do từ chối, bấm Từ chối, đề xuất cập nhật `REJECTED`, ghi nhận audit và hiển thị badge `REJECTED`. | `AiWorkoutProposalControllerUnitTest`, `AiWorkoutProposalModal.test.tsx` |
| **Học viên Chỉnh sửa & Accept thành công** | Student / `SELF_DIRECTED` | **PASS** | Thực thi trên `emulator-5554` với tài khoản `student.f02.runtime@example.com` (xác minh qua Mailpit local): Tạo đề xuất v1 -> sửa Day 1 từ 3 lên 4 hiệp -> lưu v2 -> so sánh bản gốc -> bấm Chấp thuận & Áp dụng -> tạo thành công kế hoạch `ACTIVE` trong DB và điều hướng đến màn hình chi tiết kế hoạch hiển thị 4 hiệp. | `AiWorkoutProposalRealFastApiIntegrationTest.multiTier_editDraft_andApplyEditedVersion_succeeds` (luồng đa tầng thật giữa Spring Boot + FastAPI + Postgres Testcontainers: Student sửa draft v2 -> Accept -> Plan ACTIVE aggregateVersion=1 -> Idempotent replay).
| **Học viên Thử can thiệp đề xuất của Trainer** | Student / `HUMAN_COACH` | **PASS (DESIGN RESTRICTION)** | **Ranh giới điều hướng sản phẩm:** Trong chế độ `HUMAN_COACH`, đề xuất chỉ được quản lý tại Trainer Workspace (`RelationshipDetailScreen` -> `ProgramScreen`). Màn hình Học viên chỉ hiển thị kế hoạch khi đã được kích hoạt, không có đường dẫn điều hướng mở modal đề xuất pending của Trainer. | `AiWorkoutProposalModal.test.tsx` (test case: *"hides edit draft button and disables accept button when student views trainer-managed proposal (HUMAN_COACH)"*), và server 403 `AiRunAccessDeniedException` tests trong `AiWorkoutProposalServiceUnitTest`.

---

## 5. Kiểm soát Chi phí & An toàn Git

1. **Chi phí AI:**
   - Sử dụng `SimulatedF02WorkoutProvider` trên FastAPI và PostgreSQL Testcontainers fixtures.
   - Số tiền đã chi: **0.00 USD** (Hoàn toàn không gọi API trả phí bên ngoài).

2. **Tuân thủ Git:**
   - Thực hiện trên branch: `feat/p2-m3-f02-draft-edit-20261029`.
   - Baseline đối chiếu: commit `9a47fa5073572900b5fe5655bdc704c4cfe53854` (Merge PR #118).
   - Không commit, push, create PR, merge, hoặc chuyển branch.
   - Toàn bộ thay đổi nằm trong working tree để người dùng rà soát.

---

## 6. Dự thảo Thông điệp Commit & Mô tả PR (Đề xuất)

### Commit Message Đề xuất:
```text
feat(ai,workout,mobile): implement F02 draft edit, revision, and provenance

- Add Flyway V51 migration adding revision, original_payload, is_customized, and last_modified_by
- Add OpenAPI PUT /ai/recommendations/{id}/draft endpoint with optimistic locking (expectedRevision)
- Implement draft editing with F02RuntimeOutputValidator and active exercise variation locks
- Implement strict expectedRevision validation on Accept to prevent accidental stale revision application
- Implement safe idempotent replay with operation/revision/payload identity and authority checks
- Extend React Native Expo AiWorkoutProposalModal with draft editing, revision badges, side-by-side original AI comparison, and authority guards
- Verified with 85 automated tests (49 backend, 33 mobile, 3 pytest) and live Mobile GUI runtime execution on Android emulator
```

### Nội dung PR Description Đề xuất:
```markdown
## Summary
Triển khai hoàn chỉnh gói tính năng Chỉnh sửa bản thảo đề xuất giáo án (Draft Proposal Customization & Provenance - F02/P2-M3) xuyên suốt các tầng: Database Migration V51 -> OpenAPI Contract -> Spring Boot Modular Monolith -> React Native Expo Mobile Client.

## Key Changes
1. **Database & Provenance (Flyway V51):**
   - Bổ sung `revision` (default 1), `original_payload` (jsonb), `is_customized` (bool, default false), `last_modified_by` (uuid).
   - Bảo toàn bản gốc AI `original_payload` qua nhiều lần sửa, ngăn gắn nhãn AI cho nội dung người dùng sửa đổi.
2. **Backend Domain & Services:**
   - Triển khai `updateDraft(UpdateDraftCommand)` với optimistic locking chống lost update (`expectedRevision`).
   - Kiểm soát chặt chẽ thẩm quyền: `SELF_DIRECTED` thuộc về Student, `HUMAN_COACH` thuộc về Trainer phụ trách.
   - Bổ sung rào chắn Accept: từ chối 409 nếu draft đã sửa (`revision > 1`) mà request thiếu `expectedRevision`.
   - Nâng cấp replay guard: kiểm tra khớp revision áp dụng thực tế và thẩm quyền hiện hành.
   - Xung đột kế hoạch active (`ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS`) trả 409 và rollback toàn vẹn giữ `PENDING`.
3. **Mobile Client (React Native Expo):**
   - Modal hỗ trợ chuyển đổi giữa chế độ xem và chế độ biên tập từng buổi/bài tập.
   - Hiển thị badge `v{revision}`, `Đã tùy chỉnh` và nút chuyển đổi xem song song bản gốc AI.
   - Guard thẩm quyền: tự động ẩn nút sửa và disable nút duyệt khi học viên xem đề xuất ở chế độ `HUMAN_COACH`.
4. **Kiểm chứng:**
   - 85/85 Automated tests PASS (49 backend, 33 mobile, 3 pytest).
   - Diễn tập Mobile GUI runtime thực tế trên Android emulator Pixel 8 với 8 ảnh chụp màn hình bằng chứng.
   - Không phát sinh chi phí OpenAI ($0.00 spend).
```
