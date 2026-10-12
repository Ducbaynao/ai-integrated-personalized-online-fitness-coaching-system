# B06 Schedule and Planned Workout Occurrence Decisions

## 1. Decision record

- **Checkpoint:** `B06-PLANNED-OCCURRENCE-DOMAIN-DECISIONS`.
- **Status:** **APPROVED**.
- **Approval date:** 2026-10-10.
- **Decision owner:** Person B / Coaching Experience Owner.
- **Scope:** Product and domain decisions for assigning published Workout Plan sessions to dates and times, Trainer scheduling proposals, Planned Workout materialization, and the read entry required by B05 START/SKIP.
- **Not implementation evidence:** Approval of this record does not by itself prove implementation. The later `B06-PLANNED-OCCURRENCE-CONTRACT` checkpoint declares the first-flow OpenAPI surface, but the proposal workflow, batch materializer, persistence, backend service, and Mobile flow remain unimplemented.

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
4. A nonexistent local time in a daylight-saving transition is rejected. For an ambiguous local time, the confirming Student must select an explicit offset.
5. Replaying the same logical command with the same `commandKey` returns the same committed outcome and creates no additional occurrence.
6. Different command keys must not create the same plan session at the same planned-start instant. The same source session may be scheduled at another valid time or in another valid plan-week batch.
7. Items in one batch must not overlap for the Student.
8. A new item must not overlap another still-effective Planned Workout occurrence for that Student. Overlap conflicts with a known end identify the affected item; the unknown-end conflict below is batch-level.
9. Appointment and Planned Workout never change each other's lifecycle implicitly.
10. The first scheduling flow does not confirm a `COACH_REQUIRED` session. Support remains blocked until a later contract can require and validate the appropriate Planned Workout–Appointment link and qualifying Appointment.

For the first flow, `SCHEDULED` is the schedule-occupying occurrence status in the current executable lifecycle, including a `SCHEDULED` occurrence whose execution has started. `COMPLETED`, `ATTEMPTED`, `MISSED`, `SKIPPED`, `RESCHEDULED`, and `CANCELLED` are terminal and do not block under this unknown-end rule. If the Student has any schedule-occupying occurrence with `planned_end_at = NULL`, direct batch confirmation or ACCEPT of a Trainer proposal rejects the entire batch with `WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN` (409); no occurrence is created. This is a batch-level error, not an item error, and must not reveal the existing occurrence's identity or timing to an unauthorized actor. Checking overlap with only an individual new item cannot establish safety when the existing end is unknown. The backend must not infer an end from the template, treat null as zero or an unbounded interval, or silently repair legacy data. The path to unblock is a valid terminal lifecycle transition or a later designed, audited process to verify and update the end. This policy does not assert that production contains such rows, and the current backend has not implemented this guard.

This approval does not define a broader Appointment conflict matrix. The contract checkpoint must preserve Appointment independence and specify only the checks needed for the approved occurrence flow without inventing implicit Appointment mutations.

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
3. For a legacy unstarted `COACH_REQUIRED` occurrence, SKIP needs no Appointment. START requires the qualifying Appointment or accountable override already defined by the executable V16 database predicate. Calendar actions and the B05 START guard must use the same condition; neither invents another Appointment/override rule.

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

This OpenAPI declaration is not runtime evidence. The materializer design and implementation still require:

- persistence for proposal/batch/item lifecycle, actor audit, idempotent receipts, source version, local intent, IANA timezone, offset, and converted instants;
- database-backed duplicate and Student-overlap protection that is safe under concurrent transactions;
- atomic item validation with item-addressable errors;
- Workout-owned resolution of the one effective published version and its sessions;
- Student occurrence reads with the approved window, order, pagination, nullable summary, concealment, and backend-derived actions;
- reschedule/cancel history and proposal flows in later independently reviewable checkpoints;
- tests for authority loss, version change, replay, concurrent duplicates/overlaps, DST gaps/ambiguity, the 90-day boundary, batch rollback, source immutability, execution sealing, concealment, and legacy nullable source data.

The existing schema cannot represent the complete pending Trainer-proposal lifecycle or all approved batch timezone/local-intent facts. A forward migration is therefore expected, but its table/constraint design is deferred to the persistence-planning checkpoint. Existing V5/V16/V28–V30 migrations must not be rewritten.

## 11. Deferred implementation details, not unresolved product approval

The following implementation details remain for the materializer design/persistence checkpoints:

- exact proposal/batch/item table layout and optimistic tokens;
- exact lock ordering and database constraint strategy;
- reconciliation of future reschedule/cancel lifecycle semantics with the current `SCHEDULED` schedule-occupancy predicate;
- action eligibility for future commands beyond B05 START/SKIP;
- the exact qualifying Appointment states/link required before future `COACH_REQUIRED` scheduling is enabled;
- whether reschedule retains one occurrence row with append-only history or creates linked replacement occurrence identity, subject to existing B04/B05 invariants.

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

The `B06-PROPOSAL-READ-PRESENTATION` runtime checkpoint adds a Trainer-scoped read endpoint and nullable presentation fields to the shared proposal response. Workout supplies the plan/session names and optional session focus from the exact referenced source version through a Workout-owned query port; User supplies current Trainer display name through a User-owned query port. Schedule does not read either module's persistence directly. Original IDs, proposal versions, and stored command receipts remain authoritative; presentation is resolved for each authorized HTTP response, including command replay. The subsequently merged Mobile proposal checkpoint implements Trainer list/detail/withdraw; Trainer proposal creation remains deferred.

## 17. Plan-session supervision read prerequisite

The `B06-PLAN-SESSION-SUPERVISION-READ` checkpoint adds the stored, version-scoped `supervisionRequirement` to B04 `WorkoutPlanSession` version-detail responses. This read metadata lets a future Trainer Mobile form distinguish sessions before composing a proposal; it does not implement that form or alter the B06 first-flow rejection of `COACH_REQUIRED`. The source plan/version/session IDs and optimistic plan aggregate version remain distinct and authoritative. Mobile proposal creation remains deferred.
