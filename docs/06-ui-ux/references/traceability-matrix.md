# UX Requirement Traceability Matrix

| Nghiệp vụ | Bề mặt UI bắt buộc | Actor | Tài liệu chi tiết |
|---|---|---|---|
| Common account và multi-role | Onboarding, role switcher, Become a Trainer | Student/Trainer | `student/student-screens.md` |
| Trainer verification | Application status, review queue, detail | Trainer/Admin | `trainer/trainer-screens.md`, `admin/admin-screens.md` |
| Coaching Period | Coaching status, history, transition | Student/Trainer | Student và Trainer screens |
| Data sharing permission | Permission summary và editor | Student/Trainer | Student flows |
| Fitness Goal | Goal dashboard, detail, proposal, version history | Student/Trainer | Student screens |
| Exercise Library | Browse/detail/picker; active, archived, unavailable and canonical mapping states | Student/Trainer/Admin | `screen-inventory.md`, `component-specifications.md`, `admin/admin-screens.md` |
| Workout Plan | Student current/list/detail, SELF_DIRECTED authoring and successor; TR-03 Program authority context; TR-05 Trainer-owned DRAFT builder, activation/lifecycle, immutable history and significant version publication | Student/Trainer | `screen-inventory.md`, `navigation-architecture.md`, `student/student-screens.md`, `trainer/trainer-screens.md` |
| Planned vs Actual Workout | Student tự xác nhận batch nhiều buổi từ plan ACTIVE tại `/(app)/workout-plans/[planId]/schedule`, rồi đọc occurrence thật tại `/(app)/workouts/schedule` và mở B05 START/SKIP theo `availableActions`; Trainer TR-03 đọc lịch đã xác nhận tại `/(app)/coaching/[relationshipId]/workout-schedule` khi có VIEW+ hiện hành, không có execution actions/history; Current và history/detail là Actual Workout riêng | Student/Trainer | Student/Trainer flows, `navigation-architecture.md` |
| Trainer schedule proposal decision | Student xem danh sách/detail với tên nullable từ API tại `/(app)/workout-schedule-proposals`, xác nhận ACCEPT/REJECT toàn bộ; Trainer xem danh sách/detail theo Student tại TR-03, VIEW chỉ đọc, CONTRIBUTE có thể tạo/rút PENDING bằng supervision read metadata đã có. | Student/Trainer | `screen-inventory.md`, `navigation-architecture.md`, Student/Trainer flows |
| Schedule change | Student direct detail/đổi lịch/hủy từng occurrence tại `/(app)/workouts/schedule/[occurrenceId]`, lineage và conflict review; Trainer TR-03 tạo một proposal RESCHEDULE/CANCEL, xem list/detail và rút PENDING tại `/(app)/coaching/[relationshipId]/change-proposals`; Student xem list/detail và ACCEPT/REJECT PENDING tại `/(app)/workout-change-proposals`. | Student/Trainer | `screen-inventory.md`, `navigation-architecture.md`, Student và Trainer flows |
| Body measurement | Add measurement, history, provenance, quality | Student/Trainer | Student screens |
| Progress | Current Goal và Lifetime tabs | Student/Trainer | Student screens |
| Nutrition lifecycle | V39/V40 basic and strategy; V41 catalog uses Admin-selected pinned USDA Foundation revision and per-portion conversion evidence, while historical actual stays snapshotted. Student frozen target correction overlays current comparison without rewriting original target/actual; Trainer sees correction history only with current NUTRITION_GOAL VIEW. | Student/Trainer/Admin | ST-12/ST-13/ST-15, AD-16, B07 decision record and OpenAPI |
| Nutrition AI | Photo result, confidence, confirmation/correction | Student | Student screens |
| AI Assistance | Recommendation detail, evidence, missing data, review | Student/Trainer | Components và actor screens |
| Attention Signal | Queue, filter, signal detail, resolution | Trainer/Admin | Trainer/Admin screens |
| Chat | Conversation tied to relationship/context | Student/Trainer | Navigation và screen inventory |
| Admin governance | Permission-scoped modules and action workflows | Admin | Admin screens |
| Privileged access | Reason, scope, expiry, step-up authentication | Admin | Admin flows |
| Audit | Immutable event detail/search/export by permission | Admin | Admin screens |

Khi thêm chức năng mới, cập nhật ma trận này trước hoặc cùng pull request với màn hình mới.
