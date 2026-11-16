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
