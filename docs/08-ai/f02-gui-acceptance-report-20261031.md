# Báo Cáo Kiểm Chứng Kỹ Thuật & GUI Runtime F02 (P2-M3)

> **Mã đầu việc:** P2-M3 — Kiểm chứng GUI Form F02 sau PR #123, đối soát dữ liệu đa tầng và rà soát hồ sơ nghiệm thu
> **Repository:** `D:\Projects\ai-integrated-personalized-online-fitness-coaching-system`
> **Branch thực hiện:** `test/p2-m3-f02-gui-acceptance-20261031`
> **Commit nền xác minh (Baseline commit):** `b471e25c024447ead5abc08783a8c044a7cfb021` (origin/main, tích hợp PR #123 `aa2a9a6`, PR #122 `70a5001`, và hotfix PR #124 `a90ede2`)
> **Thời điểm thực hiện kiểm chứng:** 2026-10-31 23:20 – 23:55 (UTC+7)
> **Môi trường runtime kiểm chứng thực tế:**
> - Mobile: Android Emulator Pixel 8 (Android 14 API 34, `emulator-5554`), Expo SDK 54 / React Native 0.81.5, Metro port 8081
> - Backend: Java 21, Spring Boot 4.1.1 (port 8080, profile `dev`, mode `http`)
> - AI Service: Python 3.12, FastAPI (port 8000, `SimulatedF02WorkoutProvider`, $0.00 chi phí API)
> - Database: PostgreSQL 18.6 (Docker port 5433, schema `fitness`, Flyway history V01–V53 thành công, bao gồm V52 `source_snapshot` và V53 chat)
> **Thư mục ảnh bằng chứng thực tế:** [`artifacts/f02-mobile-runtime-acceptance-20261031/`](artifacts/f02-mobile-runtime-acceptance-20261031)

---

## 1. Tóm Tắt Kết Quả Kiểm Chứng & Phân Loại Bằng Chứng

Đợt kiểm chứng ngày 31/10/2026 đã đối soát toàn diện chức năng F02 (Tạo đề xuất giáo án 1 tuần) sau khi merge PR #123. Toàn bộ các kết luận được phân định minh bạch theo từng phương thức kiểm chứng:

1. **GUI Runtime trên Android Emulator Pixel 8:**
   - **Form F02 mới:** Hiển thị trực quan các trường nhập liệu thời lượng, ngày bắt đầu (tính theo `Asia/Ho_Chi_Minh`), chip thiết bị, và nút radio xác nhận chấn thương/hạn chế thể chất (bắt buộc xác nhận trung thực, không tự động điền sẵn).
   - **Student `SELF_DIRECTED`:** Tạo đề xuất v1 trạng thái `PENDING`, chỉnh sửa draft sang revision v2 (`is_customized = true`), kích hoạt chế độ so sánh song song với bản AI gốc, xem thẻ Before/After impact card, và Accept thành công cho cả 2 trường hợp:
     - *Đã có ACTIVE plan:* Kích hoạt `publishVersion` trên cùng Plan ID `3e26acd8...`, nâng aggregate version từ 8 lên 9 (bảo toàn lịch sử các buổi tập).
     - *Chưa có ACTIVE plan:* Kích hoạt `createDraft -> activate` (`CREATED_AND_ACTIVATED_NEW_PLAN`), tạo mới giáo án `ACTIVE` v1 `7844c7a5...`.
   - **Trainer `HUMAN_COACH`:** Chọn học viên được giao (`smoke.student@example.com`), tạo đề xuất AI, chỉnh sửa draft số hiệp bài 1 từ 3 lên 4, lưu draft v2, đối chiếu Before/After và Accept áp dụng công bố phiên bản mới (v4) trên cùng Plan ID của học viên (`bb2a6813...`).
   - **Validation GUI:** Chặn gửi form và hiển thị field error song ngữ ngay trên màn hình khi thiếu xác nhận hạn chế (C1) hoặc có hạn chế nhưng thiếu mô tả (C2), giữ nguyên 100% dữ liệu đã nhập.
2. **Live API & Database Inspection:**
   - **Phân quyền Student trong `HUMAN_COACH` (B4):** Kiểm chứng qua Live API call với token của `smoke.student` trả về đúng HTTP 403 `AI_FORBIDDEN` (*"In HUMAN_COACH mode, only the assigned coach may request a workout proposal"*).
   - **Validation Fail-Fast (C3, C4, C7):** Kiểm chứng qua Live API call với dữ liệu thiếu thiết bị (C3) và ngày bắt đầu trong quá khứ (C4) trả về đúng HTTP 400 `VALIDATION_FAILED`. Đối soát log FastAPI `task-142` xác nhận **0 incoming request** trong cửa sổ thực hiện `2026-10-31 16:50:52.943Z - 16:50:53.042Z UTC` (23:50:52 UTC+7).
   - **Xung đột & Source Guards (D1, D2, D3, D5):** Kiểm chứng qua Live API kết hợp thao tác dữ liệu: sai `expectedPlanAggregateVersion` (D1), Goal version bị thay đổi trước khi Accept (D2), và stale revision (D3) đều trả về HTTP 409 `AI_RUN_LIFECYCLE_CONFLICT`. Đối soát DB xác nhận `ai_recommendation_applications` không có bản ghi nào được tạo và proposal giữ nguyên `PENDING` (rollback toàn vẹn).
3. **Automated Component & Integration Tests:**
   - **Cô lập học viên (E1–E3):** Được chứng minh qua 2 automated tests trong `AiWorkoutProposalModal.test.tsx` (`resets proposal and user inputs when studentId changes` và `ignores stale in-flight response when studentId changes during request`) kết hợp code inspection cơ chế `currentStudentRef` và `activeRequestIdRef`. *(Không thực hiện drill GUI trực tiếp bằng độ trễ nhân tạo trên emulator)*.
   - **Continuity states & Inactivity (F4–F5):** Được chứng minh qua 19 tests trong `F02ContextBuilderUnitTest.java`, 25 tests trong `AiWorkoutProposalFullSliceIntegrationTest.java` (chạy mới trong phiên), và 53 tests trong `test_f02_provider.py` (kế thừa từ PR #123). *(Không mô phỏng lịch sử 100 ngày bằng log giả trên emulator)*.

---

## 2. Ma Trận Kiểm Chứng Chi Tiết Từng Ca (Test Evidence Matrix)

| Test ID | Yêu cầu kiểm chứng | Phương thức kiểm chứng | Bằng chứng cụ thể | Kết quả | Hiệu chỉnh so với báo cáo ban đầu & Phần chưa chứng minh |
| :---: | :--- | :--- | :--- | :---: | :--- |
| **A1** | Nhập form F02 mới (thời lượng, ngày, thiết bị, hạn chế) | **GUI runtime** (Pixel 8 Emulator) | Ảnh [`gui_01_student_new_form_initial.png`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_01_student_new_form_initial.png) | **PASS** | Không có khoảng trống. |
| **A2** | Generate proposal, loading, chống gửi trùng | **GUI runtime** + **Component unit test** + **Code inspection** | Ảnh [`gui_04_student_proposal_v1_generated.png`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_04_student_proposal_v1_generated.png) (kết quả sinh v1); test `AiWorkoutProposalModal.test.tsx` (disable submit khi đang generating); code inspection guard `isGenerating` | **PASS** | **Hiệu chỉnh:** Ảnh tĩnh chỉ chứng minh kết quả sinh proposal v1; hành vi loading spinner và chống gửi trùng (anti-double-submit) được chứng minh kết hợp qua unit test component và guard `isGenerating` trong code. Dùng simulated provider; không chứng minh chất lượng văn phong LLM thật. |
| **A3** | Chỉnh draft, revision v2, so sánh AI gốc | **GUI runtime** (Pixel 8 Emulator) | Ảnh [`gui_05`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_05_student_draft_edit_mode.png), [`gui_06`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_06_student_draft_field_edited.png), [`gui_08`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_08_student_proposal_v2_customized.png), [`gui_09`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_09_student_original_ai_comparison_view.png) | **PASS** | Không có khoảng trống. |
| **A4** | Thẻ đối chiếu Before / After & tác động kế hoạch | **GUI runtime** (Pixel 8 Emulator) | Ảnh [`gui_07`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_07_student_before_after_impact_card.png) (có plan active) & [`gui_11`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_11_student_before_after_no_active_plan.png) (chưa có plan) | **PASS** | Không có khoảng trống. |
| **A5** | Accept & Apply khi đã có ACTIVE plan (publishVersion) | **GUI runtime** + **DB inspection** | Ảnh [`gui_10_student_plan_active_applied_v9.png`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_10_student_plan_active_applied_v9.png); DB Plan `3e26acd8...` aggregate version tăng 8 -> 9 | **PASS** | Không có khoảng trống. |
| **A6** | Accept & Apply khi chưa có ACTIVE plan (createDraft -> activate) | **GUI runtime** + **DB inspection** | Ảnh [`gui_12_student_plan_active_new_created_v1.png`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_12_student_plan_active_new_created_v1.png); DB Plan `7844c7a5...` status `ACTIVE` v1 | **PASS** | Không có khoảng trống. |
| **A7** | Đối soát DB: Version, Receipt, Provenance | **DB inspection** (PostgreSQL 18) | Truy vấn SQL tại Mục 3.1: `ai_recommendations` APPLIED, `ai_recommendation_applications` ghi nhận payload nguyên tử | **PASS** | Không có khoảng trống. |
| **B1** | Trainer chọn học viên và tạo proposal v1 | **GUI runtime** (Pixel 8 Emulator) | Ảnh [`gui_13`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_13_trainer_new_form_initial.png), [`gui_14`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_14_trainer_proposal_v1_generated.png) | **PASS** | Không có khoảng trống. |
| **B2** | Trainer chỉnh draft, xem preview, Accept | **GUI runtime** (Pixel 8 Emulator) | Ảnh [`gui_15`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_15_trainer_draft_edit_mode.png), [`gui_16`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_16_trainer_proposal_v2_customized.png), [`gui_17`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_17_trainer_plan_active_applied_v4.png) | **PASS** | Không có khoảng trống. |
| **B3** | Kiểm tra quyền & sở hữu kế hoạch học viên | **DB inspection** (PostgreSQL 18) | Plan `bb2a6813...` nâng version 4, `student_id = 5c6f...` (Smoke Student), `created_by = 1cb9...` (Trainer) | **PASS** | Không có khoảng trống. |
| **B4** | Chặn Student thao tác vượt quyền trong `HUMAN_COACH` | **Live API** + **Automated test** | Request `POST /api/v1/ai/workout-proposals` bằng token Student trả về HTTP 403 `AI_FORBIDDEN`; test `acceptRecommendation_whenModeTransitionedToHumanCoach_deniesStudentAcceptance` PASS | **PASS (API & Test)** | **Hiệu chỉnh:** Sửa phân loại từ "GUI PASS" sang **Live API & Automated Test PASS**. Giao diện Mobile Student được bảo vệ bằng test ẩn nút/disable accept trong `AiWorkoutProposalModal.test.tsx` (chưa chụp ảnh trực tiếp trên emulator phiên này). |
| **C1** | Validation: Thiếu xác nhận chấn thương / hạn chế | **GUI runtime** (Pixel 8) + **Live API** | Ảnh [`gui_02_validation_error_c1_missing_confirmation.png`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_02_validation_error_c1_missing_confirmation.png); API trả HTTP 400 field `limitations` | **PASS** | Không có khoảng trống. |
| **C2** | Validation: Có hạn chế nhưng thiếu mô tả | **GUI runtime** (Pixel 8) + **Live API** | Ảnh [`gui_03_validation_error_c2_missing_limitation_desc.png`](artifacts/f02-mobile-runtime-acceptance-20261031/gui_03_validation_error_c2_missing_limitation_desc.png); API trả HTTP 400 field `limitationsDescription` | **PASS** | Không có khoảng trống. |
| **C3** | Validation: Thiếu thiết bị / bodyweight | **Live API** + **Backend unit test** | Node request gửi `equipment: []` nhận HTTP 400 `field: equipment, code: REQUIRED`; test trong `F02ContextBuilderUnitTest` PASS | **PASS (API & Test)** | **Hiệu chỉnh:** Làm rõ được kiểm chứng qua Live API & Unit test; không chụp ảnh riêng trên emulator vì chip mặc định luôn chọn BODYWEIGHT. |
| **C4** | Validation: Ngày bắt đầu không hợp lệ (quá khứ) | **Live API** + **Backend unit test** | Node request gửi `startDate: 2020-01-01` nhận HTTP 400 `field: startDate, code: PAST_DATE`; test trong `F02ContextBuilderUnitTest` PASS | **PASS (API & Test)** | **Hiệu chỉnh:** Làm rõ kiểm chứng qua Live API & Unit test; không chụp ảnh riêng trên emulator. |
| **C5** | Validation: Thiếu profile hoặc Goal bắt buộc | **Backend unit test** + **Code inspection** | `F02ContextBuilderUnitTest` kiểm tra `activeGoal` ném `AiRunValidationException`; code [`F02ContextBuilder.java:128-144](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02ContextBuilder.java#L128-L144) | **PASS (Test & Code)** | Không xóa Goal trực tiếp trong DB runtime để bảo toàn fixtures kiểm chứng. |
| **C6** | Giữ dữ liệu nhập khi gặp lỗi validation | **GUI runtime** (Pixel 8 Emulator) | Quan sát thực tế trên Pixel 8: khi gặp lỗi C1 hoặc C2, toàn bộ chip thiết bị, số ngày, thời lượng giữ nguyên không bị mất | **PASS** | Không có khoảng trống. |
| **C7** | Fail-fast: 0 request tới provider khi validation fail | **FastAPI log inspection** | Log `task-142.log` ghi nhận đúng 0 request trong cửa sổ test `2026-10-31 16:50:52.943Z - 16:50:53.042Z UTC` (23:50:52 UTC+7) | **PASS** | Cửa sổ thời gian được đối soát khớp từng mili-giây. |
| **D1** | Xung đột version kế hoạch sau preview (`expectedPlanAggregateVersion`) | **Live API** + **Automated integration test** | Node request gửi `expectedPlanAggregateVersion = 999` nhận HTTP 409 `AI_RUN_LIFECYCLE_CONFLICT: Workout plan version conflict...` | **PASS (API & Test)** | **Hiệu chỉnh:** Ghi rõ là Live API drill; ảnh banner reload trên emulator kế thừa từ PR #121 (`mobile_f02_draft_07_active_plan_conflict_banner.png`). |
| **D2** | Goal version thay đổi trước khi Accept | **Live API** + **DB manipulation drill** | Sửa `goalVersionNumber` lên 999 trong DB -> Gửi accept nhận HTTP 409 `Fitness goal version has changed since proposal was generated...` | **PASS (API & DB)** | DB applications count = 0, proposal giữ nguyên PENDING. |
| **D3** | Stale draft revision (`expectedRevision = 1` khi draft v2) | **Live API** + **Automated integration test** | Node request gửi `expectedRevision = 1` khi draft v2 nhận HTTP 409 `Revision conflict: expected revision 1 but current is 2` | **PASS (API & Test)** | Không có khoảng trống. |
| **D4** | Chặn tự động retry apply khi xung đột; hỗ trợ reload | **Component unit test** + **Code inspection** | [`AiWorkoutProposalModal.tsx:434-450](../../apps/mobile/src/features/workout-plan/components/AiWorkoutProposalModal.tsx#L434-L450) (`reload-ai-proposal-button`); `AiWorkoutProposalModal.test.tsx` PASS | **PASS (Test & Code)** | **Hiệu chỉnh:** Hành vi dừng apply và chờ user bấm reload được chứng minh qua code inspection và component test; không phải ảnh tĩnh. |
| **D5** | Tính nguyên tử: Rollback toàn vẹn khi conflict | **DB inspection** (PostgreSQL 18) | Kiểm tra DB sau các ca D1..D3: 0 application record, recommendation giữ nguyên PENDING, không có cập nhật plan dở dang | **PASS** | Không có khoảng trống. |
| **D6** | Catalog item chuyển inactive sau khi proposal được tạo | **Automated test** + **Code inspection** | [`F02RuntimeOutputValidator.java:196-202](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02RuntimeOutputValidator.java#L196-L202) gọi `lockAuthoringReferences`; ném `AiOutputValidationException` (HTTP 422) và rollback apply | **PASS (Test & Code)** | Được kiểm chứng trong bộ test backend; chưa chạy ca tắt exercise catalog trực tiếp trong DB runtime. |
| **E1–E3**| Cô lập học viên & hủy response bất đồng bộ muộn | **Component unit test** + **Code inspection** | `AiWorkoutProposalModal.test.tsx` (2 tests: resets on studentId change, drops stale in-flight response); code `currentStudentRef` & `activeRequestIdRef` | **PASS (Test & Code)** | **Hiệu chỉnh:** Sửa phân loại từ "GUI PASS" sang **Component Test & Code Inspection PASS**; không tạo độ trễ nhân tạo trên live emulator. |
| **F1** | Ngày địa phương theo `Asia/Ho_Chi_Minh` (UTC+7) | **GUI runtime** (Pixel 8) + **Backend unit test** | Form tự sinh ngày mai theo UTC+7 (`2026-11-01` / `2026-11-02`); `F02ContextBuilderUnitTest` kiểm tra Clock và ZoneId | **PASS** | **Hiệu chỉnh:** Ghi rõ một ca ngày địa phương cụ thể không chứng minh loại bỏ 100% mọi rủi ro lệch ngày ở các múi giờ quốc tế khác; cần mở rộng holdout tests ở P2-M6. |
| **F2** | Chuyển đổi ngày không bị trừ lùi do parse UTC | **Live API** + **DB inspection** | Mobile gửi chuỗi ISO `YYYY-MM-DD`, backend parse theo ZoneId học viên; DB lưu đúng ngày bắt đầu | **PASS** | Không có khoảng trống. |
| **F3** | Dữ liệu thiết bị & bodyweight hiển thị đúng | **GUI runtime** (Pixel 8 Emulator) | Chip thiết bị phản ánh đúng danh mục (BODYWEIGHT, DUMBBELL, BARBELL, RESISTANCE_BAND, CABLE, MACHINE) | **PASS** | Không có khoảng trống. |
| **F4–F5**| Phân loại Continuity (`CONFIRMED_NEW`, `UNKNOWN_HISTORY`, `PROLONGED_INACTIVITY` $\ge 100$ ngày) | **Backend unit test** + **AI pytest** | `F02ContextBuilderUnitTest.java` (19 tests PASS); `test_f02_provider.py` (53 tests PASS) | **PASS (Test)** | **Hiệu chỉnh:** Sửa phân loại từ "GUI PASS" sang **Automated Test PASS**; không seed log tập giả lập 100 ngày trên live emulator. |

---

## 3. Bằng Chứng Đối Soát Dữ Liệu Thực Tế (PostgreSQL 18)

### 3.1. Đối soát ca Student `SELF_DIRECTED` (Plan có sẵn: Nâng cấp Version)
```sql
-- Kiểm tra Recommendation và Application
SELECT id, status, decision_authority, revision, is_customized, target_entity_id, decided_at
FROM fitness.ai_recommendations WHERE id = '016d06c4-4448-4fd5-881d-2cc494ca6e61';
-- Kết quả: status = 'APPLIED', decision_authority = 'STUDENT', revision = 2, is_customized = true, target_entity_id = '3e26acd8-ce5b-4409-acc3-eaff71f1755d'

SELECT action, applied_revision, is_customized, resulting_plan_id, expected_plan_aggregate_version
FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = '016d06c4-4448-4fd5-881d-2cc494ca6e61';
-- Kết quả: action = 'PUBLISHED_NEW_VERSION', applied_revision = 2, is_customized = true, expected_plan_aggregate_version = 8

-- Kiểm tra Workout Plan Aggregate Version đã tăng
SELECT id, status, version, updated_at FROM fitness.workout_plans WHERE id = '3e26acd8-ce5b-4409-acc3-eaff71f1755d';
-- Kết quả: status = 'ACTIVE', version = 9 (Aggregate version tăng đúng 8 -> 9 theo database trigger guard)
```

### 3.2. Đối soát ca Trainer `HUMAN_COACH` (Áp dụng cho học viên được giao)
```sql
SELECT id, student_id, status, decision_authority, revision, is_customized, target_entity_id, decided_by, decided_at
FROM fitness.ai_recommendations WHERE id = '5e7c0bad-002b-4c48-8610-6f05fece5e76';
-- Kết quả:
--   student_id = '5c6f49c6-f4f6-4941-b217-2574b3830395' (Smoke Student)
--   decided_by = '1cb982ef-b7e6-493c-86f9-fe4994f8ad93' (Smoke Trainer)
--   status = 'APPLIED', decision_authority = 'TRAINER', revision = 2, is_customized = true

SELECT application_payload->>'action' as action, application_payload->>'appliedRevision' as rev, application_payload->>'expectedPlanAggregateVersion' as agg_ver
FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = '5e7c0bad-002b-4c48-8610-6f05fece5e76';
-- Kết quả: action = 'PUBLISHED_NEW_VERSION', rev = '2', agg_ver = '3'

SELECT id, student_id, status, version, created_by, updated_at
FROM fitness.workout_plans WHERE id = 'bb2a6813-6fae-400a-bfbf-52938143cb74';
-- Kết quả: status = 'ACTIVE', version = 4, updated_at = '2026-10-31 16:48:34.460222+00'
```

### 3.3. Đối soát Student Guard trong `HUMAN_COACH` (Live API)
```text
Request: POST http://127.0.0.1:8080/api/v1/ai/workout-proposals (Token: smoke.student)
Headers: Authorization: Bearer eyJraWQiOiJzdzFvaV84...
Response HTTP 403 Forbidden:
{
  "errorCode": "AI_FORBIDDEN",
  "message": "In HUMAN_COACH mode, only the assigned coach may request a workout proposal.",
  "timestamp": "2026-10-31T16:50:19.811024200Z",
  "requestId": "16c126c1-a4d7-4ad7-81ca-b9b11953205a",
  "fieldErrors": []
}
```

---

## 4. Rà Soát Chi Tiết Policy & Nguồn Phê Duyệt Chuyên Môn (D06)

Bảng đối chiếu minh bạch nguồn gốc, phạm vi và trạng thái phê duyệt của từng tham số kỹ thuật / chính sách vận hành trong hệ thống:

| Tham số / Chính sách | Giá trị thực thi trong code | Vị trí code thực tế | Nguồn yêu cầu / Cơ sở pháp lý | Phiên bản | Phạm vi áp dụng | Phân loại trạng thái | Việc còn thiếu / Hành động tiếp theo |
| :--- | :--- | :--- | :--- | :---: | :--- | :---: | :--- |
| **Ca gián đoạn 100 ngày** | Phát hiện khoảng trống $\ge 100$ ngày không có workout `COMPLETED` | [`F02ContextBuilder.java:263`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02ContextBuilder.java#L263) | [`acceptance-criteria.md:18`](../01-requirements/acceptance-criteria.md#L18) (AC #5) & [`business-rules.md:41`](../02-domain/business-rules.md#L41) (BR #8) | v1.0 | F02, F03, F04 khi học viên có lịch sử gián đoạn | **APPROVED INVARIANT cho ca 100 ngày** | Không coi tải cũ là năng lực hiện tại. Ngưỡng tổng quát cho mọi mức gián đoạn khác là **OPERATIONAL CANDIDATE** chờ Hội đồng D06 chuẩn hóa. |
| **Trần an toàn buổi/tuần khi nghỉ dài** | Tối đa 3 buổi / tuần | [`F02RuntimeOutputValidator.java:108`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02RuntimeOutputValidator.java#L108), `simulated_f02_provider.py:76` | Heuristic kỹ thuật lập trình an toàn (conservative return-to-training) | v1.0 | F02 khi `PROLONGED_INACTIVITY` | **OPERATIONAL CANDIDATE (Chờ D06)** | Cần Hội đồng Chuyên môn Thể hình (D06) thẩm định và ký duyệt văn bản quy chuẩn chính thức trước khi release. |
| **Trần an toàn số hiệp khi nghỉ dài** | Tối đa 3 sets / bài tập | [`F02RuntimeOutputValidator.java:132`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02RuntimeOutputValidator.java#L132), `simulated_f02_provider.py:80` | Heuristic kỹ thuật lập trình an toàn | v1.0 | F02 khi `PROLONGED_INACTIVITY` | **OPERATIONAL CANDIDATE (Chờ D06)** | Cần Hội đồng Chuyên môn Thể hình (D06) thẩm định và ký duyệt văn bản quy chuẩn chính thức. |
| **Tiêu chí `RECENT_CONSISTENT`** | $\ge 2$ buổi tập hoàn thành trong 28 ngày qua VÀ buổi gần nhất $\le 14$ ngày | [`F02ContextBuilder.java:266–276`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02ContextBuilder.java#L266-L276) | Tham số vận hành đề xuất trong Implementation Plan mục 11.1 | v1.0 | F02 Context Builder để gán nhãn `RECENT_CONSISTENT` | **OPERATIONAL CANDIDATE (Chờ D06)** | Hệ thống hiện **fail-closed** về `UNKNOWN_HISTORY` nếu không thỏa. Cần D06 đánh giá xem 2 buổi / 28 ngày có đủ tin cậy để cho phép progressive overload hay không. |
| **Biên độ thời lượng buổi tập** | 15 đến 180 phút (mặc định 60 phút) | [`F02ContextBuilder.java:391–429`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02ContextBuilder.java#L391-L429), [`AiWorkoutProposalModal.tsx:110`](../../apps/mobile/src/features/workout-plan/components/AiWorkoutProposalModal.tsx#L110) | Giới hạn sinh lý thực nghiệm | v1.0 | Form F02 Student & Trainer | **OPERATIONAL CANDIDATE (Chờ D06)** | Cần chuyên gia D06 rà soát ngưỡng khuyến nghị tối ưu (hiện tại form đặt mặc định 60 phút). |
| **Ngưỡng volume $\ge 20\%$ cho significant change** | $\ge 20\%$ volume | *Chưa có trong code hay contract F02*; ý tưởng trao đổi sơ bộ của team | Không có văn bản chính thức; [`business-rules.md:39`](../02-domain/business-rules.md#L39) chỉ quy định định tính | N/A | F03 (Milestone P2-M4) | **UNAPPROVED PROPOSAL / CANDIDATE IDEA (Chưa được duyệt)** | **Đã xóa bỏ nhận định 20% là quy tắc đã duyệt**. Cần D06 định nghĩa rõ định lượng cho F03. |
| **Dữ liệu đo lường còn thiếu** | Coi là UNKNOWN, tuyệt đối không tự điền 0 | [`F02ContextBuilder.java:191–195`](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02ContextBuilder.java#L191-L195) | [`phase-2-release-scope.md`](../00-project-overview/phase-2-release-scope.md) mục Bất biến cốt lõi | v1.0 | Toàn bộ pipeline AI và Progress Engine | **APPROVED INVARIANT (Đã phê duyệt)** | Đã kiểm chứng: cân nặng $\le 0$ bị loại bỏ, không truyền 0 vào prompt. |

---

## 5. Nguồn Gốc & Kết Quả Kiểm Thử Tự Động (Test Provenance)

Toàn bộ các bộ kiểm thử được phân định nguồn gốc chạy rõ ràng, không tính trùng lặp:

1. **Backend Integration Tests (Chạy mới trong phiên 31/10/2026):**
   - Runner: Maven Surefire 3.5.6, OpenJDK 21, Spring Boot Test, Testcontainers PostgreSQL 18.
   - Lệnh thực thi: `./mvnw.cmd test "-Dtest=F02ContextBuilderUnitTest,AiWorkoutProposalFullSliceIntegrationTest"` (Task background ID `task-759`).
   - Thời điểm hoàn thành: **2026-10-31 23:54:02+07:00**.
   - Kết quả: **Tests run: 44, Failures: 0, Errors: 0, Skipped: 0 (BUILD SUCCESS, 27.88s)**.
   - Chi tiết suites:
     - `com.fitnesscoaching.platform.AiWorkoutProposalFullSliceIntegrationTest`: **25 tests PASS** (22.24s).
     - `com.fitnesscoaching.platform.modules.ai.application.service.F02ContextBuilderUnitTest`: **19 tests PASS** (1.318s).
2. **Mobile Jest Unit Tests (Chạy mới trong phiên 31/10/2026):**
   - Runner: Jest 29.7.0, React Native Testing Library, Expo SDK 54.
   - Lệnh thực thi: `npm --prefix apps/mobile test -- src/__tests__/AiWorkoutProposalModal.test.tsx`.
   - Thời điểm hoàn thành: **2026-10-31 23:53:16+07:00**.
   - Kết quả: **Tests: 13 passed, 13 total (3.84s)**.
   - Bao phủ: Renders initial form, accept, reject, edit draft, revision increment, conflict errors, Before/After card, physical limitations validation, student isolation, drop stale in-flight responses.
3. **AI Service Pytest (Kế thừa từ PR #123, không chạy lại phiên này):**
   - Runner: Pytest 8.x, Python 3.12 virtualenv.
   - Nguồn kế thừa: PR #123 commit `aa2a9a6` (CI Run ID 40941421443).
   - Kết quả kế thừa: **53 passed** (`services/ai-service/tests/test_f02_provider.py`). Không tính là bài kiểm tra chạy mới trong phiên này.

---

## 6. Điều Kiện & Kế Hoạch Chuyển Tiếp Sang F03 (Milestone P2-M4)

Theo đúng quy định của [`phase-2-implementation-plan.md`](../07-development/phase-2-implementation-plan.md) (Mục 6 & 12.3) và [`phase-2-feature-data-authority-matrix.md`](phase-2-feature-data-authority-matrix.md) (Mục 4), công việc triển khai **F03 (Thay bài và điều chỉnh volume/load)** được phân định rõ ràng giữa các điều kiện bắt buộc trước khi code, các điều kiện trước khi bật tính năng, và các điều kiện trước release:

### 6.1. Điều kiện bắt buộc trước khi bắt đầu triển khai code F03
1. **Thiết kế hợp đồng Typed Changes F03:**
   - Payload đề xuất F03 phải có kiểu tường minh: `targetRef` (planId, versionNumber, sessionIndex, exerciseIndex), `changeType` (`EXERCISE_SUBSTITUTION`, `LOAD_ADJUSTMENT`, `VOLUME_ADJUSTMENT`), `before`, `after`, `reason`, `scope`, `evidence`. Tuyệt đối không dùng object tự do.
2. **Kế thừa hạ tầng Apply & Command đã kiểm chứng từ P2-M3:**
   - F03 phải tái sử dụng nguyên vẹn hạ tầng command: `publishVersion` (cho thay đổi chiến lược lớn) hoặc workout session change history (cho thay đổi nhỏ trong buổi), aggregate version guard, source snapshot, idempotency key và bảng application receipt. F03 **không được tạo luồng mutation riêng**.
3. **Bảo toàn bất biến Actual Workout:**
   - Buổi tập đã hoàn thành (`COMPLETED`) hoặc đang diễn ra (`IN_PROGRESS`) là bất biến, không được sửa như một kế hoạch tương lai.

### 6.2. Điều kiện trước khi kích hoạt hành vi phụ thuộc policy (Policy Activation)
1. **Quy tắc phân loại Minor vs Significant Changes:**
   - Minor change (thay bài tập tương đương cùng movement pattern / thiết bị sẵn có, điều chỉnh tải trong 1 buổi): Cho phép preview và áp dụng mà không cần nâng plan version lớn.
   - Significant change (thay đổi cấu trúc chương trình, tần suất, mục tiêu tuần): Bắt buộc huấn luyện viên (hoặc học viên tự tập) phê duyệt tường minh để xuất bản plan version mới (`publishVersion`).
2. **Ràng buộc dữ liệu lịch sử cho đề xuất tăng tải:**
   - Chỉ đề xuất tăng tải khi có đủ dữ liệu actual workout (sets, reps, load, RPE) đạt chất lượng trong coverage window theo rule. Nếu thiếu RPE hoặc dữ liệu không đủ -> **chỉ hỗ trợ thay bài tập tương đương, không tự tiện đề xuất tăng tải**.

### 6.3. Điều kiện trước Pilot / Production Release
1. **Thẩm định chuyên môn D06:**
   - Hội đồng Chuyên môn Thể hình (D06) phê duyệt catalog bài tập thay thế tương đương (alternative exercise mapping) và biên độ điều chỉnh tải an toàn.
2. **Đánh giá chất lượng mô hình LLM thật (Phase 2 Benchmark F10/P2-M6):**
   - Đo lường độ chính xác và an toàn của các đề xuất thay bài trên tập test set chuẩn hóa trước khi mở production.

> [!CAUTION]
> **Giới hạn phạm vi nhiệm vụ:** Đợt kiểm chứng này dừng lại ở việc hoàn thiện hồ sơ P2-M3 và xác lập các gate kỹ thuật cho F03. **Chưa triển khai bất kỳ mã nguồn hoặc migration nào cho F03 trong phiên này.**
