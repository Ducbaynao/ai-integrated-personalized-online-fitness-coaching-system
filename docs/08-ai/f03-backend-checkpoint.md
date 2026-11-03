# F03 Backend Checkpoint - 2026-11-03

## 1. Trạng Thái Pull Request & Merge CI
- **Pull Request Backend F03:** PR #135 (`feat/p2-m4-f03-contract-package-20261101`).
- **Trạng thái:** **MERGED** vào `main` tại commit `eb6f6aacfaca58c3ba12109f125585f148c719b6`.
- **CI hậu merge PR #135:** Run 41530055353, **SUCCESS**.
- **Baseline main xác minh:** `8430f4ae5eb9e7cd5f3ac7fab72f77ba1cccc627` (CI run 41643163902, 6/6 jobs SUCCESS).

## 2. Việc Đã Hoàn Thành Ở Backend Slice
- **Khôi phục môi trường hoàn chỉnh:**
  - Khởi động Docker Desktop và PostgreSQL container `digital-fitness-postgres` (:5433).
  - Khởi động FastAPI AI Service (:8000) với router F03 và internal key.
  - Khởi động Spring Boot Backend (:8080) với profile `dev` và `AI_SERVICE_MODE=http`.
- **Xác thực môi trường cục bộ:**
  - Sử dụng mật khẩu theo cấu hình test/dev cục bộ của dự án cho các tài khoản seed (`student.f02.runtime@example.com`, `smoke.trainer@example.com`, `smoke.student@example.com`); tuyệt đối không lưu hoặc phơi bày secrets dạng rõ.
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
- **Toàn bộ Unit & Contract Tests:**
  - Unit Tests: 30/30 PASS (11 service, 7 contract, 4 context builder, 8 validator).
  - Schema fixtures: 32/32 PASS.
  - Python tests: 67/67 PASS.
- **Live API End-to-End đã đo lường (Backend :8080 -> FastAPI :8000 -> PostgreSQL :5433):**
  - Case A (SELF_DIRECTED occurrence accept & replay): PASS.
  - Case B (HUMAN_COACH trainer accept, student 403 forbidden): PASS.
  - Case C (Template scope accept, publish new plan version): PASS.
  - Case D (Reject proposal, zero domain mutation): PASS.
  - Case E (Target version conflict, 409 conflict, rollback to PENDING): PASS.
- **Báo Cáo Nghiệm Thu Backend F03:**
  - File: `docs/08-ai/f03-backend-acceptance-report-20261101.md`.

## 3. Ghi Chú Về Script Kiểm Thử Cục Bộ
- Trong phiên kiểm chứng Backend ngày 2026-11-03, một script scratch tạm thời (`scratch/run_live_f03_tests.py`) đã được dùng để đo lường 5/5 ca live API. Script này là công cụ tạm thời, không nằm trong git tracking và không có trong working tree hiện tại; do đó không khẳng định việc tái chạy script này trong phiên làm việc Mobile.

## 4. Các Gate Còn Mở (Open Gates)
- **Hội đồng chuyên môn D06 / OD-3:** Các ngưỡng phần trăm tăng tải tối đa, số tuần tích lũy RPE yêu cầu tiếp tục chờ hội đồng D06 phê duyệt chính thức theo kế hoạch chung Phase 2.
- **Chất lượng LLM thực tế:** Toàn bộ kiểm thử kỹ thuật và live verification sử dụng Simulated Provider ($0.00 chi phí); chưa đo lường chất lượng sinh nội dung của LLM thực tế với live tokens.
- **Nghiệm thu Milestone P2-M4:** Backend Slice đã merged, nhưng toàn bộ milestone P2-M4 cần hoàn thành Mobile Slice và được Project Owner / Tech Lead ký duyệt.

## 5. Bước Kế Tiếp
- Triển khai và kiểm chứng Mobile Client F03 trên branch `feat/p2-m4-f03-mobile`:
  - API Client & Error Mapping (`apps/mobile/src/services/aiWorkoutAdjustmentApi.ts`).
  - Types (`apps/mobile/src/types/aiWorkoutAdjustment.ts`).
  - Giao diện đề xuất & Before/After (`AiWorkoutAdjustmentModal.tsx`).
  - Tích hợp vào màn hình workout occurrence và workout plan detail.
  - Bộ kiểm thử tự động Mobile F03 đạt 100% PASS, lint và typecheck đạt 0 lỗi.
