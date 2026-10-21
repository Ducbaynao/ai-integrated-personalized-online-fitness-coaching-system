# B06 Schedule and Planned Workout Occurrence Decisions

## 2026-10-20 — Coaching Appointment and supervision decisions (APPROVED)

Person B approved the following product scope for the combined B06/B08 checkpoint. This is a **decision record**, not evidence that the Appointment contract, A01 schema change, backend commands, Mobile screens, or notification events are executable. Existing Planned Workout and B05 START/SKIP implementations remain the runtime baseline until their guards are reconciled and tested.

1. Coaching Appointment and Planned Workout are independent lifecycles with an explicit ID link. An Appointment change or cancellation never silently creates, moves, or cancels a Planned Workout occurrence.
2. Either Student or Trainer in the current Coaching Period can request an Appointment; the other party accepts or rejects. Pause/end blocks new commands. The Student retains own history; a former Trainer cannot read live Appointment data. Authority is rechecked at decision time and before an idempotent replay result is disclosed.
3. Store IANA timezone and explicit UTC offsets. Acceptance rechecks both Student and Trainer interval conflicts in one transaction. Missing legacy time data is an explicit error, never an inferred interval.
4. A `COACH_REQUIRED` Planned Workout may be confirmed or executed only with a confirmed Appointment matching Student, Trainer, Coaching Period and the contract-published qualifying interval. A linked Appointment change/cancellation must not make the occurrence invalid. No implicit conversion to self-performed training is allowed.
5. This flow changes one specific Appointment. Automatic recurrence remains unfinished B06 work.

### 2026-10-20 qualifying and change addendum (APPROVED)

6. For a new `COACH_REQUIRED` confirmation or START, only a `CONFIRMED` Appointment qualifies; `COMPLETED` is history only. The Appointment must match Student, Trainer and the **current** Coaching Period. The complete workout interval `[plannedStartAt, plannedEndAt)` must fit inside the Appointment interval `[startAt,endAt)`. A missing end or identity never qualifies, including for legacy rows. At START, PostgreSQL time must be inside the Appointment interval, with no grace period. This rule does not retroactively make a legacy link qualifying or backfill missing fields.
7. Moving a confirmed Appointment is a bilateral reschedule request; the other party ACCEPTs or REJECTs. The request and its ACCEPT must be before the Appointment's current `startAt` according to PostgreSQL time. Either party may cancel before that start with a reason and notification to the other party, **except** when a `COACH_REQUIRED` occurrence still depends on it. Neither command changes the occurrence implicitly. No reschedule/cancel command is available at or after start. Completion and no-show are not approved for new runtime.

The previously approved rule that an occurrence with **any** linked Appointment cannot use the first-flow direct reschedule/cancel command remains in force until an Appointment-coordination command explicitly handles both invariants. A V5 foreign key or V16 trigger alone does not establish qualifying actor, period or interval. On merged main, V16 and B05 still accept `COMPLETED` without pair, period or containment. The unmerged V45 candidate and Schedule-owned B05 query in this worktree narrow START to decision 6; this worktree also extends **Student direct batch confirmation only** with an explicit `appointmentId` per `COACH_REQUIRED` item. Workout locks and validates the separate confirmed Appointment through a Schedule-owned port and links it to the newly inserted occurrence in the same transaction; failure rolls back the batch and link. Trainer schedule proposals still reject this supervision mode because V32 does not persist an Appointment ID. SKIP is unchanged. The A01 Schema Change Request records the schema review gate.

An initial review-only YAML draft was superseded and is excluded from the PR; `contracts/openapi/openapi.yaml` is the sole executable Appointment contract. This worktree implements the matching Schedule-owned commands/read path and Mobile entry as **local, unmerged changes**, not deployment evidence. V45 remains a forward migration **candidate** awaiting A01 independent schema review before merge. Focused integration tests, rather than YAML lint or the decision record alone, determine which runtime invariants have been verified. Automatic recurrence and Appointment completion/no-show remain outside this checkpoint.

## 1. Decision record

- **Checkpoint:** `B06-PLANNED-OCCURRENCE-DOMAIN-DECISIONS`.
- **Status:** **APPROVED**.
- **Approval date:** 2026-10-10.
- **Decision owner:** Person B / Coaching Experience Owner.
- **Scope:** Product and domain decisions for assigning published Workout Plan sessions to dates and times, Trainer scheduling proposals, Planned Workout materialization, and the read entry required by B05 START/SKIP.
- **Not implementation evidence:** Approval of this record does not by itself prove implementation. At the original approval boundary, the later `B06-PLANNED-OCCURRENCE-CONTRACT` checkpoint declared the first-flow OpenAPI surface while the proposal workflow, batch materializer, persistence, backend service, and Mobile flow remained unimplemented. Later sections distinguish the subsequently delivered runtime checkpoints.

These decisions extend the existing B04 and B05 boundaries. Workout Plan activation remains separate from scheduling. Planned Workout and Actual Workout remain separate. Appointment and Planned Workout retain independent lifecycles.

## 2. Confirmed implementation baseline

At approval time, the repository provides the following executable foundation:

- V5 stores Workout Plans, versions, sessions, Planned Workouts, recurring schedules, Appointments, reschedule requests, and appointment history.
- V16 enforces supervision and accepted-reschedule integrity at the database boundary.
- V28 gives a materialized Planned Workout an optimistic `version` and immutable source session identity. Publishing a new plan version does not remap an existing occurrence.
- V29 and V30 implement the B05 execution seal and frozen execution snapshot. START/SKIP require the occurrence identity and optimistic occurrence version.
- Workout owns the `WorkoutSchedulingUseCase` boundary for materialize/reschedule collaboration with B06.
- The Mobile navigation and screen inventory reserve ST-05 for the B06 Planned Workout calendar and explicitly prevent plan, plan-version, or session identity from substituting for occurrence identity/version in START/SKIP.

The following runtime capabilities are not implemented at this baseline:

- no production implementation or caller of `WorkoutSchedulingUseCase.materialize`;
- no batch scheduling or Trainer proposal aggregate;
- no production backend for the declared Student/Trainer Planned Workout calendar reads or scheduling commands;
- no automatic materializer, rolling horizon, or system actor;
- no B06 Mobile calendar, proposal, confirmation, reschedule, or cancel flow.

Raw SQL fixtures that insert `planned_workouts` establish test data only. They do not define the product materialization trigger or authority.

## 3. Approved scheduling flow and authority

1. Scheduling is a separate command after a Workout Plan has been activated. Activation never implicitly creates Planned Workout occurrences.
2. A Student schedules multiple plan sessions as one batch and is the only actor who confirms the Student's schedule.
3. In a current effective Coaching Period, a Trainer may propose a batch only when current `WORKOUT_PLAN` authority is at least `CONTRIBUTE`.
4. A Trainer may read the Student's intended schedule, including a pending proposal or confirmed Planned Workout schedule exposed by B06, only when current `WORKOUT_PLAN` authority is at least `VIEW`.
5. Plan authority never allows a Trainer to confirm a schedule on behalf of the Student.
6. A Trainer proposal remains a proposal and creates no `planned_workout` rows.
7. The Student accepts the complete proposal or rejects it. The first flow has no partial acceptance or in-place proposal editing; a changed schedule is submitted as a new batch.
8. The proposing Trainer may withdraw a proposal while it is pending.
9. The first version has no fixed calendar expiry for a proposal. Acceptance nevertheless rechecks current actor authority, Coaching Period, plan, source version, and conflicts. A proposal that is no longer valid cannot be materialized.
10. Confirmation is all-or-nothing: the complete batch is persisted in one transaction or no occurrence is created. Validation errors must identify the item or items that require correction.

Authority is evaluated by the backend at the action boundary. Role, Trainer profile, plan authorship, relationship existence, or a historical grant alone is insufficient.

## 4. Approved plan source and batch mapping

1. At Student confirmation, the backend selects the published plan version effective at the confirmation boundary. Every item in that batch uses the same selected plan version.
2. A Trainer proposal is bound to its proposed source plan/version. If the effective version changes before Student acceptance, the proposal is not automatically remapped and cannot be materialized; a new batch is required.
3. Every created occurrence retains its original plan-session and therefore plan-version source identity. Later publication does not rewrite or migrate it.
4. The Student selects the batch's starting week. The system suggests calendar dates from each session's `weekNumber`, `dayNumber`, and `sequenceNumber`.
5. The Student selects the start time and may move a suggested date within the corresponding plan week.
6. A plan session may appear at most once in the same batch and plan week. Repeating that plan week requires a new batch.
7. Every item has a start and end. Template duration may suggest the end but does not silently become an authoritative scheduled end. The backend requires `end > start`.
8. Every confirmed start must be strictly in the future at the authoritative confirmation boundary and no more than 90 days after that boundary.

The effective-version selection, item validation, occurrence inserts, receipts, and audit records belong to one transaction. The implementation must lock and recheck the required Workout and authority boundaries rather than trusting a version or capability inferred by the client.

## 5. Approved time, identity, and conflict semantics

1. The Student confirms one IANA timezone for the batch.
2. The system preserves the entered local date/time intent, IANA timezone, selected UTC offset where required, and converted instant.
3. A later User timezone change does not move an existing occurrence automatically.
4. A nonexistent local time in a daylight-saving transition is rejected. For an ambiguous local time in Student direct confirmation, the Student selects an explicit offset. For a Trainer proposal, the Trainer selects the explicit offset and the Student confirms it as part of the whole-proposal decision.
5. Replaying the same logical command with the same `commandKey` returns the same committed outcome and creates no additional occurrence.
6. Different command keys must not create the same plan session at the same planned-start instant. The same source session may be scheduled at another valid time or in another valid plan-week batch.
7. Items in one batch must not overlap for the Student.
8. A new item must not overlap another still-effective Planned Workout occurrence for that Student. Overlap conflicts with a known end identify the affected item; the unknown-end conflict below is batch-level.
9. Appointment and Planned Workout never change each other's lifecycle implicitly.
10. The first scheduling flow does not confirm a `COACH_REQUIRED` session. Support remains blocked until a later contract can require and validate the appropriate Planned Workout–Appointment link and qualifying Appointment.

**APPROVED addendum (Person B, 2026-10-12) — Trainer proposal timezone:** The proposing Trainer explicitly selects one IANA timezone for the proposal. The Student sees that timezone and each item's local start/end and chosen UTC offsets before deciding to ACCEPT or REJECT the entire proposal. This proposal timezone is not inferred from the Student profile, Trainer profile, device, `/users/me`, or an older occurrence. Student acceptance confirms the timezone supplied in the proposal; the backend remains authoritative for validation/materialization. This policy does not grant Trainer read access to Student profile timezone. The Mobile create form implements this input/display rule; it does not alter the existing backend mutation contract.

For the first flow, `SCHEDULED` is the schedule-occupying occurrence status in the current executable lifecycle, including a `SCHEDULED` occurrence whose execution has started. `COMPLETED`, `ATTEMPTED`, `MISSED`, `SKIPPED`, `RESCHEDULED`, and `CANCELLED` are terminal and do not block under this unknown-end rule. If the Student has any schedule-occupying occurrence with `planned_end_at = NULL`, direct batch confirmation or ACCEPT of a Trainer proposal rejects the entire batch with `WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN` (409); no occurrence is created. This is a batch-level error, not an item error, and must not reveal the existing occurrence's identity or timing to an unauthorized actor. Checking overlap with only an individual new item cannot establish safety when the existing end is unknown. The backend must not infer an end from the template, treat null as zero or an unbounded interval, or silently repair legacy data. The path to unblock is a valid terminal lifecycle transition or a later designed, audited process to verify and update the end. This policy does not assert that production contains such rows. The later Student batch materializer implements this guard, as described in section 12.

This first-flow approval does not define a broader Appointment conflict matrix. The first-flow contract preserves Appointment independence without implicit Appointment mutations; the later reschedule/cancel policy is recorded separately in section 18.

## 6. Approved behavior after confirmation

1. Reschedule and cancel are separate commands. They preserve occurrence history and source plan/session/version identity.
2. The Student makes the final reschedule/cancel decision. A Trainer with a current effective Coaching Period and current `WORKOUT_PLAN` authority of at least `CONTRIBUTE` may propose a change but cannot decide for the Student.
3. Once execution has started, the B05 execution seal prevents schedule changes.
4. Publishing a plan version does not change an existing occurrence. Pausing or archiving a plan does not delete occurrence or execution history.
5. The backend derives `availableActions` from actual occurrence state, execution seal, supervision, authority, and applicable B04/B05 policy. Mobile must not infer actions from one status field.

The original decision record did not define the action matrix after plan pause/archive. The Student-calendar addendum below resolves START/SKIP for confirmed occurrences; it does not define future schedule or Appointment actions.

### Student calendar action policy approved 2026-10-10

Person B approved the following additional product decisions for the Student calendar read and B05 command reconciliation. This approval is policy, not evidence that a read API or command guard already implements it:

1. A confirmed `SCHEDULED` occurrence survives a source plan transition to `PAUSED`, `COMPLETED`, or `ARCHIVED`. That transition alone does not remove Student START/SKIP eligibility; occurrence-level guards still decide.
2. Once execution has started, the occurrence remains sealed even while `SCHEDULED`. Neither START nor SKIP is offered again; COMPLETE/ABORT belongs to the existing execution flow.
3. For a legacy unstarted `COACH_REQUIRED` occurrence, SKIP needs no Appointment. At this record's approval date, START required the then-qualifying Appointment or accountable override from V16. The later 2026-10-20 decision above **supersedes this START allowance**: a new START now requires the current `CONFIRMED` Appointment with matching parties/period, interval containment and PostgreSQL-time guard, regardless of an old override. Calendar actions and B05 START must use the same current condition. This historical line is retained to explain the earlier runtime, not to authorize an alternative new START path.

The calendar read is a snapshot. START/SKIP continue to recheck actor, occurrence version, and all command guards at mutation time. Other `COACH_REQUIRED` scheduling and Appointment creation remain deferred.

## 7. Approved read and execution entry

1. A Student may read the Student's own Planned Workout schedule.
2. A Trainer may read a Student's intended schedule, including pending proposals and confirmed Planned Workout occurrences exposed by B06, only during the current effective Coaching Period and with current `WORKOUT_PLAN` authority of at least `VIEW`.
3. Trainer schedule visibility does not grant execution-history access, other live Student data, or access after the relationship/period/permission ceases to be effective.
4. Student occurrence reads return at least:
   - `occurrenceId`;
   - `occurrenceVersion`;
   - planned start/end and preserved time presentation data;
   - nullable plan/session summary for legacy-safe presentation;
   - backend-derived `availableActions`.
5. The read window is half-open: `[from, until)`.
6. Results sort by `plannedStartAt ASC`, then `occurrenceId ASC`, and are paginated.
7. Mobile may default its calendar request to a month view. An API request window may span at most 93 days.
8. In the first flow, only the Student who owns the occurrence invokes START or SKIP.
9. START/SKIP recheck Student authority, occurrence optimistic version, supervision eligibility, and execution seal. A stale read never authorizes the mutation.

## 8. Approved audit and automation boundary

- Audit records the Student confirmation and the proposing Trainer when a proposal was involved.
- Proposal create/withdraw/accept/reject and confirmed batch creation must retain actor and authoritative timestamps appropriate to their lifecycle.
- The first flow has no automatic materializer, rolling horizon, or system actor.
- A future automatic scheduler must be approved separately and must reuse authoritative Workout boundaries rather than writing Workout persistence directly.

## 9. Module boundaries

- Workout remains the owner of Workout Plan, version/session resolution, Planned Workout source identity, occurrence optimistic version, adjustments, execution seal, and B05 snapshot semantics.
- B06 owns schedule collaboration, batch/proposal lifecycle, local-time intent, conflict orchestration, and Student/Trainer scheduling decisions.
- B06 calls Workout-owned application ports and never imports or queries Workout persistence directly.
- Workout consumes Coaching-owned authority queries; neither Workout nor Schedule derives Trainer authority from role or reads Coaching persistence directly.
- Appointment remains Schedule-owned and optional to a Planned Workout except where a future approved supervision contract explicitly requires a qualifying link.
- Mobile consumes backend projections and commands; it does not generate occurrence IDs, hardcode occurrence versions, resolve plan versions, or infer `availableActions`.

## 10. Declared first-flow contract and expected schema consequences

`contracts/openapi/openapi.yaml` now declares the independently implementable first-flow contract:

- Student atomic batch confirmation at `POST /planned-workout-batches`;
- Trainer proposal creation, visible proposal detail, withdrawal, and Student list/accept/reject operations;
- Student and current-authority Trainer occurrence calendar reads with `[from, until)`, the 93-day maximum, stable ordering, and pagination;
- explicit plan/proposal/occurrence optimistic tokens, actor-scoped command keys, source plan-version identity, local-time intent, IANA timezone, selected offsets, converted instants, nullable legacy-safe summaries, and backend-derived `availableActions`;
- stable validation, concealment, authority, stale-version, duplicate, overlap, unsupported-supervision, and idempotency error semantics, including item-addressable batch failures.

The OpenAPI declaration alone was not runtime evidence. At the first-flow contract checkpoint, materializer design and implementation still required:

- persistence for proposal/batch/item lifecycle, actor audit, idempotent receipts, source version, local intent, IANA timezone, offset, and converted instants;
- database-backed duplicate and Student-overlap protection that is safe under concurrent transactions;
- atomic item validation with item-addressable errors;
- Workout-owned resolution of the one effective published version and its sessions;
- Student occurrence reads with the approved window, order, pagination, nullable summary, concealment, and backend-derived actions;
- reschedule/cancel history and proposal flows in later independently reviewable checkpoints;
- tests for authority loss, version change, replay, concurrent duplicates/overlaps, DST gaps/ambiguity, the 90-day boundary, batch rollback, source immutability, execution sealing, concealment, and legacy nullable source data.

At that contract checkpoint, the existing schema could not represent the complete pending Trainer-proposal lifecycle or all approved batch timezone/local-intent facts. Forward migrations were therefore required; sections 12 and 14 describe the subsequently delivered V31/V32 boundaries. Existing V5/V16/V28–V30 migrations must not be rewritten.

## 11. First-flow deferred implementation details

At the first-flow planning boundary, the following implementation details were deferred to later checkpoints. The V31/V32 implementation sections below distinguish what has since been delivered; the reschedule/cancel-specific work remains future scope:

- exact proposal/batch/item table layout and optimistic tokens;
- exact lock ordering and database constraint strategy;
- reconciliation of future reschedule/cancel implementation with the current `SCHEDULED` schedule-occupancy predicate and the later approved policy in section 18;
- implementation of future action eligibility beyond B05 START/SKIP under the later approved policy in section 18;
- the exact qualifying Appointment states/link required before future `COACH_REQUIRED` scheduling is enabled;
- persistence, audit, and read projection for the linked replacement occurrence identity approved later in section 18.

These details must implement the approved decisions above. They are not permission to add automatic materialization, Trainer confirmation, partial proposal acceptance, implicit remapping, or coupled Appointment/Workout lifecycle behavior.

## 12. Student direct-batch implementation checkpoint

The `B06-STUDENT-BATCH-MATERIALIZER` implementation checkpoint adds only Student direct confirmation at `POST /planned-workout-batches`. V31 stores confirmed batches/items, local-time intent, source identity, actor-scoped receipts, and nullable batch links on occurrences. New batch rows have database-backed duplicate and `SCHEDULED` known-range exclusion; existing rows are not rewritten or enrolled in those constraints. The Workout transaction holds the Student lock while it checks legacy source duplicates, known-range overlap, and the approved unknown-end guard. Migration reports legacy anomalies without inferring or changing their end times. Local wall-time text retains the input's nanosecond value; authoritative planned instants use PostgreSQL microsecond precision.

This checkpoint does not implement Trainer proposals/acceptance, Student or Trainer calendar reads, reschedule/cancel, `COACH_REQUIRED` scheduling, automatic materialization, or Mobile START/SKIP entry. Those declared contracts remain implementation work.

## 13. Student calendar read implementation checkpoint

`B06-STUDENT-CALENDAR-READ` implements the Student-owned `GET /planned-workouts` projection and reconciles B05 START with the qualifying Appointment/override predicate. This implementation is distinct from the approved policy above. Trainer calendar reads, proposals, reschedule/cancel, `COACH_REQUIRED` scheduling, automatic materialization, and the Mobile START/SKIP entry remain outside this checkpoint.

## 14. Trainer proposal implementation checkpoint

`B06-TRAINER-SCHEDULE-PROPOSALS` adds V32 and the backend create/list/detail/withdraw/ACCEPT/REJECT proposal workflow. A pending proposal stores immutable source/local-time intent but no occurrence. The Student alone decides it; ACCEPT rechecks current Trainer authority and the original Coaching Period, then invokes the Workout-owned batch materializer inside the same transaction. REJECT bypasses materialization guards. V32 stores proposal status history, actor-scoped command receipts, and confirmed-batch lineage. Existing B05 execution seal and Student calendar behavior are unchanged. Trainer calendar, Appointment mutation, reschedule/cancel, Mobile proposal UI, and automatic materialization remain deferred. This section describes implementation scope, not independent review or CI status.

## 15. Trainer calendar read implementation checkpoint

`B06-TRAINER-CALENDAR-READ` implements the declared Trainer `GET /students/{studentId}/planned-workouts` using the Student calendar's Workout-owned projection, window validation, ordering, and pagination. Every request evaluates current `WORKOUT_PLAN VIEW` authority through the Coaching port and conceals denied Student schedules. The Trainer response has an empty `availableActions` list: schedule visibility does not confer Student START/SKIP or Workout Execution access. This is implementation scope, not evidence of independent review or CI success; Appointment mutation, reschedule/cancel, Mobile proposal UI, and automatic materialization remain deferred.

## 16. Proposal read and presentation decisions approved 2026-10-11

Person B approved these additional product decisions. They do not change the proposal mutation, Student decision, or former-Trainer authority rules above:

1. In the TR-03 Student workspace, a Trainer lists only proposals they created for that Student. The list requires a current effective Coaching Period and current `WORKOUT_PLAN VIEW` or higher; the authorization check precedes count and pagination. It does not expose proposals from another Trainer.
2. The Trainer list includes `PENDING` and terminal proposals with an optional status filter. `VIEW` suffices for reading; withdrawal of a `PENDING` proposal still requires `CONTRIBUTE` and all existing command guards.
3. The Student sees the proposing Trainer's **current** User display name when available. This nullable read-time presentation is not a name frozen at proposal creation and is not audit evidence. The Student keeps their own proposal read access after the Coaching Period ends; ACCEPT still rechecks current authority and may fail, while REJECT remains available under the existing lifecycle.

The `B06-PROPOSAL-READ-PRESENTATION` runtime checkpoint adds a Trainer-scoped read endpoint and nullable presentation fields to the shared proposal response. Workout supplies the plan/session names and optional session focus from the exact referenced source version through a Workout-owned query port; User supplies current Trainer display name through a User-owned query port. Schedule does not read either module's persistence directly. Original IDs, proposal versions, and stored command receipts remain authoritative; presentation is resolved for each authorized HTTP response, including command replay. The subsequently merged Mobile proposal checkpoint implements Trainer list/detail/withdraw; Trainer proposal creation was deferred at that checkpoint and implemented in the later Mobile Trainer Create checkpoint.

## 17. Plan-session supervision read prerequisite

The `B06-PLAN-SESSION-SUPERVISION-READ` checkpoint adds the stored, version-scoped `supervisionRequirement` to B04 `WorkoutPlanSession` version-detail responses. This read metadata lets the subsequently implemented Trainer Mobile form distinguish sessions before composing a proposal; it does not itself implement that form or alter the B06 first-flow rejection of `COACH_REQUIRED`. The source plan/version/session IDs and optimistic plan aggregate version remain distinct and authoritative.

## 18. Reschedule and cancel product decisions approved 2026-10-12

**Checkpoint:** `B06-RESCHEDULE-CANCEL-DECISIONS`. **Status:** **APPROVED** by Person B / Coaching Experience Owner on 2026-10-12. These are product/domain decisions for a later Planned Workout change flow, not a declaration that OpenAPI, migrations, backend commands, or Mobile reschedule/cancel UI already exist. The original confirmed batch and any accepted scheduling proposal remain historical evidence.

1. The Student may directly reschedule or cancel **one** Planned Workout occurrence. A Trainer may only propose a change to one occurrence while a current effective Coaching Period and current `WORKOUT_PLAN` authority of at least `CONTRIBUTE` exist. The Student alone ACCEPTs or REJECTs that proposal. The first change flow does not move or cancel a whole batch or series.
2. Only a `SCHEDULED` occurrence without an execution seal is eligible. Terminal occurrences and any occurrence whose execution has STARTed cannot be rescheduled or cancelled. The backend rechecks occurrence state and optimistic version within the mutation transaction.
3. Reschedule requires the **old** planned start still to be in the future. The Student may cancel an overdue `SCHEDULED`, unsealed occurrence only with a reason. A Trainer may propose a change only before the occurrence's planned start.
4. A source plan becoming `PAUSED`, `COMPLETED`, or `ARCHIVED` does not by itself revoke reschedule/cancel eligibility for an already confirmed occurrence. The original source plan version, session, and prescription remain authoritative; no remapping to a later version occurs.
5. Reschedule creates a **new occurrence ID** and transitions the old row to `RESCHEDULED`. The replacement's `rescheduled_from_id` points to its immediate predecessor; the original planned-time anchor is preserved across the whole chain. Cancel transitions the current row to `CANCELLED` and creates no replacement. Both retain append-only audit/history.
6. The replacement may move to another date or week within the approved time limit. A changed execution date does not change the source version's plan-week/session identity or prescription. Audit/history retains the old and new scheduled times.
7. The replacement start must be strictly in the future and no more than 90 days from authoritative PostgreSQL time. Preserve the selected IANA timezone, entered local start/end intent, and selected start/end UTC offsets; reject nonexistent DST local times or invalid offsets. Recheck logical duplicate and time overlap during direct execution or proposal ACCEPT, not only when proposing.
8. Keep the existing uniqueness invariant: the same source plan session and planned-start instant cannot be materialized again, even when the earlier occurrence is historical and terminal. Do not relax the V31 source-instant index in this decision checkpoint.
9. Do not reschedule a legacy row lacking the source version/session or local-time intent needed to preserve source identity. The Student may cancel a legacy `SCHEDULED`, unsealed row, including one with a null planned end, if no Appointment is linked; never infer an end. Any **other** `SCHEDULED` occurrence with an unknown end continues to block creation of the replacement under the unknown-end guard.
10. The first change flow blocks both reschedule and cancel if **any** Appointment is linked to the occurrence, regardless of Appointment status. It never moves or cancels the Appointment implicitly. `COACH_REQUIRED` reschedule is deferred to the separate Appointment-coordination checkpoint. A `COACH_REQUIRED` occurrence without a linked Appointment may be cancelled under the ordinary guards.
11. Do not rewrite the original confirmed batch or accepted scheduling proposal. The Student retains read and REJECT access to a change proposal. ACCEPT rechecks the proposing Trainer's current authority and Coaching Period, occurrence version and seal, and conflicts. A stale change proposal remains `PENDING` and returns 409; it is neither silently remapped nor automatically expired in this first flow.
12. The calendar continues to return terminal rows in its existing time window. Present `RESCHEDULED`/`CANCELLED` and predecessor/replacement lineage clearly. Only Student calendar responses may gain backend-derived reschedule/cancel `availableActions`; Trainer calendar remains read-only. The Student can read their own history, while a Trainer can read the Student's schedule/history only while current authority remains effective.

These decisions **do not change** the existing B05 START/SKIP policy, execution seal, optimistic occurrence-version checks, or the current Student/Trainer calendar authority boundary. The existing Appointment reschedule-request table is Appointment-owned; it is not, by itself, a Planned Workout change workflow.

The subsequent `B06-RESCHEDULE-CANCEL-CONTRACT` checkpoint declares Student occurrence detail/direct reschedule/direct cancel, Trainer one-occurrence change-proposal create/list/detail/withdraw, Student proposal list/detail/ACCEPT/REJECT, lineage in calendar/detail, and stable error semantics in OpenAPI. This is a **contract declaration only**: no reschedule/cancel migration, backend command/read implementation, or Mobile flow is delivered by this checkpoint. A separate change-proposal aggregate and a detail read spanning calendar windows are contract design choices, not additional product policy. Existing confirmed batch/proposal records stay unchanged. The contract's Student late-cancel reason is supplied with direct cancel or with a Student ACCEPT decision; it is never inferred from the Trainer's proposal note. `WORKOUT_SCHEDULE_OCCURRENCE_START_PASSED` compares the occurrence being changed's current `plannedStartAt`, not the chain-wide `originalPlannedStartAt`. The read contract exposes one nullable `replacementOccurrenceId` for the approved one-to-one chain. V5 does not enforce unique `rescheduled_from_id` for legacy rows, so implementation must audit ambiguous legacy lineage rather than silently choose one successor. V31 duplicate/overlap constraints cover only batch-linked rows; transaction guards must also cover legacy conflicts. Receipt storage, exact lock order, one-new-successor enforcement, audit persistence, legacy lineage projection, Appointment-link query, and action/command parity must be established in later implementation checkpoints.
