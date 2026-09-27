# Navigation Architecture

## Common account và role switching

Một User có thể có Student Profile, Trainer Profile hoặc cả hai. Role switcher chỉ xuất hiện khi người dùng có nhiều capability đang hoạt động. Trainer Profile chưa verified không được mở các chức năng coaching có authority.

## Student Mobile

Bottom navigation gồm năm destination:

1. `Home`: hôm nay, action required và tóm tắt. Cung cấp lối vào trực tiếp quản lý mục tiêu (`Manage Fitness Goals →`).
2. `Plan`: workout plan, schedule và workout execution.
3. `Progress`: current goal, lifetime, measurement và progress photo.
4. `Nutrition`: daily target, food log và nutrition progress.
5. `Profile`: account, coaching, data sharing và preferences.

Stack navigation hỗ trợ các route chuyên biệt cho Student Goal Management:

- `/(app)/goals`: màn hình quản lý Goal hiện tại (`CurrentGoalScreen` - ST-02) với các tab Overview, Targets, History (Versions & Transitions) và Proposals.
- `/(app)/goals/[goalId]`: màn hình xem chi tiết một Goal cụ thể theo identifier.
- `/(app)/goal-proposals/[proposalId]`: màn hình xem chi tiết so sánh và quyết định Accept/Reject đề xuất mục tiêu (`GoalProposalDetailScreen` - ST-03).

Chat, notification và AI Assistance mở theo icon hoặc từ context; không biến AI thành tab/coaching mode riêng.

## Trainer Mobile

Bottom navigation:

1. `Overview`: session hôm nay, review due và attention queue.
2. `Students`: danh sách và Student Coaching Workspace.
3. `Schedule`: appointment, conflict và reschedule request.
4. `Messages`: conversation theo coaching relationship.
5. `Profile`: availability, verification, capacity và role switcher.

## Admin Web

Sidebar theo permission, không hiển thị capability bị cấm:

- Dashboard
- Users and Roles
- Trainers and Verification
- Moderation and Support
- Content and Exercise
- Knowledge
- AI Operations
- Data Operations and Integrations
- Notifications Jobs and System Health
- Security and Audit
- Configuration and Feature Flags

## Deep link và context

Notification và deep link phải điều hướng đến đúng resource và scope:
- Mọi route thuộc `/(app)/goals` và `/(app)/goal-proposals/[proposalId]` được bảo vệ bởi guard `canAccessFitnessGoals(user)` (yêu cầu capability `hasStudentProfile`). Nếu User không có Student Profile, hệ thống chuyển hướng về `/(app)`.
- Proposal notification dẫn trực tiếp đến `/(app)/goal-proposals/[proposalId]`. Nếu proposal không tồn tại hoặc đã xử lý, màn hình hiển thị trạng thái tương ứng kèm nút quay lại an toàn.
- Nếu actor không còn quyền truy cập, hiển thị permission state thay vì fallback sang dữ liệu khác.

## Navigation invariants

- Coaching Mode không phải destination cố định; nó là context thể hiện trong Profile, Goal và Plan.
- Goal detail luôn cho phép xem version và transition history.
- Student Workspace là hub của Trainer, không bắt PT ghép dữ liệu từ nhiều màn hình rời rạc.
- Admin action nhạy cảm phải giữ resource context, reason và audit reference trong flow.

