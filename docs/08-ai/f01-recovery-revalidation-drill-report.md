# Báo Cáo Kiểm Chứng Kỹ Thuật: Orphan Recovery, Revalidation & Retry (P2-M2)

> **Mã đầu việc:** P2-M2 Bổ sung bằng chứng độ tin cậy — Orphan Recovery, Pre-Delivery Revalidation & Retry Semantics  
> **Repository:** `D:\Projects\ai-integrated-personalized-online-fitness-coaching-system`  
> **Branch thực hiện:** `test/f01-trainer-openai-e2e-20261027`  
> **Commit nền (Base commit):** `f53357c71cd51f8309dce02c1943421fd8a3e67a` (Merge PR #112 trên `origin/main`)  
> **Ngày thực hiện:** 2026-10-27  
> **Môi trường:** Java 21, Spring Boot 4.1.1, PostgreSQL 18 (pgvector), JUnit 5, Testcontainers, React Native Jest  
> **Trạng thái:** **PASS các bài kiểm thử tự động (Unit & Integration tests); Giữ Runtime Crash/Restart Drill trên dev environment ở trạng thái NOT RUN.**

---

## 1. Mục Tiêu & Bối Cảnh

Trong các phiên kiểm chứng trước của Milestone P2-M2, một số nhận định kỹ thuật cần được đối soát lại trực tiếp với mã nguồn và bằng chứng thực thi:
1. **Phục hồi run mồ côi (Orphan-run Recovery):** Cơ chế nền xử lý các AI Run bị bỏ rơi ở trạng thái `RUNNING` khi backend gặp sự cố đột ngột hoặc khởi động lại. Cần bằng chứng kiểm chứng cụ thể về việc chuyển đổi trạng thái sang `FAILED (TIMEOUT)` mà không ghi đè các run đã hoàn tất hợp lệ.
2. **Tái xác thực quyền trước khi giao hàng (Pre-delivery Authorization Revalidation):** Xác minh hook kiểm tra trạng thái tài khoản và vai trò của actor ngay trước khi trả kết quả AI và khi polling kết quả.
3. **Cơ chế Thử lại (Error Recovery & Retry):** Phân định ranh giới giữa việc "gửi lại được câu hỏi" và "phục hồi thành công khi upstream hết lỗi".

---

## 2. Kiểm Chứng Cơ Chế Phục Hồi Run Mồ Côi (Orphan Recovery Drill)

### 2.1. Kiến Trúc & Cấu Hình Kỹ Thuật

- **Service:** [`AiRunOrphanRecoveryService.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiRunOrphanRecoveryService.java)
- **Scheduler:** [`AiRunOrphanRecoveryScheduler.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/in/scheduled/AiRunOrphanRecoveryScheduler.java)
- **Cấu hình (`application.yml`):**
  ```yaml
  ai:
    orphan-recovery:
      enabled: ${AI_ORPHAN_RECOVERY_ENABLED:true}
      timeout-threshold-seconds: ${AI_ORPHAN_RECOVERY_TIMEOUT_SECONDS:120}
      fixed-delay-ms: ${AI_ORPHAN_RECOVERY_FIXED_DELAY_MS:60000}
      initial-delay-ms: ${AI_ORPHAN_RECOVERY_INITIAL_DELAY_MS:10000}
      batch-size: ${AI_ORPHAN_RECOVERY_BATCH_SIZE:50}
  ```

### 2.2. Cơ Chế Khóa Hàng & Xử Lý Xung Đột

Repository [`JdbcAiRunRepository.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/adapter/out/persistence/JdbcAiRunRepository.java) thực hiện 2 nguyên tắc an toàn tuyệt đối:
1. **Khóa hàng tránh xung đột giữa các pod cleaner:**
   ```sql
   SELECT id FROM fitness.ai_runs
   WHERE status IN ('QUEUED', 'RUNNING')
     AND (
         (status = 'RUNNING' AND started_at <= :cutoff)
         OR (status = 'QUEUED' AND created_at <= :cutoff)
     )
   ORDER BY created_at ASC
   LIMIT :batchSize
   FOR UPDATE SKIP LOCKED;
   ```
2. **Cập nhật có điều kiện (Optimistic Status Guard):**
   ```sql
   UPDATE fitness.ai_runs
   SET status = 'FAILED'::fitness.ai_run_status,
       error_code = :errorCode,
       error_message = :errorMessage,
       completed_at = :completedAt,
       latency_ms = COALESCE(latency_ms, EXTRACT(EPOCH FROM (:completedAt - COALESCE(started_at, created_at))) * 1000)
   WHERE id = :id
     AND status IN ('QUEUED', 'RUNNING');
   ```
   Nếu run đã hoàn tất hợp lệ (`SUCCEEDED`) ngay trước khi cleaner chạy, câu lệnh `UPDATE` trả về 0 row; cleaner bỏ qua và không làm hỏng kết quả nghiệp vụ.

### 2.3. Kết Quả Kiểm Thử Thực Tế ([`AiRunOrphanRecoveryIntegrationTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/AiRunOrphanRecoveryIntegrationTest.java))

Đã chạy kiểm thử tích hợp trên môi trường Testcontainers PostgreSQL thật:
```bash
cd services/backend; .\mvnw.cmd test -Dtest=AiRunOrphanRecoveryIntegrationTest
```
**Kết quả kiểm thử tích hợp tự động: 9/9 tests PASS 100% (Thời gian chạy: 25.26s).**

> [!NOTE]
> **Phân định rõ ranh giới kiểm chứng:**
> - **Kiểm thử tích hợp tự động (Integration Tests):** **PASS 9/9** — Xác minh toàn bộ logic câu lệnh SQL, khóa `FOR UPDATE SKIP LOCKED`, optimistic status guard, và timeout threshold trong transactional context với PostgreSQL Testcontainers.
> - **Live Runtime Crash/Restart Drill:** **NOT RUN** — Chưa thực hiện can thiệp giết tiến trình backend đột ngột (SIGKILL / task kill) giữa lúc đang có request live trên môi trường dev rồi khởi động lại để quan sát scheduler trong runtime. Kết quả kiểm thử tích hợp không thay thế cho live runtime drill.

| STT | Tên ca kiểm thử | Kịch bản kiểm chứng | Kết quả thực tế | Đánh giá |
| :---: | :--- | :--- | :--- | :---: |
| 1 | `schedulerInvocation_passesThroughTransactionalProxy` | Kích hoạt scheduled job qua Spring proxy | Chuyển run mồ côi sang `FAILED (TIMEOUT)` | **PASS** |
| 2 | `staleRunningRun_isReconciledToFailedWithTimeout` | Run `RUNNING` quá hạn 120s | Reconcile sang `FAILED`, error_code `TIMEOUT`, lưu errorMessage | **PASS** |
| 3 | `staleQueuedRun_isReconciledToFailedWithTimeout` | Run `QUEUED` quá hạn 120s không có worker nhận | Reconcile sang `FAILED`, error_code `TIMEOUT` | **PASS** |
| 4 | `activeRunningRun_withinThreshold_isUntouched` | Run đang xử lý trong hạn mức thời gian (< 30s) | Không bị cleaner đụng tới, giữ nguyên `RUNNING` | **PASS** |
| 5 | `terminalRun_isNeverAlteredByCleaner` | Run `SUCCEEDED` tạo từ lâu trong quá khứ | Không bị cleaner chỉnh sửa hay ghi đè | **PASS** |
| 6 | `raceCondition_workerCompletesFirst_cleanerDoesNotOverwrite` | Worker hoàn thành trước khi cleaner cập nhật | Cleaner phát hiện trạng thái đã đổi, trả false và không ghi đè | **PASS** |
| 7 | `raceCondition_recoveryCompletesFirst_lateCompletionThrowsConflictAndPreservesFailed` | Cleaner thu hồi trước khi worker trả kết quả muộn | Worker bị từ chối với `AiRunLifecycleConflictException`, giữ nguyên `FAILED` | **PASS** |
| 8 | `recovery_preservesNullTokensWhenUnknown` | Thu hồi run bị crash chưa nhận được usage từ provider | `input_token_count` và `output_token_count` giữ nguyên `NULL` (không bị ép thành 0) | **PASS** |
| 9 | `multiThreadedConcurrentCleaner_safeRowLocking` | Nhiều luồng cleaner quét đồng thời | Khóa hàng `FOR UPDATE SKIP LOCKED` an toàn, không có deadlock | **PASS** |

---

## 3. Kiểm Chứng Tái Xác Thực Quyền Trước Khi Giao Kết Quả (Pre-Delivery Revalidation)

### 3.1. Đối Chiếu Mã Nguồn Thực Tế

Trong báo cáo trước đây, có nhận định "chưa có hook revalidation trước khi trả kết quả". Qua rà soát chi tiết mã nguồn hiện hành:
- Phương thức [`revalidateActorAuthorization(UUID actorId)`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiRunF01Service.java#L290-L308) **ĐÃ ĐƯỢC TRIỂN KHAI VÀ GỌI CHẶT CHẼ** tại 3 vị trí trọng yếu:
  1. [`AiKnowledgeOrchestrationService.java:351`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiKnowledgeOrchestrationService.java#L351): Trước khi trả `serviceResponse` thành công cho client.
  2. [`AiKnowledgeOrchestrationService.java:388`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiKnowledgeOrchestrationService.java#L388): Trước khi trả thông báo lỗi/từ chối cho client.
  3. [`AiRunF01Service.java:201`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/main/java/com/fitnesscoaching/platform/modules/ai/application/service/AiRunF01Service.java#L201): Trong phương thức `getF01Run(actorId, runId)` trước khi kiểm tra quyền sở hữu run (`enforceOwnership`).

### 3.2. Quy Tắc Nghiệp Vụ Revalidation

```java
AccountStatus accountStatus = accountStatusQuery.getAccountStatus(actorId)
        .orElseThrow(() -> new AccountUnavailableException("Actor account does not exist"));
if (accountStatus != AccountStatus.ACTIVE) {
    throw new AccountUnavailableException("Actor account is not active");
}

boolean isStudent = userRoleQuery.hasActiveRole(actorId, "STUDENT");
boolean isTrainer = userRoleQuery.hasActiveRole(actorId, "TRAINER");
if (!isStudent && !isTrainer) {
    throw new AiRunAccessDeniedException("User lacks STUDENT or TRAINER capability to access AI general knowledge output");
}
```

### 3.3. Kết Quả Kiểm Thử Thực Tế

1. **Unit Tests ([`AiRunF01ServiceUnitTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/application/service/AiRunF01ServiceUnitTest.java)):**
   - Chạy lệnh: `cd services/backend; .\mvnw.cmd test -Dtest=AiRunF01ServiceUnitTest`
   - **Kết quả: 11/11 tests PASS 100% (2.49s).**
   - Xác nhận:
     - Actor null → `AiRunAccessDeniedException`.
     - Tài khoản không tồn tại hoặc bị `SUSPENDED`/`DISABLED` → `AccountUnavailableException`.
     - Tài khoản active nhưng không có role `STUDENT` hoặc `TRAINER` → `AiRunAccessDeniedException`.
     - Tài khoản bị thu hồi role `STUDENT` nhưng vẫn còn role `TRAINER` (hoặc ngược lại) → Thành công hợp lệ theo chính sách đa vai trò độc lập.
     - `getF01Run` chặn truy cập nếu tài khoản bị khóa hoặc role bị thu hồi; chặn truy cập nếu run thuộc về user khác.
2. **Integration Test ([`AiRunF01PersistenceIntegrationTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/AiRunF01PersistenceIntegrationTest.java#L463-L506)):**
   - Chạy lệnh: `cd services/backend; .\mvnw.cmd test -Dtest=AiRunF01PersistenceIntegrationTest`
   - **Kết quả: 8/8 tests PASS 100% (25.43s).**
   - Test case `getF01Run_revalidatesAccountStatusAndRoles` xác nhận:
     - Khi tài khoản bị cập nhật `status = 'DISABLED'` trong cơ sở dữ liệu thật → API trả về `HTTP 403 Forbidden` (`errorCode = 'ACCOUNT_UNAVAILABLE'`).
     - Khi role `STUDENT` bị xóa khỏi `user_roles` → API trả về `HTTP 403 Forbidden` (`errorCode = 'AI_FORBIDDEN'`).

---

## 4. Kiểm Chứng Cơ Chế Phục Hồi Lỗi & Thử Lại (Retry Semantics)

Cần phân biệt rõ 3 mức độ của cơ chế thử lại trong hệ thống:

1. **Mức 1: Khả năng gửi lại câu hỏi (Retry Dispatch):**
   - **Đã kiểm chứng:** Trong phiên kiểm chứng live trên emulator Pixel 8 (ca TR-05), khi upstream trả về HTTP 502, giao diện hiển thị thẻ báo lỗi kèm nút "Thử lại". Nhấn nút "Thử lại" đã kích hoạt gửi lại câu hỏi và ghi nhận run ID mới trong PostgreSQL (`0f8f6a02-b743-41ed-95bc-e681defe8350`).
2. **Mức 2: Thử lại tiếp tục thất bại đúng thiết kế:**
   - Trong ca TR-05 live, do câu hỏi mang keyword `trigger_provider_error`, stub giả lập tiếp tục ném 502. Việc run thứ hai ghi nhận `FAILED` là hành vi hoàn toàn chính xác của hệ thống khi sự cố upstream chưa chấm dứt.
3. **Mức 3: Thử lại phục hồi thành công khi upstream hết lỗi (Recovery Success):**
   - **Đã kiểm chứng tự động:** Trong test suite [`AiKnowledgeScreen.test.tsx:284-326`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/__tests__/AiKnowledgeScreen.test.tsx#L284-L326), kịch bản mô phỏng lượt 1 lỗi 504 Gateway Timeout và lượt 2 thành công:
     - Gửi câu hỏi "Câu hỏi bị lỗi timeout" -> Nhận lỗi 504.
     - Người dùng gõ dở câu khác trong ô input -> Nhấn "Thử lại".
     - Hệ thống gửi lại chính xác câu hỏi bị lỗi ban đầu, nhận response hợp lệ và chuyển đổi giao diện sang hiển thị câu trả lời `INFORMATION` hoàn chỉnh.
     - Chạy lệnh: `npm test --prefix apps/mobile -- apps/mobile/src/__tests__/AiKnowledgeScreen.test.tsx` -> **PASS 100% (18/18 tests)**.

---

## 5. Kết Luận Bổ Sung Bằng Chứng Cho P2-M2

| Hạng mục kiểm chứng | Cơ chế kỹ thuật | File / Phương thức kiểm thử | Kết quả | Kết luận kỹ thuật |
| :--- | :--- | :--- | :---: | :--- |
| **Orphan Recovery (Tích hợp tự động)** | Background sweeper khóa hàng `FOR UPDATE SKIP LOCKED`, chuyển `RUNNING` quá hạn 120s sang `FAILED (TIMEOUT)` | [`AiRunOrphanRecoveryIntegrationTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/AiRunOrphanRecoveryIntegrationTest.java) | **9/9 PASS** | **ĐÃ KIỂM CHỨNG BẰNG TESTCONTAINERS POSTGRESQL** |
| **Orphan Recovery (Live Runtime Drill)** | Can thiệp gián đoạn tiến trình Spring Boot khi run đang RUNNING, khởi động lại và quan sát scheduler reconcile trực tiếp trên môi trường dev | Chưa thực hiện kill process trực tiếp trên môi trường dev | **NOT RUN** | **CHƯA CHẠY DRILL TRÊN RUNTIME DEV (Không dùng kết quả test thay thế)** |
| **Pre-Delivery Revalidation** | Kiểm tra `AccountStatus.ACTIVE` và `STUDENT`/`TRAINER` capability trước khi trả kết quả | [`AiRunF01ServiceUnitTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/modules/ai/application/service/AiRunF01ServiceUnitTest.java), [`AiRunF01PersistenceIntegrationTest.java`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/services/backend/src/test/java/com/fitnesscoaching/platform/AiRunF01PersistenceIntegrationTest.java) | **19/19 PASS** | **ĐÃ CÓ ĐẦY ĐỦ BẰNG CHỨNG THỰC TẾ** |
| **Error Recovery & Retry** | Nút "Thử lại" gửi lại câu hỏi gốc; chuyển đổi hiển thị thành công khi upstream phục hồi | [`AiKnowledgeScreen.test.tsx`](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/apps/mobile/src/__tests__/AiKnowledgeScreen.test.tsx), Mobile Emulator TR-05 | **18/18 PASS (Unit) + TR-05 Live** | **ĐÃ CÓ ĐẦY ĐỦ BẰNG CHỨNG THỰC TẾ** |
