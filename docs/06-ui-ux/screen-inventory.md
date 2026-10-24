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
| ST-05 | Plan and Calendar | Route `/(app)/workout-plans` là Plan hub; từ chi tiết kế hoạch ACTIVE, `/(app)/workout-plans/[planId]/schedule` cho Student chọn session và xác nhận batch all-or-nothing qua POST `/planned-workout-batches`. `/(app)/workouts/schedule` là Student Planned Workout calendar với backend-derived START/SKIP và occurrence ID/version thật. Detail `/(app)/workouts/schedule/[occurrenceId]` hiển thị lineage, RESCHEDULED/CANCELLED và Student Đổi lịch/Hủy trực tiếp chỉ theo backend `availableActions`; `/(app)/workout-schedule-proposals` là đề xuất xác nhận cả đợt lịch; `/(app)/workout-change-proposals` và detail là đề xuất Trainer đổi/hủy **một occurrence**, Student ACCEPT/REJECT PENDING với proposal version thật. |
| ST-06 | Workout Plan Detail | Routes `/(app)/workout-plans/[planId]`, `/history`, `/edit`; owner/read context, sessions/prescriptions, immutable versions, SELF_DIRECTED authoring and explicit Trainer-delivered successor |
| ST-07 | Workout Execution | Route `/(app)/workouts/current`; authoritative in-progress execution, set log, substitution, RPE/note và complete/abort. START/SKIP được mở từ ST-05 calendar bằng occurrence identity/version. |
| ST-08 | Workout Result | Routes `/(app)/workouts/history`, `/(app)/workouts/[executionId]`; planned/performed time, backend-derived terminal status và legacy missing-source state. |
| ST-09 | Progress Dashboard | Current Goal/Lifetime, trend, continuity |
| ST-10 | Measurements | Add/history/source/quality |
| ST-11 | Progress Photos | Consent, visibility, comparison |
| ST-12 | Nutrition Today | B07 basic route `/(app)/nutrition`: explicit local day/IANA timezone, completeness and nullable actual nutrient totals; target preview lives separately at `/(app)/nutrition/strategy` and never redefines actual intake |
| ST-13 | Food Log Entry | Same route: private USER_REPORTED food per serving, unquantified description, or a published USDA Foundation catalog revision with explicit supported gram/serving/ml evidence; all-unknown private food stays DRAFT. Catalog log pins its revision and nutrient snapshot; versioned correction/removal and explicit day confirmation remain Student-owned. |
| ST-14 | Food Photo Confirmation | AI estimate, confidence, correction |
| ST-15 | Nutrition Goal and Proposal | Route `/(app)/nutrition/strategy`: Student NEW_GOAL/CHANGE_TARGET with explicit effective date/timezone, target versions, pause/resume/end, Trainer proposal ACCEPT/REJECT, day-type preview/selection and version-bound override. Frozen nutrient/day correction shows original/current target and append-only reason/actor history; comparison uses corrected target, not altered actual. 409 requires review again. |
| ST-16 | AI Recommendation Detail | Evidence, missing data, authority-aware action |
| ST-17 | Coaching Profile | Route `/(app)/coaching`; relationship lifecycle, current period, history list, and Student-owned sharing permission management |
| ST-18 | Appointment request and reschedule | Local candidate routes `/(app)/appointments`, `/new`, `/requests/[requestId]`, `/[appointmentId]` and `/[appointmentId]/reschedule`; explicit timezone/offset, bilateral decision, reasoned cancel and separate Planned Workout lifecycle. Backend remains authority for interval, version and current Coaching Period. |
| ST-19 | Chat | `/(app)/chat`, `/(app)/chat/[conversationId]`, `/(app)/chat/new`: one text conversation per HUMAN_COACH period, Student reads own prior periods; recipient explicitly acknowledges DELIVERED/READ. Trainer sees only current authorized period. REST is authoritative and WebSocket only invalidates. |
| ST-20 | Notification Center | `/(app)/notifications`: mixed Chat, lifecycle and B08 local-candidate Attention/Review notices with generic preview only, explicit read, pagination and authority-checked destination reads. Historical 100-day backfill makes signals but no notice. No push/email, quiet-hours or preferences. |
| ST-21 | Exercise Library | Browse, search/filter active Exercise catalog |
| ST-22 | Exercise Detail | Route `/(app)/exercises/[exerciseId]`; instructions, muscle, equipment, variation and media state |
| ST-23 | Review and Attention | `/(app)/attention`: Student explicitly confirms an IANA timezone for human-period review cycles, reads own review tasks and rule signals across periods, acknowledges a completed review, and reads signal history. Local candidate reads explicit UNKNOWN / NO_QUALIFYING_GAP / DETECTED status for LOW return-after-100-calendar-days evidence from two COMPLETED workouts; no GET materialization and no automatic Plan change. |

Exercise picker là contextual flow dùng lại ST-21/ST-22 trong Plan Builder, không phải một Exercise Library khác. Historical plan có thể mở Exercise archived/unavailable ở read-only state.

**B06 automatic recurrence (local candidate, chưa merge):** ST-05 mở danh sách `/planned-workout-series`, detail `/{seriesId}` và form Student từ `/workout-plans/[planId]/series`; TR-03 mở danh sách `/coaching/[relationshipId]/planned-workout-series`, form `/new` và detail `/{seriesId}`. Form chọn thứ Hai, IANA timezone, session từ published version và local start/end; không tự gắn Appointment. Detail hiển thị outcome từng kỳ, Student quyết định đề xuất hoặc dừng, Trainer chỉ rút PENDING khi còn quyền. DST gap/ambiguous và overlap là kết quả có thể đọc lại, không phải lịch tập được tạo.

## Trainer Mobile

| ID | Màn hình | Core capability |
|---|---|---|
| TR-01 | Trainer Overview | Sessions, review due, attention queue; B03 pending relationships are available from `/(app)/coaching` |
| TR-02 | Student List | Needs attention, on track, inactive, review due |
| TR-03 | Student Coaching Workspace | B03 relationship context at `/(app)/coaching/[relationshipId]`; B04 Program context at `/(app)/coaching/[relationshipId]/program` lists only backend-visible current/history plans and removes Student plan cache on authority loss. B06 Trainer reads confirmed occurrences at `/(app)/coaching/[relationshipId]/workout-schedule` with current WORKOUT_PLAN VIEW+, without START/SKIP or execution/history access. Trainer proposal list/detail use `/(app)/coaching/[relationshipId]/schedule-proposals`; PENDING withdrawal requires current CONTRIBUTE. Create route `/new` uses published source version/session and explicit Trainer-selected IANA timezone, local times and offsets; Student sees these before an all-or-nothing decision. B07 read-only nutrition day is `/(app)/coaching/[relationshipId]/nutrition`, authorized by current NUTRITION_LOGS VIEW and concealed after authority loss. |
| TR-04 | Attention Signal Detail | `/(app)/coaching/[relationshipId]/attention`: current ACTIVE HUMAN_COACH period only; rule evidence/severity/history, ACK, RESOLVE or DISMISS with reason and version. Workout evidence additionally requires current WORKOUT_HISTORY VIEW. |
| TR-05 | Plan Builder | Trainer create/edit DRAFT at relationship/Student-scoped routes; detail/version history and significant complete-snapshot publication under `/(app)/students/[studentId]/workout-plans/[planId]`; occurrence-scoped minor change remains B05 |
| TR-06 | Goal Proposal Builder | Trainer proposes; Student decides |
| TR-07 | Nutrition Proposal Builder | Route `/(app)/coaching/[relationshipId]/nutrition-strategy`: current NUTRITION_GOAL VIEW read; CONTRIBUTE+ proposal only, Student publishes |
| TR-08 | Coaching Review | Same period-scoped route: Trainer completes an OPEN 14-day review task with summary, evidence references and explicitly missing/unknown data; Student acknowledgment is separate and optional. Review does not mutate strategy. |
| TR-09 | Trainer Schedule | Same local-candidate Appointment routes as Student, opened from the current Student relationship; create/request/decision/cancel remain backend-authorized, with no implicit Planned Workout mutation or automatic recurrence. |
| TR-10 | Reschedule Request | Từ TR-03/Trainer calendar mở `/(app)/coaching/[relationshipId]/change-proposals`: Trainer VIEW+ xem proposal đổi/hủy một occurrence do chính mình tạo, lọc/trang/detail; CONTRIBUTE+ chọn occurrence thật, gửi RESCHEDULE với IANA timezone/local start-end/UTC offsets rõ ràng hoặc CANCEL với lý do tùy chọn, và rút PENDING bằng proposalVersion thật. Backend recheck quyền, seal, Appointment, thời gian và xung đột; Student ACCEPT/REJECT trên Mobile thuộc checkpoint tiếp theo. |
| TR-11 | AI Insight Review | Accept/modify/reject for Trainer workflow |
| TR-12 | Student Data Permission | Route `/(app)/coaching/[relationshipId]/sharing`; authoritative allowed/denied/expired/revoked/not-configured states and VIEW/CONTRIBUTE/MANAGE levels |

## Admin Web

| ID | Màn hình | Core capability |
|---|---|---|
| AD-01 | Dashboard | Action required, AI/system health, platform metrics |
| AD-02 | Users and Roles | Lifecycle, permission, security, audit |
| AD-03 | Trainer Applications | Review queue and decisions |
| AD-04 | Trainer Verification Detail | Verification/activity/certification separated |
| AD-05 | Exercise Library | List/detail và route create/edit Draft; active/archived read-only; duplicate/merge |
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
| AD-16 | Governed Nutrition Catalog | `/nutrition/catalog`: Admin with CATALOG_MANAGE selects a small pinned USDA Foundation subset, reviews source/portion evidence, publishes, revises, and retires with reason and audit; private Student foods never appear here. |

## Ưu tiên triển khai

Phase 1 UI triển khai trước các màn hình shared, ST-01 đến ST-10, ST-12, ST-17 đến ST-20, TR-01 đến TR-10 và các Admin governance cơ bản AD-01 đến AD-06, AD-09, AD-10, AD-14. AI-specific screens có thể triển khai sau nhưng navigation và component boundary phải được chừa sẵn.
