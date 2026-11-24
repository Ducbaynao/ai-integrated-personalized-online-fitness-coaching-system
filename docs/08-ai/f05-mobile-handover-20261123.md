# F05 — Bàn giao triển khai Mobile Workout Rescheduling

**Ngày cập nhật:** 24/11/2026, UTC+7.
**Vai trò thực hiện:** DEV-A (theo phân công chỉ đạo Phase 2).
**Branch:** `feat/mobile-f05-rescheduling`.
**Base commit:** `c289ff044c0ac7c5bd2f3036b013acac09f986f8` (Merge PR #167).
**Tình trạng CI base PR #167:** [Run 45168230342](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/actions/runs/45168230342) trên commit `c289ff044c0ac7c5bd2f3036b013acac09f986f8` (**6/6 jobs success**).
**Tình trạng CI head PR #168:** [Run 45225943805](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/actions/runs/45225943805) (**6/6 jobs success**).
**Trạng thái tính năng:** **ĐÃ HOÀN TẤT TRIỂN KHAI VÀ KIỂM CHỨNG TOÀN DIỆN MOBILE F05** (bao gồm Component Tests, Live API & Domain Integration, và Expo Web Interactive UI Verification với ảnh chụp màn hình). Nghiệm thu phát hành chính thức (P2-M4/Release) và kiểm thử trên thiết bị di động thật (native physical device) vẫn **MỞ** theo quy định.

---

## 1. Bối cảnh và phạm vi triển khai

Căn cứ theo tiến độ ngày 24/11/2026:
- F05 Backend / AI Service và các bổ sung BL-01, BL-02, BL-03 đã merge vào `main` tại PR #167.
- Xác nhận CI sau merge PR #167: toàn bộ 6/6 jobs hoàn tất thành công (`completed / success`).
- Nhánh `feat/mobile-f05-rescheduling` được tạo trực tiếp từ `main` đã cập nhật fast-forward tại commit `c289ff044c0ac7c5bd2f3036b013acac09f986f8`.
- Draft PR #168 đã được tạo trên GitHub và CI checks đã xác nhận thành công 6/6 jobs.
- Hoàn thiện kiểm chứng tương tác thực tế (Interactive UI Verification) trên Expo Web qua browser automation (Google Chrome Chromium / Playwright) với chuỗi dịch vụ thật: Backend Spring Boot (8080), AI Service FastAPI (8000), PostgreSQL (5433).

---

## 2. Các thành phần Mobile F05 đã triển khai & tinh chỉnh

Tận dụng cấu trúc hiện có, apiClient, Expo Router, React Native UI components, token semantic colors, không tạo thêm dependency thừa:

1. **Contract Types ([aiWorkoutReschedule.ts](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/types/aiWorkoutReschedule.ts))**:
   - Định nghĩa đầy đủ các schema theo OpenAPI và `contracts/ai-schemas/f05-workout-reschedule.schema.json`:
     `WorkoutRescheduleApiRequest`, `WorkoutRescheduleApiResponse`, `AiWorkoutRescheduleDetail`, `F05WorkoutReschedulePayload`, `F05RescheduleDestination`, `AcceptRescheduleApiRequest`, `AcceptRescheduleApiResponse`, `RejectRescheduleApiRequest`, `RejectRescheduleApiResponse`.
   - Phân định rõ ràng các trạng thái `RescheduleRecommendationStatus`: `PENDING`, `ACCEPTED`, `REJECTED`, `APPLIED`, `EXPIRED`, `CANCELLED`.
   - Phân định `RescheduleDecisionAuthority`: `STUDENT`, `TRAINER`.

2. **API Client & Xử lý lỗi ([aiWorkoutRescheduleApi.ts](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/services/aiWorkoutRescheduleApi.ts))**:
   - `generate()`: Gửi yêu cầu sinh đề xuất dời lịch qua `POST /api/v1/ai/workout-reschedules` kèm `commandKey`, `occurrenceId`, `expectedOccurrenceVersion`, `reason`, `preferredTimeOfDay`.
   - `getDetail()`: Đọc chi tiết đề xuất qua `GET /api/v1/ai/workout-reschedules/{recommendationId}`.
   - `accept()`: Gửi lệnh chấp nhận và áp dụng nguyên tử qua `POST /api/v1/ai/workout-reschedules/{recommendationId}/accept` kèm `commandKey`, `expectedOccurrenceVersion`, `expectedAppointmentVersion`, `expectedAppointmentId`.
   - `reject()`: Gửi lệnh từ chối qua `POST /api/v1/ai/workout-reschedules/{recommendationId}/reject` kèm `decisionNote`.
   - `getWorkoutRescheduleErrorMessage()`: Ánh xạ chuẩn xác mã lỗi từ backend thành hướng dẫn tiếng Việt rõ ràng (`WORKOUT_SCHEDULE_OVERLAP`, `PLANNED_WORKOUT_VERSION_CONFLICT`, `APPOINTMENT_VERSION_CONFLICT`, `WORKOUT_SCHEDULE_COACH_REQUIRED_RESCHEDULE_UNSUPPORTED`, `APPOINTMENT_COACH_REQUIRED_DEPENDENCY`, `AI_RUN_LIFECYCLE_CONFLICT`, 403 `HUMAN_COACH`).

3. **Core Modal Component ([AiWorkoutRescheduleModal.tsx](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/features/workout-schedule/components/AiWorkoutRescheduleModal.tsx))**:
   - Tái sử dụng cho cả Học viên và Huấn luyện viên.
   - Hỗ trợ chọn khung giờ ưu tiên (`MORNING` [09:00], `AFTERNOON` [14:00], `EVENING` [18:00]) và nhập lý do; có ghi chú rõ ràng các mốc này là phỏng đoán heuristic, không phải availability đã xác nhận.
   - Hiển thị đầy đủ thông tin: Tiêu đề, giải thích AI, reasoning, usedData, missingData, giờ ban đầu và giờ đề xuất mới (giờ địa phương, offset UTC, IANA timezone).
   - Phân quyền nghiêm ngặt theo `proposal.decisionAuthority` và `coachingMode`:
     - Trong `HUMAN_COACH`: nếu Học viên mở xem, nút "Chấp nhận & Áp dụng" được ẩn/khóa kèm thông báo: "Trong chế độ có Huấn luyện viên (HUMAN_COACH), chỉ Huấn luyện viên mới có quyền phê duyệt áp dụng thay đổi giáo án."
     - Trong `SELF_DIRECTED`: Học viên có quyền phê duyệt áp dụng.
     - Huấn luyện viên trong `HUMAN_COACH`: có quyền phê duyệt áp dụng.
   - Xử lý hết hạn (TTL 30 phút, tính động số phút còn lại theo `proposal.expiresAt`) và xung đột (409): hiển thị cảnh báo và cung cấp nút "Tạo đề xuất mới".
   - **Bảo toàn danh tính lệnh & Phát lại sau timeout (Idempotent Retry Replay)**:
     - Khi gặp timeout mạng, thao tác thử lại giữ nguyên `pendingAcceptCommandKey`.
     - Tinh chỉnh `canAccept`: Nếu đang giữ `pendingAcceptCommandKey` từ lần bấm trước bị lỗi, client vẫn cho phép bấm "Thử lại cùng lệnh (Xác nhận & Áp dụng)" kể cả khi đồng hồ client đã quá `expiresAt`, khớp với thứ tự ưu tiên Replay trước Expiry của Backend.
   - **Ngăn phản hồi đến muộn (stale async guard)**: sử dụng ref theo dõi `activeOccurrenceId`, `activeStudentId` và `targetRecommendationId`, hủy cập nhật state nếu màn hình unmount hoặc đối tượng đổi trước khi request hoàn tất.
   - Hiển thị biên nhận thành công (Applied Receipt) kèm `resultingEntityId` và cờ phát lại `replayed`.

4. **Tích hợp màn hình Học viên ([StudentWorkoutScheduleDetailScreen.tsx](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/features/workout-schedule/StudentWorkoutScheduleDetailScreen.tsx))**:
   - Thêm nút "Đề xuất dời lịch tập (AI)" (`testID="ai-workout-reschedule-button"`) cho các buổi ở trạng thái `SCHEDULED`.
   - Kết nối `useRelationships` để truyền đúng `coachingMode` (`HUMAN_COACH` khi có coach active, ngược lại `SELF_DIRECTED`).
   - Duy trì hiển thị thẻ biên nhận thành công trong modal cho đến khi người dùng bấm "Hoàn tất và quay lại" (`onClose`), sau đó mới đóng modal và điều hướng mượt mà tới buổi tập kế thừa.

5. **Tích hợp màn hình Huấn luyện viên ([TrainerWorkoutScheduleScreen.tsx](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/features/workout-schedule/TrainerWorkoutScheduleScreen.tsx))**:
   - Trong `TrainerOccurrenceCard`, phân định rõ ràng giữa quyền `canContribute` (cho đề xuất thủ công) và `canManage` (cho đề xuất AI dời lịch).
   - Nút "Đề xuất dời lịch (AI)" (`testID="trainer-ai-reschedule-${item.occurrenceId}"`) chỉ hiển thị khi có quyền `MANAGE` đối với `WORKOUT_PLAN`, đúng theo contract backend.
   - Giữ mở modal hiển thị biên nhận cho HLV cho đến khi bấm nút hoàn tất.

6. **Phối hợp Lịch hẹn ([AppointmentScreens.tsx](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/features/appointments/AppointmentScreens.tsx))**:
   - Khi có `plannedWorkoutId`: hiển thị nút điều hướng theo vai trò (`testID="open-linked-workout-button"`):
     - Học viên: Điều hướng tới chi tiết buổi tập (`/workouts/schedule/${item.plannedWorkoutId}`) để tạo đề xuất dời lịch AI.
     - Huấn luyện viên: Tự động tra cứu quan hệ huấn luyện đang hoạt động (`useRelationships`) của `studentId` và điều hướng trực tiếp tới `/coaching/${activeRelationship.id}/workout-schedule`, bảo toàn nguyên vẹn ngữ cảnh học viên và buổi tập liên kết.

---

## 3. Đối chiếu Acceptance / Test IDs và Phân định Mức độ Kiểm chứng

Bảng phân định minh bạch giữa các tầng kiểm chứng:
- **Unit/Component Test (Mock API)**: Kiểm chứng logic component, layout, accessibility và state transitions trên Jest.
- **Backend Integration**: Kiểm chứng tích hợp Spring Boot MockMvc, HTTP FastAPI, PostgreSQL Testcontainers, và transactions nguyên tử.
- **Live API & Domain Integration**: Kiểm chứng client contract qua HTTP REST tới Spring Boot 8080, FastAPI 8000 và PostgreSQL 5433 thật.
- **Expo Web Interactive UI Verification**: Kiểm chứng tương tác end-to-end trên trình duyệt Chrome (login, click, form, receipt visual) qua Playwright.
- **Native Device Runtime (Physical/AVD)**: Kiểm nghiệm file build APK/AAB trên thiết bị Android/iOS thật.
- **Real Model Evaluation**: Đánh giá latency/cost trên LLM production (Non-goal của Phase 2 Mobile).

| Acceptance / Test ID | Tiêu chí nghiệm thu | Bằng chứng hiện có | Phân định mức độ kiểm chứng | Trạng thái |
|---|---|---|---|:---:|
| **T01 / T27** | PROPOSAL lifecycle: Generate → Review (reasoning, missingData, usedData) → Accept/Reject → Receipt. | Component test `AiWorkoutRescheduleModal.test.tsx` (11 tests); Backend test `AiWorkoutRescheduleIntegrationTest.java` (5 tests); Live API Scenario 1; Interactive UI Scenario 1 ([ui_01_student_self_directed_receipt.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_01_student_self_directed_receipt.png)). | Component Tests + Backend Integration + Live API + Expo Web Interactive UI | **PASS** |
| **T02** | Phân định thẩm quyền theo mode: `HUMAN_COACH` chỉ HLV mới Accept; `SELF_DIRECTED` Học viên Accept; Học viên `HUMAN_COACH` bị khóa Accept và có cảnh báo. | Component test `AiWorkoutRescheduleModal.test.tsx`; Live API Scenario 2 (HTTP 403); Interactive UI Scenario 2 ([ui_02_student_human_coach_blocked.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_02_student_human_coach_blocked.png)). | Component Tests + Live API + Expo Web Interactive UI | **PASS** |
| **T03 / T04** | Quyền `MANAGE` vs `CONTRIBUTE` của Trainer; xử lý mất quyền / 403. | `trainerWorkoutScheduleScreen.test.tsx` kiểm tra ẩn nút khi thiếu `MANAGE`; Live API Scenario 3; Interactive UI Scenario 3 ([ui_03_trainer_apply_success.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_03_trainer_apply_success.png)). | Component Tests + Live API + Expo Web Interactive UI | **PASS** |
| **T05** | Xử lý trôi phiên bản (HTTP 409): `PLANNED_WORKOUT_VERSION_CONFLICT`, `APPOINTMENT_VERSION_CONFLICT`. | Component test `AiWorkoutRescheduleModal.test.tsx`; Live API Scenario 5 (HTTP 409); Interactive UI Scenario 5 ([ui_05_conflict_toast.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_05_conflict_toast.png)). | Component Tests + Live API + Expo Web Interactive UI | **PASS** |
| **T06 / T25** | Idempotent Retry sau Timeout: giữ nguyên `commandKey`, xử lý phát lại (`replayed: true`), cho phép replay kể cả khi quá `expiresAt`. | Component test `AiWorkoutRescheduleModal.test.tsx`; Live API Scenario 7 & 8; Interactive UI Scenario 7 ([ui_07_replay_safe.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_07_replay_safe.png) - browser fetch replay HTTP 200 replayed=true, DB count=1). | Component Tests + Backend Integration + Live API + Expo Web Interactive UI | **PASS** |
| **T09** | Bảo toàn lịch sử: buổi tập cũ giữ nguyên, tạo bản ghi kế thừa, biên nhận hiển thị `resultingEntityId`. | Component test `AiWorkoutRescheduleModal.test.tsx`; Backend audit trigger; Live API Scenario 1 & 4; Interactive UI Scenario 1 & 4 ([ui_04_reject_preserved.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_04_reject_preserved.png)). | Component Tests + Backend Integration + Live API + Expo Web Interactive UI | **PASS** |
| **T10 / BL-03** | Phối hợp Lịch hẹn: điều hướng liên kết từ Lịch hẹn sang Buổi tập/Lịch tập theo vai trò; bảo toàn context `studentId`. | `appointmentScreens.test.tsx` (6 tests); Live API Scenario 6 (appointment drift 409); Interactive UI Scenario 6 ([ui_06_appointment_nav.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_06_appointment_nav.png)). | Component Tests + Live API + Expo Web Interactive UI | **PASS** |
| **T23** | TTL Hết hạn (30 phút): hiển thị thời gian còn lại, khóa chấp nhận mới khi quá hạn, cho phép tạo đề xuất mới. | `AiWorkoutRescheduleModal.test.tsx`; Live API Scenario 8; Countdown dynamic remaining minutes & regenerate UI state. | Component Tests + Live API + Expo Web Interactive UI | **PASS** |
| **Stale Async Guard** | Ngăn response đến muộn ghi đè dữ liệu khi unmount, đổi Student hoặc đổi Buổi tập. | `AiWorkoutRescheduleModal.test.tsx` (kiểm tra React refs bảo vệ an toàn). | Component Tests (Mock) | **PASS** |
| **Live API & Domain Integration** | Chạy toàn bộ 8 luồng tích hợp Mobile API Client -> Spring Boot Backend (8080) -> FastAPI AI Service (8000) -> PostgreSQL (5433). | Script `tools/verify_f05_mobile_live_integration.py` đạt 8/8 kịch bản. JSON evidence: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json). | Live API & Domain Integration (Real Services) | **PASS (8/8 Scenarios)** |
| **Expo Web Interactive UI Verification** | Tương tác trực tiếp trên giao diện thực tế (Đăng nhập -> Lịch tập -> Mở Modal -> Chọn slot -> Tạo đề xuất -> Bấm Accept -> Biên nhận visual) qua Playwright/Chrome headless. | Script `tools/verify_f05_mobile_ui_interactive.py` đạt 7/7 kịch bản. JSON evidence: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_ui_interactive_20261124.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_ui_interactive_20261124.json), Thư mục screenshots: [`docs/08-ai/artifacts/f05-runtime-evidence/screenshots/`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/). | Interactive UI Verification (Expo Web + Live Services) | **PASS (7/7 Scenarios)** |
| **Native Device Runtime (Physical/AVD)** | Chạy file APK/AAB trên thiết bị di động thật hoặc máy ảo Android/iOS AVD. | Giữ mở cho vòng kiểm thử manual QA/release do môi trường agent là Windows CLI headless không có display. | Native Device Runtime | **CHƯA KIỂM CHỨNG (OPEN)** |
| **Real LLM Model Validation** | Đánh giá chất lượng đề xuất với model Gemini/OpenAI production thật. | Non-goal của Phase 2 Mobile; sử dụng simulated provider theo đúng quy định. | Real Model Evaluation | **MỞ (Non-goal Phase 2)** |

---

## 4. Bằng chứng kiểm chứng tự động đã chạy

Các kiểm tra đã chạy thành công trong phiên làm việc:

1. **Mobile Test Suites (96 suites, 831 tests PASS)**:
   - 5 test suites trọng tâm F05:
     * `aiWorkoutRescheduleApi.test.ts`: **18/18 tests PASS**
     * `AiWorkoutRescheduleModal.test.tsx`: **11/11 tests PASS**
     * `workoutScheduleChange.test.tsx`: **11/11 tests PASS**
     * `trainerWorkoutScheduleScreen.test.tsx`: **2/2 tests PASS**
     * `appointmentScreens.test.tsx`: **6/6 tests PASS**
   - 91 suites hồi quy toàn bộ mobile: **783/783 tests PASS**

2. **Kiểm tra cú pháp & Typecheck**:
   - `npm run typecheck:mobile` (`tsc --noEmit`): **PASS** (0 errors).
   - `npm run lint:mobile` (`expo lint`): **PASS** (0 errors, 0 warnings).

3. **Kiểm tra Hợp đồng & UX docs**:
   - `npm run validate:contracts`: **PASS** (10 schemas, 48 contract fixtures verified with Ajv 2020).
   - `npm run validate:ux-docs`: **PASS** (18 required files).

4. **Kiểm tra Git diff**:
   - `git diff --check`: **PASS** (0 whitespace/formatting issues).

5. **Kiểm chứng Runtime Tích Hợp Live API (8/8 Scenarios PASS)**:
   - Script chạy: `tools/verify_f05_mobile_live_integration.py`
   - Báo cáo chi tiết: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_report_20261123.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_report_20261123.md)
   - JSON Evidence: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json)

6. **Kiểm chứng Tương Tác Giao Diện Thực Tế Expo Web (7/7 Scenarios PASS)**:
   - Script chạy: `tools/verify_f05_mobile_ui_interactive.py`
   - JSON Evidence: [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_ui_interactive_20261124.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_ui_interactive_20261124.json)
   - 7 file ảnh screenshot bằng chứng trực quan:
     * [ui_01_student_self_directed_receipt.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_01_student_self_directed_receipt.png)
     * [ui_02_student_human_coach_blocked.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_02_student_human_coach_blocked.png)
     * [ui_03_trainer_apply_success.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_03_trainer_apply_success.png)
     * [ui_04_reject_preserved.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_04_reject_preserved.png)
     * [ui_05_conflict_toast.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_05_conflict_toast.png)
     * [ui_06_appointment_nav.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_06_appointment_nav.png)
     * [ui_07_replay_safe.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_07_replay_safe.png)

---

## 5. Các giới hạn và Gate còn mở

Các giới hạn được giữ nguyên đúng theo thỏa thuận kỹ thuật:
- **COACH_REQUIRED disjoint move**: Vẫn tuân thủ guard bảo vệ của Schedule backend (`APPOINTMENT_COACH_REQUIRED_DEPENDENCY`). Không tự ý bỏ qua supervision.
- **Request status EXPIRED/CANCELLED**: V45 database hiện chỉ hỗ trợ `PENDING/ACCEPTED/REJECTED`. Mobile không tự tạo thêm trạng thái không có trong hợp đồng.
- **Availability đầy đủ & Recovery chuyên môn (D06/OD-3)**: Vẫn dùng slot gợi ý có khung giờ buổi sáng/chiều/tối; các quy tắc hồi phục sinh lý chuyên sâu vẫn tiếp tục **MỞ** chờ Hội đồng chuyên môn phê duyệt.
- **Provider thật**: Kiểm thử sử dụng API contract và simulated provider; việc đánh giá latency/cost trên LLM production vẫn tiếp tục **MỞ**.
- **Native Physical Device Verification**: Thử nghiệm trên thiết bị di động vật lý (iOS/Android) hoặc máy ảo AVD tiếp tục **MỞ** chờ vòng kiểm thử manual QA trước khi release.
- **Sign-off**: Quyết định nghiệm thu toàn bộ tính năng F05/P2-M4 thuộc về Project Owner / Reviewer chính thức.
