# F05 Workout Rescheduling — Verification and Handover

**Reviewed:** 2026-11-16 (UTC+7)
**Branch:** `feat/p2-m4-f05-rescheduling`
**Implementation HEAD before review:** `b459d2d`
**Status:** Draft technical review; merge/release acceptance remains open.

## Evidence and corrections

This replaces the inaccurate narrative committed at `b459d2d`. The original
[runtime JSON](runtime_verification_evidence.json) is unchanged. Its timestamp is
`2026-11-16T12:18:20.161878700Z` (19:18:20 UTC+7). It records generate/read/accept
and domain readback for **one** successful scenario. The other four cases are
evidenced by test source and Surefire results, not this JSON alone.

The final-review rerun produced
[separate runtime JSON](runtime_verification_evidence_review.json). The test
normally overwrites the original path, so its new output was retained separately
and the original content restored from HEAD with Windows CRLF checkout line
endings. Neither run's payload was edited.

The [review record](review_verification.json) records current report hashes,
counts and the retained artifact hash. Raw Surefire output is local generated
build output, not committed logs or proof of remote CI.

| Earlier claim | Verified description |
|---|---|
| `/api/v1/ai/reschedule-workout/...` | `/api/v1/ai/workout-reschedules` and detail/accept/reject routes |
| PostgreSQL 16, V1–V10 | `pgvector/pgvector:pg18`; saved runtime log reports PostgreSQL 18.6 and 60 migrations through V60 |
| Spring Boot 3.3.4 | Spring Boot 4.1.1; saved runtime JVM 21.0.12.1 |
| External HTTP into Spring | MockMvc dispatches in-process through Spring MVC/security; Spring calls Uvicorn over real HTTP |
| Runtime validation with Ajv | Ajv runs in the Node contract tool; Spring uses `F05RuntimeOutputValidator` |
| 34 backend tests including five HTTP tests | 34 service/validator/database tests **plus five** MockMvc/FastAPI tests = 39 |
| Audit action `APPLY_RESCHEDULE` | Test asserts `AI_WORKOUT_RESCHEDULE_APPLIED` for the replacement occurrence |
| D05 authorizes atomic apply | Atomic apply is authority-matrix sections 1.5/1.7; D05 concerns deferred AI Goal/Nutrition Target proposals |
| F05 enforces a +1h minimum | No +1h lower bound in the F05 validator; apply uses the Workout domain's future-time/90-day guard |

Actual schema: `contracts/ai-schemas/f05-workout-reschedule.schema.json`.
These commits do not add separate `f05-workout-reschedule.payload.json` or
`f05-context.envelope.json` files.

## Boundary exercised

```text
MockMvc (in-process MVC/security) -> Spring F05 orchestration
  -> HTTP Uvicorn /internal/v1/workout-reschedules/generate
  -> simulated F05 provider -> Spring validation/persistence
  -> authorized accept/reject -> WorkoutScheduleChangeUseCase -> PostgreSQL
```

The test starts Spring with RANDOM_PORT, but assertions use MockMvc, not that
server port. This is integration verification, not a Mobile journey, staging
smoke test or external model evaluation.

OpenAPI base URL includes `/api/v1`:

| Method | Route |
|---|---|
| POST | `/api/v1/ai/workout-reschedules` |
| GET | `/api/v1/ai/workout-reschedules/{recommendationId}` |
| POST | `/api/v1/ai/workout-reschedules/{recommendationId}/accept` |
| POST | `/api/v1/ai/workout-reschedules/{recommendationId}/reject` |

GET currently attempts to persist EXPIRED for an expired PENDING recommendation.
The five runtime tests do not directly cover GET expiry or GET/accept concurrency.

## Tests and final-review changes

Saved pre-review reports showed zero failures/errors/skips in these suites:

| Suite | Tests | Layer |
|---|---:|---|
| F05RuntimeOutputValidatorUnitTest | 11 | Validator unit |
| AiWorkoutRescheduleServiceUnitTest | 12 | Service unit |
| AiWorkoutRescheduleIntegrationTest | 11 | PostgreSQL; simulated Java AI client |
| AiWorkoutRescheduleRealFastApiIntegrationTest | 5 | MockMvc + HTTP FastAPI + PostgreSQL |
| WorkoutModuleBoundaryTest | 4 | Architecture |
| WorkoutScheduleChangeIntegrationTest | 15 | Workout domain/database |
| AiWorkoutProposalFullSliceIntegrationTest | 25 | F02 regression |
| AiWorkoutAdjustmentFullSliceIntegrationTest | 30 | F03 regression |

Five FastAPI integration scenarios cover:
1. Generate/read/accept, occurrence lineage, one application and an apply audit.
2. Identical retry with one replacement and `replayed=true`.
3. Reject with original SCHEDULED and no replacement.
4. Source-version conflict (409) with no replacement.
5. Student HUMAN_COACH accept denied (403).

Checks executed in this review:
- OpenAPI valid; 10 schemas / 46 fixtures pass via `npm run validate:contracts`.
- Contract scenarios 21/21 pass. These are predominantly F01; they are not 21 F05 behavioral tests.
- AI service Ruff initially found nine F05 issues. Removed unused import,
  used UTC alias and narrowed date/timezone exception handlers. Full Ruff now passes.
- Full AI service pytest: 85/85 pass, including F05 internal-route authentication.
  Dependency deprecation and local pytest-cache warnings remain.
- Extended the existing revoked-permission integration case to check reads before
  and after revocation. It failed before the fix because revoked Trainer access
  still returned the recommendation.
- Fixed F05 readback to re-evaluate current WORKOUT_PLAN/VIEW authority, failing
  closed if the authority query is unavailable. Reran all four F05 Backend suites:
  **39/39 pass**, zero failures/errors/skips, Maven BUILD SUCCESS in 48.147 seconds.
  The extended revoked-read test passes. Exact report timings are in the review record.

## Policy, scope and remaining gates

| Topic | Source / limit |
|---|---|
| TTL 30 minutes | Approved **experimental default**, authority matrix section 1.5; rollout values may be reviewed |
| One occurrence | Approved release decision D02 |
| Slot horizon | Existing Workout command and OpenAPI require future start through +90 days; not proof of recovery suitability |
| Generation clock skew | F05 validator permits 120 seconds of past-clock skew; domain apply requires a future destination using database time |
| Source eligibility | Extended with project-owner approval on 2026-11-16. Future unstarted sources and ended known intervals with no execution/log are eligible. Both source kinds are covered by the FastAPI integration flow |
| Supervision / Appointment | Mapping/domain guards exist; the five runtime cases do not exercise approved appointment changes or COACH_REQUIRED application |
| Recovery / availability | Provider chooses a deterministic morning slot. Its recovery sentence does not establish specialist-approved recovery checks or availability search |
| Mobile ownership | No F05 Mobile UI in this branch. Named assignee/reviewer must come from task/issue, not inference from product roles |

Open before acceptance: verify availability/recovery and Appointment/supervision against matrix section 6;
applicable D06/OD-3 review; real provider quality/latency/usage/cost; F05 Mobile;
required F05 failure/recovery verification. GET/decision concurrency remains a
review item, not a proven guarantee. None of these gates is closed by test counts.

## Branch and handover scope

Base used here is local `origin/main` at `893e366`; refs were not refreshed.
Existing commits:
- `3c15e96`: delete 13 scratch scripts. Retained tool reads `JWT_SECRET` or
  `AUTH_JWT_SECRET`, plus optional `API_BASE` (not the variable names in the old handover).
- `c00dce6`: F04 studentId route support, four Scenario 9 PNGs, manifest/checklist.
- `b713e2a`, `a81fa6a`, `b459d2d`: F05 implementation, expiry fix and integration evidence.

Cleanup does not erase historical exposure or establish secret rotation.
F04 artifacts remain prior evidence; no GUI run was repeated here. F04 checkpoint
and P2-M4 acceptance remain open.

[PR description](../../f05-rescheduling-pr-description.md) covers the complete branch.
Final-review edits remain in the working tree; no staging, commit, fetch, push,
PR creation or deployment was performed.


## Missed-workout extension verified 2026-11-16

The project owner approved the proposed source eligibility extension in the working
session. The shared Workout evidence predicate now allows an unstarted SCHEDULED
source with no linked Actual Workout log when its start is future or its known
valid planned interval has ended. Apply uses database time under the existing
Student/occurrence locks. Generation and calendar actions use the same predicate.
The voluntary reschedule cutoff is the known planned end. Progress aggregation
and automatic attention reporting keep their existing windows.

The extension preserves source time and original anchor, creates one linked
successor, and retains append-only change/audit history. The existing tests now
exercise START/reschedule races for both future and missed sources, historical
execution logs without a seal, late START after AI generation, idempotent replay,
concurrent decisions, unknown end, unfinished interval and audit rollback.

The main Backend run passed 66 tests, comprising 45 F05 tests and 21 Workout
schedule-change tests. Follow-up regression passed 111 tests across Schedule
change proposals (11), scheduling proposals (33), batches (24), Progress (28),
attention rules (11) and module boundary (4). The total is 177 passing Backend tests. Exact current suite results and follow-up regression
results are recorded in the missedReschedulingUpdate section of
[review_verification.json](review_verification.json).

The generated missed-source runtime payload is retained separately at
[runtime_verification_evidence_missed.json](runtime_verification_evidence_missed.json).
It was produced by MockMvc -> Spring -> HTTP Uvicorn with simulated provider ->
Workout domain -> PostgreSQL. Test evidence now writes to target/f05-runtime-evidence
so reruns preserve the retained repository artifacts.

## Recovery, availability, and appointment coordination verified 2026-11-17

A dedicated continuation sprint on branch `feat/f05-recovery-availability-appointment`
(based on `origin/main` at `ff7cfc7c5076fe92bd8457d2a0c31709fb712652`) addressed
the F05 behavioral gaps with verified evidence:

1. **Slot Selection & Schedule Availability (Heuristic Slot & Domain Overlap)**:
   - `simulated_f05_provider.py` and `SimulatedF05AiServiceClient.java` map `preferredTimeOfDay` case-insensitively (`MORNING` -> 09:00, `AFTERNOON` -> 14:00, `EVENING` -> 18:00) as heuristic simulated defaults.
   - Candidate slots are bounded and strictly future-calculated relative to current UTC time with a maximum 90-iteration search, preventing past slots and clamping candidates within the 90-day domain horizon.
   - Preserves timezone offset and validates DST transition gaps.
   - **Crucial Boundary Clarification**: The verified availability scope covers only candidate slot generation, timezone/DST validation, and domain schedule overlap prevention (`WORKOUT_SCHEDULE_OVERLAP`). It does **not** claim physiological recovery rules (such as muscle group fatigue, minimum rest periods, or systemic readiness), which remain open pending specialist review under D06/OD-3.

2. **Appointment Coordination and Supervision (`COACH_REQUIRED`)**:
   - **Revalidation on Accept**: `AiWorkoutRescheduleService.doAcceptReschedule` checks `liveOccurrence.linkedAppointmentId()` and `liveOccurrence.linkedAppointmentVersion()` against `sourceSnapshot`. Any appointment cancellation, reschedule, or re-link between proposal generation and application aborts with `AiRunLifecycleConflictException` (HTTP 409).
   - **Supervision Invariant Guard**: `F05RuntimeOutputValidator` enforces that an occurrence with `COACH_REQUIRED` supervision cannot be degraded to optional/self-directed in the proposal.
   - **Domain Transfer**: When a `COACH_REQUIRED` session has a qualifying confirmed appointment, `workoutScheduleChangeUseCase.reschedule` atomically transfers the appointment link (`fitness.appointments.planned_workout_id`) to the successor occurrence (`replacementId`).
   - **Missing Appointment Guard**: Rescheduling a `COACH_REQUIRED` session without a qualifying confirmed appointment aborts with `WORKOUT_SCHEDULE_COACH_REQUIRED_RESCHEDULE_UNSUPPORTED` (HTTP 409).

3. **Concurrency and Transactional Failure Verification**:
   - **GET vs. Decision Concurrency**: `updateDecisionIfStatus` conditionally updates recommendation status from `PENDING` to `EXPIRED` only if the recommendation is still `PENDING` at database level, preventing concurrent GET calls from overwriting an `APPLIED` or `REJECTED` decision. Verified both in sequence and in multi-threaded concurrent race (`concurrentGetAndAcceptNearExpiry_resolvesToConsistentTerminalState`).
   - **Destination Overlap Race**: An overlapping planned workout scheduled on the destination slot between proposal generation and accept aborts with `WORKOUT_SCHEDULE_OVERLAP` (HTTP 409), leaving the original occurrence in `SCHEDULED`.
   - **Audit Failure Rollback**: Simulating an insert failure on `fitness.audit_logs` triggers an atomic transaction rollback: no replacement workout is created, old occurrence remains `SCHEDULED`, and recommendation status remains unchanged.

### Verification Summary

- **Contracts**: `npm run validate:contracts` passes (OpenAPI valid, 10 schemas, 46 fixtures).
- **AI Service**: `pytest` passes 86/86; `ruff check .` passes with zero issues.
- **Backend Test Suites**:
  - `AiWorkoutRescheduleIntegrationTest`: **23/23 passed** (including COACH_REQUIRED transfer, appointment revalidation, overlap conflict, audit rollback, GET concurrency race).
  - `AiWorkoutRescheduleRealFastApiIntegrationTest`: **6/6 passed** (MockMvc + HTTP Uvicorn + PostgreSQL 18.6).
  - `AiWorkoutRescheduleServiceUnitTest`: **14/14 passed** (including linked appointment change conflict, conditional expiry).
  - `F05RuntimeOutputValidatorUnitTest`: **12/12 passed** (including COACH_REQUIRED degradation rejection).
  - Total F05 tests: **55 passed** (zero failures, zero errors).
- **Regression Suite**: `WorkoutScheduleChangeIntegrationTest`: **21/21 passed**.
- **Operational Note**: Testcontainers integration suites were verified against live Docker container `pgvector/pgvector:pg18` prior to system restart at 05:02 UTC. Starting the Docker service on Windows requires elevated Administrator privileges. Unit and contract suites rerun continuously without container dependency.

### Open Gates and Release Boundaries
- **D06 / OD-3 Review**: Physiological recovery rules (minimum rest between sessions, muscle recovery windows) remain open pending specialist review.
- **Real Provider Evaluation**: Current tests use deterministic simulated providers; latency, cost, and LLM output consistency require evaluation against real model endpoints.
- **Mobile F05 Client**: Mobile UI/journey is not implemented in this backend branch. Assignee and reviewer roles must be confirmed from official task tracking, not inferred from product roles.
