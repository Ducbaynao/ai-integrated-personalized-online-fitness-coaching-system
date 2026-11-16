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

- F05 Backend: 39 tests (23 unit + 11 database + 5 FastAPI integration).
  Final-review rerun results and report hashes are in the verification record.
- Saved prior regression reports: F02 25, F03 30, Workout scheduling 15 and
  module boundary 4 pass. These were inspected, not rerun in final review.
- Final-review AI service Ruff passes; pytest 85/85; OpenAPI valid;
  10 AI schemas / 46 fixtures; contract scenarios 21/21.
- Runtime: in-process MockMvc -> Spring -> HTTP Uvicorn/FastAPI with simulated
  provider -> domain -> PostgreSQL 18.6, all 60 migrations through V60.
  Does not verify external HTTP into Spring, Mobile or a real model.
- [Verification record](artifacts/f05-runtime-evidence/review_verification.json)
  and [handover](artifacts/f05-runtime-evidence/verification_report.md).

## Reviewer decisions and limits

Open as **draft technical review**, not merge/release acceptance:

- Approved F05 includes a missed workout; domain currently rejects a source after
  its planned start, while runtime fixtures use future occurrences.
- Deterministic slot generation does not prove recovery/availability suitability.
  Review specialist rules and Appointment/COACH_REQUIRED cases.
- TTL 30 minutes is the approved experimental default; +90 days is the domain
  horizon. No F05 +1h lower bound exists.
- Real provider evaluation, Mobile F05, required failure/recovery verification
  and GET/decision concurrency remain open.
- Owner/reviewer assignment belongs to the task/issue; F01 and other milestone
  acceptance work are not completed by this branch.
