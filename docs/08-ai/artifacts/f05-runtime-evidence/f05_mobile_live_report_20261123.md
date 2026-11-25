# Báo Cáo Kiểm Chứng Live Integration F05: API, Domain & Expo Web Interactive UI

**Ngày cập nhật:** 24/11/2026
**Người thực hiện:** DEV-A
**Branch:** `feat/mobile-f05-rescheduling`
**PR:** Draft PR #168
**Repository:** `ai-integrated-personalized-online-fitness-coaching-system`
**API Evidence Artifact:** [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json)
**UI Evidence Artifact:** [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_ui_interactive_20261124.json`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_ui_interactive_20261124.json)
**Visual Screenshots Directory:** [`docs/08-ai/artifacts/f05-runtime-evidence/screenshots/`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/)

---

## 1. Môi Trường Thực Nghiệm (Live Integration Environment)

Kiểm chứng thực nghiệm được thực hiện trên chuỗi thành phần dịch vụ thật đang chạy local:
- **Mobile Client & Web Runtime:** Expo Web Bundler (port 8081, React Native Web, Metro).
- **Automation Runner:** Google Chrome Headless & Playwright (`tools/verify_f05_mobile_ui_interactive.py`, viewport 390x844).
- **Backend Core:** Spring Boot Modular Monolith (Java 21, Spring Boot 3.4.4, profile `dev`, mode `http`, port 8080).
- **AI Service:** FastAPI Service (Python 3.12, Uvicorn, port 8000) với `SimulatedF05WorkoutProvider`.
- **Database:** PostgreSQL 18.6 (Docker port 5433 -> 5432, schema `fitness`, Flyway 60 migrations verified).
- **Cache & Mail:** Redis (port 6379), Mailpit (port 1025/8025).

---

## 2. Kết Quả 8 Kịch Bản Tích Hợp Live API & Domain (API Level Verification)

Harness `tools/verify_f05_mobile_live_integration.py` thực thi kiểm chứng ở tầng **Live API & Domain Integration** (gửi HTTP request theo đúng client contract, đi qua pipeline controller, domain services, transactions, HTTP call sang FastAPI AI Service, và persistence vào PostgreSQL với DB readback):

| Kịch bản | Mô tả kiểm chứng | Kết quả API / DB Readback | Phân loại & Ghi chú | Trạng thái |
|---|---|---|---|:---:|
| **Scenario 1** | **SELF_DIRECTED End-to-End**:<br>Generate -> Review -> Accept -> Receipt -> Successor created | HTTP 200; `status: APPLIED`, `replayed: false`, `resultingEntityId` hợp lệ.<br>DB readback: Predecessor status = `RESCHEDULED`, Successor status = `SCHEDULED` (`rescheduled_from_id` trỏ đúng predecessor), Recommendation status = `APPLIED`. | Standard Flow | **PASS** |
| **Scenario 2** | **HUMAN_COACH Student Blocked**:<br>Học viên gửi yêu cầu Accept khi đang ở chế độ huấn luyện có HLV | HTTP 403 Forbidden (`errorCode: AI_FORBIDDEN`). Quyền quyết định thuộc về HLV (`decisionAuthority: TRAINER`), học viên không được đơn phương apply. | Standard Flow | **PASS** |
| **Scenario 3** | **HUMAN_COACH Trainer Apply**:<br>HLV phụ trách có quyền `MANAGE` duyệt và apply đề xuất | HTTP 200; `status: APPLIED`, `resultingEntityId` hợp lệ.<br>DB readback: Predecessor status = `RESCHEDULED`, Successor status = `SCHEDULED`. | Standard Flow | **PASS** |
| **Scenario 4** | **Reject Proposal**:<br>Học viên từ chối đề xuất với lý do (`decisionNote`) | HTTP 200; `status: REJECTED`.<br>DB readback: Buổi tập gốc giữ nguyên trạng thái `SCHEDULED`, version không đổi (`1`), recommendation status = `REJECTED`. | Standard Flow | **PASS** |
| **Scenario 5** | **Stale Source / Version Conflict**:<br>Buổi tập bị thay đổi đồng thời (version drift 1 -> 2) trước khi Accept | HTTP 409 Conflict (`errorCode: AI_RUN_LIFECYCLE_CONFLICT`). Snapshot gốc được bảo toàn. | **Controlled Fault Injection** | **PASS** |
| **Scenario 6** | **Appointment Drift Coordination**:<br>Lịch hẹn liên kết bị trôi version (1 -> 2) trước khi Accept | HTTP 409 Conflict (`errorCode: AI_RUN_LIFECYCLE_CONFLICT`). Snapshot gốc được bảo toàn. | **Controlled Fault Injection** | **PASS** |
| **Scenario 7** | **Timeout & Idempotent Replay**:<br>Gửi lại cùng `commandKey` sau sự cố timeout mạng | HTTP 200; `replayed: true`, `resultingEntityId` khớp chính xác với lần chạy đầu.<br>DB readback: Số lượng successor record trong database chính xác bằng **1** (không tạo duplicate entity). | Standard Flow | **PASS** |
| **Scenario 8** | **Expired Proposal Semantics**:<br>Đề xuất đã hết hạn (`expiresAt` trong quá khứ) | 8A. Replay với `commandKey` đã apply thành công trước đó: HTTP 200 `replayed: true` (Idempotent replay ưu tiên trước kiểm tra expiry).<br>8B. Gửi lệnh Accept mới với `commandKey` mới trên đề xuất đã hết hạn: HTTP 409 Conflict (`AI_RUN_LIFECYCLE_CONFLICT`). | **Controlled Fault Injection** | **PASS** |

---

## 3. Kết Quả 7 Kịch Bản Kiểm Chứng Tương Tác UI Thực Tế Trên Expo Web

Harness `tools/verify_f05_mobile_ui_interactive.py` sử dụng browser automation (Google Chrome Chromium qua Playwright) kết nối trực tiếp vào Expo Web (`http://localhost:8081`), thực hiện đăng nhập thực tế, điều hướng màn hình, nhập liệu form, mở modal AI, tương tác các nút bấm và kiểm tra hiển thị biên nhận visual:

| Kịch bản UI | Hành vi tương tác & Kiểm chứng | Bằng chứng trực quan & DB | Trạng thái |
|---|---|---|:---:|
| **UI-01. Student SELF_DIRECTED Reschedule** | Đăng nhập học viên tự tập -> Mở chi tiết buổi tập tương lai -> Bấm "Đề xuất dời lịch tập (AI)" -> Chọn khung giờ sáng (`time-pref-morning`) -> Nhập lý do -> Bấm "Tạo đề xuất dời lịch (AI)" -> Xem review thẻ đề xuất kèm lý giải AI -> Bấm "Chấp nhận & Áp dụng". | [ui_01_student_self_directed_receipt.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_01_student_self_directed_receipt.png)<br>Biên nhận `reschedule-applied-receipt` hiển thị mã buổi mới.<br>DB: Predecessor `RESCHEDULED`, Successor `SCHEDULED`. | **PASS** |
| **UI-02. Student HUMAN_COACH Authority Guard** | Đăng nhập học viên có HLV -> Mở chi tiết buổi tập -> Mở modal dời lịch -> Bấm tạo đề xuất. | [ui_02_student_human_coach_blocked.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_02_student_human_coach_blocked.png)<br>Hộp thoại cảnh báo quyền HLV hiển thị rõ ràng; nút "Chấp nhận & Áp dụng" bị ẩn/khóa hoàn toàn (`acceptButtonCount = 0`). | **PASS** |
| **UI-03. Trainer HUMAN_COACH Apply** | Đăng nhập HLV (`smoke.trainer`) -> Mở lịch tập học viên liên kết có quyền `MANAGE` -> Bấm nút "Đề xuất dời lịch (AI)" tại thẻ buổi tập -> Bấm tạo đề xuất -> Bấm "Chấp nhận & Áp dụng". | [ui_03_trainer_apply_success.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_03_trainer_apply_success.png)<br>Nút Accept được kích hoạt cho HLV có quyền `MANAGE`; biên nhận hiển thị mã buổi tập kế thừa mới; DB tạo successor. | **PASS** |
| **UI-04. Reject Proposal** | Đăng nhập học viên -> Tạo đề xuất dời lịch -> Bấm "Từ chối đề xuất này" -> Nhập lý do từ chối -> Bấm "Xác nhận từ chối". | [ui_04_reject_preserved.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_04_reject_preserved.png)<br>Thẻ biên nhận từ chối `reschedule-rejected-receipt` hiển thị thông báo buổi tập gốc giữ nguyên; DB xác nhận buổi tập gốc vẫn `SCHEDULED` (version không đổi). | **PASS** |
| **UI-05. Stale Source / Version Conflict (409)** | Đăng nhập học viên -> Tạo đề xuất dời lịch -> Kích hoạt giả lập trôi version trong DB (`UPDATE version = version + 1`) -> Bấm "Chấp nhận & Áp dụng". | [ui_05_conflict_toast.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_05_conflict_toast.png)<br>Thẻ lỗi `reschedule-error-state` hiển thị thông báo xung đột trạng thái rõ ràng và cung cấp nút "Tạo đề xuất mới" (`regenerate-reschedule-button`). | **PASS** |
| **UI-06. Two-Sided Appointment Bilateral Workflow & F05 Coordination** | 1. Học viên gửi yêu cầu dời lịch hẹn sang buổi chiều (14:00) qua Schedule API.<br>2. HLV chấp nhận yêu cầu dời lịch hẹn (lịch hẹn chuyển sang trạng thái `CONFIRMED`, version 1).<br>3. HLV đăng nhập Expo Web, mở chi tiết lịch hẹn đã cập nhật, bấm "Mở lịch tập học viên liên kết" để điều hướng sang lịch tập học viên.<br>4. HLV mở modal AI Reschedule trên thẻ buổi tập, sinh đề xuất F05 tự động căn chỉnh với lịch hẹn buổi chiều, bấm "Chấp nhận & Áp dụng". | [ui_06_appointment_two_sided_flow.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_06_appointment_two_sided_flow.png)<br>Biên nhận `reschedule-applied-receipt` hiển thị mã buổi tập mới.<br>DB Readback: Buổi tập cũ `RESCHEDULED`, successor `SCHEDULED` đúng giờ 14:00, lịch hẹn trong `fitness.appointments` được tự động liên kết sang buổi tập mới (`planned_workout_id = successor_id`) và version lịch hẹn tăng lên `2`. | **PASS** |
| **UI-07. Real UI Retry After Network Response Drop** | 1. Học viên tự tập tạo đề xuất dời lịch.<br>2. Bấm nút "Chấp nhận & Áp dụng" lần 1: Browser gửi request tới Backend (Backend xử lý thành công HTTP 200 và commit tạo successor), nhưng mạng bị rớt khiến client không nhận được phản hồi.<br>3. UI bắt lỗi mạng, hiển thị thông báo lỗi `Chưa thể hoàn tất thao tác - Mất kết nối mạng` và cập nhật nhãn nút thành `Thử lại cùng lệnh (Xác nhận & Áp dụng)`.<br>4. Người dùng bấm trực tiếp vào nút Thử lại trên giao diện UI: Client gửi lại chính xác cùng `commandKey` ban đầu.<br>5. Backend phát hiện lệnh trùng lặp, trả về biên nhận cache an toàn (`replayed: true`). | [ui_07_ui_retry_response_drop.png](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/artifacts/f05-runtime-evidence/screenshots/ui_07_ui_retry_response_drop.png)<br>Biên nhận hiển thị ghi chú `(Thao tác đã được ghi nhận trước đó và phát lại an toàn)`.<br>DB Assert: Số lượng successor trong database chính xác bằng **1** (chống nhân đôi tuyệt đối). | **PASS** |

---

## 4. Phân Định Minh Bạch Mức Độ Kiểm Chứng (Verification Level Breakdown)

| Tầng kiểm chứng | Phạm vi thực hiện | Kết quả | Ghi chú & Giới hạn |
|---|---|:---:|---|
| **Mobile Component Tests** (`@testing-library/react-native`) | 5 test suites (48 tests): modal F05, phân quyền `MANAGE` vs `CONTRIBUTE`, chặn Student trong `HUMAN_COACH`, đếm ngược hết hạn, từ chối đề xuất, điều hướng lịch hẹn liên kết, chống phản hồi trễ (stale async guard). | **PASS (48/48)** | Chạy trên Jest mô phỏng React Native component tree. Toàn bộ 96 suites (831 tests) của mobile đều đạt. |
| **Live API & Domain Integration** (`verify_f05_mobile_live_integration.py`) | 8 scenarios: gọi HTTP REST API theo đúng contract `aiWorkoutRescheduleApi.ts`, xuyên qua Spring Boot 8080, FastAPI 8000 và PostgreSQL 5433. | **PASS (8/8)** | Xác thực 100% contract, serialization, tính nguyên tử transaction, thẩm quyền role/mode và readback database. |
| **Expo Web Interactive UI Verification** (`verify_f05_mobile_ui_interactive.py`) | 7 scenarios tương tác người dùng qua Google Chrome / Playwright trên Expo Web (`http://localhost:8081`): đăng nhập, điều hướng SPA, nhập lý do, chọn slot heuristic, review AI reasoning, phân quyền bấm nút, bắt lỗi 409 xung đột, điều hướng lịch hẹn, replay idempotent. | **PASS (7/7)** | Sinh 7 file ảnh screenshot visual làm bằng chứng nghiệm thu giao diện người dùng. |
| **Native Device Runtime (Physical Device / Android Virtual Device)** | Chạy file APK/AAB trên thiết bị Android/iOS vật lý hoặc máy ảo AVD với bàn phím cảm ứng và Keystore phần cứng. | **CHƯA KIỂM CHỨNG (OPEN)** | Môi trường agent là Windows CLI headless không có display server để mở Android Virtual Device (AVD). Được giữ mở cho vòng kiểm thử manual QA/release. |
| **Real LLM Model Validation** | Đánh giá chất lượng sinh đề xuất với model Gemini/OpenAI production thật. | **OPEN (Non-goal Phase 2)** | Tiếp tục dùng simulated provider theo đúng quy định phân tách Phase 2. |

---

## 5. Làm Rõ Các Điểm Kỹ Thuật Quan Trọng

1. **Phân biệt T10 / BL-03 vs Kịch bản 6:**
   - Trong kiểm chứng Live API (`verify_f05_mobile_live_integration.py` Scenario 6): Đây là kiểm chứng lỗi bảo vệ trôi phiên bản phía Backend (HTTP 409 Conflict khi `expectedAppointmentVersion` bị lệch).
   - Trong kiểm chứng UI (`verify_f05_mobile_ui_interactive.py` Scenario 6) và component tests (`appointmentScreens.test.tsx`): Đây là kiểm chứng quy trình phối hợp 2 phía giữa Lịch hẹn và Lịch tập. Khi xem lịch hẹn có gắn buổi tập liên kết, màn hình hiển thị hướng dẫn và nút điều hướng theo vai trò ("Mở lịch tập học viên liên kết" cho HLV, "Mở buổi tập liên kết" cho Học viên) để chuyển mượt mà sang lịch tập mà không làm mất ngữ cảnh.

2. **Thẩm quyền HLV trong T03 / T04:**
   - Trên UI (`TrainerWorkoutScheduleScreen.tsx`), nút "Đề xuất dời lịch (AI)" được bảo vệ chặt chẽ bởi quyền `MANAGE` đối với phạm vi chia sẻ `WORKOUT_PLAN` (phân biệt với `canContribute`).
   - Nếu HLV bị mất quyền giữa chừng (mid-request), Backend trả về HTTP 403 `AI_FORBIDDEN` / `AI_RUN_LIFECYCLE_CONFLICT`, và Mobile UI hiển thị thông báo lỗi rõ ràng.

3. **Cơ chế TTL Hết hạn T23:**
   - Backend quản lý hạn dùng nghiêm ngặt 30 phút (`proposal.expiresAt`).
   - Mobile UI tính toán độc lập số phút còn lại để hiển thị cho người dùng và tự động vô hiệu hóa nút "Chấp nhận & Áp dụng" khi quá hạn, chuyển sang hiển thị nút "Tạo đề xuất mới".
   - Đặc biệt, nếu trước đó gặp lỗi mạng và đang giữ `pendingAcceptCommandKey`, UI vẫn cho phép bấm nút "Thử lại cùng lệnh (Xác nhận & Áp dụng)" kể cả khi client clock đã quá hạn, vì Backend xử lý kiểm tra Idempotent Replay trước khi kiểm tra Expiry.

4. **Bảo vệ phản hồi trễ (Stale Async Guard):**
   - Sử dụng các React ref (`activeStudentIdRef`, `activeOccurrenceIdRef`, `isMountedRef`) trong `AiWorkoutRescheduleModal.tsx` để đảm bảo nếu người dùng chuyển đổi buổi tập hoặc đóng modal trước khi API trả lời, dữ liệu cũ không ghi đè lên state hiện tại.

5. **Trạng thái kết nối GitHub Connector:**
   - Lệnh kết nối GitHub qua MCP server ghi nhận mã phản hồi thô: `401 Bad credentials` / `401 Unauthorized`.
   - File mô tả PR được lưu trữ trực tiếp và đầy đủ tại [`docs/08-ai/f05-pr168-description.md`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/docs/08-ai/f05-pr168-description.md) làm tài liệu nguồn chính thức.
