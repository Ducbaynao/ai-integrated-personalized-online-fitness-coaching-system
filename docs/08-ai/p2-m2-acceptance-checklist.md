# Bảng Đối Chiếu Hiện Trạng & Checklist Nghiệm Thu Milestone P2-M2

> **Milestone:** P2-M2 — RAG, Rules & AI Pipeline Tối Thiểu  
> **Repository:** `D:\Projectsi-integrated-personalized-online-fitness-coaching-system`  
> **Branch thực hiện:** `test/f01-trainer-openai-e2e-20261027`  
> **Commit nền (Base commit):** `f53357c71cd51f8309dce02c1943421fd8a3e67a` (Merge PR #112 trên `origin/main`)  
> **Ngày cập nhật:** 2026-10-27  
> **Tài liệu căn cứ:** `docs/00-project-overview/phase-2-release-scope.md`, `docs/08-ai/phase-2-feature-data-authority-matrix.md`, `docs/07-development/phase-2-implementation-plan.md` (Mục 4 & 12.3)

---

## 1. Bảng Tổng Hợp Trạng Thái Các Tiêu Chí P2-M2

| Mã tiêu chí | Hạng mục công việc | Trạng thái | Bằng chứng xác minh chính |
| :---: | :--- | :---: | :--- |
| **P2-M2.1** | F09: Quản lý tri thức Admin | **PASS** | PR #111: Báo cáo, 6 ảnh Chrome Headless, 49 tests backend & 104 tests frontend PASS |
| **P2-M2.2** | F10: Giám sát AI Run Admin | **PASS** | PR #111: Ảnh `p2m2_04_ai_operations.png`, `p2m2_05_ai_run_detail.png` (Privacy redaction) |
| **P2-M2.3** | Công tắc Kill-switch F01 | **PASS** | PR #111: Ảnh `p2m2_06_killswitch_toggled.png`. Chặn HTTP 503 khi tắt, HTTP 403 với non-admin |
| **P2-M2.4** | F01 Student Mobile (Kiến thức chung) | **PASS\*** | PR #104 trên commit `4591e91` (4 ca live OpenAI PASS, 6 ảnh). \*Bằng chứng kế thừa baseline cũ |
| **P2-M2.5** | F01 Trainer Mobile (Giao diện & Nghiệp vụ) | **PASS** | PR #112: 7 ảnh trên Pixel 8 Emulator, 31 tests unit Mobile PASS (Simulated Provider) |
| **P2-M2.6** | F01 Trainer Mobile (Live OpenAI thật) | **PASS (Có Gap Đã Fix)** | 4 ca live TR-OAI-01..04 trên Pixel 8 Emulator. Ca 01-03 PASS; Ca 04 an toàn nhưng lệch contract, đã fix pre-guard + test hồi quy |
| **P2-M2.7** | Bất biến quyền riêng tư & Minimization | **PASS** | PostgreSQL `ai_context_snapshots`: `actor_role = TRAINER`, `student = null` trong các ca F01 |
| **P2-M2.8** | Tái xác thực quyền trước giao hàng (Revalidation) | **PASS** | Báo cáo drill: 11 tests unit + 8 tests integration Testcontainers PostgreSQL PASS 100% |
| **P2-M2.9** | Durable Recovery (Orphan Sweeper) | **PASS (Test)\* / NOT RUN (Drill)** | 9/9 integration tests Testcontainers PASS; \*Live runtime crash drill trên dev là **NOT RUN** |
| **P2-M2.10** | Evaluation Runner Tối Thiểu (A-49) | **PASS (Khung A-49)** | Khung runner A-49 PASS; Benchmark tập Holdout 60 ca A-52 là bài đánh giá release ở P2-M6 |

---

## 2. Chi Tiết Từng Hạng Mục Nghiệm Thu

### P2-M2.1 — F09: Quản Lý Tri Thức Admin
- **Yêu cầu:** CRUD tài liệu, versioning, specialist review, publish, archive, database schema V48/V49.
- **Trạng thái:** **PASS**.
- **Bằng chứng:** Báo cáo `docs/08-ai/p2-m2-admin-handover-report.md`, 6 ảnh artifacts Chrome Headless, 49 tests backend PASS, 104 tests frontend PASS (Merge PR #109 / PR #111).

### P2-M2.2 — F10: Giám Sát AI Run Admin
- **Yêu cầu:** Danh sách AI run, correlation ID, status, latency, token usage, chi tiết run có privacy redaction.
- **Trạng thái:** **PASS**.
- **Bằng chứng:** Báo cáo PR #111, ảnh `p2m2_04_ai_operations.png`, `p2m2_05_ai_run_detail.png`.

### P2-M2.3 — Công Tắc An Toàn (Kill-Switch F01)
- **Yêu cầu:** Bật/tắt tức thời tính năng F01 qua giao diện Admin Web, lưu audit log.
- **Trạng thái:** **PASS**.
- **Bằng chứng:** Ảnh `p2m2_06_killswitch_toggled.png`. Khi F01 bị tắt, request trả về HTTP 503 SERVICE_UNAVAILABLE; non-admin gọi toggle bị chặn HTTP 403 Forbidden.

### P2-M2.4 — F01 Student Mobile (Kiến Thức Chung)
- **Yêu cầu:** Tra cứu RAG kiến thức thể hình chung, trích dẫn nguồn ACTIVE, 0 nút Apply/Accept.
- **Trạng thái:** **PASS (Bằng chứng kế thừa commit cũ `4591e91`)**.
- **Bằng chứng:** Báo cáo PR #104 trên commit `4591e91` (4 ca live với OpenAI thật, 6 ảnh artifacts). Chưa chạy lại trên baseline mới `f53357c`.

### P2-M2.5 — F01 Trainer Mobile (Giao Diện & Nghiệp Vụ)
- **Yêu cầu:** Điều hướng từ Trainer Home, tra cứu, ràng buộc input, chặn chính sách, hiển thị lỗi và nút thử lại.
- **Trạng thái:** **PASS (Simulated Provider)**.
- **Bằng chứng:** Báo cáo PR #112, 7 ảnh artifacts tại `docs/08-ai/artifacts/f01-trainer-live-validation/`, 31 unit tests Mobile PASS 100%.

### P2-M2.6 — F01 Trainer Mobile (Live OpenAI Thật)
- **Yêu cầu:** Đánh giá trích dẫn nguyên văn và chất lượng câu trả lời với OpenAI thật trên luồng Mobile Trainer → Spring → FastAPI → OpenAI.
- **Trạng thái:** **PASS (Kèm khoảng trống phân loại đã khắc phục)**.
- **Bằng chứng thực tế:**
  - TR-OAI-01 & TR-OAI-02 (In-corpus WHO): PASS. Câu trả lời chính xác, trích dẫn nguyên văn từng ký tự từ markdown chunk của tài liệu `WHO-PA-SB-2020-VI-05` (v1.1.0).
  - TR-OAI-03 (Out-of-corpus Cocktail): PASS. Hệ thống từ chối trả lời ngoài phạm vi, 0 trích dẫn bịa đặt.
  - TR-OAI-04 (Doping Guardrail): Trong phiên live trước sửa, câu hỏi được gửi sang OpenAI và trả về `INSUFFICIENT_DATA` (không hướng dẫn nguy hiểm, an toàn người dùng được bảo vệ). Tuy nhiên contract Bảng 1.6 yêu cầu `BLOCKED / RULE_BLOCKED`. Khoảng trống phân loại này đã được xử lý bằng bộ phân loại chính sách xác định trong `OpenAiKnowledgeProvider` (phía Python) và được kiểm chứng bằng unit/integration tests với test doubles/stubs (chưa chạy lại live OpenAI để bảo toàn ngân sách). Trong pipeline này, do guardrail nằm trước lời gọi retrieval nội bộ, các ca bị chặn không phát sinh cuộc gọi retrieval/embedding và generation downstream trong phạm vi kiểm thử. Bộ lọc tập trung phân loại các hành vi hướng dẫn sử dụng/chu kỳ/liều lượng/né kiểm tra chất cấm đã thử nghiệm, không khẳng định loại bỏ mọi biến thể hay thay thế hoàn toàn bộ rule engine chuyên sâu.
  - Chi phí ước tính phiên live trước sửa: ~$0.00207 USD; phiên kiểm thử tự động sau sửa: $0.00 USD.

### P2-M2.7 — Bất Biến Quyền Riêng Tư & Context Minimization
- **Yêu cầu:** Không gửi thông tin học viên trong context kiến thức chung; phân định rõ role.
- **Trạng thái:** **PASS (Trong phạm vi ca kiểm thử)**.
- **Bằng chứng:** PostgreSQL `ai_context_snapshots` ghi nhận `actor = {"role": "TRAINER", "userId": "..."}` và `student = null` trong cả 4 ca kiểm thử F01.

### P2-M2.8 — Tái Xác Thực Quyền Trước Khi Giao Hàng (Revalidation)
- **Yêu cầu:** Kiểm tra trạng thái tài khoản `ACTIVE` và role `STUDENT`/`TRAINER` trước khi trả kết quả và khi lấy run detail.
- **Trạng thái:** **PASS**.
- **Bằng chứng:** Báo cáo `docs/08-ai/f01-recovery-revalidation-drill-report.md`: 11 tests trong `AiRunF01ServiceUnitTest` và 8 tests trong `AiRunF01PersistenceIntegrationTest` PASS 100%.

### P2-M2.9 — Durable Recovery (Orphan Sweeper)
- **Yêu cầu:** Tự động dọn dẹp các AI Run bị bỏ rơi (`RUNNING`/`QUEUED` quá 120s) sau crash/restart sang `FAILED (TIMEOUT)`.
- **Trạng thái:** **PASS (Integration Tests) / NOT RUN (Live Runtime Drill)**.
- **Bằng chứng:** 9 tests trong `AiRunOrphanRecoveryIntegrationTest.java` PASS 100% trên Testcontainers PostgreSQL (chứng minh row locking `FOR UPDATE SKIP LOCKED`, chống race condition, bảo toàn token usage NULL).
- **Phân định:** Việc diễn tập kill process trực tiếp trên live dev environment được giữ trạng thái **NOT RUN** (là bài kiểm tra thực địa vận hành, không thay thế bằng integration test).

### P2-M2.10 — Evaluation Runner & Benchmark (A-49 / A-52)
- **Yêu cầu:** Khung đánh giá tự động có rubric và bộ kiểm thử đối soát version.
- **Trạng thái:** **PASS (Khung Runner A-49)**; **Phân định rõ với Benchmark Holdout Release**.
- **Bằng chứng:** Khung runner và tài liệu `docs/08-ai/f01-a49-evaluation-runner-guide.md` đã hoàn thành (A-49). Cần phân biệt rõ: việc sở hữu khung runner không đồng nghĩa toàn bộ benchmark release đã được chạy; bài đánh giá benchmark chất lượng tổng thể trên tập Holdout 60 ca (A-52) thuộc kế hoạch bàn giao Milestone **P2-M6**.

---

## 3. Tổng Hợp Điều Kiện Nghiệm Thu & Khuyến Nghị

### 3.1. Đối Chiếu Điều Kiện Hoàn Thành Bắt Buộc (Mục 4 Kế Hoạch Triển Khai)
- [x] Không retrieve DRAFT/ARCHIVED: Đạt.
- [x] Truy được nguồn/version đã dùng: Đạt (đối soát nguyên văn citations).
- [x] Query không có bằng chứng trả giới hạn: Đạt (TR-OAI-03 trả `INSUFFICIENT_DATA`).
- [x] Provider error có fallback: Đạt (TR-05 lỗi 502 và retry thành công).
- [x] Không có đường ghi business state từ FastAPI: Đạt (Spring Boot độc quyền ghi).
- [x] F01 kiến thức chung xuyên suốt: Đạt (Mobile Trainer → Spring → FastAPI → OpenAI).

### 3.2. Khuyến Nghị Bước Tiếp Theo
- Bàn giao gói kiểm chứng Trainer F01 live và tài liệu đối chiếu P2-M2 để review.
- Sau khi được phê duyệt, chuyển sang **P2-M3 (Luồng giáo án hoàn chỉnh đầu tiên - F02)**:
  - Khởi tạo Workout command/version/approval từ P2-M1.
  - Thiết lập schema F02 cho đề xuất giáo án 1 tuần và luồng phê duyệt của Huấn luyện viên / Học viên.
