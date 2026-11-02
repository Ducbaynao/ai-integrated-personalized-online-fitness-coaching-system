# F03 Backend Checkpoint - 2026-11-03

## 1. Việc Đã Xong
- **Khôi phục môi trường hoàn chỉnh:**
  - Khởi động Docker Desktop và PostgreSQL container `digital-fitness-postgres` (:5433).
  - Khởi động FastAPI AI Service (:8000) với router F03 và internal key.
  - Khởi động Spring Boot Backend (:8080) với profile `dev` và `AI_SERVICE_MODE=http`.
- **Giải quyết Auth hợp lệ:**
  - Sử dụng mật khẩu chuẩn `StrongPassword123!` cho các tài khoản seed local (`student.f02.runtime@example.com`, `smoke.trainer@example.com`, `smoke.student@example.com`).
  - Không sửa đổi mã nguồn xác thực, không bypass auth.
- **Khép các khoảng trống Integration Tests:**
  - Bổ sung 6 tests nâng cao vào `AiWorkoutAdjustmentFullSliceIntegrationTest.java`:
    1. Concurrent Double Accept race (CountDownLatch, 1 thắng APPLIED, 1 thua 409 Conflict).
    2. Concurrent Accept vs Reject race (1 thắng, 1 thua sạch sẽ, zero dirty state).
    3. Hai recommendation độc lập cùng target occurrence version (stale version guard).
    4. Buổi tập có status `ATTEMPTED` bị từ chối (execution-started/in-progress guard).
    5. Exercise variation bị deactivated trước khi accept (revalidation guard, `WORKOUT_PLAN_EXERCISE_UNAVAILABLE`).
    6. Replay với differing `expectedTargetVersion` bị ném 409 conflict.
  - Sửa lỗi Jackson 3 coercion: thay `decimalValue()` bằng `new BigDecimal(node.asText())`.
  - Sửa logic idempotency: không coi `null` là wildcard cho `expectedTargetVersion` và `expectedRevision`.
  - Sửa simulated variation ID để vượt qua OD-3 equivalence check.
  - **Kết quả Full Slice:** 15/15 tests PASS.
- **Chạy toàn bộ Unit & Contract Tests:**
  - Unit Tests: 30/30 PASS (11 service, 7 contract, 4 context builder, 8 validator).
  - Schema fixtures: 32/32 PASS.
  - Python tests: 67/67 PASS.
- **Chạy Live API End-to-End (Backend :8080 -> FastAPI :8000 -> PostgreSQL :5433):**
  - Case A (SELF_DIRECTED occurrence accept & replay): PASS.
  - Case B (HUMAN_COACH trainer accept, student 403 forbidden): PASS.
  - Case C (Template scope accept, publish new plan version): PASS.
  - Case D (Reject proposal, zero domain mutation): PASS.
  - Case E (Target version conflict, 409 conflict, rollback to PENDING): PASS.
- **Tạo Báo Cáo Nghiệm Thu Backend F03:**
  - File: `docs/08-ai/f03-backend-acceptance-report-20261101.md`.

## 2. Lệnh Cuối Cùng & Kết Quả
- Lệnh: `python scratch/run_live_f03_tests.py`
- Kết quả: `=== SUMMARY OF LIVE F03 TESTS ===` 5/5 PASS (Case A, B, C, D, E).

## 3. Tiến Trình & Log Đang Dùng
- Các tiến trình nền live test (task-2553 FastAPI và task-2628 Backend) đã được dừng an toàn sau khi hoàn thành đo lường.
- Không chứa secret hay raw password trong log.

## 4. Blocker
- Không có blocker kỹ thuật cho Backend F03.
- Các ngưỡng định lượng nâng cao tiếp tục chờ hội đồng D06 (theo kế hoạch chung Phase 2).

## 5. Bước Kế Tiếp Cụ Thể
- Người dùng review working tree và hồ sơ nghiệm thu Backend F03.
- Tạo draft PR Backend F03 khi người dùng ủy quyền.
- Tiếp tục chuyển sang thiết kế và triển khai Mobile Client F03 (P2-M4 Mobile Slice).
