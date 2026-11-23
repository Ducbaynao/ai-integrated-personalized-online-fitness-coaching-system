# Báo Cáo Kiểm Chứng Live API / Domain Integration F05 & Trạng Thái Mobile Runtime

**Ngày báo cáo:** 23/11/2026  
**Người thực hiện:** DEV-A  
**Branch:** `feat/mobile-f05-rescheduling`  
**PR:** Draft PR #168  
**Repository:** `ai-integrated-personalized-online-fitness-coaching-system`  
**JSON Evidence Artifact:** [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json`](f05_mobile_live_integration_20261123.json)

---

## 1. Môi Trường Thực Nghiệm (Live Integration Environment)

Kiểm chứng tích hợp dịch vụ thật được thực hiện trên chuỗi thành phần local:
- **Client/Contract Layer:** Mobile API Client contract (`apps/mobile/src/services/aiWorkoutRescheduleApi.ts`) & Live Integration Harness (`tools/verify_f05_mobile_live_integration.py`).
- **Backend Core:** Spring Boot Modular Monolith (Java 21, Spring Boot 3.4.4, profile `dev`, mode `http`, port 8080).
- **AI Service:** FastAPI Service (Python 3.12, Uvicorn, port 8000) với `SimulatedF05WorkoutProvider`.
- **Database:** PostgreSQL 18.6 (Docker port 5433 -> 5432, schema `fitness`, Flyway 60 migrations verified).
- **Cache & Mail:** Redis (port 6379), Mailpit (port 1025/8025).

> [!NOTE]
> Harness `tools/verify_f05_mobile_live_integration.py` thực thi kiểm chứng ở tầng **Live API & Domain Integration** (gửi HTTP request theo đúng contract của client, đi qua toàn bộ pipeline controller, domain services, transactions, HTTP call sang FastAPI AI Service, và persistence vào PostgreSQL với DB readback). Harness này **không** thay thế cho việc render và tương tác trên thiết bị di động thực tế (Mobile UI Runtime).

---

## 2. Kết Quả 8 Kịch Bản Tích Hợp Live API & Domain (Live Scenarios Breakdown)

Toàn bộ 8 kịch bản tích hợp domain & authority đã được kiểm chứng end-to-end với các assertion nghiêm ngặt trên cả HTTP response và PostgreSQL database readback:

| Kịch bản | Mô tả kiểm chứng | Kết quả API / DB Readback | Phân loại & Ghi chú | Trạng thái |
|---|---|---|---|:---:|
| **Scenario 1** | **SELF_DIRECTED End-to-End**:<br>Generate -> Review -> Accept -> Receipt -> Successor created | HTTP 200; `status: APPLIED`, `replayed: false`, `resultingEntityId` hợp lệ.<br>DB readback: Predecessor status = `RESCHEDULED`, Successor status = `SCHEDULED` (`rescheduled_from_id` trỏ đúng predecessor), Recommendation status = `APPLIED`. | Standard Flow | **PASS** |
| **Scenario 2** | **HUMAN_COACH Student Blocked**:<br>Học viên gửi yêu cầu Accept khi đang ở chế độ huấn luyện có HLV | HTTP 403 Forbidden (`errorCode: AI_FORBIDDEN`). Quyền quyết định thuộc về HLV (`decisionAuthority: TRAINER`), học viên không được đơn phương apply. | Standard Flow | **PASS** |
| **Scenario 3** | **HUMAN_COACH Trainer Apply**:<br>HLV phụ trách có quyền `MANAGE` duyệt và apply đề xuất | HTTP 200; `status: APPLIED`, `resultingEntityId` hợp lệ.<br>DB readback: Predecessor status = `RESCHEDULED`, Successor status = `SCHEDULED`. | Standard Flow | **PASS** |
| **Scenario 4** | **Reject Proposal**:<br>Học viên từ chối đề xuất với lý do (`decisionNote`) | HTTP 200; `status: REJECTED`.<br>DB readback: Buổi tập gốc giữ nguyên trạng thái `SCHEDULED`, version không đổi (`1`), recommendation status = `REJECTED`. | Standard Flow | **PASS** |
| **Scenario 5** | **Stale Source / Version Conflict**:<br>Buổi tập bị thay đổi đồng thời (version drift 1 -> 2) trước khi Accept | HTTP 409 Conflict (`errorCode: AI_RUN_LIFECYCLE_CONFLICT`). | **Controlled Fault Injection** (giả lập trôi version trên fixture `occ_id_4`) | **PASS** |
| **Scenario 6** | **Appointment Drift Coordination**:<br>Lịch hẹn liên kết bị trôi version (1 -> 2) trước khi Accept | HTTP 409 Conflict (`errorCode: AI_RUN_LIFECYCLE_CONFLICT`). Snapshot gốc được bảo toàn. | **Controlled Fault Injection** (giả lập trôi version trên fixture `app_id_5`). *Lưu ý: Kịch bản chứng minh cơ chế bảo vệ trôi version của Backend; không thay thế quy trình thương lượng dời lịch 2 phía trên UI.* | **PASS** |
| **Scenario 7** | **Timeout & Idempotent Replay**:<br>Gửi lại cùng `commandKey` sau sự cố timeout mạng | HTTP 200; `replayed: true`, `resultingEntityId` khớp chính xác với lần chạy đầu.<br>DB readback: Số lượng successor record trong database chính xác bằng **1** (không tạo duplicate entity). | Standard Flow | **PASS** |
| **Scenario 8** | **Expired Proposal Semantics**:<br>Đề xuất đã hết hạn (`expiresAt` trong quá khứ) | 8A. Replay với `commandKey` đã apply thành công trước đó: HTTP 200 `replayed: true` (Idempotent replay ưu tiên trước kiểm tra expiry).<br>8B. Gửi lệnh Accept mới với `commandKey` mới trên đề xuất đã hết hạn: HTTP 409 Conflict (`AI_RUN_LIFECYCLE_CONFLICT`). | **Controlled Fault Injection** (giả lập hết hạn TTL trên fixture `rec_id_1` và `rec_id_6`) | **PASS** |

---

## 3. Các Điểm Hoàn Thiện & Sửa Đổi Runtime (Runtime Fixes & Hygiene)

Trong quá trình chuẩn hóa theo yêu cầu rà soát, DEV-A đã thực hiện các cải tiến mã nguồn và quy trình kiểm thử:

1. **Bảo toàn dữ liệu nguồn Timezone (`F05ContextBuilder.java`):**
   - *Trước đây:* Nếu `occurrence.timezone()` là null hoặc rỗng, code âm thầm fallback về `"Asia/Ho_Chi_Minh"`. Điều này vô tình che giấu dữ liệu legacy không hợp lệ (vốn không thể dời lịch được theo domain rules của `WorkoutScheduleChangeService`).
   - *Khắc phục:* Nếu `occurrence.timezone()` null hoặc blank, ném ngay `AiRunLifecycleConflictException("Planned workout occurrence is missing required schedule timezone and cannot be rescheduled.")` để phát hiện sớm và bảo toàn provenance. Đã bổ sung unit test `buildContext_missingTimezone_throwsAiRunLifecycleConflictException`.

2. **Khử rò rỉ dữ liệu nhạy cảm trong Logging (`HttpF05AiServiceClient.java`):**
   - *Trước đây:* Khi FastAPI trả về mã lỗi 4xx, HTTP client đọc và ghi toàn bộ response body ra log và exception. Điều này có nguy cơ ghi lại thông tin định danh hoặc dữ liệu cá nhân phản hồi từ model/service.
   - *Khắc phục:* Rút gọn logging và exception chỉ ghi nhận mã trạng thái HTTP (`4xx client error: {code}`), loại bỏ hoàn toàn việc in thô response body.

3. **Cách ly Fixture & Teardown an toàn (`verify_f05_mobile_live_integration.py`):**
   - *Trước đây:* Script thực hiện câu lệnh blanket `UPDATE planned_workouts SET status = 'CANCELLED' ... WHERE rescheduled_from_id IS NOT NULL` và `DELETE FROM appointments WHERE student_id = ...`, gây ảnh hưởng tới dữ liệu test dùng chung của tài khoản smoke.
   - *Khắc phục:*
     - Loại bỏ hoàn toàn các câu lệnh xóa/hủy diện rộng.
     - Hàm `get_clean_sessions()` truy vấn các session chưa từng có workout nào để loại bỏ triệt để nguy cơ đụng độ khung giờ (`WORKOUT_SCHEDULE_DUPLICATE`).
     - Tọa độ thời gian của lịch hẹn test (`app_id_5`) được tính toán động trước khung giờ sớm nhất của HLV, đảm bảo không bao giờ vi phạm exclusion constraint `trainer_appointment_no_overlap`.
     - Toàn bộ kịch bản được bọc trong khối `try ... finally:` với hàm `cleanup_test_fixtures()` chỉ thao tác chính xác trên các UUID do chính phiên test tạo ra (`created_occ_ids`, `created_app_ids`, `created_rec_ids`, `created_successor_ids`).

---

## 4. Phân Định Minh Bạch Mức Độ Kiểm Chứng Giao Diện Mobile (Mobile Verification Status)

Để đảm bảo tính trung thực kỹ thuật và không tự nhận vượt quá phạm vi bằng chứng thực tế:

| Tầng kiểm chứng | Phạm vi thực hiện | Kết quả | Ghi chú & Giới hạn |
|---|---|:---:|---|
| **Mobile Component Tests** (`@testing-library/react-native`) | 5 test suites (48 tests): modal F05, phân quyền `MANAGE` vs `CONTRIBUTE`, chặn Student trong `HUMAN_COACH`, đếm ngược hết hạn, từ chối đề xuất, điều hướng lịch hẹn liên kết, chống phản hồi trễ (stale async guard). | **PASS (48/48)** | Chạy trên môi trường Jest mô phỏng React Native tree. Đảm bảo toàn bộ logic render, layout và state transitions của UI hoạt động đúng. |
| **Live API & Domain Integration** (`verify_f05_mobile_live_integration.py`) | 8 scenarios: gọi HTTP REST API theo đúng contract `aiWorkoutRescheduleApi.ts`, xuyên qua Spring Boot 8080, FastAPI 8000 và PostgreSQL 5433. | **PASS (8/8)** | Xác thực 100% contract, serialization, tính nguyên tử transaction, thẩm quyền role/mode và readback database. Không bao gồm thao tác click vật lý trên màn hình. |
| **Expo Web Bundler Runtime** (`npx expo start --web`) | Khởi chạy Metro Bundler trên cổng 8081, biên dịch thành công 1282 modules (`node_modules/expo-router/entry.js`), kiểm tra HTTP 200 OK và HTML skeleton. | **PASS (Serving)** | Xác nhận ứng dụng đóng gói và chạy được trên nền Web Expo. Khác biệt với native: token lưu in-memory thay vì hardware Keystore/Keychain, thao tác chuột thay cho cảm ứng. |
| **Mobile UI Live E2E (Interactive Native / Browser Automation)** | Thao tác tương tác trực tiếp nhiều bước từ màn hình Đăng nhập -> Lịch tập -> Mở Modal -> Bấm Accept trên thiết bị thật / emulator hoặc qua công cụ tự động hóa browser (Playwright/Puppeteer). | **NOT RUN / BLOCKED** | **Lý do:** Môi trường agent là Windows CLI headless không có màn hình đồ họa hoạt động để mở Android Emulator (AVD), và repository không cấu hình sẵn thư viện tự động hóa browser (Playwright/Puppeteer). Không tự nhận đạt E2E UI khi chưa có runner tự động. |

---

## 5. Kết Luận & Khuyến Nghị

- **Live API & Domain Integration:** **ĐẠT (PASS 8/8)** với bằng chứng thực nghiệm đầy đủ trên hệ thống dịch vụ thật.
- **Mobile Component Test & Logic UI:** **ĐẠT (PASS 48/48)** trên toàn bộ 5 test suites.
- **Khuyến nghị:** Bộ thay đổi đã sẵn sàng phục vụ review tại Draft PR #168. Thử nghiệm trên thiết bị Android/iOS vật lý có thể thực hiện bởi QA/Tester trong chu trình nghiệm thu giao diện người dùng.
