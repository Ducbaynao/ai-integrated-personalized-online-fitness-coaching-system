# Proposed title

feat(ai): add F05 rescheduling backend, F04 evidence and scratch cleanup

# PR description

## Summary

Adds backend generation, review and authorized application of a rescheduling
proposal for one Planned Workout through the existing Workout domain command,
with receipt and audit. Also removes obsolete scratch scripts and adds F04
Scenario 9 GUI evidence.

## Changes

- Add F05 OpenAPI/JSON Schema contracts, FastAPI simulated generation and Spring
  generate/read/accept/reject endpoints under `/api/v1/ai/workout-reschedules`.
- Guard decisions with coaching authority, occurrence revision and expiry;
  serialize decisions and preserve replay identity. Commit expiry without opening
  a second transaction against an already locked row.
- Recheck current WORKOUT_PLAN/VIEW authority on Trainer readback after revocation.
- Add database concurrency and MockMvc tests calling a Uvicorn process over HTTP.
- Include F04 studentId route support and four prior Android Scenario 9 screenshots
  plus manifest; retain the open F04 checkpoint.
- Delete 13 scratch scripts and read JWT configuration from environment in the
  retained tool; historical exposure/rotation remains separate.
- Correct evidence descriptions and fix F05 Python lint findings.

## Verification

- F05 Backend includes 45 tests (23 unit + 16 database + 6 FastAPI integration).
  Final-review rerun results and report hashes are in the verification record.
- Prior F02/F03 reports contain 25/30 passing tests. The missed-source update
  additionally ran 21 Workout change tests and the 111-test regression listed below.
- Final-review AI service Ruff passes; pytest 85/85; OpenAPI valid;
  10 AI schemas / 46 fixtures; contract scenarios 21/21.
- Runtime: in-process MockMvc -> Spring -> HTTP Uvicorn/FastAPI with simulated
  provider -> domain -> PostgreSQL 18.6, all 60 migrations through V60.
  Does not verify external HTTP into Spring, Mobile or a real model.
- [Verification record](artifacts/f05-runtime-evidence/review_verification.json)
  and [handover](artifacts/f05-runtime-evidence/verification_report.md).

## Reviewer decisions and limits

Review includes the implemented Backend scope and these remaining feature items.

- Missed-source rescheduling was approved on 2026-11-16 and implemented through
  shared Workout evidence. A known ended interval, SCHEDULED state and absence of
  execution/log permit rescheduling. FastAPI integration covers this flow.
- Workout change tests cover 21 cases, including missed-source history, START
  races, legacy execution evidence and atomic audit-failure rollback.
- Follow-up regression passes 111 tests across Schedule changes/proposals,
  Workout batch materialization, Progress, attention rules and module boundary.
- Deterministic slot generation does not prove recovery/availability suitability.
  Review specialist rules and Appointment/COACH_REQUIRED cases.
- TTL 30 minutes is the approved experimental default; +90 days is the domain
  horizon. No F05 +1h lower bound exists.
- Real provider evaluation, Mobile F05, required failure/recovery verification
  and GET/decision concurrency remain open.
- Owner/reviewer assignment belongs to the task/issue; F01 and other milestone
  acceptance work are not completed by this branch.
