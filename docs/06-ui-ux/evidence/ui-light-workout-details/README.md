# Light UI — workout detail, form và modal

Base main `5049b161df5692344b418daaceeaa062792fbcad`; branch `codex/ui-light-details-forms`. Giữ nguyên #185/#186, không có delta main mới lúc bắt đầu.

| Route / component thật | Đợt này |
|---|---|
| `/workout-plans/[planId]` — WorkoutPlanDetailScreen | Đồng bộ header/summary/actions/state; CTA lịch tập chính, edit/version/successor phụ; modal lifecycle và successor có safe area, keyboard avoidance và cuộn. Giữ owner/current/history/version và command identity. |
| `/students/[studentId]/workout-plans/[planId]` — TrainerWorkoutPlanDetailScreen | Đồng bộ detail, version selector và modal xác nhận; giữ MANAGE/owner/current-period gates. |
| WorkoutPlanVersionContent (nội dung hiện tại/lịch sử trong hai màn detail) | Nhóm từng ngày/session và prescription bằng card, heading/icon; giữ toàn bộ dữ liệu, formatter, canonical/unavailable warnings và null/zero. Không thêm route ngày tập. |
| `/workouts/schedule/[occurrenceId]` — StudentWorkoutScheduleDetailScreen | Đồng bộ detail, lineage/actions, reschedule/cancel form/preview/state. Giữ availableActions, UTC offset/DST/context, body/key/retry và appointment independence. |
| `/workouts/current`, `/workouts/[executionId]` — WorkoutExecutionScreen | Đồng bộ summary/exercise/set cards; nhóm ô số có tabular numerals, unit và helper rõ; selected radio có icon + checked state; lưu hiệp có spinner/disabled/error. Modal thay bài/kết thúc dùng shared controls, bounded width, safe area và cuộn. Không đổi save/terminal/substitution logic. |
| `/workout-plans/new`, `/workout-plans/[planId]/edit`; Trainer `/coaching/[relationshipId]/workout-plans/new`, `/students/[studentId]/workout-plans/[planId]/builder` | WorkoutPlanBuilderScreen: nhóm Plan → Session → Prescription, ô số co giãn, multiline, reorder ghost, delete secondary danger, Save primary. Giữ validation/toInput, planned values, version/reason/key và guards. |
| ExercisePicker + variation modal của builder/execution | Footer dùng AppButton, giữ selected/excluded/interaction-disabled gates và typed ExerciseSummary; tên dài không giới hạn hai dòng. Variation row dùng NavigationAction, không nested press. Catalog/filter internals giữ nguyên. |
| Trainer occurrence detail / Actual Workout execution | Không có route detail/execution độc lập cho Trainer trong source hiện hành. Giữ calendar read-only đã đồng bộ ở #186; không tạo route/quyền Student cho Trainer. |

Shared: dùng đúng AppButton/AppCard/AppIcon/NavigationAction hiện có; chỉ thêm hai icon reorder up/down, không đổi tokens/theme/API hoặc legacy wrapper toàn app. 44 px tối thiểu cho radio, AppButton giữ 48 px, NavigationAction giữ tối thiểu 44 px; UI state vẫn có chữ và semantics, không chỉ màu. Giữ Be Vietnam Pro/face mapping và copy có sẵn, gồm “reps”.

Reference: tái dùng context thật từ #185: Workout plans `2835:719`, My Workout `2785:86`, Notification `2763:61` trong file SuperFit `ONFwRWdC9bj4Z6mPSe6yr4`. Frame detail `2817:1426` chỉ có audit sẵn trong superfit-reference.md; không tuyên bố đã mở/đối chiếu frame đó trong đợt này. Form, set editor và modal là thiết kế bổ sung theo pattern.

**Preview, ảnh mới, 360/390/430 px, form/modal scroll/keyboard visual: NOT RUN.** Browser inventory trả về `Local browser access is unavailable for this chat`; không khởi động fixture/runtime/Backend/Docker/emulator để thay thế. Test renderer không chứng minh visual PASS hoặc Android/backend E2E.

Self-review: logic function ngoài JSX và 142 callback của 7 feature files giữ nguyên; callback `ExecutionState.onAction` được chuyển nguyên expression sang AppButton.onPress. Native event-shaped tests qua boundary của control được render kiểm set payload, finish/abort, draft submit và pending/disabled dispatch. Picker assertions vẫn giữ excluded/authority/payload; selector đổi sang role của rendered control, busy=false được ghi tường minh. Log FAIL và review JSON giữ cục bộ ngoài repo; kết quả checks/CI cuối nằm trong PR.

Follow-up có sẵn ngoài patch: mở `/workouts/[executionId]` với status terminal và có sets; source SetEditor vẫn render input/nút Lưu, trong khi inProgress chỉ gate add/substitute/finish. Focused fixture terminal cũng vẫn có input. Đây là điểm UX read-only cần kiểm riêng; chưa kiểm mutation Backend thật và không khẳng định vượt quyền/sửa được dữ liệu. Không đổi lifecycle/command semantics trong patch layout này.

Đợt UI tiếp: Goal/Nutrition/Coaching detail/form/modal; các modal AI proposal/adjustment và schedule batch/recurrence giữ nguyên. Chưa dark polish, dynamic type/device keyboard/accessibility QC hoặc release acceptance. Không tự chuyển sang đợt khác.
