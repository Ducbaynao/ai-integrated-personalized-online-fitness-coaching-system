# Báo cáo Nghiệm thu và Tích hợp Toàn diện Phase 1 — Người A (Bản Chuẩn hóa Lưu trữ Repository)

**Dự án:** AI-Integrated Personalized Online Fitness Coaching System  
**Phạm vi:** Người A (Backend, Identity & Access, Trainer Verification, Fitness Goals, Measurements & Progress, Account Administration, Support & Moderation Governance, Infrastructure Hardening)  
**Thời điểm cập nhật:** 2026-11-12  
**Branch thực hiện:** `chore/phase1-integration-acceptance`  
**Base Commit:** `f2bfa43` (`main`, Merge pull request #155)  
**Trạng thái CI trên commit gốc:** 6/6 checks PASS  

---

## 1. Tóm tắt Điều hành & Kết luận Nghiệm thu

### 1.1 Mức độ Hoàn thành Phạm vi Người A
- **Mã nguồn và Kiến trúc:** **ĐỦ ĐIỀU KIỆN ĐÓNG TRONG PHẠM VI NGƯỜI A**. Toàn bộ mã nguồn do Người A phụ trách (Xác minh HLV, Mục tiêu thể chất M2, Số đo & Tiến độ A07/M5, Quản trị tài khoản A05/M8, Hỗ trợ / Kiểm duyệt / Hiệu chỉnh dữ liệu M8, và Hạ tầng sao lưu/phục hồi) đã hoàn tất, tuân thủ nghiêm ngặt các quy tắc kiến trúc trong [AGENTS.md](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/AGENTS.md).
- **Kiểm thử tự động:** Các bộ kiểm thử tích hợp chuyên sâu, kiểm tra tĩnh, hợp đồng OpenAPI, kiểm thử mobile và web đều vượt qua (100% PASS, chi tiết tại Mục 2).
- **Hạ tầng sao lưu & Diễn tập phục hồi:**
  - Hoàn tất diễn tập sao lưu Storage-First với cơ chế **Post-Dump Catch-Up Reconcile** chống mất mát dữ liệu do ghi đồng thời.
  - Hoàn tất diễn tập phục hồi vào cơ sở dữ liệu cô lập và kiểm chứng tính bền vững qua 2 tiến trình Hệ điều hành riêng biệt với mã băm SHA-256 trùng khớp 100%.
  - Hoàn tất diễn tập nâng cấp Flyway V59 trên DB cô lập.

### 1.2 Mức độ Sẵn sàng Đóng Phase 1 Toàn Hệ thống
- **Phụ thuộc Người B:** Các phân hệ do Người B phụ trách (B06 Lịch hẹn, B07 Dinh dưỡng, B08 Xóa tài khoản / Chat) đã được tích hợp và kiểm thử chung trên `main`.
- **Phần còn chờ trước khi phát hành sản xuất:**
  1. *Áp dụng Flyway V59 vào DB chính `digital_fitness`:* Đã hoàn tất khâu chuẩn bị, sao lưu và diễn tập an toàn; được thực thi theo ủy quyền của Người dùng.
  2. *Quy trình Xóa tài khoản tự động (Account Erasure Worker):* Đang được đặt ở trạng thái an toàn tắt mặc định (`account.erasure.enabled=false`, `account.erasure.worker.enabled=false`). Việc kích hoạt trên sản xuất cần bổ nhiệm 2 cá nhân độc lập (Two-person hold principals) theo chính sách bảo vệ dữ liệu.
  3. *Nghiệm thu giao diện trực quan (Visual UI Acceptance):*
     - Admin Web: Đã được kiểm thử và xác nhận ngày 10/11/2026.
     - Mobile Tiến độ & Số đo: Đã được kiểm thử và xác nhận ngày 09/11/2026.
     - Mobile HLV nộp hồ sơ xác minh: Đã đạt kiểm thử tự động (Jest & API); việc lưu ảnh chụp màn hình tương tác trên thiết bị Native/AVD thuộc thẩm quyền kiểm tra trực tiếp của tester/reviewer.

---

## 2. Đối chiếu Chi tiết Chất lượng Bằng chứng Kiểm thử

Để tránh cộng lặp và đảm bảo tính minh bạch, dưới đây là chi tiết từng bộ kiểm thử đã được chạy và xác thực trực tiếp trên branch `chore/phase1-integration-acceptance`:

| Bộ kiểm thử (Suite / Group) | Lệnh thực thi | Môi trường / Dịch vụ phụ thuộc | Số lượng test | Kết quả | Thời gian chạy | Bằng chứng / Log |
|---|---|---|---|---|---|---|
| **Core Person A Integration** | `mvnw test -Dtest="TrainerApplicationIntegrationTest,AdminTrainerApplicationIntegrationTest,FitnessGoalProposalIntegrationTest,MeasurementApiIntegrationTest,AdminAccountAdministrationIntegrationTest,AdminSupportModerationAuditIntegrationTest,SupportModuleArchitectureTest"` | PostgreSQL 18.6 Testcontainers | 168 tests | **168 PASS (0 fail, 0 error, 0 skipped)** | 03:15 min | Task-144 |
| **Live Storage & Scanner Hardening** | `mvnw test -Dtest="LiveStorageAndScannerHardeningIntegrationTest"` | Adobe S3Mock (port 9090) + ClamAV container (port 3310) | 7 tests | **7 PASS** | 28.9 s | Task-102 |
| **Spring Context Storage Parity** | `mvnw test -Dtest="LiveProcessRestartStorageIntegrationTest"` | Adobe S3Mock (port 9090) + ClamAV container (port 3310) | 1 test | **1 PASS** | 30.1 s | Task-102 |
| **Real OS Process Restart Parity** | `powershell tools/test-real-os-process-restart.ps1` | 2 JVM OS Processes (PID 41608 -> 44296), DB 5433, S3Mock 9000, ClamAV 3310 | 1 drill | **1 PASS (100% byte match)** | 35.8 s | Task-552 |
| **Concurrent Backup Consistency Drill** | `powershell tools/test-concurrent-backup-consistency.ps1` | Local Postgres + S3Mock, mid-backup upload injection | 1 drill | **1 PASS (Zero dangling ptr)** | 18.2 s | Step 265 / Step 602 |
| **Health Indicators & Monitoring** | `mvnw test -Dtest="StorageAndScannerHealthIndicatorUnitTest"` | Mock S3 + Mock Socket | 4 tests | **4 PASS** | 1.7 s | Task-106 |
| **HTTP Input Error Handling** | `mvnw test -Dtest="InputErrorHandlingIntegrationTest"` | PostgreSQL 18.6 Testcontainers | 8 tests | **8 PASS** | 26.8 s | Task-106 |
| **Flyway V58->V59 Upgrade Drill** | `mvnw test -Dtest="FlywayV58ToV59UpgradeIntegrationTest"` | PostgreSQL 18.6 Testcontainers | 1 test | **1 PASS** | 20.0 s | Task-148 |
| **V59 Backup Restore Drill** | `mvnw test -Dtest="V59BackupRestoreDrillIntegrationTest"` | PostgreSQL 18.6 Testcontainers + S3 local (port 9000) | 1 test | **1 PASS** | 24.9 s | Task-148 |
| **Account Erasure Lifecycle** | `mvnw test -Dtest="AccountErasureHttpIntegrationTest"` | PostgreSQL 18.6 Testcontainers | 14 tests | **14 PASS** | 31.3 s | Task-114 |
| **Data Export Stream Unit** | `mvnw test -Dtest="AccountDataExportStreamTest"` | Mockito slice | 3 tests | **3 PASS** | 2.0 s | Step 186 |
| **Data Export & Chat Integration** | `mvnw test -Dtest="ChatNotificationIntegrationTest"` | PostgreSQL 18.6 Testcontainers | 19 tests | **19 PASS** | 35.9 s | Task-190 |
| **Python AI Service Full Suite** | `pytest services/ai-service/tests` | Python 3.12 venv, Simulated Provider | 82 tests | **82 PASS** | 6.2 s | Step 128 |
| **Admin Web Frontend Suite** | `npm --prefix apps/admin-web test -- --run` | Vitest v5.0.2, JSDOM | 148 tests (21 files) | **148 PASS** | 16.2 s | Task-134 |
| **Admin Web Typecheck** | `npm --prefix apps/admin-web run typecheck` | TypeScript `tsc -b` | Toàn bộ web app | **0 errors** | 5.1 s | Task-134 |
| **Mobile Native Frontend Suite** | `npm --prefix apps/mobile run test -- --watchAll=false` | Jest, React Native Testing Library | 748 tests (93 suites) | **748 PASS** | 13.5 s | Task-140 |
| **Mobile Typecheck** | `npm --prefix apps/mobile run typecheck` | TypeScript `tsc --noEmit` | Toàn bộ mobile app | **0 errors** | 6.4 s | Task-140 |
| **OpenAPI & Contracts** | `npm run validate:contracts && npm run test:contracts` | Redocly CLI + Ajv 2020 | 21 tests | **21 PASS (Valid)** | 1.2 s | Step 132 |

---

## 3. Phân tích Kỹ thuật Chuyên sâu

### 3.1 Khảo sát và Bổ sung Kiểm chứng Restart Tiến trình (Process Restart Parity)
- **Thực tế mã nguồn của `LiveProcessRestartStorageIntegrationTest`:** Phương thức `liveBackendRestart_fileParity_verified` khởi tạo `SpringApplication.run(...)` tạo ngữ cảnh `firstContext`, thực hiện lưu tệp lên S3 qua `TrainerDocumentStorageService`, xác thực tải lại, rồi đóng ngữ cảnh `firstContext.close()`. Sau đó, bài test tiếp tục gọi `SpringApplication.run(...)` tạo ngữ cảnh mới `restartedContext`, lấy bean `TrainerDocumentStorageService` mới và tải lại tệp từ S3, đối chiếu mã băm SHA-256. Bài test này chứng minh bean `TrainerDocumentStorageService` **không sử dụng bất kỳ bộ nhớ đệm cục bộ (in-memory cache) hay biến static nào**, bảo đảm dữ liệu được ghi xuống S3 thật và có thể đọc lại sau khi toàn bộ vòng đời ứng dụng Spring Boot được tạo mới. Tuy nhiên, hai lần khởi tạo ngữ cảnh này diễn ra trong **cùng một tiến trình JVM của Maven Surefire ForkedBooter**.
- **Diễn tập bổ sung với 2 Tiến trình Hệ điều hành riêng biệt (`tools/test-real-os-process-restart.ps1`):**
  - Đã đóng gói executable JAR `services/backend/target/backend-0.0.1-SNAPSHOT.jar`.
  - Khởi động **Tiến trình OS #1** (`java.exe`, PID **41608**) trên cổng 8089, kết nối DB cô lập `digital_fitness_restore_test` và S3Mock thật.
  - Xác thực phiên học viên qua API `POST /api/v1/auth/sessions`, tải lên ảnh tiến độ đo lường (`POST /api/v1/measurements/progress-photos`), quét ClamAV sạch và lưu S3 (SHA-256: `0e527d7fc4ee6f06830501a547285514f6b0fca339097e3f4340a631bf3735f4`, ID: `dbf9cb5d-4f10-466a-b286-9a29579fa55d`).
  - **Force-kill hoàn toàn Tiến trình OS #1 (PID 41608)** ở cấp hệ điều hành; xác nhận cổng 8089 được giải phóng.
  - Khởi động **Tiến trình OS #2 hoàn toàn mới** (`java.exe`, PID **44296** - xác nhận PID khác biệt rõ rệt).
  - Tải lại tệp ảnh qua API `GET /api/v1/measurements/progress-photos/{id}/file` của Tiến trình #2.
  - **Kết quả:** Tải về chính xác 128 bytes, mã băm SHA-256 trùng khớp 100% (`0e527d7fc4ee6f06...`). Xác nhận tính bền vững lưu trữ qua các tiến trình OS độc lập hoàn toàn.

### 3.2 Căn cứ Chính sách Xác thực Nâng cao (Step-Up) đối với Xét duyệt HLV
- **Khảo sát bảng `fitness.admin_action_policies`:**
  - Được khởi tạo tại migration `V16`, bổ sung tại `V57` và `V58`.
  - Quy định bắt buộc `requires_step_up = true` cho 8 hành động quản trị có mức độ rủi ro hệ thống cao:
    1. `SUSPEND_ACCOUNT` (Khóa tài khoản)
    2. `DISABLE_ACCOUNT` (Vô hiệu hóa tài khoản vĩnh viễn)
    3. `FORCE_LOGOUT` (Buộc đăng xuất toàn bộ phiên)
    4. `CHANGE_USER_ROLE` (Thay đổi phân quyền vai trò)
    5. `RESTORE_ACCOUNT` (Khôi phục tài khoản bị tạm khóa)
    6. `PRIVILEGED_ACCESS_REQUEST` (Mở phiên truy cập dữ liệu cá nhân có giới hạn thời gian)
    7. `DATA_CORRECTION` (Hiệu chỉnh dữ liệu quản trị)
    8. `PUBLISH_KNOWLEDGE` (Xuất bản tài liệu kho tri thức)
- **Kết luận về Xét duyệt HLV (`decideApplication`):**
  - Chính sách hiện hành trong cơ sở dữ liệu **không quy định step-up cho thao tác phê duyệt hoặc từ chối đơn đăng ký HLV**.
  - Endpoint `POST /api/v1/admin/trainer-applications/{applicationId}/decisions` được bảo vệ bởi:
    - Thẩm quyền vai trò quản trị viên: `@PreAuthorize("hasRole('ADMIN')")`.
    - Ràng buộc lý do từ chối: Khi `decision = 'REJECT'`, bắt buộc cung cấp `rejectionReason` (không chứa toàn khoảng trắng).
    - Khóa bi quan chống đụng độ (409 Conflict): Ngăn chặn hai admin cùng đưa ra quyết định đồng thời trên một hồ sơ.
    - Nhật ký kiểm toán bất biến: Tự động ghi nhận `TRAINER_APPLICATION_APPROVED` hoặc `TRAINER_APPLICATION_REJECTED` vào `fitness.audit_logs`.
  - **Đính chính báo cáo:** Xác nhận hệ thống thực thi đúng chính sách đã ban hành; không tồn tại lỗi thiếu sót step-up ở luồng xét duyệt HLV.

### 3.3 Phạm vi và Ranh giới của Chức năng Xuất Dữ liệu (Data Export)
- **Endpoint:** `GET /api/v1/users/me/data-export` (thuộc `CurrentUserController`).
- **Cơ chế thực thi:** `AccountDataExportService` tạo trực tiếp luồng nén ZIP (`account-export.zip`) trả về client (`StreamingResponseBody`), không lưu file trung gian trên đĩa máy chủ hay tạo URL công khai.
- **Dữ liệu được đóng gói hiện tại:**
  1. `account.json`: Thông tin tài khoản, hồ sơ cơ bản, ngôn ngữ, múi giờ, thời điểm tạo.
  2. `push-preferences.json`: Cài đặt nhận thông báo push.
  3. `sent-messages.ndjson`: Danh sách tin nhắn do chính người dùng gửi trong các cuộc trò chuyện.
  4. `attachments/*`: Toàn bộ tệp đính kèm do chính người dùng tải lên trong trò chuyện.
- **Ranh giới bảo mật:**
  - Kiểm tra trạng thái tài khoản: Nếu tài khoản không ở trạng thái `ACTIVE` (ví dụ `SUSPENDED` hoặc `DISABLED`), yêu cầu bị từ chối với HTTP 403 / 404 (`ACCOUNT_DATA_NOT_FOUND`).
  - Hủy luồng an toàn (`AbortableZipOutputStream`): Nếu tài khoản bị vô hiệu hóa trong khi đang stream dữ liệu, kết nối sẽ bị ngắt ngay lập tức, không cho phép tải tiếp.
  - Tuyệt đối không xuất mật khẩu băm (`password_hash`), token thiết bị, hoặc tin nhắn/tệp đính kèm của người khác.
- **Phạm vi chưa bao gồm:** Các bảng số đo (`measurements`), bài tập (`workouts`), và dinh dưỡng (`meals`) hiện chưa tích hợp vào gói ZIP xuất dữ liệu (đúng theo thiết kế phân định trong `b08-account-erasure-unified-closure.md`).

### 3.4 Khảo sát và Sửa lỗi Tính Nhất quán Sao lưu (Backup Consistency) khi Ghi Đồng thời
- **Khoảng trống phát hiện trong cơ chế cũ:**
  - Quy trình cũ: Đồng bộ S3 trước -> `pg_dump` sau.
  - Tình huống lỗi: Nếu một upload tệp mới diễn ra ngay sau khi bước sao chép S3 hoàn thành nhưng trước khi `pg_dump` chụp snapshot, giao dịch DB được commit và xuất hiện trong dump, nhưng tệp vật lý chưa kịp nằm trong thư mục backup S3. Sau khi restore, hệ thống sẽ chứa bản ghi tham chiếu trỏ tới tệp không tồn tại trong S3 (dangling pointer -> lỗi HTTP 404).
- **Cơ chế đã triển khai và hoàn thiện (`tools/backup-system.ps1`):**
  - **Mô hình:** `storage-first-snapshot-with-post-dump-reconcile`.
  - **Trích xuất trực tiếp từ snapshot dump:** Thay vì truy vấn DB đang sống, script sử dụng `pg_restore -a -t media_files -f - "$dumpTmpPath"` để trích xuất chính xác các bản ghi `fitness.media_files` nằm bên trong tệp snapshot vừa dump.
  - So sánh với các file đã sao chép về thư mục `storage/`.
  - Nếu phát hiện có file mới được commit trong khoảng thời gian giữa Bước 1 và Bước 2, script lập tức thực hiện **catch-up sync** tải bổ sung các file này từ S3 về gói backup và tính toán mã băm SHA-256 đưa vào `manifest.json`.
  - Nếu tệp tham chiếu trong DB không thể tải được từ S3, script sẽ **Fail-Fast** và xóa sạch chỉ riêng thư mục backup tạm của lần chạy này, ngăn chặn tạo ra bản sao lưu hỏng.
- **Kiểm chứng thực tế (`tools/test-concurrent-backup-consistency.ps1`):**
  - Kịch bản: Bơm File A trước khi backup; sử dụng hook `-PostStorageHook` để bơm File B trực tiếp vào DB và S3 đúng vào khoảng giữa bước storage sync và `pg_dump`.
  - Kết quả: Bước [3b] phát hiện chính xác File B bị thiếu trong đợt sync đầu, tự động tải bổ sung về thư mục `storage/`.
  - Khôi phục sang DB `df_concurrent_restored` và S3 `fitness-media-drill-restored`: Cả 2 file A và B đều được khôi phục nguyên vẹn, SHA-256 trùng khớp 100%, **hoàn toàn loại trừ lỗi dangling pointer**.
  - Đã cập nhật tài liệu chi tiết tại [database/backup/README.md](file:///D:/Projects/ai-integrated-personalized-online-fitness-coaching-system/database/backup/README.md).

### 3.5 Giám sát Hệ thống (Monitoring), Probes và Quyền Hạn
- **Spring Boot Actuator:**
  - Cổng endpoint: `/actuator/health`, `/actuator/info`, `/actuator/metrics`.
  - **Liveness probe:** `/actuator/health/liveness` (theo dõi `livenessState`).
  - **Readiness probe:** `/actuator/health/readiness` (theo dõi `readinessState,db`).
  - **Custom Indicators:**
    - `storage`: Kiểm tra kết nối tới S3 endpoint và kiểm tra bucket `fitness-media` tồn tại.
    - `scanner`: Kiểm tra kết nối tới ClamAV daemon trên port 3310 qua lệnh PING.
- **Quyền xem chi tiết (`health details`):**
  - Cấu hình: `management.endpoint.health.show-details: when_authorized`, `management.endpoint.health.roles: ADMIN`.
  - Người dùng ẩn danh chỉ nhìn thấy `{"status": "UP"}` hoặc `{"status": "DOWN"}`. Chỉ người dùng có vai trò `ADMIN` mới có quyền xem chi tiết từng thành phần phụ thuộc (`db`, `storage`, `scanner`, `diskSpace`), ngăn chặn rò rỉ topology hạ tầng.
- **Human-in-the-loop (Human Review):**
  - Mọi đề xuất AI (bài tập, dinh dưỡng) đều bắt buộc phải qua bước Huấn luyện viên xem xét, phê duyệt và học viên đồng ý; AI không có quyền tự quyết định nghiệp vụ.
  - Phê duyệt HLV, hiệu chỉnh dữ liệu, kiểm duyệt nội dung đều do quản trị viên con người kiểm soát có ghi nhật ký kiểm toán bất biến.

---

## 4. Tình trạng Tích hợp Cơ sở dữ liệu Dùng chung (`digital_fitness`)

- **Host/Port:** `localhost:5433` (Docker container `digital-fitness-postgres`).
- **Phiên bản hiện tại:** **Flyway V59 (Hoàn tất áp dụng và xác thực)**.
- **Bản sao lưu trước nâng cấp:** `database/backup/backups/20261112_231115/` (DB dump SHA-256: `a8b93fe8c325e27eaf387606bf193861664abea812979ffbd68bcbb7aa941002`, 3 objects S3).
- **Kết quả thực thi Migration V59 trên DB chính:**
  - Lệnh thực thi: Docker container `flyway/flyway:11` migrate.
  - Kết quả: `Successfully applied 1 migration to schema "fitness", now at version v59 (execution time 00:00.025s)`.
  - Kiểm tra tính hợp lệ (`flyway validate`): `Successfully validated 60 migrations (execution time 00:00.303s)`.
  - Khởi tạo sequence: `fitness.support_case_number_seq` đồng bộ hóa vượt mốc cao nhất của dữ liệu cũ (`SUP-4980699`), đã kiểm thử cấp số liên tục (`4980700`..`4980710`) không đụng độ và không phân mảnh.
  - Dữ liệu lịch sử: Toàn bộ bảng dữ liệu cũ (bao gồm các support cases `SUP-3815347`..`SUP-4980699`) nguyên vẹn 100%.

---

## 5. Danh mục Phụ thuộc Người B và Kế hoạch Bàn giao

| Phân hệ / Hạng mục | Người phụ trách | Trạng thái hiện tại | Ảnh hưởng tới Người A | Điều kiện kích hoạt sản xuất |
|---|---|---|---|---|
| **Lịch hẹn & Xếp lịch (B06)** | Người B | Đã merge `main`, test passing | Không xung đột | Sẵn sàng |
| **Dinh dưỡng cơ bản (B07)** | Người B | Đã merge `main`, test passing | Không xung đột | Sẵn sàng |
| **Cổng xóa tài khoản Workout/Nutrition (B08)** | Người B | Đã cài đặt các interface port và test tích hợp | Đã liên kết qua `AccountErasureWorker` | Chờ bổ nhiệm Hold Principals |
| **Two-Person Hold Principals** | Vận hành / Quản trị | Quy định tài liệu sẵn sàng, cờ đang tắt | Không ảnh hưởng mã nguồn Phase 1 | Cần cấu hình danh tính 2 admin độc lập trước khi bật worker |

---

## 6. Đề xuất Nội dung Pull Request (PR)

**Tiêu đề:**  
`chore(phase1): finalize Person A integration, acceptance verification, storage drills, and V59 migration readiness`
