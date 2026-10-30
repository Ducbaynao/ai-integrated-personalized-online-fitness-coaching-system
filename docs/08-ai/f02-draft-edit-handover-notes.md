# Ghi chú Tiếp quản & Bàn giao: F02 Draft Edit, Revision & AI Provenance (P2-M3)

- **Thời điểm cập nhật:** 2026-10-30
- **Branch hiện hành:** `feat/p2-m3-f02-draft-edit-20261029`
- **HEAD Commit:** `9a47fa5073572900b5fe5655bdc704c4cfe53854` (Merge PR #118)
- **Trạng thái:** Hoàn tất gói tính năng chỉnh sửa bản thảo đề xuất giáo án (Draft Edit), quản lý revision, bảo lưu AI provenance gốc, optimistic locking, kiểm định thẩm quyền đa tầng và cập nhật Mobile UI. Hoàn tất toàn bộ diễn tập Mobile GUI runtime thực tế trên Android emulator cho cả hai chế độ `HUMAN_COACH` (Trainer) và `SELF_DIRECTED` (Student). Toàn bộ mã nguồn và artifacts nằm trong working tree sẵn sàng để review và bàn giao.

---

## 1. Kết quả Rà soát Nghiệp vụ & Kỹ thuật Chuyên sâu

### 1.1. Quyền hạn theo Chế độ Huấn luyện (Authority Invariants)
- **`SELF_DIRECTED`:** Đề xuất thuộc thẩm quyền của Học viên (`STUDENT`). Học viên là người chỉnh sửa draft và chấp thuận. Huấn luyện viên hoặc người dùng khác bị chặn 403 `AiRunAccessDeniedException`.
- **`HUMAN_COACH`:** Đề xuất thuộc thẩm quyền của Huấn luyện viên phụ trách (`TRAINER`). Chỉ Huấn luyện viên phụ trách trong kỳ huấn luyện hợp lệ mới có quyền chỉnh sửa và chấp thuận. Học viên chỉ được xem và bị chặn 403 nếu cố tình can thiệp.
- **Mobile UI Guard:** Component `AiWorkoutProposalModal.tsx` tính toán `hasAuthority` dựa trên vai trò hiện tại và `decisionAuthority` của recommendation. Khi không có quyền (ví dụ Học viên xem bản thảo do Trainer quản lý ở chế độ `HUMAN_COACH`), nút "Chỉnh sửa bản thảo" bị ẩn hoàn toàn và các nút "Chấp thuận & Áp dụng" / "Từ chối" bị vô hiệu hóa (`disabled`).

### 1.2. Trạng thái & Vòng đời Đề xuất (Lifecycle & State Integrity)
- Chỉ cho phép chỉnh sửa khi đề xuất ở trạng thái `PENDING` và chưa hết hạn (`expires_at` còn hiệu lực).
- Đề xuất đã `APPLIED`, `REJECTED`, hoặc quá hạn 7 ngày sẽ bị từ chối 409 `AiRunLifecycleConflictException`.
- Chặn thay đổi khi kỳ huấn luyện (`coachingPeriodId`) hoặc chế độ huấn luyện bị chuyển dịch giữa lúc tạo đề xuất và lúc chỉnh sửa/áp dụng.

### 1.3. Bảo tồn Provenance & Quản lý Revision
- **Bảo toàn Bản gốc AI:** Cột `original_payload` (jsonb) lưu trữ nguyên vẹn đề xuất ban đầu của mô hình AI khi tạo. Qua các lần chỉnh sửa bản thảo ($v1 \rightarrow v2 \rightarrow v3$), chỉ có `structured_payload` được cập nhật, `original_payload` giữ nguyên vẹn.
- **Không nhầm lẫn nguồn gốc:** Khi người dùng chỉnh sửa, trường `is_customized` được đặt thành `true` và `last_modified_by` lưu ID người chỉnh sửa. Tuyệt đối không gắn nhãn "AI xác nhận" cho nội dung con người sửa đổi.
- **Optimistic Locking chống Lost Update:** API `PUT /ai/recommendations/{id}/draft` bắt buộc gửi `expectedRevision`. Nếu phát hiện phiên bản hiện tại trong DB khác `expectedRevision`, hệ thống ném 409 `Revision conflict`. Khi lưu thành công, revision tự động tăng lên $N+1$.
- **Xác thực Revision khi Chấp thuận & Chống Áp dụng Nhầm Phiên bản Cũ (Stale Revision Guard):**
  - **Lý do trường `expectedRevision` là tùy chọn trong Contract:** Để duy trì tương thích ngược (backwards compatibility) với các client Slice 1 hoặc caller chưa nâng cấp chỉ thao tác với bản thảo AI gốc ($v=1$).
  - **Lỗ hổng tiềm ẩn đã được khắc phục:** Trước đây, nếu client mở xem $v=1$, bản thảo sau đó bị sửa đổi thành $v=2$, rồi request Accept gửi đi không kèm `expectedRevision`, server sẽ vô tình áp dụng $v=2$.
  - **Cơ chế bảo vệ chặt chẽ tại [`AiWorkoutProposalService.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiWorkoutProposalService.java#L236-L245):**
    - Nếu bản thảo đã từng bị sửa đổi (`rec.revision() > 1`) mà request Accept **không truyền** `expectedRevision`, server từ chối ngay với mã lỗi `409 Conflict`: *"Revision conflict: draft has been modified to revision %d; expectedRevision is required when accepting."*
    - Nếu request Accept có truyền `expectedRevision`, server kiểm tra đối chiếu tuyệt đối `rec.revision() == command.expectedRevision()`, nếu khác nhau trả về `409 Conflict: Stale revision`.
    - Khi chấp thuận đề xuất gốc chưa chỉnh sửa (`rec.revision() == 1`), `expectedRevision` có thể để trống (mặc định hiểu là revision 1 an toàn).
  - **Bảo vệ Idempotent Replay:**
    - Khi replay một request Accept trước đó, hệ thống đối soát `operation / revision / payload identity`.
    - Nếu `command.expectedRevision()` được cung cấp, nó phải khớp chính xác với revision của bản ghi đã áp dụng trong DB (`savedRev`).
    - Nếu `command.expectedRevision()` bị bỏ trống, replay chỉ được chấp nhận nếu bản ghi áp dụng ban đầu có `savedRev == 1`. Bất kỳ sự sai lệch nào đều ném `409 Conflict`.
    - Caller thực hiện replay vẫn phải vượt qua toàn bộ bước kiểm tra thẩm quyền hiện hành (`coachingPeriod` còn hiệu lực, actor vẫn là bên phụ trách).
- **Audit Immutability:** Mọi thao tác cập nhật draft đều ghi nhận audit log `AI_RECOMMENDATION_DRAFT_UPDATED` lưu trữ cả dữ liệu trước (`beforeDataJson`) và sau (`afterDataJson`).

### 1.4. Validation & Thẩm định lúc Áp dụng (Re-validation on Apply)
- Payload chỉnh sửa phải vượt qua `F02RuntimeOutputValidator`: kiểm tra schema Draft 2020-12, giới hạn 1 tuần (`durationWeeks == 1`), số buổi, số bài tập, và kiểm tra biến thể bài tập tồn tại và `ACTIVE` thông qua `exerciseReferenceQuery.lockAuthoringReferences`.
- **Re-validation trước khi tạo Plan:** Tại thời điểm chấp thuận (`acceptRecommendation`), hệ thống gọi lại validator một lần nữa bên trong transaction boundary trước khi sinh kế hoạch nhằm đảm bảo danh mục bài tập chưa bị vô hiệu hóa (`ARCHIVED`) giữa lúc lưu draft và lúc bấm duyệt.

### 1.5. Xung đột Kế hoạch Đang Hoạt động (`ACTIVE` Plan Boundary)
- Khi bấm duyệt đề xuất, nếu học viên đã có kế hoạch tập luyện đang hoạt động (`ACTIVE`), hệ thống ném `WorkoutPlanFailure` (`ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS`) trả mã 409.
- Transaction rollback sạch sẽ: recommendation giữ nguyên `PENDING`, không tạo kế hoạch dở dang, không lưu application receipt.
- Hệ thống không tự ý archive, ghi đè hoặc thay thế kế hoạch đang active; quy trình kế thừa kế hoạch (successor workflow) thuộc các milestone tiếp theo.

### 1.6. Đánh giá Migration V51
- **File:** `services/backend/src/main/resources/db/migration/V51__ai_recommendation_draft_revisions.sql`.
- **Số thứ tự:** V51 là số liên tục tiếp theo ngay sau V50 (`V50__account_erasure_workflow.sql`), không bị trùng lặp.
- **Tương thích dữ liệu:** Sử dụng `IF NOT EXISTS`, có giá trị `DEFAULT 1` cho `revision` và `DEFAULT false` cho `is_customized`. Thực hiện `UPDATE fitness.ai_recommendations SET original_payload = structured_payload WHERE original_payload IS NULL;` để backfill dữ liệu lịch sử một cách an toàn.

---

## 2. Đối soát Bằng chứng Kiểm thử (Dữ liệu Thực tế từ Output)

> [!NOTE]
> Báo cáo này tách bạch rõ ràng giữa: (1) Automated Code Tests (JUnit/Jest/Pytest), (2) Static Contract/Schema Validation, và (3) Typecheck.

### 2.1. Kiểm thử Mã nguồn Tự động (Automated Code Tests)

**Tổng cộng:** **85 ca kiểm thử tự động**, kết quả **85/85 PASS (100%)**, 0 Failures, 0 Errors, 0 Skipped.

#### A. Backend Java Tests (Maven / Surefire) — 49/49 PASS
*Thực thi qua `services/backend/mvnw.cmd test` trên PostgreSQL Testcontainers:*
1. `AiWorkoutProposalControllerUnitTest`: **5 tests PASS** (PUT `/draft`, GET detail, POST `/accept`, POST `/reject`, POST `/workout-proposals`).
2. `AiWorkoutProposalServiceUnitTest`: **16 tests PASS** (phân quyền student/trainer, chặn 403, stale revision 409, update draft thành công, validation failure, audit failure, idempotency replay trùng/khác payload, replay với revision xung đột ném 409, và ca kiểm thử bảo vệ bắt buộc `expectedRevision` khi draft đã sửa `acceptRecommendation_whenDraftModifiedAndExpectedRevisionMissing_throwsConflict`).
3. `F02RuntimeOutputValidatorUnitTest`: **4 tests PASS** (schema hợp lệ, exercise catalog check, D02 1-week scope limit, JSON hỏng).
4. `AiWorkoutProposalPersistenceIntegrationTest`: **5 tests PASS** (Flyway V51 migration, lưu/đọc revision, provenance, customized flag, trigger chặn authority lệch).
5. `AiWorkoutProposalFullSliceIntegrationTest`: **14 tests PASS** (full slice: Student sửa draft -> lên v2 -> accept áp dụng đúng v2; stale revision 409; stale revision on accept rollback; double accept concurrency race; active plan conflict rollback; coaching mode & period transition guards; audit failure rollback).
6. `AiWorkoutProposalRealFastApiIntegrationTest`: **5 tests PASS** (giao tiếp HTTP thực tế giữa Spring Boot và tiến trình FastAPI Uvicorn, bao gồm multiTier_editDraft_andApplyEditedVersion_succeeds bao phủ trọn vẹn luồng HTTP -> FastAPI -> PUT draft v2 -> 409 stale revision check -> Accept -> Plan active trong DB -> Replay check).

#### B. Mobile Client Tests (React Native / Jest) — 33/33 PASS
*Thực thi qua `npm --prefix apps/mobile test -- --watchAll=false`:*
1. `AiWorkoutProposalModal.test.tsx`: **7 tests PASS**
   - Sinh đề xuất giáo án mẫu từ form tùy chọn.
   - Chấp thuận đề xuất với `expectedRevision` hợp lệ.
   - Từ chối đề xuất cập nhật trạng thái `REJECTED`.
   - Chỉnh sửa form (buổi tập, bài tập, sets, reps, thời gian nghỉ), lưu draft, tăng revision lên v2, hiển thị huy hiệu "Đã tùy chỉnh", toggle so sánh với bản AI gốc.
   - Xử lý xung đột phiên bản (409 Revision conflict) khi lưu draft.
   - Hiển thị banner lỗi khi xung đột với kế hoạch active sẵn có.
   - **Guard thẩm quyền:** Ẩn nút "Chỉnh sửa bản thảo" và vô hiệu hóa nút duyệt khi học viên xem đề xuất do Trainer quản lý ở chế độ `HUMAN_COACH`.
2. `aiWorkoutProposalApi.test.ts`: **11 tests PASS** (gọi các API endpoints, ánh xạ chuẩn xác các mã lỗi và xung đột revision).
3. `studentWorkoutPlanScreens.test.tsx`: **15 tests PASS** (kiểm thử hồi quy toàn bộ màn hình kế hoạch tập luyện kế thừa của học viên).

#### C. Python AI Service Tests (FastAPI / Pytest) — 3/3 PASS
*Thực thi qua `services/ai-service/.venv/Scripts/python.exe -m pytest`:*
1. `test_simulated_f02_workout_provider_returns_valid_proposal`: **PASS**.
2. `test_simulated_f02_workout_provider_handles_four_days_selection`: **PASS**.
3. `test_internal_workout_proposal_endpoint_requires_auth`: **PASS**.

---

### 2.2. Kiểm định Hợp đồng Tĩnh & Schemas (Static Validation)
*Tách riêng khỏi kiểm thử thực thi mã nguồn:*
- **OpenAPI 3.0 Lint:** `redocly lint contracts/openapi/openapi.yaml` -> **0 lỗi** (Valid API description).
- **AI Schema Validation:** `node tools/validate-ai-schemas.mjs` -> **PASS** (6 schemas Draft 2020-12, 22 contract fixtures verified với Ajv).

### 2.3. Kiểm tra Kiểu Dữ liệu (Typecheck)
- **Mobile TypeScript:** `npm --prefix apps/mobile run typecheck` -> **PASS** (0 lỗi, exit code 0).

---

## 3. Trạng thái Môi trường Local & Kiểm chứng Mobile GUI Runtime Thực tế

- **Cơ sở dữ liệu Cục bộ (`digital_fitness` port 5433):**
  - Đã sao lưu dữ liệu an toàn ra scratch directory (`digital_fitness_backup_pre_v50_v51.sql`, 3.9 MB).
  - Đã chạy Flyway migrate thành công đến **V51** (`V51__ai_recommendation_draft_revisions.sql`).
- **Spring Boot Backend (port 8080):**
  - Đang chạy tiến trình `task-524` với mã nguồn nhánh `feat/p2-m3-f02-draft-edit-20261029`.
  - Endpoint `/actuator/health/readiness` đạt **HTTP 200 UP**.
  - Endpoint `PUT /api/v1/ai/recommendations/{id}/draft` và `POST /api/v1/ai/recommendations/{id}/accept` đã hoạt động với đầy đủ rào chắn `expectedRevision`.
- **FastAPI AI Service (port 8000):**
  - Hoạt động ổn định với `SimulatedF02WorkoutProvider` (chi phí $0.00). Endpoint `http://127.0.0.1:8000/health` trả về **HTTP 200 OK** (`{"status":"ok","service":"fitness-coaching-ai-service"}`).
- **Kiểm chứng Mobile GUI Runtime Thực tế trên Android Emulator (`emulator-5554`):**
  - **Môi trường & Thiết bị:** Android Emulator Pixel 8 (`emulator-5554`), Android 14 API 34, Expo Go kết nối Metro Bundler port 8081 và reverse ADB port 8080/8081.
  - **Dịch vụ Hỗ trợ SMTP:** Khởi động container `digital-fitness-mailpit` (port 1025 SMTP, port 8025 Web UI/API). Đã đăng ký và xác minh email thành công 100% qua endpoint chuẩn `POST /api/v1/auth/email-verifications/confirmations` (không dùng SQL sửa trạng thái tài khoản).
  - **Kịch bản 1 — Huấn luyện viên (`HUMAN_COACH`):**
    - Diễn viên: Coach Alex Smoke (`smoke.trainer@example.com`, `user_id = 1cb982ef-b7e6-493c-86f9-fe4994f8ad93`) quản lý học viên Smoke Student (`5c6f49c6-f4f6-4941-b217-2574b3830395`) trong quan hệ huấn luyện `95501599-414d-4d0d-b663-296978c402a5`.
    - Các ca đã kiểm chứng:
      1. **Ca B — Tạo đề xuất AI gốc (v1):** Tại `CoachingHubScreen` -> `ProgramScreen`, mở `AiWorkoutProposalModal`, chọn 3 buổi/tuần và bấm "Tạo đề xuất AI". Hệ thống tạo đề xuất `d1b040dc-bd36-443e-857e-6c6ab61c3f92` ở trạng thái `PENDING`, `TRAINER`, `v1`, badge "Đề xuất AI gốc" (Ảnh `mobile_f02_draft_01_proposal_v1.png`).
      2. **Ca B — Chỉnh sửa bản thảo (Draft Edit Mode):** Bấm nút "Chỉnh sửa bản thảo" (`start-edit-draft-button`), giao diện chuyển sang chế độ biên tập từng buổi tập và bài tập (Ảnh `mobile_f02_draft_02_edit_mode.png`).
      3. **Ca B — Chỉnh sửa trường dữ liệu:** Di chuyển đến Day 3 (Full Body Conditioning), sửa số hiệp (sets) bài tập 1 từ `3` thành `4` (Ảnh `mobile_f02_draft_03_field_edited.png`).
      4. **Ca B — Lưu bản thảo & Tăng revision:** Bấm "Lưu bản thảo", cơ sở dữ liệu ghi nhận cập nhật `revision = 2`, `is_customized = true`, `last_modified_by = 1cb982ef...`, `original_payload` giữ nguyên vẹn. Modal hiển thị ngay badge tím "v2", badge "Đã tùy chỉnh" và nút "So sánh với bản AI gốc" (Ảnh `mobile_f02_draft_04_saved_v2.png` và `mobile_f02_draft_05_customized_v2_comparison_available.png`).
      5. **Ca B — So sánh với bản gốc AI:** Bấm "So sánh với bản AI gốc" (`toggle-original-comparison-button`), giao diện mở rộng hiển thị song song cả bản thảo hiện hành (v2) và bản gốc ban đầu của AI (v1) cùng nút "Ẩn bản AI gốc" (Ảnh `mobile_f02_draft_06_original_comparison_view.png`).
      6. **Ca D — Xung đột Kế hoạch Active & Rollback Sạch sẽ (Conflict & Rollback):** Cuộn xuống phần quyết định thẩm quyền (nêu rõ "kế hoạch 1 tuần (phiên bản v2) sẽ được tự động tạo và kích hoạt"), bấm "Chấp thuận & Áp dụng". Do Smoke Student đã có sẵn kế hoạch `ACTIVE` (`bb2a6813-6fae-400a-bfbf-52938143cb74`), backend Workout domain command phát hiện xung đột và trả về HTTP 409 `ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS`. Toàn bộ transaction rollback sạch sẽ: đề xuất giữ nguyên trạng thái `PENDING`, bảng `ai_recommendation_applications` có đúng 0 hàng, và Mobile hiển thị banner cảnh báo trung tính: *"Học viên đã có một kế hoạch tập luyện đang áp dụng. Hệ thống không cho phép áp dụng đồng thời nhiều kế hoạch tập luyện."* (Ảnh `mobile_f02_draft_07_active_plan_conflict_banner.png`).
      7. **Ca C — Từ chối đề xuất đã chỉnh sửa (Reject):** Nhập ghi chú thẩm quyền vào ô `ai-decision-note-input`, bấm "Từ chối đề xuất" (`reject-ai-proposal-button`). Đề xuất chuyển trạng thái thành `REJECTED`, DB lưu `decided_by = 1cb982ef...`, `decided_at` và `decision_note`. Giao diện Mobile cập nhật ngay badge `Trạng thái: REJECTED` và thông báo *"Đã từ chối đề xuất"* (Ảnh `mobile_f02_draft_08_rejected_status.png`).
  - **Kịch bản 2 — Học viên Tự định hướng (`SELF_DIRECTED`):**
    - Diễn viên: Học viên Student F02 Runtime (`student.f02.runtime@example.com`, `user_id = 9c0bad94-120a-437f-a6a7-d1d439f382cf`), hoàn tất onboarding, ở kỳ huấn luyện `SELF_DIRECTED` (`period_id = cd77ff7f-807f-4223-a2c8-6e74caf762df`) và chưa có kế hoạch nào đang áp dụng.
    - Các ca đã kiểm chứng:
      8. **Ca A — Tạo đề xuất gốc v1 (Student):** Tại màn hình `StudentWorkoutPlansScreen` ("Kế hoạch tập luyện"), bấm "Đề xuất giáo án 1 tuần (AI)", chọn 3 buổi/tuần và bấm "Tạo đề xuất giáo án (AI)". Hệ thống tạo đề xuất `ba92f34f-a2a1-4ab6-b293-5e4622e0ac03` ở trạng thái `PENDING`, thẩm quyền `STUDENT`, `v1`, badge "Đề xuất AI gốc" (Ảnh `mobile_f02_draft_09_student_proposal_v1.png`).
      9. **Ca A — Chỉnh sửa & Lưu bản thảo v2 (Student):** Bấm "Chỉnh sửa bản thảo", sửa số hiệp (sets) bài tập 1 của Buổi 1 từ `3` thành `4`. Bấm "Lưu bản thảo", DB ghi nhận cập nhật `revision = 2`, `is_customized = true`, `last_modified_by = 9c0bad94...`. Modal hiển thị badge `v2`, badge "Đã tùy chỉnh" và danh sách hiển thị rõ ràng `4 hiệp x 8-12 lần` (Ảnh `mobile_f02_draft_10_student_saved_v2.png`).
      10. **Ca A — So sánh bản gốc AI (Student):** Bấm "So sánh với bản AI gốc", giao diện hiển thị thẻ "Bản gốc từ AI (chưa sửa)" với cấu trúc 3 buổi ban đầu song song cùng bản tùy chỉnh (Ảnh `mobile_f02_draft_11_student_comparison_view.png`).
      11. **Ca A — Chấp thuận & Áp dụng Kế hoạch Thành công (Accept & Apply):** Cuộn xuống phần quyết định thẩm quyền, bấm "Chấp thuận & Áp dụng" (với `expectedRevision = 2`). Hệ thống thực thi lệnh domain, cập nhật đề xuất sang `APPLIED`, và tự động tạo kế hoạch tập luyện `3e26acd8-ce5b-4409-acc3-eaff71f1755d` ở trạng thái `ACTIVE`. Mobile tự động điều hướng sang màn hình chi tiết kế hoạch (`WorkoutPlanDetailScreen`), hiển thị đầy đủ thông tin: *"1-Week GENERAL FITNESS Starter Plan"*, *"Đang áp dụng · Học viên tạo"*, và bài tập Barbell Bench Press ghi nhận chuẩn xác `4 hiệp · 8–12 reps` đã chỉnh sửa (Ảnh `mobile_f02_draft_12_student_plan_active_applied.png`).

### 3.1. Ma trận Trạng thái Kiểm chứng Giao diện Thực tế (Mobile Runtime Status Matrix)

Nhằm tuân thủ nguyên tắc không đánh đồng giữa kiểm thử tự động và diễn tập giao diện thực tế (Live GUI Runtime), bảng dưới đây phân định rõ ràng kết quả từng kịch bản:

| Kịch bản | Thẩm quyền & Chế độ | Kết quả Live GUI Runtime | Chi tiết & Giới hạn | Bằng chứng Kiểm thử Tự động Đi kèm |
| :--- | :--- | :---: | :--- | :--- |
| **Tạo đề xuất gốc v1 (Trainer)** | Trainer / `HUMAN_COACH` | **PASS** | Chạy trên `emulator-5554` (Pixel 8), đề xuất v1 sinh thành công, badge "Đề xuất AI gốc". | `AiWorkoutProposalRealFastApiIntegrationTest`, `AiWorkoutProposalModal.test.tsx` |
| **Chỉnh sửa bản thảo (Draft Edit)** | Trainer / `HUMAN_COACH` | **PASS** | Mở form sửa buổi tập, sửa số hiệp từ 3 thành 4 trên Day 3. | `AiWorkoutProposalModal.test.tsx` (edit form tests) |
| **Lưu draft v2 & So sánh bản gốc** | Trainer / `HUMAN_COACH` | **PASS** | DB ghi nhận `revision=2`, `is_customized=true`, UI hiển thị badge "v2", nút so sánh mở song song bản gốc AI v1. | `AiWorkoutProposalServiceUnitTest`, `AiWorkoutProposalPersistenceIntegrationTest` |
| **Xung đột Kế hoạch Active & Rollback** | Trainer / `HUMAN_COACH` | **PASS** | Accept v2 gặp active plan sẵn có -> nhận 409 `ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS`, rollback sạch sẽ giữ `PENDING`, 0 application receipt, banner cảnh báo chuẩn xác. | `AiWorkoutProposalFullSliceIntegrationTest` (`acceptRecommendation_whenActivePlanAlreadyExists_rollsBackTransactionAndReturnsConflict`) |
| **Từ chối đề xuất (Reject)** | Trainer / `HUMAN_COACH` | **PASS** | Nhập lý do từ chối, bấm Từ chối, đề xuất cập nhật `REJECTED`, ghi nhận audit và hiển thị badge `REJECTED`. | `AiWorkoutProposalControllerUnitTest`, `AiWorkoutProposalModal.test.tsx` |
| **Học viên Tạo, Sửa v2, So sánh & Áp dụng thành công** | Student / `SELF_DIRECTED` | **PASS** | Thực thi trên `emulator-5554` với tài khoản `student.f02.runtime@example.com` (xác minh qua Mailpit local): Tạo đề xuất v1 -> sửa Day 1 từ 3 lên 4 hiệp -> lưu v2 -> so sánh bản gốc -> bấm Chấp thuận & Áp dụng -> tạo thành công kế hoạch `ACTIVE` trong DB và điều hướng đến màn hình chi tiết kế hoạch hiển thị 4 hiệp. | `AiWorkoutProposalRealFastApiIntegrationTest.multiTier_editDraft_andApplyEditedVersion_succeeds` (luồng đa tầng thật Spring Boot + FastAPI + Postgres Testcontainers: Student sửa draft v2 -> Accept -> Plan ACTIVE aggregateVersion=1 -> Idempotent replay). |
| **Học viên Thử can thiệp đề xuất của Trainer** | Student / `HUMAN_COACH` | **PASS (DESIGN RESTRICTION)** | **Ranh giới điều hướng sản phẩm:** Trong chế độ `HUMAN_COACH`, đề xuất chỉ được quản lý tại Trainer Workspace (`RelationshipDetailScreen` -> `ProgramScreen`). Màn hình Học viên chỉ hiển thị kế hoạch khi đã được kích hoạt, không có đường dẫn điều hướng mở modal đề xuất pending của Trainer. | `AiWorkoutProposalModal.test.tsx` (test case: *"hides edit draft button and disables accept button when student views trainer-managed proposal (HUMAN_COACH)"*), và server 403 `AiRunAccessDeniedException` tests trong `AiWorkoutProposalServiceUnitTest`. |

### 3.2. Danh mục Artifacts Bằng chứng Mobile Runtime

Tất cả 12 ảnh chụp màn hình thực tế từ Android Emulator được lưu trữ tại `docs/08-ai/artifacts/f02-mobile-runtime-validation/`:
1. [`mobile_f02_draft_01_proposal_v1.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_01_proposal_v1.png) — Ca B: Đề xuất AI ban đầu của Trainer (v1, PENDING, TRAINER).
2. [`mobile_f02_draft_02_edit_mode.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_02_edit_mode.png) — Ca B: Giao diện chế độ chỉnh sửa bản thảo.
3. [`mobile_f02_draft_03_field_edited.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_03_field_edited.png) — Ca B: Thao tác sửa số hiệp bài tập từ 3 thành 4 trên Day 3.
4. [`mobile_f02_draft_04_saved_v2.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_04_saved_v2.png) — Ca B: Giao diện sau khi lưu draft: hiển thị v2 và badge "Đã tùy chỉnh".
5. [`mobile_f02_draft_05_customized_v2_comparison_available.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_05_customized_v2_comparison_available.png) — Ca B: Huy hiệu và nút bấm mở so sánh bản gốc.
6. [`mobile_f02_draft_06_original_comparison_view.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_06_original_comparison_view.png) — Ca B: Chế độ hiển thị song song bản AI gốc bên dưới bản sửa đổi.
7. [`mobile_f02_draft_07_active_plan_conflict_banner.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_07_active_plan_conflict_banner.png) — Ca D: Banner cảnh báo xung đột kế hoạch active sẵn có, rollback an toàn giữ PENDING.
8. [`mobile_f02_draft_08_rejected_status.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_08_rejected_status.png) — Ca C: Trạng thái đề xuất cập nhật thành REJECTED sau khi huấn luyện viên từ chối kèm ghi chú.
9. [`mobile_f02_draft_09_student_proposal_v1.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_09_student_proposal_v1.png) — Ca A: Học viên SELF_DIRECTED tạo đề xuất giáo án mẫu 1 tuần (v1, PENDING, STUDENT).
10. [`mobile_f02_draft_10_student_saved_v2.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_10_student_saved_v2.png) — Ca A: Học viên lưu bản thảo v2 sau khi sửa Day 1 từ 3 thành 4 hiệp, hiển thị badge v2 và "Đã tùy chỉnh".
11. [`mobile_f02_draft_11_student_comparison_view.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_11_student_comparison_view.png) — Ca A: Học viên mở so sánh song song bản thảo v2 với bản gốc AI ban đầu.
12. [`mobile_f02_draft_12_student_plan_active_applied.png`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f02-mobile-runtime-validation/mobile_f02_draft_12_student_plan_active_applied.png) — Ca A: Học viên Chấp thuận & Áp dụng thành công, điều hướng trực tiếp đến màn hình chi tiết kế hoạch tập luyện ACTIVE hiển thị chuẩn xác 4 hiệp.

---

## 4. Danh sách Files đã Thay đổi và Mục đích

```text
Contracts & Database:
- contracts/openapi/openapi.yaml (Thêm endpoint PUT /draft, schemas request/response, expectedRevision)
- services/backend/src/main/resources/db/migration/V51__ai_recommendation_draft_revisions.sql (Migration thêm revision, provenance, customized flag)

Backend:
- services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/domain/AiRecommendation.java (Domain record/builder)
- services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/port/in/AiWorkoutProposalUseCase.java (Port updateDraft, UpdateDraftCommand/Result)
- services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/port/out/AiRecommendationRepository.java (Repository port)
- services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/out/persistence/JdbcAiRecommendationRepository.java (JDBC persistence updateDraft)
- services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiWorkoutProposalService.java (Service logic updateDraft, re-validation, optimistic lock)
- services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/AiWorkoutProposalController.java (REST Controller PUT /draft)
- services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/dto/UpdateRecommendationDraftApiRequest.java (DTO request)
- services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/dto/UpdateRecommendationDraftApiResponse.java (DTO response)
- services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/dto/AcceptRecommendationApiRequest.java (DTO mở rộng expectedRevision)
- services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/dto/AiRecommendationDetailApiResponse.java (DTO mở rộng revision, originalPayload, isCustomized)

Mobile:
- apps/mobile/src/types/aiWorkoutProposal.ts (TypeScript definitions)
- apps/mobile/src/services/aiWorkoutProposalApi.ts (API client methods & error mappings)
- apps/mobile/src/features/workout-plan/components/AiWorkoutProposalModal.tsx (UI Edit form, badges, comparison, authority guards)

Tests:
- services/backend/src/test/java/com/fitnesscoaching/platform/AiWorkoutProposalPersistenceIntegrationTest.java
- services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/adapter/in/web/AiWorkoutProposalControllerUnitTest.java
- services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/application/service/AiWorkoutProposalServiceUnitTest.java
- services/backend/src/test/java/com/fitnesscoaching/platform/AiWorkoutProposalFullSliceIntegrationTest.java
- apps/mobile/src/__tests__/AiWorkoutProposalModal.test.tsx

Docs:
- docs/08-ai/f02-draft-edit-handover-notes.md (Ghi chú tiếp quản)
- docs/08-ai/f02-workout-proposal-readiness-and-slice-report.md (Báo cáo tổng hợp lát cắt F02)
```

---

## 5. Trạng thái Hoàn thành & Kế hoạch Tiếp theo

### 5.1. Đánh giá Gói Chỉnh Draft F02
- **Kết luận:** Gói chỉnh draft F02 **ĐỦ ĐIỀU KIỆN REVIEW VÀ BÀN GIAO**.
- Các yêu cầu về nghiệp vụ, hợp đồng, cơ sở dữ liệu, backend service, audit log, và mobile UI trong phạm vi gói Draft Edit đã được triển khai đầy đủ, đối soát qua 85 bài kiểm thử tự động (49 backend, 33 mobile, 3 pytest) cùng 12 ảnh bằng chứng Mobile GUI runtime thực tế trên Android emulator.
- Lưu ý phạm vi: Sử dụng simulated provider ($0.00 spend); kịch bản học viên có kế hoạch ACTIVE sẵn có vẫn nhận xung đột 409 theo đúng contract; quy trình thay thế kế hoạch đang active thuộc phạm vi công việc kế tiếp và không tuyên bố hoàn thành toàn bộ Milestone P2-M3.

### 5.2. Thứ tự Công việc Kế tiếp
Theo đúng tài liệu phê duyệt (`phase-2-release-scope.md` và `phase-2-implementation-plan.md`):
1. **Bước 1:** Người dùng rà soát (code review) và thực hiện các thao tác Git (stage, commit, tạo PR, merge) cho gói chỉnh draft F02 trên nhánh `feat/p2-m3-f02-draft-edit-20261029`.
2. **Bước 2 (Tiếp tục trong P2-M3):** Xử lý nghiệp vụ xung đột kế hoạch đang hoạt động (`ACTIVE` plan) theo contract (làm rõ hoặc bổ sung workflow kế thừa/thay thế giáo án active nếu thuộc phạm vi).
3. **Bước 3 (P2-M3):** Bổ sung kiểm chứng và đối chiếu tiêu chí nghiệm thu toàn diện của Milestone P2-M3 trước khi đóng milestone.
4. **Lưu ý định tuyến:** Tính năng **F03** (Thay bài tập, điều chỉnh volume/load trong kế hoạch hiện có) thuộc về **Milestone P2-M4** (theo Mục 1 bảng phạm vi phát hành và implementation plan), không thuộc P2-M3.


---

## 6. Gói Bổ sung: Hoàn thiện Vòng đời & Kế hoạch ACTIVE (P2-M3 Active Plan Lifecycle)

- **Thời điểm hoàn thiện:** 2026-10-30
- **Branch:** `feat/p2-m3-f02-active-plan-lifecycle-20261030`
- **Baseline origin/main:** `2cc90b96b8f3dfdfca733696719c398d3495a07b` (PR #120 merged, CI 6/6 SUCCESS, Run ID 40935396051)
- **Mục tiêu & Phạm vi triển khai:**
  1. **Khi chưa có ACTIVE plan:** Duy trì luồng chuẩn `createDraft` -> `activate`, tạo kế hoạch mới phiên bản 1.
  2. **Khi đã có ACTIVE plan (cùng thẩm quyền sở hữu):**
     - Thực thi `workoutPlanCommandUseCase.publishVersion` xuất bản version mới trên cùng Plan ID (tăng `aggregate_version`, đóng version cũ tại boundary timestamp và mở version mới).
     - Bảo vệ ràng buộc duy nhất: `uq_workout_plan_one_active_per_student` (tối đa 1 kế hoạch ACTIVE cho mỗi học viên).
     - Ghi nhận `ai_recommendation_applications` với `action = "PUBLISHED_NEW_VERSION"`, `planVersionId`, `appliedRevision`, `isCustomized`.
  3. **Khi khác thẩm quyền sở hữu:**
     - Nếu Học viên ở `SELF_DIRECTED` muốn thay đổi kế hoạch do Huấn luyện viên tạo từ trước: Domain command ném `WORKOUT_PLAN_SUCCESSOR_REQUIRED` (409 Conflict), transaction rollback toàn vẹn, recommendation giữ `PENDING`.
     - Nếu Huấn luyện viên muốn thay đổi kế hoạch do Học viên tạo: Domain command ném `WORKOUT_PLAN_ACCESS_DENIED` (403 Forbidden).
  4. **Bảo vệ chống Stale Plan Version & Active Plan xuất hiện sau Preview (Plan Version Guards):**
     - Mở rộng hợp đồng `AcceptRecommendationRequest`: hỗ trợ `expectedPlanId`, `expectedPlanAggregateVersion`, `expectedNoActivePlan`.
     - Phân biệt rành mạch giữa `expectedRevision` (phiên bản bản thảo AI draft $v1, v2, ...$) và `expectedPlanAggregateVersion` (phiên bản kế hoạch tập luyện trong domain aggregate $0, 1, 2, ...$).
     - Nếu người dùng xem trước khi chưa có plan active nhưng trước khi Accept xuất hiện plan active -> Server từ chối 409 (`expectedNoActivePlan` conflict).
     - Nếu người dùng xem trước khi plan ở version $V$, nhưng trước khi Accept có writer khác xuất bản version $V+1$ -> Server từ chối 409 (`WORKOUT_PLAN_VERSION_CONFLICT`).
     - Tương thích ngược: Các caller không truyền thông số plan version vẫn tuân theo cơ chế kiểm tra tự động của domain command.
  5. **Bảo toàn Dữ liệu Lịch sử & Scheduling:**
     - Các buổi tập đã xếp lịch (`planned_workouts`) đã materialize giữ nguyên định danh session và version ban đầu; không bị re-link hay re-map.
     - Lần xếp lịch trong tương lai mới chọn phiên bản kế hoạch mới có hiệu lực.
     - Toàn bộ buổi tập đang diễn ra (`IN_PROGRESS`), đã hoàn thành (`COMPLETED`, `FROZEN`) và nhật ký thực hiện (`workout_session_logs`) là bất biến.
  6. **F11 Decision Events:**
     - Ghi nhận quyết định Accept / Reject qua `decided_by`, `decided_at`, `decision_note` trên bảng `ai_recommendations`.
     - Lưu trữ application receipt trên bảng `ai_recommendation_applications`.
     - Ghi nhận nhật ký kiểm toán bất biến `AI_RECOMMENDATION_APPLIED` / `AI_RECOMMENDATION_REJECTED` trong cùng transaction boundary (rollback toàn bộ nếu audit lỗi).
     - Không ép buộc nhập ghi chú để được phép Reject đề xuất.
  7. **Mobile Client (React Native Expo):**
     - Hiển thị thẻ đối chiếu cụ thể trước khi xác nhận (**Before / After & Plan Impact Card**): Kế hoạch hiện tại (tên, vX) vs Kế hoạch đề xuất (tên, vX+1) cùng cảnh báo tác động xếp lịch.
     - Gửi kèm `expectedRevision`, `expectedPlanId`, `expectedPlanAggregateVersion`, `expectedNoActivePlan` khi bấm Chấp thuận.
     - Ánh xạ thông điệp tiếng Việt rõ ràng cho các mã lỗi `WORKOUT_PLAN_SUCCESSOR_REQUIRED`, `WORKOUT_PLAN_VERSION_CONFLICT`, `WORKOUT_PLAN_ACCESS_DENIED`.
     - Nút "Tải lại đề xuất" đồng thời tải lại trạng thái kế hoạch active mới nhất từ server để xem lại trước khi thử lại.

---

## 7. Ma trận Đối soát Tiêu chí Nghiệm thu P2-M3 (T01–T08, F11)

> [!IMPORTANT]
> Toàn bộ tên ca và kết quả cần chứng minh dưới đây được trích xuất **nguyên văn 100% từ tài liệu kế hoạch đã được phê duyệt** (`docs/07-development/phase-2-implementation-plan.md` mục 11.1).

| Mã | Tình huống (Nguyên văn từ Kế hoạch) | Kết quả Cần chứng minh (Nguyên văn từ Kế hoạch) | Triển khai trong Codebase | Bằng chứng Kiểm thử Tự động Mới | Kiểm chứng Runtime Local | Trạng thái & Gaps |
| :--- | :--- | :--- | :--- | :--- | :---: | :--- |
| **T01** | Student tự tập Accept plan | Chỉ plan/phạm vi xác nhận thay đổi; audit/version đúng | `AiWorkoutProposalService.acceptRecommendation`: tạo plan v1 (nếu chưa có) hoặc publishVersion vN+1 (nếu đã có); ghi nhận audit | `AiWorkoutProposalFullSliceIntegrationTest.fullSlice_selfDirected_generateAndAccept_succeeds` & `existingActivePlan_whenOwnerAccepts_publishesNewVersionSuccessfully` | **NOT RUN** (Không có emulator đang chạy) | **Implementation PASS**. Cần chạy GUI trên thiết bị trước nghiệm thu milestone. |
| **T02** | Student HUMAN_COACH tự Accept plan AI | Bị chặn; Trainer hợp lệ mới quyết định | `AiWorkoutProposalService`: kiểm tra `coachingContextQuery` và `decisionAuthority`; chặn 403 `AiRunAccessDeniedException` | `AiWorkoutProposalFullSliceIntegrationTest.fullSlice_humanCoach_authorityEnforced` & `AiWorkoutProposalServiceUnitTest` | **NOT RUN** (Không có emulator đang chạy) | **Implementation PASS**. Đã có bằng chứng lịch sử (PR #120); phiên này chưa chạy lại live GUI. |
| **T03** | Trainer không liên quan, view-only, revoked hoặc hết period | Không request/read/apply trái scope | Kiểm tra quan hệ huấn luyện, coaching period continuity, quyền `MANAGE` | `AiWorkoutProposalFullSliceIntegrationTest.fullSlice_humanCoach_authorityEnforced` (unrelated trainer 403) | **NOT RUN** | **Implementation PASS**. |
| **T04** | Thu hồi quyền trong lúc model đang xử lý | Không trả output nhạy cảm khi job hoàn tất hoặc mở lại | `getRecommendation` và `acceptRecommendation` re-validate quyền hiện hành trước khi đọc hoặc áp dụng | `AiWorkoutProposalFullSliceIntegrationTest.revokedAuthority_cannotReplay` | **NOT RUN** | **Implementation PASS**. |
| **T05** | Nguồn đổi trước hoặc trong transaction; insert plan/log/appointment cạnh tranh | Hard conflict hoặc revalidation đúng phân loại; khóa/constraint chặn race; thay đổi không liên quan không bắt sinh lại | Khóa bi quan `lockStudentAndActor`, `lockPlan`; kiểm tra `expectedPlanAggregateVersion`, `expectedNoActivePlan`, `expectedRevision` | `AiWorkoutProposalFullSliceIntegrationTest.existingActivePlan_whenPlanVersionChangedAfterPreview_causesConflictAndRollback` & `noActivePlan_whenActivePlanAppearedAfterPreview_causesConflictAndRollback` | **NOT RUN** | **Implementation PASS**. |
| **T06** | Hai Accept đồng thời hoặc retry sau timeout | Một tác động nghiệp vụ; không duplicate plan/log/audit thành công | Khóa `findByIdForUpdate` trên recommendation; idempotent replay đối chiếu hash/commandKey/revision/plan | `AiWorkoutProposalFullSliceIntegrationTest.concurrentDoubleAccept_createsExactlyOnePlan` & `idempotencyReplay_andConflictHandling` | **NOT RUN** | **Implementation PASS**. |
| **T07** | Measurement/body fat thiếu hoặc suspect | Unknown/giới hạn; không bịa zero hoặc suy ra tăng cơ | `F02RuntimeOutputValidator` và `F02ContextBuilder` xử lý dữ liệu thiếu; schema cho phép missingData | `F02RuntimeOutputValidatorUnitTest`, schema validation | **NOT RUN** | **Implementation PASS**. Thuộc pipeline Context/Validator. |
| **T08** | Người mới khác người nghỉ 100 ngày | Rule xử lý khác, không lấy tải cũ làm năng lực hiện tại | Prompt và Context builder phân biệt kinh nghiệm BEGINNER vs ADVANCED; rule engine validation | `F02AiServiceClientTest`, AI Service mock/simulated provider | **NOT RUN** | **Implementation PASS** trong pipeline rule. |
| **F11** | Feedback quyết định và kết quả tập thực tế để đánh giá chất lượng (phần đầu) | Ghi Accept/Reject như decision event; tùy chọn feedback tách riêng, không ép lý do để được Reject | Lưu `decided_by`, `decided_at`, `decision_note` trên `ai_recommendations`; receipt trên `ai_recommendation_applications`; audit bất biến; reason tùy chọn | `AiWorkoutProposalFullSliceIntegrationTest.rejectProposal_createsNoWorkoutPlan` & `auditFailureDuringAccept_rollsBackEntireTransaction` | **NOT RUN** | **Implementation PASS** cho phần quyết định P2-M3. Phần feedback outcome người dùng thuộc P2-M6. |

---

## 8. Kết quả Kiểm thử Tự động Chi tiết (Automated Evidence)

1. **Backend Unit Tests:**
   - File: `AiWorkoutProposalServiceUnitTest.java`
   - Lệnh: `mvnw.cmd test -Dtest=AiWorkoutProposalServiceUnitTest`
   - Kết quả: **22/22 tests PASS (100%)**, 0 failures, 0 errors.
2. **Backend Full Slice Integration Tests (PostgreSQL 18 Testcontainers):**
   - File: `AiWorkoutProposalFullSliceIntegrationTest.java`
   - Lệnh: `mvnw.cmd test -Dtest=AiWorkoutProposalFullSliceIntegrationTest`
   - Kết quả: **18/18 tests PASS (100%)**, 0 failures, 0 errors.
3. **Backend Controller Unit Tests:**
   - File: `AiWorkoutProposalControllerUnitTest.java`
   - Lệnh: `mvnw.cmd test -Dtest=AiWorkoutProposalControllerUnitTest`
   - Kết quả: **5/5 tests PASS (100%)**, 0 failures, 0 errors.
4. **Backend Concurrency Guard B05:**
   - File: `WorkoutExecutionConcurrencyIntegrationTest.java`
   - Lệnh: `mvnw.cmd test -Dtest=WorkoutExecutionConcurrencyIntegrationTest#doubleStartSameOccurrenceCreatesExactlyOneExecutionAndSnapshot`
   - Kết quả: **1/1 test PASS (100%)**, 0 failures, 0 errors.
5. **Mobile Client Tests:**
   - Lệnh: `npm --prefix apps/mobile test -- --watchAll=false src/__tests__/AiWorkoutProposalModal.test.tsx src/__tests__/aiWorkoutProposalApi.test.ts`
   - Kết quả: **2 suites PASS, 22/22 tests PASS (100%)**.
6. **Mobile Typecheck:**
   - Lệnh: `npm --prefix apps/mobile run typecheck`
   - Kết quả: `tsc --noEmit` hoàn thành với **Exit code 0 (0 lỗi)**.
7. **Hợp đồng & Schemas:**
   - Lệnh: `npm run validate:contracts` & `node --test tools/validate-contracts.test.mjs`
   - Kết quả: OpenAPI 3.0 valid, AI Schemas passed (6 schemas, 22 fixtures), Contract test suites: 21/21 tests PASS.

---

## 9. Đánh giá Môi trường & Kết luận

- **Tuyên bố minh bạch về Runtime Local:**
  Trong phiên làm việc này, các tiến trình backend daemon (port 8080) và AI service daemon (port 8000) không được bật chạy ngầm; không có thiết bị giả lập Android (`emulator-5554`) nào đang kết nối (`adb devices` trống). Do đó, các kịch bản kiểm chứng Mobile GUI runtime thực tế trên thiết bị đối với gói bổ sung này được ghi nhận chính xác là **NOT RUN**. Không sử dụng kết quả kiểm thử tự động Testcontainers để khẳng định ca Mobile runtime đã PASS.
- **Đánh giá Implementation:**
  Toàn bộ mã nguồn, hợp đồng, rào chắn concurrency, version guards, và mobile UI component đã được rà soát, vá các lỗ hổng tiềm ẩn (stale plan version race, active plan appearance race), và kiểm chứng tự động toàn diện trên Testcontainers PostgreSQL 18.
- **Đề xuất Bước tiếp theo:**
  Commit và đẩy branch `feat/p2-m3-f02-active-plan-lifecycle-20261030`, tạo Pull Request vào `main` để người phụ trách tiến hành code review và staging runtime validation trước khi merge.
