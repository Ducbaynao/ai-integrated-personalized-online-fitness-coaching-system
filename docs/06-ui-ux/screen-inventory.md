# Screen Inventory

## Shared and onboarding

| ID | Màn hình | Ghi chú |
|---|---|---|
| SH-01 | Welcome and Sign In | Email/password; social login là future option |
| SH-02 | Register | Common User Account |
| SH-03 | Choose Current Purpose | Self training, coaching, hoặc cả hai |
| SH-04 | Student Onboarding | Profile, schedule, equipment, limitation, goal |
| SH-05 | Trainer Application | Profile, certificates, verification lifecycle |
| SH-06 | Role Switcher | Chỉ khi capability hợp lệ |

## Student Mobile

| ID | Màn hình | Core capability |
|---|---|---|
| ST-01 | Student Home | Today workout, session, goal, nutrition, action required |
| ST-02 | Current Goal | Target, timeline, active training time, progress |
| ST-03 | Goal Proposal Detail | Current vs proposed, reason, accept/reject |
| ST-04 | Goal History | Version, transition, previous goals |
| ST-05 | Plan and Calendar | Planned workout, appointment, schedule adherence |
| ST-06 | Workout Plan Detail | Version, exercise, ownership, assigned by |
| ST-07 | Workout Execution | Set, rep, weight, RPE, notes, performed date |
| ST-08 | Workout Result | Actual vs planned, completion, reschedule context |
| ST-09 | Progress Dashboard | Current Goal/Lifetime, trend, continuity |
| ST-10 | Measurements | Add/history/source/quality |
| ST-11 | Progress Photos | Consent, visibility, comparison |
| ST-12 | Nutrition Today | Target vs actual, completeness, day type |
| ST-13 | Food Log Entry | Text/search/manual |
| ST-14 | Food Photo Confirmation | AI estimate, confidence, correction |
| ST-15 | Nutrition Goal and Proposal | Lifecycle/version/approval |
| ST-16 | AI Recommendation Detail | Evidence, missing data, authority-aware action |
| ST-17 | Coaching Profile | Mode, trainer, period, sharing permission |
| ST-18 | Reschedule Request | Conflict, accept/reject, workout independence |
| ST-19 | Chat | Relationship-scoped messages |
| ST-20 | Notification Center | Deep links and preferences |
| ST-21 | Exercise Library | Browse, search/filter active Exercise catalog |
| ST-22 | Exercise Detail | Instructions, muscle, equipment, variation and media state |

Exercise picker là contextual flow dùng lại ST-21/ST-22 trong Plan Builder, không phải một Exercise Library khác. Historical plan có thể mở Exercise archived/unavailable ở read-only state.

## Trainer Mobile

| ID | Màn hình | Core capability |
|---|---|---|
| TR-01 | Trainer Overview | Sessions, review due, attention queue |
| TR-02 | Student List | Needs attention, on track, inactive, review due |
| TR-03 | Student Coaching Workspace | Overview, program, workout, progress, nutrition, schedule, messages, AI, history |
| TR-04 | Attention Signal Detail | Evidence, severity, acknowledge/resolve |
| TR-05 | Plan Builder | Workout plan and significant/minor change |
| TR-06 | Goal Proposal Builder | Trainer proposes; Student decides |
| TR-07 | Nutrition Proposal Builder | Strategic target proposal |
| TR-08 | Coaching Review | Review data, request data, feedback, plan action |
| TR-09 | Trainer Schedule | Appointment, availability, conflict |
| TR-10 | Reschedule Request | Trainer-initiated flow and revalidation |
| TR-11 | AI Insight Review | Accept/modify/reject for Trainer workflow |
| TR-12 | Student Data Permission | Allowed/limited/not shared states |

## Admin Web

| ID | Màn hình | Core capability |
|---|---|---|
| AD-01 | Dashboard | Action required, AI/system health, platform metrics |
| AD-02 | Users and Roles | Lifecycle, permission, security, audit |
| AD-03 | Trainer Applications | Review queue and decisions |
| AD-04 | Trainer Verification Detail | Verification/activity/certification separated |
| AD-05 | Exercise Library | Draft/active/archived, duplicate/merge |
| AD-06 | Knowledge Publishing | Draft-review-publish-version/archive |
| AD-07 | AI Operations | Runs, errors, latency, validation, outcomes |
| AD-08 | AI Run Detail and Evaluation | Replay/evaluation without business action |
| AD-09 | Moderation Cases | Report lifecycle and audited actions |
| AD-10 | Support Cases | Data correction through validation workflow |
| AD-11 | Privileged Access | Reason, scope, expiry, step-up |
| AD-12 | Data Operations | Measurement ingestion and data-quality issues |
| AD-13 | Jobs and Integrations | Status, retry/cancel subject to policy |
| AD-14 | Audit Log | Immutable, search/export by permission |
| AD-15 | Configuration and Flags | Staged rollout and critical-change audit |

## Ưu tiên triển khai

Phase 1 UI triển khai trước các màn hình shared, ST-01 đến ST-10, ST-12, ST-17 đến ST-20, TR-01 đến TR-10 và các Admin governance cơ bản AD-01 đến AD-06, AD-09, AD-10, AD-14. AI-specific screens có thể triển khai sau nhưng navigation và component boundary phải được chừa sẵn.
