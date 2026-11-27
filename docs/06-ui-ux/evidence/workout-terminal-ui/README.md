# Workout Execution terminal UX

Phạm vi: bảo trì Phase 1 B05/ST-07, Student Mobile `/workouts/current` và `/workouts/[executionId]`; không thay đổi F-ID, milestone Phase 2, policy Backend hoặc contract.

## Policy đã đối chiếu

- `WorkoutExecutionStatus`: IN_PROGRESS, COMPLETED, PARTIALLY_COMPLETED, ABORTED. `WorkoutExecution.requireInProgress()` từ chối cả ba trạng thái terminal bằng WORKOUT_EXECUTION_TERMINAL.
- `WorkoutExecutionCommandService.upsertSet()` kiểm Student mutation authority và lifecycle trước cả identical-set replay; cùng clientSetId/body có thể replay trong IN_PROGRESS trước kiểm version. Payload mới vẫn cần expectedVersion. Substitute cũng chỉ dùng khi IN_PROGRESS; complete/abort giữ command receipt và phiên bản trong hash.
- `WorkoutExecutionAccessPolicy`: chủ sở hữu + account ACTIVE + Student profile mới được mutation; owner read cũng áp dụng các điều kiện đó. Trainer đọc lịch sử qua authority riêng, không có quyền sửa set; màn này không tạo luồng Trainer mới.
- OpenAPI `/workout-executions/{executionId}/sets/{clientSetId}` là PUT actual set, không phải correction endpoint. `support/correction/DataCorrectionService` hiện chỉ thực thi MEASUREMENT và USER_PROFILE; không có correction set được phép để giữ trên màn terminal. Quy tắc hỗ trợ correction trong tài liệu authority không chứng minh một implementation cho Workout Execution.
- `WorkoutExecutionDomainTest.abortRequiresZeroCompletedBaselineSetsAndTerminalIsImmutable`, `WorkoutExecutionPersistenceIntegrationTest` (identical retry) và `V29WorkoutExecutionMigrationIntegrationTest.terminalExecutionAndChildrenRejectUpdatesAndHardDeletes` là bằng chứng source/test có sẵn, không phải Backend tests được chạy trong task này.

Observation cũ tại `ui-light-workout-details/README.md` được xác nhận là UX defect: SetEditor mời nhập/lưu trên terminal trong khi command Backend từ chối. Không có bằng chứng vượt quyền Backend.

## Thay đổi hẹp

- Terminal render SetResult thay editor/radio/save, vẫn giữ từng trạng thái execution/set, dữ liệu actual, ghi chú, đơn vị thật và planned/performed timestamps. Null không thành zero; không mượn đơn vị prescription để khẳng định đơn vị actual.
- Student đang hoạt động và sở hữu execution IN_PROGRESS giữ editor. Không hiển thị dữ liệu cached của Student khác. Guard đọc scope hiện tại khi dispatch, khóa mutation đồng thời và khóa input pending; modal không còn khả dụng khi mất quyền/lifecycle.
- Mutation nhận snapshot version/body; retry set cùng nội dung giữ clientSetId/body/version; retry terminal cùng commandKey giữ version gốc sau refresh. Không thay policy correction, receipt, transport hay apiClient.
- Response cũ không ghi đè version mới/terminal/current khác execution, không khôi phục cache hoặc điều hướng sau đổi account/capability/status. Kiểm tra lại scope sau khi chờ history invalidation. Không khẳng định request chưa gửi khi transport lỗi.

## Kiểm chứng và giới hạn

- Regression terminal chạy trên source gốc: cả COMPLETED, PARTIALLY_COMPLETED, ABORTED FAIL vì còn control Lưu hiệp. Full cold baseline có timeout ở test sẵn có nên không dùng kết quả đó để kết luận defect; dùng lượt isolated terminal để xác nhận.
- Focused Jest dùng `--runInBand`: screen + API, gồm owner/capability/profile/account status/version, callback cũ, duplicate/pending, late response, retry identity/version và null/zero/unknown units. Kết quả cuối và lint/typecheck/CI được ghi trong PR.
- Self-review trong task; review độc lập cho phần retry/authority đã phát hiện các race sau await, current khác execution và suy unit; có regression và fix tương ứng.
- **Device/browser visual QA, kích thước/keyboard/screen reader thực, Backend E2E: NOT RUN.** Component/API mocks không chứng minh các lớp này. Không chạy Backend local vì không thay đổi Backend.
- Không sử dụng browser/phiên đăng nhập QA, không khởi động emulator, không restart dịch vụ, không sửa .env, credentials, dữ liệu hoặc runtime checkout. Không sửa file của task timeout; giữ lượt local nặng riêng và nhường trước khi chờ CI.
