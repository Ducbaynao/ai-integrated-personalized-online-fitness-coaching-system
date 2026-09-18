# UX Requirement Traceability Matrix

| Nghiệp vụ | Bề mặt UI bắt buộc | Actor | Tài liệu chi tiết |
|---|---|---|---|
| Common account và multi-role | Onboarding, role switcher, Become a Trainer | Student/Trainer | `student/student-screens.md` |
| Trainer verification | Application status, review queue, detail | Trainer/Admin | `trainer/trainer-screens.md`, `admin/admin-screens.md` |
| Coaching Period | Coaching status, history, transition | Student/Trainer | Student và Trainer screens |
| Data sharing permission | Permission summary và editor | Student/Trainer | Student flows |
| Fitness Goal | Goal dashboard, detail, proposal, version history | Student/Trainer | Student screens |
| Workout Plan | Plan list/detail/builder/version summary | Student/Trainer | Student và Trainer screens |
| Planned vs Actual Workout | Calendar, session detail, execution result | Student/Trainer | Student flows |
| Schedule change | Request, conflict, accept/reject | Student/Trainer | Student và Trainer flows |
| Body measurement | Add measurement, history, provenance, quality | Student/Trainer | Student screens |
| Progress | Current Goal và Lifetime tabs | Student/Trainer | Student screens |
| Nutrition lifecycle | Goal, target, proposal, daily target, actual | Student/Trainer | Student screens |
| Nutrition AI | Photo result, confidence, confirmation/correction | Student | Student screens |
| AI Assistance | Recommendation detail, evidence, missing data, review | Student/Trainer | Components và actor screens |
| Attention Signal | Queue, filter, signal detail, resolution | Trainer/Admin | Trainer/Admin screens |
| Chat | Conversation tied to relationship/context | Student/Trainer | Navigation và screen inventory |
| Admin governance | Permission-scoped modules and action workflows | Admin | Admin screens |
| Privileged access | Reason, scope, expiry, step-up authentication | Admin | Admin flows |
| Audit | Immutable event detail/search/export by permission | Admin | Admin screens |

Khi thêm chức năng mới, cập nhật ma trận này trước hoặc cùng pull request với màn hình mới.
