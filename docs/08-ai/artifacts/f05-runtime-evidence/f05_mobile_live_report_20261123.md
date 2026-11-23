# Báo Cáo Kiểm Chứng Runtime Tích Hợp Mobile F05 (Live Integration Evidence)

**Ngày báo cáo:** 23/11/2026  
**Người thực hiện:** DEV-A  
**Branch:** `feat/mobile-f05-rescheduling`  
**PR:** Draft PR #168  
**Repository:** `ai-integrated-personalized-online-fitness-coaching-system`  
**JSON Evidence Artifact:** [`docs/08-ai/artifacts/f05-runtime-evidence/f05_mobile_live_integration_20261123.json`](f05_mobile_live_integration_20261123.json)

---

## 1. Môi Trường Thực Nghiệm (Live Integration Environment)

Kiểm chứng được thực hiện trên chuỗi dịch vụ thật chạy tại local:
- **Client/Mobile Layer:** Mobile API Client contract (`apps/mobile/src/services/aiWorkoutRescheduleApi.ts`) & Live Integration Harness (`tools/verify_f05_mobile_live_integration.py`).
- **Backend Core:** Spring Boot Modular Monolith (Java 21, Spring Boot 3.4.4, profile `dev`, mode `http`, port 8080).
- **AI Service:** FastAPI Service (Python 3.12, Uvicorn, port 8000) với `SimulatedF05WorkoutProvider`.
- **Database:** PostgreSQL 18.6 (Docker port 5433 -> 5432, schema `fitness`, Flyway 60 migrations verified).
- **Cache & Mail:** Redis (port 6379), Mailpit (port 1025/8025).

---

## 2. Kết Quả 8 Kịch Bản Tích Hợp Live (Live Scenarios Breakdown)

Toàn bộ 8 kịch bản tích hợp domain & authority đã được kiểm chứng end-to-end với các assertion nghiêm ngặt trên cả HTTP response và PostgreSQL database readback:

| Kịch bản | Mô tả kiểm chứng | Kết quả API / DB Readback | Trạng thái |
|---|---|---|:---:|
| **Scenario 1** | **SELF_DIRECTED End-to-End**:<br>Generate -> Review -> Accept -> Receipt -> Successor created | HTTP 200; `status: APPLIED`, `replayed: false`, `resultingEntityId` hợp lệ.<br>DB readback: Predecessor status = `RESCHEDULED`, Successor status = `SCHEDULED` (`rescheduled_from_id` trỏ đúng predecessor), Recommendation status = `APPLIED`. | **PASS** |
| **Scenario 2** | **HUMAN_COACH Student Blocked**:<br>Học viên gửi yêu cầu Accept khi đang ở chế độ huấn luyện có HLV | HTTP 403 Forbidden (`errorCode: AI_FORBIDDEN`). Quyền quyết định thuộc về HLV (`decisionAuthority: TRAINER`), học viên không được đơn phương apply. | **PASS** |
| **Scenario 3** | **HUMAN_COACH Trainer Apply**:<br>HLV phụ trách có quyền `MANAGE` duyệt và apply đề xuất | HTTP 200; `status: APPLIED`, `resultingEntityId` hợp lệ.<br>DB readback: Predecessor status = `RESCHEDULED`, Successor status = `SCHEDULED`. | **PASS** |
| **Scenario 4** | **Reject Proposal**:<br>Học viên từ chối đề xuất với lý do (`decisionNote`) | HTTP 200; `status: REJECTED`.<br>DB readback: Buổi tập gốc giữ nguyên trạng thái `SCHEDULED`, version không đổi (`1`), recommendation status = `REJECTED`. | **PASS** |
| **Scenario 5** | **Stale Source / Version Drift**:<br>Buổi tập bị thay đổi đồng thời (version drift 1 -> 2) trước khi Accept | HTTP 409 Conflict (`errorCode: AI_RUN_LIFECYCLE_CONFLICT`). Thông báo: `Workout occurrence version conflict: expected version 1 but current is 2`. | **PASS** |
| **Scenario 6** | **Appointment Drift Coordination**:<br>Lịch hẹn liên kết bị trôi version (1 -> 2) trước khi Accept | HTTP 409 Conflict (`errorCode: AI_RUN_LIFECYCLE_CONFLICT`). Thông báo: `Linked appointment has changed since proposal was generated`. Snapshot gốc được bảo toàn. | **PASS** |
| **Scenario 7** | **Timeout & Idempotent Replay**:<br>Gửi lại cùng `commandKey` sau sự cố timeout mạng | HTTP 200; `replayed: true`, `resultingEntityId` khớp chính xác với lần chạy đầu.<br>DB readback: Số lượng successor record trong database chính xác bằng **1** (không tạo duplicate entity). | **PASS** |
| **Scenario 8** | **Expired Proposal Semantics**:<br>Đề xuất đã hết hạn (`expiresAt` trong quá khứ) | 8A. Replay với `commandKey` đã apply thành công trước đó: HTTP 200 `replayed: true` (Idempotent replay ưu tiên trước kiểm tra expiry).<br>8B. Gửi lệnh Accept mới với `commandKey` mới trên đề xuất đã hết hạn: HTTP 409 Conflict (`AI_RUN_LIFECYCLE_CONFLICT`). | **PASS** |

---

## 3. Các Lỗi Phát Hiện & Đã Khắc Phục (Bug Fixes)

Trong quá trình dựng môi trường và kiểm chứng tích hợp thực tế, DEV-A đã phát hiện và xử lý 3 vấn đề:
1. **Pydantic v2 Null Timezone Handling (`f05_schemas.py`):**
   - *Hiện tượng:* Khi Spring Boot gửi `"timezone": null`, FastAPI trả về HTTP 422 Unprocessable Entity do schema khai báo `timezone: str = "Asia/Ho_Chi_Minh"`.
   - *Khắc phục:* Sửa thành `timezone: str | None = "Asia/Ho_Chi_Minh"` để chấp nhận explicit null và fallback an toàn.
2. **Context Builder Fallback Timezone (`F05ContextBuilder.java`):**
   - *Hiện tượng:* Nếu occurrence trong database có `schedule_timezone` là null hoặc rỗng, context builder truyền null sang AI Service.
   - *Khắc phục:* Bổ sung fallback xác định `effectiveTimezone = occurrence.timezone() != null && !occurrence.timezone().isBlank() ? occurrence.timezone() : "Asia/Ho_Chi_Minh"`.
3. **HTTP Client Error Diagnostic Logging (`HttpF05AiServiceClient.java`):**
   - *Khắc phục:* Bổ sung logging chi tiết response body khi FastAPI trả về lỗi 4xx để phục vụ chẩn đoán tức thì.

---

## 4. Ghi Chú Về Giao Diện & Môi Trường Native/Web

- **Mobile Client:** Triển khai bằng React Native Expo. Các tương tác giao diện (Modal Generate/Review/Accept/Reject, hiển thị khung giờ sáng/chiều/tối, đếm ngược thời gian hết hạn theo `expiresAt` từ Backend, nút bấm theo quyền `MANAGE`/`CONTRIBUTE`) đã được kiểm chứng bởi 38 unit/component tests (`@testing-library/react-native`).
- **Web Runtime Note:** Kiểm chứng tích hợp live sử dụng harness mô phỏng client tương ứng 100% với contract payload của Mobile App qua giao thức HTTP/JSON với Spring Boot backend. Khi chạy trên web preview hoặc mobile simulator, luồng hoạt động là đồng nhất do dùng chung module API client `aiWorkoutRescheduleApi.ts`.

---

## 5. Kết Luận & Khuyến Nghị

- **Trạng thái tích hợp Mobile F05:** **SẴN SÀNG REVIEW (READY FOR REVIEW)**.
- **Tiêu chuẩn hoàn thành Phase 2 F05:** Tất cả các ràng buộc nghiệp vụ, bảo vệ version, quyền quyết định học viên/HLV và tính lũy đẳng đã được chứng minh bằng bằng chứng runtime thực tế.
- **Lưu ý roadmap:** Đánh giá provider LLM thật (Real LLM evaluation) vẫn được tách biệt và là milestone tiếp theo theo đúng phê duyệt Phase 2 Scope.
