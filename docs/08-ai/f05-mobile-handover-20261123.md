# F05 — Bàn giao triển khai Mobile Workout Rescheduling

**Ngày cập nhật:** 23/11/2026, UTC+7.  
**Vai trò thực hiện:** DEV-A (theo phân công chỉ đạo Phase 2).  
**Branch:** `feat/mobile-f05-rescheduling`.  
**Base commit:** `c289ff044c0ac7c5bd2f3036b013acac09f986f8` (Merge PR #167).  
**Tình trạng CI base:** [Run 45168230342](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/actions/runs/45168230342) trên commit `c289ff044c0ac7c5bd2f3036b013acac09f986f8` đã hoàn thành thành công (**6/6 jobs success**, bao gồm Backend đã hoàn tất).  
**Trạng thái tính năng:** **ĐÃ TRIỂN KHAI VÀ KIỂM CHỨNG MOBILE F05** cho luồng hợp đồng tích hợp. Nghiệm thu toàn bộ tính năng và mốc phát hành (P2-M4/Release) vẫn **MỞ** theo quy định.

---

## 1. Bối cảnh và phạm vi triển khai

Căn cứ theo tiến độ ngày 23/11/2026:
- F05 Backend / AI Service và các bổ sung BL-01, BL-02, BL-03 đã merge vào `main` tại PR #167.
- Xác nhận CI sau merge PR #167: toàn bộ 6/6 jobs hoàn tất thành công (`completed / success`).
- Nhánh mới `feat/mobile-f05-rescheduling` được tạo trực tiếp từ `main` đã cập nhật fast-forward tại commit `c289ff044c0ac7c5bd2f3036b013acac09f986f8`. Working tree được bảo toàn sạch sẽ, không ghi đè bất kỳ thay đổi nào.

---

## 2. Các thành phần Mobile F05 đã triển khai

Tận dụng cấu trúc hiện có, apiClient, Expo Router, React Native UI components, token semantic colors, không tạo thêm dependency thừa:

1. **Contract Types (`apps/mobile/src/types/aiWorkoutReschedule.ts`)**:
   - Định nghĩa đầy đủ các schema theo OpenAPI và `contracts/ai-schemas/f05-workout-reschedule.schema.json`:
     `WorkoutRescheduleApiRequest`, `WorkoutRescheduleApiResponse`, `AiWorkoutRescheduleDetail`, `F05WorkoutReschedulePayload`, `F05RescheduleDestination`, `AcceptRescheduleApiRequest`, `AcceptRescheduleApiResponse`, `RejectRescheduleApiRequest`, `RejectRescheduleApiResponse`.
   - Phân định rõ ràng các trạng thái `RescheduleRecommendationStatus`: `PENDING`, `ACCEPTED`, `REJECTED`, `APPLIED`, `EXPIRED`, `CANCELLED`.
   - Phân định `RescheduleDecisionAuthority`: `STUDENT`, `TRAINER`.

2. **API Client & Xử lý lỗi (`apps/mobile/src/services/aiWorkoutRescheduleApi.ts`)**:
   - `generate()`: Gửi yêu cầu sinh đề xuất dời lịch qua `POST /api/v1/ai/workout-reschedules` kèm `commandKey`, `occurrenceId`, `expectedOccurrenceVersion`, `reason`, `preferredTimeOfDay`.
   - `getDetail()`: Đọc chi tiết đề xuất qua `GET /api/v1/ai/workout-reschedules/{recommendationId}`.
   - `accept()`: Gửi lệnh chấp nhận và áp dụng nguyên tử qua `POST /api/v1/ai/workout-reschedules/{recommendationId}/accept` kèm `commandKey`, `expectedOccurrenceVersion`, `expectedAppointmentVersion`, `expectedAppointmentId`.
   - `reject()`: Gửi lệnh từ chối qua `POST /api/v1/ai/workout-reschedules/{recommendationId}/reject` kèm `decisionNote`.
   - `getWorkoutRescheduleErrorMessage()`: Ánh xạ chuẩn xác mã lỗi từ backend thành hướng dẫn tiếng Việt rõ ràng:
     - `WORKOUT_SCHEDULE_OVERLAP`: Xung đột trùng khung giờ tập.
     - `PLANNED_WORKOUT_VERSION_CONFLICT`: Buổi tập bị trôi phiên bản trên máy chủ.
     - `APPOINTMENT_VERSION_CONFLICT`: Lịch hẹn liên kết bị trôi phiên bản.
     - `WORKOUT_SCHEDULE_COACH_REQUIRED_RESCHEDULE_UNSUPPORTED`: Chặn đổi buổi COACH_REQUIRED khi không có lịch hẹn bao trọn.
     - `APPOINTMENT_COACH_REQUIRED_DEPENDENCY`: Hướng dẫn phối hợp với HLV khi chuyển lịch hẹn rời buổi tập bắt buộc.
     - `AI_RUN_LIFECYCLE_CONFLICT` (expired 30 phút, already applied, idempotency conflict).
     - 403 `HUMAN_COACH`: Phân định thẩm quyền HLV vs Học viên.

3. **Core Modal Component (`apps/mobile/src/features/workout-schedule/components/AiWorkoutRescheduleModal.tsx`)**:
   - Tái sử dụng cho cả Học viên và Huấn luyện viên.
   - Hỗ trợ chọn khung giờ ưu tiên (`MORNING` [09:00], `AFTERNOON` [14:00], `EVENING` [18:00]) và nhập lý do; có ghi chú rõ ràng các mốc này là phỏng đoán heuristic, không phải availability đã xác nhận.
   - Hiển thị đầy đủ thông tin: Tiêu đề, giải thích AI, reasoning, usedData, missingData, giờ ban đầu và giờ đề xuất mới (giờ địa phương, offset UTC, IANA timezone).
   - Kiểm tra thẩm quyền nghiêm ngặt theo `proposal.decisionAuthority` và `coachingMode`:
     - Trong `HUMAN_COACH`: nếu Học viên mở xem, nút "Chấp nhận & Áp dụng" được ẩn/khóa kèm thông báo: "Trong chế độ có Huấn luyện viên (HUMAN_COACH), chỉ Huấn luyện viên mới có quyền phê duyệt áp dụng thay đổi giáo án."
     - Trong `SELF_DIRECTED`: Học viên có quyền phê duyệt áp dụng.
     - Huấn luyện viên trong `HUMAN_COACH`: có quyền phê duyệt áp dụng.
   - Xử lý hết hạn (TTL 30 phút, tính động số phút còn lại theo `proposal.expiresAt`) và xung đột (409): hiển thị cảnh báo và cung cấp nút "Tạo đề xuất mới".
   - Bảo toàn danh tính lệnh (retry identity): Khi gặp timeout mạng (status 0), thao tác thử lại giữ nguyên `commandKey` để backend phát lại an toàn (`replayed: true`), tránh tạo lệnh trùng lặp.
   - Ngăn phản hồi đến muộn (stale response): sử dụng ref theo dõi `activeOccurrenceId`, `activeStudentId` và `targetRecommendationId`, hủy cập nhật state nếu màn hình unmount hoặc đối tượng đổi trước khi request hoàn tất.
   - Hiển thị biên nhận thành công (Applied Receipt) kèm `resultingEntityId` và cờ phát lại `replayed`.

4. **Tích hợp màn hình Học viên (`StudentWorkoutScheduleDetailScreen.tsx`)**:
   - Thêm nút "Đề xuất dời lịch tập (AI)" (`testID="ai-workout-reschedule-button"`) cho các buổi ở trạng thái `SCHEDULED`.
   - Kết nối `AiWorkoutRescheduleModal`, tự động cập nhật cache và điều hướng tới buổi tập thay thế khi áp dụng thành công.

5. **Tích hợp màn hình Huấn luyện viên (`TrainerWorkoutScheduleScreen.tsx`)**:
   - Trong `TrainerOccurrenceCard`, phân định rõ ràng giữa quyền `canContribute` (cho đề xuất thủ công) và `canManage` (cho đề xuất AI dời lịch).
   - Nút "Đề xuất dời lịch (AI)" (`testID="trainer-ai-reschedule-${item.occurrenceId}"`) chỉ hiển thị khi có quyền `MANAGE` đối với `WORKOUT_PLAN`, đúng theo contract backend.
   - Mở modal F05 với ngữ cảnh `isTrainer = true`, `coachingMode = 'HUMAN_COACH'`, cập nhật lại lịch học viên sau khi HLV áp dụng.

6. **Phối hợp Lịch hẹn (`AppointmentScreens.tsx`)**:
   - Trong chi tiết lịch hẹn (`AppointmentDetailScreen`), khi có `plannedWorkoutId`:
     Hiển thị hướng dẫn và nút điều hướng theo vai trò (`testID="open-linked-workout-button"`):
     - Học viên: Điều hướng tới chi tiết buổi tập (`/workouts/schedule/${item.plannedWorkoutId}`) để tạo đề xuất dời lịch AI (F05) mới.
     - Huấn luyện viên: Điều hướng tới khu vực quản lý huấn luyện (`/coaching`), tránh lỗi truy cập màn hình chỉ dành riêng cho Học viên.

---

## 3. Đối chiếu Acceptance / Test IDs

| Test ID / Yêu cầu | Triển khai & Kiểm chứng trên Mobile | Trạng thái |
|---|---|---|
| **T01 / T27** — PROPOSAL lifecycle | Modal hỗ trợ đầy đủ luồng: Generate → Read payload/explanation → Accept/Apply hoặc Reject. Nhận biên nhận `resultingEntityId`, `replayed`. | **VERIFIED (Unit & Component Tests)** |
| **T02** — Quyền theo mode | Trong `HUMAN_COACH`, Student bị chặn nút Accept và hiển thị hướng dẫn quyền HLV. Trainer có quyền `MANAGE` được phép Accept. Trong `SELF_DIRECTED`, Student được phép Accept. | **VERIFIED (Component Tests)** |
| **T03 / T04** — Mất quyền / Scope | Khi mất quyền hoặc 403, modal hiển thị thông báo lỗi thẩm quyền và không cho phép thực hiện thao tác áp dụng. Nút HLV yêu cầu quyền `MANAGE`. | **VERIFIED (Component Tests)** |
| **T05** — Trôi phiên bản / Stale | Khi gặp HTTP 409 (`PLANNED_WORKOUT_VERSION_CONFLICT`, `APPOINTMENT_VERSION_CONFLICT`), modal hiển thị giải thích xung đột và cung cấp nút "Tạo đề xuất mới". | **VERIFIED (Component Tests)** |
| **T06 / T25** — Idempotent retry | Lưu giữ `pendingAcceptCommandKey`, truyền đầy đủ `expectedOccurrenceVersion`, `expectedAppointmentVersion`, `expectedAppointmentId` khi gọi Accept. Khi thử lại sau timeout, giữ nguyên `commandKey`. Phản ánh đúng cờ `replayed` trên biên nhận. | **VERIFIED (Unit & Component Tests)** |
| **T09** — Bảo toàn lịch sử | Buổi cũ được giữ lại; biên nhận hiển thị ID buổi thay thế (`resultingEntityId`). Không can thiệp Actual Workout. | **VERIFIED (Component Tests)** |
| **T10 / BL-03** — Phối hợp lịch hẹn | Kiểm tra containment lịch hẹn; hiển thị hướng dẫn đổi lịch hẹn hai bên trước nếu muốn đổi sang khoảng giờ khác; nút liên kết giữa Lịch hẹn và Buổi tập phân quyền theo vai trò. | **VERIFIED (Component Tests)** |
| **T23** — Hết hạn đề xuất | Kiểm tra TTL 30 phút (`expiresAt`) hiển thị động số phút còn lại; chặn nút Accept khi đã hết hạn và yêu cầu tạo đề xuất mới. | **VERIFIED (Component Tests)** |
| **Stale Async Guard** | Ngăn response đến muộn ghi đè dữ liệu khi đổi Student hoặc rời màn hình thông qua ref guards. | **VERIFIED (Component Tests)** |

---

## 4. Bằng chứng kiểm chứng tự động

Các kiểm tra đã chạy thành công trong phiên làm việc:

1. **Mobile Test Suites (46/46 PASS)**:
   - `aiWorkoutRescheduleApi.test.ts`: **18/18 tests PASS** (tham số API, retry identity, error mapping tiếng Việt, field errors).
   - `AiWorkoutRescheduleModal.test.tsx`: **10/10 tests PASS** (luồng Student SELF_DIRECTED, luồng Trainer HUMAN_COACH, chặn quyền Student HUMAN_COACH, từ chối đề xuất, xử lý xung đột 409, hướng dẫn lịch hẹn liên kết, ngăn phản hồi trễ, thử lại giữ nguyên commandKey sau lỗi mạng, kiểm tra copy disclaimer heuristic và đếm ngược hết hạn).
   - `workoutScheduleChange.test.tsx`: **11/11 tests PASS** (hồi quy đổi lịch thủ công Học viên).
   - `trainerWorkoutScheduleScreen.test.tsx`: **2/2 tests PASS** (hồi quy màn hình lịch HLV và phân định thẩm quyền MANAGE vs CONTRIBUTE cho nút AI Reschedule).
   - `appointmentScreens.test.tsx`: **5/5 tests PASS** (hồi quy màn hình lịch hẹn và điều hướng liên kết buổi tập theo vai trò Student vs Trainer).

2. **Kiểm tra cú pháp & Typecheck**:
   - `npm run typecheck:mobile` (`tsc --noEmit`): **PASS** (0 errors).
   - `npm run lint:mobile` (`expo lint`): **PASS** (0 errors, 0 warnings).

3. **Kiểm tra Hợp đồng & UX docs**:
   - `npm run validate:contracts`: **PASS** (10 schemas, 48 contract fixtures).
   - `npm run validate:ux-docs`: **PASS** (18 required files).

4. **Kiểm tra Git diff**:
   - `git diff --check`: **PASS** (0 whitespace/formatting issues).

---

## 5. Các giới hạn và Gate còn mở

Các giới hạn được giữ nguyên đúng theo thỏa thuận kỹ thuật:
- **COACH_REQUIRED disjoint move**: Vẫn tuân thủ guard bảo vệ của Schedule backend (`APPOINTMENT_COACH_REQUIRED_DEPENDENCY`). Không tự ý bỏ qua supervision.
- **Request status EXPIRED/CANCELLED**: V45 database hiện chỉ hỗ trợ `PENDING/ACCEPTED/REJECTED`. Mobile không tự tạo thêm trạng thái không có trong hợp đồng.
- **Availability đầy đủ & Recovery chuyên môn (D06/OD-3)**: Vẫn dùng slot gợi ý có khung giờ buổi sáng/chiều/tối; các quy tắc hồi phục sinh lý chuyên sâu vẫn tiếp tục **MỞ** chờ Hội đồng chuyên môn phê duyệt.
- **Provider thật**: Kiểm thử sử dụng API contract và simulated provider; việc đánh giá latency/cost trên LLM production vẫn tiếp tục **MỞ**.
- **Sign-off**: Quyết định nghiệm thu toàn bộ tính năng F05/P2-M4 thuộc về Project Owner / Reviewer chính thức.
