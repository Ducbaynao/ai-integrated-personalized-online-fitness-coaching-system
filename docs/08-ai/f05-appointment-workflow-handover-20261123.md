# F05-BL-03 — Appointment coordination handover

**Date:** 2026-11-23 (UTC+7).  
**Branch:** `feat/f05-appointment-workflow`.  
**Base:** `bee4c08605d0c087236c5a297edb75bb2149f1aa` (PR #166 merge).  
**Scope:** F05 / P2-M4, AI orchestration over existing Schedule and Workout commands.  
**Status:** Implemented for the supported bilateral Schedule workflow; see evidence record for executed test results. Full feature/release acceptance remains OPEN.

## Baseline and existing work

Read-only GitHub checks confirmed PR #166 merged at this base, remote `main` matched local `main`/`origin/main`, and post-merge [CI run 45143204129](https://github.com/Ducbaynao/ai-integrated-personalized-online-fitness-coaching-system/actions/runs/45143204129) succeeded. This CI proves the base only, not the uncommitted implementation.

At session entry the requested branch already existed and contained nine modified F05 files. No branch was created or switched. The existing changes were inspected, preserved and completed in scope; the initial diff/status are retained under `D:/Projects/reports/f05-bl03-20261123/`. There were no unrelated pre-existing changes identified. No Git mutations, staging, commit, push, issue/PR creation, messages or deployment were performed.

## Behavior and authority

Previously a fresh F05 proposal used the generic simulated next-day slot even after both parties moved the linked Appointment. It could repeatedly fail the containment guard. Appointment ID/version were revalidated, but the explicit Appointment expectations in an Accept request were not part of persisted replay identity.

The supported flow is now:

1. Read/review the F05 destination and current Appointment separately. A proposal does not move the Appointment.
2. If the Appointment time must change, a party uses `POST /api/v1/appointments/{appointmentId}/reschedule-requests`. The other party decides through `POST /api/v1/appointment-reschedule-requests/{requestId}/decisions`.
3. Only an actual accepted Schedule decision updates the Appointment time/version. Pending or rejected intent cannot authorize a different time.
4. Generate again through `POST /api/v1/ai/workout-reschedules` using a new command key. This produces a distinct recommendation/run and a fresh source snapshot. The old snapshot, payload and decision history are not rewritten. Review the new recommendation before accepting it.
5. Java and Python simulated providers can select a workout slot inside the current CONFIRMED Appointment. They preserve the source workout duration rather than using the entire Appointment length. This is a bounded simulated heuristic, not full availability or specialist recovery validation.
6. Accept rechecks current authority, source/Appointment identity and version. The Workout command locks/revalidates source, overlap, supervision and confirmed Appointment containment, then transfers the active link with the successor/audit/receipt in one transaction. Appointment time/status are not changed by this command.

F05 decision authority remains Student in SELF_DIRECTED and the authorized Trainer in HUMAN_COACH. Schedule consent remains bilateral, independently of that F05 decision. A Student request for an Appointment change is not permission to decide a HUMAN_COACH workout proposal. No transaction spans the wait for counterparty consent or the provider call.

## Corrections made while completing the existing diff

- Appointment start/end are included in the internal Java/FastAPI request. Context rejects a version mismatch between the calendar read and the Appointment interval read, avoiding a mixed-version envelope.
- Explicit `expectedAppointmentId` and `expectedAppointmentVersion` are checked and persisted in application receipts. Different retry intent conflicts. The existing fingerprint encoding is retained so old receipts still replay; the added fields are compared separately. If a legacy receipt lacks these fields, explicit expectations must match the immutable original source snapshot; malformed/missing source cannot establish an explicit expectation.
- A new PostgreSQL regression reproduced the incompatible fingerprint change before the fix. A provider regression reproduced unintended expansion of a 45-minute workout to a three-hour Appointment before the duration fix.
- Provider UTC rendering works without a system IANA timezone database. Start/end offsets are calculated for their respective instants. Domain/runtime validation remains authoritative.
- Stale Appointment conflicts explain the next step: complete the Schedule workflow if needed, then generate and review a new F05 proposal.
- OpenAPI descriptions document this flow and the limits. Public endpoint shapes are unchanged; no migration is needed.

## Verified scenarios and evidence boundaries

Latest successful reports cover **139 distinct Backend tests across eight suites**: F05 PostgreSQL integration 46, F05 real-FastAPI integration 7, F05 service unit 24, context unit 9, validator unit 12, Workout schedule-change 21, Appointment HTTP 9, Schedule change-proposal 11. All have zero failures/errors/skips. Tests were run in stages; only affected suites reran after fixes (52-test integration run, then 70-test service/integration run). This is not a claim of one final 139-test command. Full AI Service pytest **90/90**, including 7 F05 provider cases, and Ruff passed. OpenAPI, 10 schemas/48 fixtures, 21 contract tests, UX docs (18 required files), and `git diff --check` passed. Two existing Starlette/httpx/AnyIO deprecation warnings remain.

See [machine-readable verification](artifacts/f05-runtime-evidence/bl03_verification_20261123.json) for commands, source diff fingerprint, suite counts, source/report hashes and run timestamps. Raw local logs are outside the repository under `D:/Projects/reports/f05-bl03-20261123/`; generated Surefire XML is under `services/backend/target/surefire-reports`.

| Requirement | Executable evidence |
|---|---|
| Both initiation directions | `f05Bl03_studentRequestsAppointmentReschedule_trainerAccepts_proposalAccepted`; `f05Bl03_trainerRequestsAppointmentReschedule_studentAccepts_proposalAccepted` use real Spring Schedule commands and PostgreSQL |
| Old proposal rejected, new one reviewed/applied | `f05Bl03_staleProposalBlockedWhenAppointmentVersionAdvances_freshProposalSucceeds`; `bilateralAppointmentRefresh_throughRealFastApi_preservesSnapshotAndAppliesNewProposal` additionally exercises MockMvc + real HTTP FastAPI + PostgreSQL, verifies original snapshot unchanged, 45-minute duration, link and no Actual log |
| Pending/rejected consent | `f05Bl03_pendingOrRejectedChangeRequest_doesNotChangeAppointment`; parameterized `f05Bl03_unacceptedRequests_doNotAuthorizeProposedSlot` |
| Cancelled Appointment | `f05Bl03_cancelledAppointment_blocksApplyWithoutCancellingWorkout` uses the real cancel command; the Workout stays SCHEDULED |
| Supervision | `f05Bl03_coachRequired_disjointMoveBlocked_butContainingChangeAndFreshProposalWork` verifies both the dependency rejection and a supported containing interval change followed by F05 application |
| Competing Schedule/F05 decisions | `f05Bl03_appointmentDecisionAndF05ApplyRace_onlyOneCommits` uses latches/futures, requires exactly one committed winner, and checks application count |
| Retry identity and compatibility | New Appointment expectation unit/integration tests, `f05Bl03_idempotentReplayPreservesFingerprintAndReceipt`, `f05Bl03_legacyReceiptFingerprint_stillReplays`, and `f05Bl03_legacyExplicitAppointmentReceipt_replaysOnlyMatchingSource` |
| Source drift, overlap, revocation, rollback | Existing F05, Workout schedule-change, Appointment and Schedule change-proposal suites rerun; includes T04 and proposal/apply audit failure regressions from BL-01/BL-02 |

Applicable acceptance IDs: T02/T03/T04 authority; T05 source/Appointment drift and competing writers; T06/T25 replay; T09 lineage/no Actual mutation; T10 supervision; T23 expiry/rollback; T27 proposal decision semantics. These scenario checks do not certify every possible variant of each broad test ID.

## Boundaries that remain OPEN

- **COACH_REQUIRED disjoint move:** existing approved Schedule guards reject an Appointment change that leaves the current SCHEDULED required occurrence outside the new interval (`APPOINTMENT_COACH_REQUIRED_DEPENDENCY`). This implementation does not weaken the guard or temporarily leave an invalid occurrence. An interval that still covers the current occurrence can be accepted, followed by a separately reviewed F05 move inside that interval. Moving both to a wholly disjoint interval requires an additional approved coordination design; it is not claimed as delivered here.
- **Expired/cancelled change requests:** V45's `appointment_requests_v2` status constraint permits only PENDING/ACCEPTED/REJECTED. Tests verify DB rejection of unsupported EXPIRED/CANCELLED values and unchanged consent/time; they do not prove an expiry/withdraw workflow exists. Cancellation of the Appointment itself is separately implemented/tested. No new lifecycle or migration is invented in this slice.
- **Availability/recovery:** Student/PT availability search and specialist physiological rules D06/OD-3 remain OPEN. A confirmed interval and deterministic slot do not prove recovery suitability.
- **Client/model/operations:** Mobile F05 journeys, real provider evaluation (quality/latency/usage/cost), remaining operational drills and feature/milestone sign-off remain OPEN. This work creates no recurring series and does not alter Actual Workout.
- The new HTTP integration uses MockMvc for inbound Spring requests, real HTTP to Uvicorn, and a simulated provider. It does not verify an external gateway, Mobile or a live LLM.

Assignee/reviewer ownership must come from official task tracking. This document does not infer developers from product roles or grant feature acceptance. Next useful work is client handover for the supported workflow, with the two explicit Schedule limits above visible to reviewers.
