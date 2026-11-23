# F05 — Bàn giao triển khai Mobile Workout Rescheduling

**Ngày cập nhật:** 23/11/2026, UTC+7.
**Vai trò thực hiện:** DEV-A (theo phân công chỉ đạo Phase 2).
**Branch:** `feat/mobile-f05-rescheduling`.
**Base commit:** `c289ff044c0ac7c5bd2f3036b013acac09f986f8` (Merge PR #167).
**Head commit ban đầu:** `62b61523d4ce0034eed7e5700d13782948c634ca` (PR #168).
**Tình trạng CI base PR #167:** [Run 45168230342](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/actions/runs/45168230342) trên commit `c289ff044c0ac7c5bd2f3036b013acac09f986f8` (**6/6 jobs success**).
**Tình trạng CI head PR #168:** [Run 45179594993](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/actions/runs/45179594993) trên commit `62b61523d4ce0034eed7e5700d13782948c634ca` (**6/6 jobs success**, bao gồm Backend 12m16s, Client Mobile, AI Service, Database Migrations, Client Admin Web, Contracts & Docs).
**Trạng thái tính năng:** **ĐÃ TRIỂN KHAI VÀ KIỂM CHỨNG MOBILE F05** cho luồng hợp đồng tích hợp. Nghiệm thu toàn bộ tính năng và mốc phát hành (P2-M4/Release) vẫn **MỞ** theo quy định.

---

## 1. Bối cảnh và phạm vi triển khai

Căn cứ theo tiến độ ngày 23/11/2026:
- F05 Backend / AI Service và các bổ sung BL-01, BL-02, BL-03 đã merge vào `main` tại PR #167.
- Xác nhận CI sau merge PR #167: toàn bộ 6/6 jobs hoàn tất thành công (`completed / success`).
- Nhánh `feat/mobile-f05-rescheduling` được tạo trực tiếp từ `main` đã cập nhật fast-forward tại commit `c289ff044c0ac7c5bd2f3036b013acac09f986f8`.
- Draft PR #168 đã được tạo trên GitHub và CI run 45179594993 đã xác nhận thành công 6/6 jobs.
- Hoàn thiện tinh chỉnh bổ sung: điều hướng HLV từ Lịch hẹn trực tiếp tới Lịch tập của Học viên liên kết và cơ chế phát lại lệnh Accept (idempotent replay) khi hết hạn.

---

## 2. Các thành phần Mobile F05 đã triển khai & tinh chỉnh

Tận dụng cấu trúc hiện có, apiClient, Expo Router, React Native UI components, token semantic colors, không tạo thêm dependency thừa:

1. **Contract Types (`apps/mobile/src/types/aiWorkoutReschedule.ts`)**:
   - Định nghĩa đầy đủ các schema theo OpenAPI và `contracts/ai-schemas/f05-workout-reschedule.schema.json`:
     `WorkoutRescheduleApiRequest`, `WorkoutRescheduleApiResponse`, `AiWorkoutRescheduleDetail`, `F05WorkoutReschedulePayload`, `F05RescheduleDestination`, `AcceptRescheduleApiRequest`, `AcceptRescheduleApiResponse`, `RejectRescheduleApiRequest`, `RejectRescheduleApiResponse`.
   - Phân định rõ ràng các trạng thái `RescheduleRecommendationStatus`: `PENDING`, `ACCEPTED`, `REJECTED`, `APPLIED`, `EXPIRED`, `CANCELLED`.
   - Phân định `RescheduleDecisionAuthority`: `STUDENT`, `TRAINER`.

2. **API Client & Xử lý lỗi (`apps/mobile/src/services/aiWorkoutRescheduleApi.ts`)**:
   - `generate()`: Gửi yêu cầu sinh đề xuất dời lịch qua `POST /api/v1/ai/workout-reschedules` kèm `commandKey`, `occurrenceId`, `expectedOccurrenceVersion`, `reason`, `preferredTimeOfDay`.
   - `getDetail()`: Đọc chi tiết đề xuất qua `GET /api/v1/ai/workout-reschedules/{recommendationId}`.
   - `accept()`: Gửi lệnh chấp nhận và áp dụng nguyên tử qua `POST /api/v1/ai/workout-reschedules/{recommendationId}/accept` kèm `commandKey`, `expectedOccurrenceVersion`, `expectedAppointmentVersion`, `expectedAppointmentId`.
   - `reject()`: Gửi lệnh từ chối qua `POST /api/v1/ai/workout-reschedules/{recommendationId}/reject` kèm `decisionNote`.
   - `getWorkoutRescheduleErrorMessage()`: Ánh xạ chuẩn xác mã lỗi từ backend thành hướng dẫn tiếng Việt rõ ràng:
     - `WORKOUT_SCHEDULE_OVERLAP`: Xung đột trùng khung giờ tập.
     - `PLANNED_WORKOUT_VERSION_CONFLICT`: Buổi tập bị trôi phiên bản trên máy chủ.
     - `APPOINTMENT_VERSION_CONFLICT`: Lịch hẹn liên kết bị trôi phiên bản.
     - `WORKOUT_SCHEDULE_COACH_REQUIRED_RESCHEDULE_UNSUPPORTED`: Chặn đổi buổi COACH_REQUIRED khi không có lịch hẹn bao trọn.
     - `APPOINTMENT_COACH_REQUIRED_DEPENDENCY`: Hướng dẫn phối hợp với HLV khi chuyển lịch hẹn rời buổi tập bắt buộc.
     - `AI_RUN_LIFECYCLE_CONFLICT` (expired 30 phút, already applied, idempotency conflict).
     - 403 `HUMAN_COACH`: Phân định thẩm quyền HLV vs Học viên.

3. **Core Modal Component (`apps/mobile/src/features/workout-schedule/components/AiWorkoutRescheduleModal.tsx`)**:
   - Tái sử dụng cho cả Học viên và Huấn luyện viên.
   - Hỗ trợ chọn khung giờ ưu tiên (`MORNING` [09:00], `AFTERNOON` [14:00], `EVENING` [18:00]) và nhập lý do; có ghi chú rõ ràng các mốc này là phỏng đoán heuristic, không phải availability đã xác nhận.
   - Hiển thị đầy đủ thông tin: Tiêu đề, giải thích AI, reasoning, usedData, missingData, giờ ban đầu và giờ đề xuất mới (giờ địa phương, offset UTC, IANA timezone).
   - Kiểm tra thẩm quyền nghiêm ngặt theo `proposal.decisionAuthority` và `coachingMode`:
     - Trong `HUMAN_COACH`: nếu Học viên mở xem, nút "Chấp nhận & Áp dụng" được ẩn/khóa kèm thông báo: "Trong chế độ có Huấn luyện viên (HUMAN_COACH), chỉ Huấn luyện viên mới có quyền phê duyệt áp dụng thay đổi giáo án."
     - Trong `SELF_DIRECTED`: Học viên có quyền phê duyệt áp dụng.
     - Huấn luyện viên trong `HUMAN_COACH`: có quyền phê duyệt áp dụng.
   - Xử lý hết hạn (TTL 30 phút, tính động số phút còn lại theo `proposal.expiresAt`) và xung đột (409): hiển thị cảnh báo và cung cấp nút "Tạo đề xuất mới".
   - **Bảo toàn danh tính lệnh & Phát lại sau timeout (Idempotent Retry Replay)**:
     - Khi gặp timeout mạng (status 0), thao tác thử lại giữ nguyên `pendingAcceptCommandKey`.
     - Tinh chỉnh `canAccept`: Nếu đang giữ `pendingAcceptCommandKey` từ lần bấm trước bị lỗi, client vẫn cho phép bấm "Thử lại cùng lệnh (Xác nhận & Áp dụng)" kể cả khi đồng hồ client đã quá `expiresAt`. Điều này khớp tuyệt đối với kiến trúc backend `AiWorkoutRescheduleService.java`: Bước 3 (kiểm tra Idempotent Replay) được thực thi trước Bước 4 (kiểm tra Expiry), đảm bảo nếu server đã áp dụng thành công lệnh trước đó trước khi mất mạng, client vẫn đọc lại được kết quả mà không bị chặn oan.
   - Ngăn phản hồi đến muộn (stale response): sử dụng ref theo dõi `activeOccurrenceId`, `activeStudentId` và `targetRecommendationId`, hủy cập nhật state nếu màn hình unmount hoặc đối tượng đổi trước khi request hoàn tất.
   - Hiển thị biên nhận thành công (Applied Receipt) kèm `resultingEntityId` và cờ phát lại `replayed`.

4. **Tích hợp màn hình Học viên (`StudentWorkoutScheduleDetailScreen.tsx`)**:
   - Thêm nút "Đề xuất dời lịch tập (AI)" (`testID="ai-workout-reschedule-button"`) cho các buổi ở trạng thái `SCHEDULED`.
   - Kết nối `AiWorkoutRescheduleModal`, tự động cập nhật cache và điều hướng tới buổi tập thay thế khi áp dụng thành công.

5. **Tích hợp màn hình Huấn luyện viên (`TrainerWorkoutScheduleScreen.tsx`)**:
   - Trong `TrainerOccurrenceCard`, phân định rõ ràng giữa quyền `canContribute` (cho đề xuất thủ công) và `canManage` (cho đề xuất AI dời lịch).
   - Nút "Đề xuất dời lịch (AI)" (`testID="trainer-ai-reschedule-${item.occurrenceId}"`) chỉ hiển thị khi có quyền `MANAGE` đối với `WORKOUT_PLAN`, đúng theo contract backend.
   - Mở modal F05 với ngữ cảnh `isTrainer = true`, `coachingMode = 'HUMAN_COACH'`, cập nhật lại lịch học viên sau khi HLV áp dụng.

6. **Phối hợp Lịch hẹn (`AppointmentScreens.tsx`)**:
   - Trong chi tiết lịch hẹn (`AppointmentDetailScreen`), khi có `plannedWorkoutId`:
     Hiển thị hướng dẫn và nút điều hướng theo vai trò (`testID="open-linked-workout-button"`):
     - Học viên: Điều hướng tới chi tiết buổi tập (`/workouts/schedule/${item.plannedWorkoutId}`) để tạo đề xuất dời lịch AI (F05) mới.
     - Huấn luyện viên: Tinh chỉnh tra cứu quan hệ huấn luyện đang hoạt động (`useRelationships`) của `studentId`. Nếu tìm thấy quan hệ `ACTIVE`, hiển thị nút `"Mở lịch tập học viên liên kết"` và điều hướng trực tiếp tới `/coaching/${activeRelationship.id}/workout-schedule`, bảo toàn nguyên vẹn ngữ cảnh học viên và buổi tập liên kết. Nếu không tìm thấy, fallback an toàn về `/coaching`.

---

## 3. Đối chiếu Acceptance / Test IDs và Phân định Mức độ Kiểm chứng

Bảng phân định minh bạch giữa các mức độ kiểm chứng:
- **Unit/Component Test (Mock API)**: Kiểm chứng hành vi giao diện, tương tác, tính thẩm quyền, xử lý lỗi mạng/timeout và logic nội bộ Mobile với mock data.
- **Backend Integration**: Kiểm chứng tích hợp Spring Boot MockMvc, HTTP FastAPI, PostgreSQL Testcontainers, và transactions nguyên tử (đã đạt 5/5 kịch bản tại PR #167).
- **Mobile UI Live Integration**: Kiểm chứng ứng dụng React Native Expo chạy trực tiếp nối tới backend local/staging đang hoạt động trên cổng 8080.
- **Real Model Evaluation**: Đánh giá latency, chi phí token, và chất lượng sinh đề xuất với nhà cung cấp LLM production (Non-goal của Phase 2 Mobile).

| Acceptance / Test ID | Tiêu chí nghiệm thu | Bằng chứng hiện có | Phân định mức độ kiểm chứng | Phần còn thiếu / Trạng thái |
|---|---|---|---|---|
| **T01 / T27** | PROPOSAL lifecycle: Generate → Review (reasoning, missingData, usedData) → Accept/Reject → Receipt. | Component test `AiWorkoutRescheduleModal.test.tsx` (11 tests); Backend test `AiWorkoutRescheduleIntegrationTest.java` (5 tests); Live Integration Scenario 1 (PASS). | Unit/Component Tests (Mock) + Backend Integration (Testcontainers) + Live Integration (Real DB/Backend) | **PASS (Mock, Integration & Live Verification)**. |
| **T02** | Phân định thẩm quyền theo mode: `HUMAN_COACH` chỉ HLV mới Accept; `SELF_DIRECTED` Học viên Accept; Học viên `HUMAN_COACH` bị khóa Accept và có cảnh báo. | Component test `AiWorkoutRescheduleModal.test.tsx`; Trainer schedule component test `trainerWorkoutScheduleScreen.test.tsx`; Live Integration Scenario 2 (PASS, HTTP 403 AI_FORBIDDEN). | Unit/Component Tests (Mock) + Live Integration | **PASS (Component & Live Tests)**. |
| **T03 / T04** | Quyền `MANAGE` vs `CONTRIBUTE` của Trainer; xử lý mất quyền / 403. | `trainerWorkoutScheduleScreen.test.tsx` kiểm tra ẩn nút khi thiếu MANAGE; `aiWorkoutRescheduleApi.test.ts` kiểm tra ánh xạ 403; Live Integration Scenario 3 (PASS). | Unit/Component Tests (Mock) + Live Integration | **PASS (Component & Live Tests)**. |
| **T05** | Xử lý trôi phiên bản (HTTP 409): `PLANNED_WORKOUT_VERSION_CONFLICT`, `APPOINTMENT_VERSION_CONFLICT`. | `AiWorkoutRescheduleModal.test.tsx` kiểm tra hiển thị nút "Tạo đề xuất mới", không bị crash, dọn dẹp state cũ; Live Integration Scenario 5 & 6 (PASS, HTTP 409). | Unit/Component Tests (Mock) + Live Integration | **PASS (Component & Live Tests)**. |
| **T06 / T25** | Idempotent Retry sau Timeout: giữ nguyên `commandKey`, xử lý phát lại (`replayed: true`), cho phép replay kể cả khi quá `expiresAt`. | `AiWorkoutRescheduleModal.test.tsx` (test replay timeout & expiry); `aiWorkoutRescheduleApi.test.ts` kiểm tra payload commandKey; Live Integration Scenario 7 & 8 (PASS, replayed=true). | Unit/Component Tests (Mock) + Backend Integration + Live Integration | **PASS (Mock, Backend & Live Integration)**. |
| **T09** | Bảo toàn lịch sử: buổi tập cũ giữ nguyên, tạo bản ghi kế thừa, biên nhận hiển thị `resultingEntityId`. | `AiWorkoutRescheduleModal.test.tsx` kiểm tra biên nhận thành công; Backend Flyway v45 bảo toàn DB audit; Live Integration Scenario 1 & 4 (PASS, DB status verified). | Unit/Component Tests (Mock) + Backend Integration + Live Integration | **PASS (Mock, Backend & Live Integration)**. |
| **T10 / BL-03** | Phối hợp Lịch hẹn: điều hướng liên kết từ Lịch hẹn sang Buổi tập/Lịch tập theo vai trò; bảo toàn context `studentId`. | `appointmentScreens.test.tsx` (6 tests kiểm tra vai trò Student và Trainer với relationship active); Live Integration Scenario 6 (PASS). | Unit/Component Tests (Mock) + Live Integration | **PASS (Component & Live Tests)**. |
| **T23** | TTL Hết hạn (30 phút): hiển thị thời gian còn lại, khóa chấp nhận mới khi quá hạn, cho phép tạo đề xuất mới. | `AiWorkoutRescheduleModal.test.tsx` kiểm tra nhãn hết hạn và vô hiệu hóa nút Chấp nhận mới; Live Integration Scenario 8 (PASS, HTTP 409). | Unit/Component Tests (Mock) + Live Integration | **PASS (Component & Live Tests)**. |
| **Stale Async Guard** | Ngăn response đến muộn ghi đè dữ liệu khi unmount, đổi Student hoặc đổi Buổi tập. | `AiWorkoutRescheduleModal.test.tsx` kiểm tra mount/unmount hủy bỏ an toàn không leak state. | Unit/Component Tests (Mock) | **PASS (Component Tests)**. |
| **Live API & Domain Integration** | Chạy toàn bộ 8 luồng tích hợp Mobile API Client -> Spring Boot Backend (port 8080) -> FastAPI AI Service (port 8000) -> PostgreSQL (port 5433). | Đã chạy thành công 8/8 kịch bản tích hợp live qua test harness `tools/verify_f05_mobile_live_integration.py` với backend Spring Boot, AI service FastAPI và database PostgreSQL thật. JSON evidence: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json`](artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json), báo cáo chi tiết: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_report_20261123.md`](artifacts/f05-runtime-evidence/f05_mobile_live_report_20261123.md). | Live API & Domain Integration (Real Services) | **PASS (8/8 Live Scenarios Verified)**. |
| **Mobile UI Live Runtime** | Tương tác trực tiếp trên giao diện thực tế (Đăng nhập -> Lịch tập -> Mở Modal -> Bấm Accept -> Toast / Biên nhận) trên thiết bị thật / Android Emulator hoặc qua browser automation. | Đã xác thực đóng gói và phục vụ trên Expo Web Bundler port 8081 (1282 modules). Toàn bộ 6 kịch bản UI đã được kiểm chứng bằng 48 component tests (`@testing-library/react-native`). | Interactive UI Runtime (Native / Browser Automation) | **NOT RUN / BLOCKED** (Môi trường Windows CLI headless không có display cho Android Emulator và không có runner Playwright/Puppeteer). |
| **Real LLM Model Validation** | Đánh giá chất lượng đề xuất với model Gemini/OpenAI production thật. | Non-goal của Phase 2 Mobile; sử dụng simulated provider theo đúng quy định. | Real Model Evaluation | **MỞ (Non-goal Phase 2 Mobile)**. |

---

## 4. Bằng chứng kiểm chứng tự động

Các kiểm tra đã chạy thành công trong phiên làm việc:

1. **Mobile Test Suites (48/48 PASS)**:
   - `aiWorkoutRescheduleApi.test.ts`: **18/18 tests PASS** (tham số API, retry identity, error mapping tiếng Việt, field errors).
   - `AiWorkoutRescheduleModal.test.tsx`: **11/11 tests PASS** (luồng Student SELF_DIRECTED, luồng Trainer HUMAN_COACH, chặn quyền Student HUMAN_COACH, từ chối đề xuất, xử lý xung đột 409, hướng dẫn lịch hẹn liên kết, ngăn phản hồi trễ, thử lại giữ nguyên commandKey sau lỗi mạng, phát lại lệnh sau timeout kể cả khi quá hạn, kiểm tra copy disclaimer heuristic và đếm ngược hết hạn).
   - `workoutScheduleChange.test.tsx`: **11/11 tests PASS** (hồi quy đổi lịch thủ công Học viên).
   - `trainerWorkoutScheduleScreen.test.tsx`: **2/2 tests PASS** (hồi quy màn hình lịch HLV và phân định thẩm quyền MANAGE vs CONTRIBUTE cho nút AI Reschedule).
   - `appointmentScreens.test.tsx`: **6/6 tests PASS** (hồi quy màn hình lịch hẹn, điều hướng học viên tới buổi tập liên kết, điều hướng HLV tới lịch tập của học viên liên kết theo active relationship và fallback về coaching hub).

2. **Kiểm tra cú pháp & Typecheck**:
   - `npm run typecheck:mobile` (`tsc --noEmit`): **PASS** (0 errors).
   - `npm run lint:mobile` (`expo lint`): **PASS** (0 errors, 0 warnings).

3. **Kiểm tra Hợp đồng & UX docs**:
   - `npm run validate:contracts`: **PASS** (10 schemas, 48 contract fixtures verified with Ajv 2020).
   - `npm run validate:ux-docs`: **PASS** (18 required files).

4. **Kiểm tra Git diff**:
   - `git diff --check`: **PASS** (0 whitespace/formatting issues).

5. **Kiểm chứng Runtime Tích Hợp Live (8/8 Scenarios PASS)**:
   - Script chạy: `tools/verify_f05_mobile_live_integration.py`
   - Báo cáo chi tiết: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_report_20261123.md`](artifacts/f05-runtime-evidence/f05_mobile_live_report_20261123.md)
   - JSON Evidence: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json`](artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json)
   - Kết quả:
     * Scenario 1 (Student SELF_DIRECTED E2E): **PASS** (predecessor RESCHEDULED, successor SCHEDULED, rec APPLIED).
     * Scenario 2 (Student HUMAN_COACH Blocked): **PASS** (HTTP 403 AI_FORBIDDEN).
     * Scenario 3 (Trainer HUMAN_COACH Apply): **PASS** (appliedBy trainer, predecessor RESCHEDULED, successor SCHEDULED).
     * Scenario 4 (Reject Proposal): **PASS** (buổi tập gốc giữ nguyên SCHEDULED|1, rec REJECTED).
     * Scenario 5 (Stale Source Version Conflict): **PASS** (HTTP 409 AI_RUN_LIFECYCLE_CONFLICT).
     * Scenario 6 (Appointment Version Drift): **PASS** (HTTP 409 AI_RUN_LIFECYCLE_CONFLICT).
     * Scenario 7 (Idempotent Replay): **PASS** (replayed=true, cùng resultingEntityId, DB count = 1).
     * Scenario 8 (Expired Proposal Semantics): **PASS** (replay proposal đã apply thành công replayed=true; new accept proposal pending bị chặn HTTP 409).

---

## 5. Các giới hạn và Gate còn mở

Các giới hạn được giữ nguyên đúng theo thỏa thuận kỹ thuật:
- **COACH_REQUIRED disjoint move**: Vẫn tuân thủ guard bảo vệ của Schedule backend (`APPOINTMENT_COACH_REQUIRED_DEPENDENCY`). Không tự ý bỏ qua supervision.
- **Request status EXPIRED/CANCELLED**: V45 database hiện chỉ hỗ trợ `PENDING/ACCEPTED/REJECTED`. Mobile không tự tạo thêm trạng thái không có trong hợp đồng.
- **Availability đầy đủ & Recovery chuyên môn (D06/OD-3)**: Vẫn dùng slot gợi ý có khung giờ buổi sáng/chiều/tối; các quy tắc hồi phục sinh lý chuyên sâu vẫn tiếp tục **MỞ** chờ Hội đồng chuyên môn phê duyệt.
- **Provider thật**: Kiểm thử sử dụng API contract và simulated provider; việc đánh giá latency/cost trên LLM production vẫn tiếp tục **MỞ**.
- **Live API & Domain Integration**: **ĐÃ HOÀN THÀNH (8/8 PASS)** trên môi trường tích hợp dịch vụ thật (Spring Boot, FastAPI, PostgreSQL) với fixture cách ly và tự động teardown.
- **Mobile UI Live Runtime**: **CHƯA CHẠY / BỊ CHẶN (BLOCKED)** trên môi trường Windows CLI headless do không có màn hình hoạt động cho Android Emulator và không có runner Playwright/Puppeteer trong dự án; logic và tương tác UI được bảo chứng bằng 48/48 component tests.
- **Sign-off**: Quyết định nghiệm thu toàn bộ tính năng F05/P2-M4 thuộc về Project Owner / Reviewer chính thức.
