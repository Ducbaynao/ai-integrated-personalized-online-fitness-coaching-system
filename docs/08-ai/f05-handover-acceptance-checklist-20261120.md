# F05 — Xác minh bàn giao và checklist nghiệm thu

**Ngày xác minh:** 20/11/2026, UTC+7.  
**Phạm vi:** F05 / P2-M4; xác minh bàn giao Backend/AI Service.  
**Commit bàn giao:** `be5925872bbf0d08d7188a611a0fad616a115a84`.  
**Local HEAD khi kiểm tra:** `673ce513762613f41ee568073ea95ea1882c39d3` (`main`, working tree sạch trước khi cập nhật hồ sơ).  
**Kết luận:** **ĐÃ HOÀN TẤT XÁC MINH BÀN GIAO KỸ THUẬT** tại commit trên. **Nghiệm thu toàn bộ F05/P2-M4 còn mở.**

## 1. Bằng chứng gắn với đúng code bàn giao

- [CI run #363, attempt 1](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/actions/runs/44231122900) chạy bởi sự kiện `push` lên `main`, `head_sha` khớp đầy đủ commit bàn giao; trạng thái `completed / success`.
- Run bắt đầu **10:32:21 ngày 18/11/2026, UTC+7**, cập nhật cuối **10:44:29 cùng ngày**. Các log checkout Backend, AI Service, Contracts và Migrations đều ghi đúng SHA này.
- Cả **6/6 jobs** thành công: Backend, AI Service, Contracts & Docs Validation, Database Migrations Check, Client Mobile, Client Admin Web.
- Đã đọc trực tiếp log job và đối chiếu tên/count suite với source. Suite FastAPI dùng tên hiển thị `F05 Runtime Verification against Real FastAPI Process and PostgreSQL`, không in tên Java class ở dòng tổng kết.
- `be59258` là ancestor của local HEAD. So sánh Git không có khác biệt cho F05 context/service/validator, simulated provider Python và tests, bốn suite F05, Workout schedule-change suite, cùng source domain Workout/Schedule được xem xét. Các thay đổi F01 về sau không biến CI này thành chứng nhận cho toàn bộ HEAD hiện tại.
- Không cần suy đoán lại thời điểm chạy integration tests trước lần restart: CI này đã chạy lại đúng code bàn giao trên runner độc lập. Không gán lại timestamp/commit cho các runtime JSON lịch sử.

Nguồn máy đọc và các dòng log trích nguyên văn: [handover_verification_20261120.json](artifacts/f05-runtime-evidence/handover_verification_20261120.json). Đây là bản trích, không phải toàn bộ raw logs.

## 2. Kết quả kiểm thử được xác minh từ CI

| Suite/check | Kết quả |
|---|---|
| `AiWorkoutRescheduleServiceUnitTest` | 14 tests; 0 failures/errors/skips |
| `F05RuntimeOutputValidatorUnitTest` | 12 tests; 0 failures/errors/skips |
| `AiWorkoutRescheduleIntegrationTest` | 23 tests; 0 failures/errors/skips |
| `AiWorkoutRescheduleRealFastApiIntegrationTest` | 6 tests; 0 failures/errors/skips |
| Bốn suite F05 | **55/55 đạt** |
| `WorkoutScheduleChangeIntegrationTest` | **21/21 đạt**; không cộng lặp vào tổng Backend |
| Backend toàn job | 2.039 tests; 0 failures, 0 errors, **5 skipped**, 2.034 thực sự đạt; `BUILD SUCCESS` |
| AI Service | **86/86 pytest đạt**, Ruff đạt; gồm 4 cases `test_f05_provider.py` |
| Contracts | OpenAPI đạt; **10 schemas / 46 fixtures**; **21/21 contract tests** |
| Docs/knowledge | UX docs đạt; knowledge validation đạt; 25/25 knowledge tests đạt |
| Migration | PostgreSQL **18.7**, 60 migrations qua V60 trên database dùng một lần; cleanup thành công |

Năm Backend tests bị skip thuộc WHO-01 real embedding (2), controlled OpenAI embedding smoke (1), eligible-corpus real embedding (1), Clamd disposable (1). Không có test nào trong bốn suite F05 hoặc suite Workout schedule-change bị skip. Không báo toàn bộ 2.039 tests là passed.

Đường đi được chứng minh: **MockMvc trong tiến trình Spring → HTTP thật đến Uvicorn/FastAPI → simulated provider → Spring validation/persistence → Workout domain → PostgreSQL**. Không phải kiểm thử Mobile, HTTP từ client ngoài vào Spring hay đánh giá model thật.

## 3. Checklist đóng công việc xác minh bàn giao

- [x] Xác định commit bàn giao và đối chiếu với lịch sử local.
- [x] Xác minh CI trên chính SHA đó, gồm sự kiện, thời điểm và sáu jobs.
- [x] Xác minh log checkout cùng SHA và kết quả từng suite F05, không chỉ trạng thái xanh tổng.
- [x] Đối chiếu source của các ca Appointment, overlap, rollback và GET/Accept concurrency.
- [x] Phân biệt evidence CI mới với runtime artifacts và báo cáo local cũ.
- [x] Ghi rõ phạm vi có bằng chứng và điều kiện nghiệm thu còn mở.

## 4. Đối chiếu acceptance/test IDs

Baseline: [authority matrix mục 6 và mục 1.3–1.8](phase-2-feature-data-authority-matrix.md), [implementation plan P2-M4 và T01–T28](../07-development/phase-2-implementation-plan.md).

| ID/phạm vi | Evidence đã xác minh | Kết luận trong phạm vi bàn giao |
|---|---|---|
| T02 — authority theo mode | `studentInHumanCoachMode_acceptBlocked_T02`; FastAPI `unauthorizedActor_deniedWith403` | Backend chặn Student HUMAN_COACH tự Accept |
| T03 — unrelated/revoked/period | `unrelatedTrainer_blocked_T03`, `trainerPermissionRevoked_blocksAccept`, `coachingPeriodChanged_abortsAccept` | Có bằng chứng unrelated actor, revoked read/accept và period đổi; chưa đồng nghĩa đủ mọi biến thể scope |
| T04 — revoke khi provider đang chạy | **F05-BL-02 đã triển khai và xác minh:**<br>1. **Transaction Architecture:** Bỏ `@Transactional` mức method trong `AiWorkoutRescheduleService.generateReschedule`. `aiRunRepository.create` và `completeRun(FAILED, ...)` được cam kết độc lập qua `PROPAGATION_REQUIRES_NEW`. Nhánh thành công được bọc atomic trong `TransactionTemplate`. Run terminal status phản ánh trung thực `FAILED` / `AUTHORITY_REVOKED`, không chuyển thành `CANCELLED`. Payload không bị rò rỉ (`output_payload: null`, không tạo recommendation, không audit proposed). Khi lưu đề xuất hoặc ghi audit thất bại, rollback sạch sẽ và commit Run `FAILED` với mã `PERSISTENCE_FAILED`.<br>2. **Quyền quyết định vs Quyền đọc (MANAGE vs VIEW):** Phân định rạch ròi giữa MANAGE và VIEW theo authority matrix. Mất MANAGE vẫn giữ VIEW cho phép Trainer đọc lại proposal (`getReschedule`); mất VIEW chặn đọc lại hoàn toàn.<br>3. **Kiểm chứng:** 5 regression tests đồng bộ `CountDownLatch` trong `AiWorkoutRescheduleServiceUnitTest` (19/19 tests PASS). Đã bổ sung và chạy thành công trên PostgreSQL Testcontainers thật: 4 integration tests T04 (mất MANAGE, mất VIEW, tài khoản `SUSPENDED`, coaching period ended), 1 readback guard phân biệt MANAGE vs VIEW, 1 snapshot backward compatibility, 1 continuity/provenance persistence test, 1 transaction rollback khi audit failure (`AiWorkoutRescheduleIntegrationTest` 31/31 PASS). | **VERIFIED (Live PostgreSQL Testcontainers Integration - 31/31 PASS)** |
| T05 — source/Appointment/conflict | `occurrenceVersionConflict_failsAndRollsBack_T05`, `missedWorkoutStartedAfterGenerationCannotBeApplied`, `linkedAppointmentChangedAfterGeneration_abortsAccept`, `workoutScheduleConflictOnDestinationSlot_abortsAcceptWithOverlapConflict`; Workout START/reschedule races | Đạt các ca đã nêu; availability/recovery đầy đủ vẫn mở |
| T06/T25 — retry/concurrency | `idempotentAcceptRetry_replaysReceipt_T06_T25`, `concurrentDoubleAccept_differentKeys_onlyOneSucceeds`, `concurrentAcceptAndReject_serializedConsistently`; unit `acceptReschedule_differingParametersSameKey_throwsConflict_T25`; FastAPI replay | Có bằng chứng replay, competing decisions và khác payload ở unit layer; không nâng unit evidence thành database test |
| T09 — lịch sử planned/actual | FastAPI `completeE2EFlow_generateReadAccept_domainReadback` cho future/missed source; Workout `missedSourceReschedulesWithHistoryAndReplay`, `historicalExecutionWithoutSealBlocksMissedReschedule` | Giữ original anchor/lineage; không tạo Actual log khi reschedule; nguồn có log bị chặn. Chưa là hành trình completion/adherence đầy đủ |
| T10 — supervision/Appointment | `coachRequiredSession_withConfirmedAppointment_acceptsAndTransfersLink`, `coachRequiredSession_withoutAppointment_abortsWithConflict`; validator chặn downgrade | Đạt chuyển link cho hẹn đã đủ điều kiện và chặn thiếu hẹn; không chứng minh toàn bộ workflow đổi hẹn hai bên |
| T23 — expiry/atomicity | `expiredProposal_failsAndMarksExpired_T23`, `acceptReschedule_whenAuditFails_rollsBackEntireTransaction`, `getReschedule_whenExpiringConcurrentlyWithAccept_doesNotOverwriteAppliedStatus`, `concurrentGetAndAcceptNearExpiry_resolvesToConsistentTerminalState` | Đạt các ca expiry, audit rollback và race đã có; live crash/recovery drill vẫn riêng |
| T27 — PROPOSAL/API | FastAPI Generate/Read/Accept/Reject, receipt/audit/domain readback | Backend có evidence; Mobile presentation và cross-result mutation coverage chưa được đóng |

Các tên test trên được đối chiếu với source của commit bàn giao; log Surefire chứng minh toàn suite chạy thành công, không phải log riêng từng method. Các test IDs là yêu cầu rộng hơn một test method; không đóng toàn bộ ID chỉ từ một case đạt.

## 5. Các gate vẫn mở trước nghiệm thu toàn bộ F05

| Gate | Công việc/bằng chứng cần tiếp theo |
|---|---|
| D06/OD-3 và recovery chuyên môn | Quy tắc điều chỉnh bài tập/thể tích phục hồi được specialist/Hội đồng thể hình phê duyệt. **F05-BL-01 đã hoàn tất giải quyết:** Đã loại bỏ hoàn toàn continuity gán cứng `RECENT_CONSISTENT` trong `F05ContextBuilder`, thay bằng nguồn thực từ `ProgressEngineQuery` (kèm fallback an toàn `WorkoutCompletionEvidenceQuery` + `CoachingAuthorityQuery`), phản ánh đúng trạng thái `RECENT_ACTIVITY_OBSERVED`, `PROLONGED_INACTIVITY`, `CONFIRMED_NEW`, `RESTRICTED_HISTORY`, `UNKNOWN_HISTORY` với 8 regression unit tests backend, 5 pytest AI service, và 31/31 tests tích hợp `AiWorkoutRescheduleIntegrationTest` trên live PostgreSQL Testcontainers (bao gồm test lưu trữ provenance `daysSinceLastWorkout` và `continuityWatermark`). **Gate recovery chuyên môn D06/OD-3 vẫn tiếp tục MỞ** chờ quy tắc chuyên môn chính thức. |
| Availability đầy đủ | Đối chiếu nguồn Student/PT, khoảng lịch và acceptance; kiểm thử không có slot. Slot 09:00/14:00/18:00 và overlap guard hiện chỉ là heuristic + domain protection |
| Appointment workflow | Kiểm chứng change request hai bên → được chấp nhận → refresh snapshot/version → Accept F05. Test chuyển link hẹn CONFIRMED không chứng minh sự đồng ý thay đổi giờ hẹn |
| T04 F05 | **F05-BL-02 đã hoàn tất giải quyết:** Đã bổ sung in-flight revocation cutoff check trong `AiWorkoutRescheduleService` và readback guard trong `getValidRecommendation`. Kiểm chứng bằng 5 unit tests đồng bộ có `CountDownLatch` (`AiWorkoutRescheduleServiceUnitTest` 19/19 PASS) và **31/31 tests tích hợp** trên live PostgreSQL Testcontainers (`AiWorkoutRescheduleIntegrationTest` 31/31 PASS). Chứng minh rõ rạch ròi giữa MANAGE và VIEW, chặn rò rỉ outputPayload nhạy cảm khi actor mất quyền, không đổi trạng thái thành CANCELLED giả mạo, dùng `PROPAGATION_REQUIRES_NEW` đảm bảo FAILED/AUTHORITY_REVOKED được commit độc lập và atomic transaction cho nhánh thành công (rollback sạch sẽ khi audit/save fail). Gate kỹ thuật T04: **VERIFIED (Live PostgreSQL Testcontainers Integration - 31/31 PASS)**. |
| Mobile F05 | Xác nhận assignee/reviewer từ issue; hoàn thiện và kiểm thử Student/Trainer journeys. Job Client Mobile xanh không chứng minh F05 UI tồn tại |
| Provider thật | Đánh giá chất lượng, latency, usage/cost theo versions; simulated provider không đáp ứng gate này |
| Failure/recovery và vận hành | Thực hiện các drill còn thiếu, gồm live runtime crash/recovery theo checklist liên quan |
| Sign-off | Reviewer/owner quyết định theo acceptance sau khi đủ evidence; tài liệu này không tự cấp sign-off feature hoặc milestone |

Không suy ra assignee/reviewer từ vai trò Student/Trainer và không thay đổi ưu tiên backlog từ kết quả xác minh này.

## 6. Thao tác trong phiên xác minh

Khôi phục môi trường kiểm thử (khởi động Docker Desktop / Docker engine). Chạy thành công toàn bộ integration tests trên PostgreSQL Testcontainers thật (`pgvector/pgvector:pg18`): `AiWorkoutRescheduleIntegrationTest` (31/31 PASS), `WorkoutScheduleChangeIntegrationTest` (21/21 PASS), `AiWorkoutRescheduleRealFastApiIntegrationTest` (6/6 PASS), cùng toàn bộ F05 Backend unit tests (39/39 PASS) và AI service pytest (5/5 PASS). Xác minh đồng bộ in-flight authority revocation (T04), readback guard MANAGE vs VIEW, Training Continuity provenance (F05-BL-01), và rollback transaction atomicity. Giữ gate phục hồi chuyên môn D06/OD-3 tiếp tục MỞ.

Các runtime JSON lịch sử và các phần lịch sử trong `review_verification.json` được giữ nguyên. Mục `handoverVerification20261120` và `f05Bl01AndBl02IntegrationVerification` ghi nhận đầy đủ bằng chứng kiểm thử thực tế.

Kiểm tra hồ sơ sau cập nhật: `git diff --check` đạt; `npm run validate:ux-docs` đạt (18 required files); kiểm tra JSON/SHA, sáu jobs, counts với log, source comparison, bảo toàn toàn bộ trường lịch sử và liên kết Markdown đều đạt. Git status cuối phiên có hai file sửa (`verification_report.md`, `review_verification.json`) và hai file mới (checklist này, `handover_verification_20261120.json`); không có thay đổi sẵn có ngoài phạm vi cần bảo toàn.
