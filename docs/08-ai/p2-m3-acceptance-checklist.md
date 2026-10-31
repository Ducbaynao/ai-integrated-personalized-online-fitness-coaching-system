# Bảng Đối Chiếu Hiện Trạng & Checklist Nghiệm Thu Milestone P2-M3

> **Milestone:** P2-M3 — Luồng Giáo Án Hoàn Chỉnh Đầu Tiên (F02 AI Workout Proposal)
> **Repository:** `D:\Projects\ai-integrated-personalized-online-fitness-coaching-system`
> **Branch thực hiện:** `test/p2-m3-f02-gui-acceptance-20261031`
> **Commit nền xác minh (Baseline commit):** `b471e25c024447ead5abc08783a8c044a7cfb021` (origin/main, tích hợp PR #123 `aa2a9a6`, PR #122 `70a5001`, và hotfix PR #124 `a90ede2`)
> **Ngày cập nhật hồ sơ:** 2026-11-01 (Dữ liệu kiểm chứng thực địa ngày 31/10/2026)
> **Báo cáo kiểm chứng chi tiết:** [f02-gui-acceptance-report-20261031.md](f02-gui-acceptance-report-20261031.md)
> **Tài liệu căn cứ:** [phase-2-release-scope.md](../00-project-overview/phase-2-release-scope.md), [phase-2-feature-data-authority-matrix.md](phase-2-feature-data-authority-matrix.md), [phase-2-implementation-plan.md](../07-development/phase-2-implementation-plan.md) (Mục 5 & 12.3)

---

## 1. Nguồn Gốc & Xuất Xứ Kết Quả Kiểm Thử (Test Provenance)

Để đảm bảo tính trung thực tuyệt đối của hồ sơ nghiệm thu, nguồn gốc của các kết quả kiểm thử tự động được minh bạch như sau:
- **Backend Test Suite (44/44 tests PASS):** Chạy thực tế trong phiên làm việc ngày 31/10/2026 lúc 23:54:02+07:00 (lệnh `mvn test -Dtest=com.fitnesscoaching.platform.modules.ai.**.*,com.fitnesscoaching.platform.AiWorkoutProposal*`). Bao gồm 19 tests context builder, 25 tests full slice integration & concurrency guards.
- **Mobile Component Test Suite (13/13 tests PASS):** Chạy thực tế trong phiên làm việc ngày 31/10/2026 lúc 23:53:16+07:00 (lệnh `npm test -- AiWorkoutProposalModal.test.tsx`). Kiểm chứng cô lập học viên, loading state, anti-double-submit, và handling lỗi.
- **Python AI Service Pytest Suite (53/53 tests PASS):** Kế thừa từ kết quả CI/CD của PR #123 (commit `aa2a9a6`, `test_f02_provider.py`). Không chạy lại trong phiên kiểm chứng GUI vì code dịch vụ AI không bị sửa đổi.

---

## 2. Bảng Tổng Hợp Tiêu Chí Nghiệm Thu P2-M3

| Mã tiêu chí | Hạng mục công việc / Yêu cầu | Trạng thái kỹ thuật | Trạng thái GUI runtime | Phương thức & Bằng chứng xác minh | Ghi chú & Khoảng trống còn lại |
| :---: | :--- | :---: | :---: | :--- | :--- |
| **P2-M3.1** | Schema F02: Đề xuất 1 tuần mẫu, equipment, reps/sets/duration, ranh giới an toàn | **PASS** | **PASS** | **GUI runtime** ([gui_01](artifacts/f02-mobile-runtime-acceptance-20261031/gui_01_student_new_form_initial.png), [gui_13](artifacts/f02-mobile-runtime-acceptance-20261031/gui_13_trainer_new_form_initial.png)) + **Automated tests** ([F02ContextBuilderUnitTest.java](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/application/service/F02ContextBuilderUnitTest.java): 19 unit tests backend, [test_f02_provider.py](../../services/ai-service/tests/test_f02_provider.py): 53 tests pytest) | Cấu trúc 1 tuần (Phase 2 baseline); hỗ trợ bài tập tự do, tạ đơn, tạ đòn, máy, cáp, dây kháng lực |
| **P2-M3.2** | Pipeline AI & Source Snapshot: Context builder có guard, Flyway V52 `source_snapshot` | **PASS** | **PASS** | **Backend integration tests** (44 tests PASS) + **DB inspection** (`fitness.ai_recommendations` có snapshot JSONB) | Ghi nhận goalId, goalVersion, activePlanId, aggregateVersion, continuityState |
| **P2-M3.3** | Giao diện Mobile F02 (PR #123): Form mới, trạng thái xử lý, xem căn cứ/giới hạn, Before/After card | **PASS** | **PASS** | **GUI runtime** (Ảnh [gui_01](artifacts/f02-mobile-runtime-acceptance-20261031/gui_01_student_new_form_initial.png)–[gui_17](artifacts/f02-mobile-runtime-acceptance-20261031/gui_17_trainer_plan_active_applied_v4.png) trên Pixel 8 Emulator) + **Mobile tests** ([AiWorkoutProposalModal.test.tsx](../../apps/mobile/src/__tests__/AiWorkoutProposalModal.test.tsx): 13 tests PASS) | Phản ánh đúng 2 trường hợp: Đã có ACTIVE plan (publishVersion) và Chưa có ACTIVE plan (activate new) |
| **P2-M3.4** | Chỉnh sửa bản thảo (Draft Editing): Revision v1 -> v2, `is_customized = true`, view so sánh AI gốc | **PASS** | **PASS** | **GUI runtime** (Ảnh [gui_05](artifacts/f02-mobile-runtime-acceptance-20261031/gui_05_student_draft_edit_mode.png), [gui_06](artifacts/f02-mobile-runtime-acceptance-20261031/gui_06_student_draft_field_edited.png), [gui_08](artifacts/f02-mobile-runtime-acceptance-20261031/gui_08_student_proposal_v2_customized.png), [gui_09](artifacts/f02-mobile-runtime-acceptance-20261031/gui_09_student_original_ai_comparison_view.png), [gui_15](artifacts/f02-mobile-runtime-acceptance-20261031/gui_15_trainer_draft_edit_mode.png), [gui_16](artifacts/f02-mobile-runtime-acceptance-20261031/gui_16_trainer_proposal_v2_customized.png)) + **DB inspection** | Bảo toàn `original_payload` nguyên vẹn; cập nhật `structured_payload` có kiểm soát; DB lưu đúng revision 2 |
| **P2-M3.5** | Giao dịch Chấp thuận Nguyên tử (Atomic Accept & Apply): Revalidation nguồn, `publishVersion` / `activate`, Application Receipt | **PASS** | **PASS** | **GUI runtime** (Ảnh [gui_10](artifacts/f02-mobile-runtime-acceptance-20261031/gui_10_student_plan_active_applied_v9.png), [gui_12](artifacts/f02-mobile-runtime-acceptance-20261031/gui_12_student_plan_active_new_created_v1.png), [gui_17](artifacts/f02-mobile-runtime-acceptance-20261031/gui_17_trainer_plan_active_applied_v4.png)) + **DB inspection** + **Slice integration tests** (25 tests PASS) | Không gọi AI provider trong apply transaction; ghi nhận receipt chống áp dụng 2 lần; database trigger bảo vệ aggregate version |
| **P2-M3.6** | Quyết định F11 (Phần đầu): Lưu quyết định `APPLIED` / `REJECTED`, audit log, ghi chú tùy chọn | **PASS** | **PASS** | **DB inspection** (Bảng `ai_recommendations`, `ai_recommendation_applications`, `audit_events`) | Tách biệt hoàn toàn giữa việc Reject đề xuất và đánh giá chất lượng mô hình; không ép nhập lý do để Reject |
| **P2-M3.7** | Bảo vệ Xung Đột & Concurrency Guards: Stale revision, plan version change, goal version change, student isolation | **PASS** | **PASS (Thành phần)** | **Live API drills** (D1..D3 ném 409 `AI_RUN_LIFECYCLE_CONFLICT`, rollback sạch sẽ) + **Component unit tests** (isolation) | Đã kiểm chứng cơ chế drop stale responses và reset state khi chuyển đổi học viên qua automated tests |

---

## 3. Bảng Đối Chiếu Các Test IDs Liên Quan (Ma Trận Mục 12.3)

| Test ID | Nội dung kiểm thử quy định trong Plan | Loại bài kiểm tra | Trạng thái | Phương thức & Bằng chứng xác minh thực tế | Giới hạn & Khoảng trống |
| :---: | :--- | :---: | :---: | :--- | :--- |
| **T01** | Student tự tạo và áp dụng đề xuất trong `SELF_DIRECTED` | GUI & Integration | **PASS** | **GUI runtime** (Ảnh [gui_01](artifacts/f02-mobile-runtime-acceptance-20261031/gui_01_student_new_form_initial.png)–[gui_12](artifacts/f02-mobile-runtime-acceptance-20261031/gui_12_student_plan_active_new_created_v1.png) trên Pixel 8) + **DB inspection** (Plan version 8 -> 9 và Plan mới v1) + `AiWorkoutProposalFullSliceIntegrationTest` | Dùng simulated provider; không chứng minh chất lượng văn phong LLM thật. |
| **T02** | Trainer tạo, sửa draft và áp dụng cho học viên trong `HUMAN_COACH` | GUI & Integration | **PASS** | **GUI runtime** (Ảnh [gui_13](artifacts/f02-mobile-runtime-acceptance-20261031/gui_13_trainer_new_form_initial.png)–[gui_17](artifacts/f02-mobile-runtime-acceptance-20261031/gui_17_trainer_plan_active_applied_v4.png) trên Pixel 8) + **DB inspection** (Plan version 3 -> 4, `created_by = trainerId`) + Slice integration tests | Dùng simulated provider. |
| **T03** | Chặn Student thao tác vượt quyền khi đang ở chế độ `HUMAN_COACH` | Security & Contract | **PASS** | **Live API** (`POST /api/v1/ai/workout-proposals` trả về HTTP 403 `AI_FORBIDDEN`) + **Integration test** (`acceptRecommendation_whenModeTransitionedToHumanCoach_deniesStudentAcceptance`) + **Component unit test** | Kiểm chứng bằng Live API và component test (ẩn nút/disable accept); chưa click trực tiếp trên Pixel 8 phiên này. |
| **T04** | Tái xác thực quyền actor sau khi gọi AI trước khi lưu/trả kết quả | Backend & Unit | **PASS** | **Backend integration test** (`acceptRecommendation_whenActorRevoked_cannotReplay`) + **Code inspection** ([AiWorkoutProposalService.java](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiWorkoutProposalService.java)) | Kiểm tra revalidation trạng thái account `ACTIVE` và quyền coaching ngay trước khi lưu/giao kết quả. |
| **T05** | Source snapshot guards: Bắt xung đột khi Goal hoặc Plan thay đổi trước khi Accept | Integration & Contract | **PASS** | **Live API + DB manipulation drills** (D1 plan version conflict 409, D2 goal version conflict 409) + `AiWorkoutProposalFullSliceIntegrationTest` | Rollback toàn vẹn; recommendation giữ `PENDING`, 0 application row được tạo. |
| **T06** | Xử lý dữ liệu đo lường còn thiếu: Coi là UNKNOWN, không tự điền 0 | Domain & Unit | **PASS** | **Backend unit test** ([F02ContextBuilderUnitTest.java](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/application/service/F02ContextBuilderUnitTest.java)) + **Code inspection** ([F02ContextBuilder.java](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02ContextBuilder.java): loại bỏ bodyweight $\le 0$) | Đã kiểm chứng theo đúng bất biến cốt lõi của nền tảng. |
| **T07** | Continuity determination: `CONFIRMED_NEW`, `UNKNOWN_HISTORY`, `PROLONGED_INACTIVITY` $\ge 100$ ngày | Business & Unit | **PASS** | **Backend unit test** ([F02ContextBuilderUnitTest.java](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/application/service/F02ContextBuilderUnitTest.java): 19 tests PASS) + **AI service pytest** ([test_f02_provider.py](../../services/ai-service/tests/test_f02_provider.py)) | Kiểm chứng bằng test suites và test doubles; chưa seed log tập thực tế 100 ngày trên live emulator. |
| **T08** | Trần an toàn khi nghỉ dài $\ge 100$ ngày ($\le 3$ buổi/tuần, $\le 3$ sets/bài) | Validation Rule | **PASS** | **Backend unit test** + **Validator code** ([F02RuntimeOutputValidator.java](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02RuntimeOutputValidator.java)) | Ngưỡng $\le 3$ buổi và $\le 3$ sets là tham số vận hành bảo thủ (**OPERATIONAL CANDIDATE**), chờ Hội đồng D06 ký duyệt văn bản. |
| **T13** | Redaction dữ liệu riêng tư & correlation tracking trong AI Run log | Security & Audit | **PASS** | **DB inspection** (`fitness.ai_runs` và `fitness.ai_context_snapshots` không chứa token hay thông tin định danh thừa) | Đạt chuẩn context minimization. |
| **T18** | Ghi nhận F11 decision events khi Accept / Reject đề xuất | Audit & History | **PASS** | **DB inspection** (`ai_recommendations.status`, `decided_by`, `decided_at`, `decision_note`, `ai_recommendation_applications`) | Tách biệt hoàn toàn việc Reject đề xuất với đánh giá mô hình. |
| **T23** | Giao dịch Accept nguyên tử và Application Receipt chống replay trùng | Transaction & Idempotency | **PASS** | **DB inspection** (`fitness.ai_recommendation_applications` lưu `commandKey`, `appliedRevision`) + Full slice integration tests | Ngăn chặn áp dụng lần 2; replay trả lại receipt cũ nếu trùng lặp hoàn toàn thông số. |
| **T24** | Validation fail-fast không gọi provider khi thiếu trường bắt buộc | Integration & Cost | **PASS** | **Live API** (C3 & C4 trả HTTP 400 `VALIDATION_FAILED`) + **FastAPI log inspection** (Log FastAPI ghi nhận 0 request trong cửa sổ test) | Bảo vệ chi phí $0.00 cho các request lỗi đầu vào. |
| **T25** | Ngăn chặn áp dụng lần 2 (Double Apply Protection) và Stale Revision | Concurrency Guard | **PASS** | **Live API** (Ca D3 trả HTTP 409 `Revision conflict`) + **Automated integration tests** | Không phát sinh cập nhật kế hoạch dở dang. |
| **T27** | Timezone local `Asia/Ho_Chi_Minh` và tính toán ngày nhất quán | Integration & Time | **PASS** | **GUI runtime** (Form hiển thị ngày mai theo UTC+7) + **Backend unit test** ([F02ContextBuilderUnitTest.java](../../services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/application/service/F02ContextBuilderUnitTest.java) với Clock & ZoneId) | Một ca ngày địa phương cụ thể không chứng minh loại bỏ 100% mọi rủi ro lệch ngày ở các múi giờ quốc tế khác; cần mở rộng holdout tests ở P2-M6. |
| **T28** | Cô lập dữ liệu học viên (Data Isolation & No Leakage) | Mobile Client | **PASS** | **Component unit tests** ([AiWorkoutProposalModal.test.tsx](../../apps/mobile/src/__tests__/AiWorkoutProposalModal.test.tsx): 13 tests PASS) + **Code inspection** (`currentStudentRef` & `activeRequestIdRef`) | Chứng minh qua automated component tests; không tạo độ trễ nhân tạo trên live emulator. |

---

## 4. Rà Soát Nguồn Gốc Policy & Thẩm Quyền Phê Duyệt (D06)

| Tham số / Chính sách | Giá trị thực thi trong code | Nguồn gốc / Cơ sở pháp lý | Phân loại trạng thái | Thẩm quyền & Hành động tiếp theo |
| :--- | :--- | :--- | :---: | :--- |
| **Ca gián đoạn 100 ngày** | Phát hiện khoảng trống $\ge 100$ ngày không có workout `COMPLETED` | [acceptance-criteria.md](../01-requirements/acceptance-criteria.md) (AC #5) & [business-rules.md](../02-domain/business-rules.md) (BR #8) | **APPROVED INVARIANT cho ca 100 ngày** | Không coi tải cũ là năng lực hiện tại. Ngưỡng tổng quát cho mọi mức gián đoạn khác là **OPERATIONAL CANDIDATE** chờ Hội đồng D06 chuẩn hóa. |
| **Trần buổi/tuần khi nghỉ dài** | Tối đa 3 buổi / tuần | Heuristic kỹ thuật lập trình an toàn ([F02RuntimeOutputValidator.java](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02RuntimeOutputValidator.java)) | **OPERATIONAL CANDIDATE** | Chờ Hội đồng Chuyên môn Thể hình (D06) thẩm định và ký duyệt văn bản quy chuẩn chính thức. |
| **Trần số hiệp khi nghỉ dài** | Tối đa 3 sets / bài tập | Heuristic kỹ thuật lập trình an toàn ([F02RuntimeOutputValidator.java](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02RuntimeOutputValidator.java)) | **OPERATIONAL CANDIDATE** | Chờ Hội đồng Chuyên môn Thể hình (D06) thẩm định và ký duyệt văn bản quy chuẩn chính thức. |
| **Tiêu chí `RECENT_CONSISTENT`** | $\ge 2$ buổi / 28 ngày & gần nhất $\le 14$ ngày | Tham số vận hành đề xuất trong [phase-2-implementation-plan.md](../07-development/phase-2-implementation-plan.md) mục 11.1 | **OPERATIONAL CANDIDATE** | Hệ thống hiện **fail-closed** về `UNKNOWN_HISTORY` nếu không thỏa. Chờ D06 xác nhận ngưỡng có đủ tin cậy hay không. |
| **Biên độ thời lượng buổi tập** | 15 đến 180 phút (mặc định 60 phút) | Giới hạn sinh lý thực nghiệm ([F02ContextBuilder.java](../../services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/F02ContextBuilder.java)) | **OPERATIONAL CANDIDATE** | Chờ chuyên gia D06 rà soát ngưỡng khuyến nghị tối ưu. |
| **Ngưỡng volume $\ge 20\%$ cho significant change** | $\ge 20\%$ volume | *Chưa có trong contract hay code F02*; ý tưởng trao đổi sơ bộ của team | **UNAPPROVED PROPOSAL (Chưa được duyệt)** | **Tuyệt đối không coi 20% là quy tắc đã duyệt**. [business-rules.md](../02-domain/business-rules.md) chỉ quy định định tính. Cần D06 định nghĩa rõ định lượng cho F03. |
| **Dữ liệu đo lường còn thiếu** | Coi là UNKNOWN, không tự điền 0 | [phase-2-release-scope.md](../00-project-overview/phase-2-release-scope.md) mục Bất biến cốt lõi | **APPROVED INVARIANT** | Đã kiểm chứng: cân nặng $\le 0$ bị loại bỏ, không truyền 0 vào prompt. |

---

## 5. Trạng Thái Tổng Thể P2-M3 & Phân Định Trách Nhiệm Quyết Định

- **Mặt Kỹ Thuật (Architecture, Integrity, Concurrency, Database):** **ĐẠT (PASS)**. Hạ tầng command `publishVersion`, aggregate version trigger, source snapshot JSONB, atomic transaction và concurrency guards đã hoàn thiện và đạt 100% tests tự động (44 backend, 13 mobile, 53 pytest).
- **Mặt Giao Diện (GUI & User Experience):** **ĐẠT (PASS)**. Cả 2 luồng Student và Trainer đều thao tác thành công trên thiết bị giả lập thực tế với 17 ảnh bằng chứng xác thực trong thư mục [artifacts/f02-mobile-runtime-acceptance-20261031/](artifacts/f02-mobile-runtime-acceptance-20261031/).
- **Mặt Chuyên Môn Thể Hình (D06 Specialist Council):** **CHỜ THẨM ĐỊNH**. Cần Hội đồng Chuyên môn Thể hình ký biên bản phê duyệt các tham số candidate policies trước khi phát hành tính năng cho người dùng thật.

---

## 6. Phân Định Điều Kiện Chuyển Tiếp Sang F03 (Milestone P2-M4)

1. **Điều kiện bắt buộc trước khi bắt đầu code F03:**
   - Hoàn thiện hợp đồng Typed Changes F03 (`targetRef`, `before/after`, `reason`, `scope`, `evidence`) theo [phase-2-feature-data-authority-matrix.md](phase-2-feature-data-authority-matrix.md) mục 4.
   - Tái sử dụng hạ tầng command `publishVersion`, aggregate version guard, source snapshot, idempotency replay receipt từ P2-M3.
   - Bảo toàn bất biến: Buổi tập đã hoàn thành (`COMPLETED`) hoặc đang diễn ra (`IN_PROGRESS`) là bất biến, không được sửa như kế hoạch tương lai.
2. **Điều kiện trước khi kích hoạt hành vi phụ thuộc policy:**
   - Chuẩn hóa tiêu chí phân loại minor vs significant changes (để quyết định khi nào sinh version mới vs khi nào chỉ ghi change history).
   - Quy tắc kiểm tra tính đầy đủ của RPE / lịch sử trước khi đề xuất tăng tải (nếu thiếu RPE -> chỉ hỗ trợ thay bài tập, không tăng tải).
3. **Điều kiện trước Pilot / Production Release:**
   - Thẩm định chuyên môn D06 cho danh mục bài tập thay thế và nguyên tắc điều chỉnh tải.
   - Đánh giá chất lượng mô hình LLM thật theo F10/P2-M6.

*(D06 không phải là blocker cho việc bắt đầu thiết kế hợp đồng và cấu trúc dữ liệu F03; D06 là gate trước khi bật tính năng production/pilot).*
