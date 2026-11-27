# Workout Plan coaching-context UX fix

## Cause and scope

Base: `origin/main@7ebeb970db75dc0768144699e3c832c75c0a886c`, isolated branch
`codex/fix-workout-plan-coaching-context-ux`. B03/M2 Coaching owns period lifecycle;
B04/M3 Workout owns plan authorization. Student is actor and subject of self-directed
setup. This is Phase 1 source-domain/UX maintenance, not an AI feature or QR change.

The supplied QA finding (completed onboarding, zero open periods) explains that account's
failure, without proving all errors with the same code share a cause. Source inspection
confirms onboarding creates a Student profile without selecting authority, plan entry
allowed the full form, and no standalone self-directed setup command existed. Existing
period selection correctly requires `started_at <= now < ended_at` (unbounded end allowed).
This fix addresses a missing setup path and late UX validation; it does not change the
effective-period query or weaken Workout authorization.

## Behavior and review packet

- Before: Student enters the complete plan and receives `COACHING_PERIOD_REQUIRED` on save.
- After: plan hub and direct create route load current context first. Missing/expired
  context offers explicit self-directed confirmation or existing Coaching navigation.
  Form access requires a fresh successful GET showing effective SELF_DIRECTED.
- HUMAN_COACH explains Trainer plan authority. Existing ACTIVE/PAUSED relationships
  without an effective period and scheduled future periods require Coaching review.
  Network/permission errors never become missing data or self-directed authority.
- New narrow POST uses the existing Coaching lifecycle command infrastructure and
  existing period writer. It rejects existing effective/future periods and ACTIVE/PAUSED
  relationships, serializes with relationship acceptance on the Student lock, records
  audit/receipt atomically and rechecks capability on retries. It never closes/reassigns
  an existing period or edits fitness history. An old receipt projects current authority.
- Save rechecks context but never sends setup. If context is lost around create, the
  mounted editor retains name/description/session/prescription state and command identity.
  Setup failure permits reload/retry with the same setup key. Coaching navigation uses
  stack push; returning requires context recheck. Drafts are not persisted and are
  discarded on account/capability/session change. Old read/setup/create completions are fenced.
- No migration, dependency, Workout backend command, sharing policy, relationship command
  payload, expectedVersion, or old receipt semantics changed. Existing missing-context and
  HUMAN_COACH direct plan creation remain denied server-side.

## Validation

Local checks on 2026-11-27:

| Check | Result |
| --- | --- |
| Mobile context, Student/Trainer plan screens, API/payload/state/navigation regression | 8 suites, 77 tests PASS |
| Mobile TypeScript | PASS |
| Mobile lint | PASS; 12 existing warnings in Goal/QR tests, no errors |
| CoachingLifecycleIntegrationTest, WorkoutPlanPersistenceIntegrationTest, CoachingAuthorityServiceUnitTest, CoachingErrorAdviceTest | 54 tests PASS |
| Updated legacy ACTIVE/PAUSED-without-period regression | Focused rerun 1/1 PASS |
| OpenAPI / UX docs / schema contract regression | PASS / 18 required files PASS / 22 tests PASS |
| Staged diff whitespace check | PASS |

Self-review checked period boundaries, Student/Trainer authority, setup/save separation,
atomic audit/receipts, unchanged plan payload and retry identity, stale-session fencing,
and draft retention. GitHub head checks and required owner review are separate gates;
local self-review does not establish independent owner approval.

Tests use a disposable Testcontainers PostgreSQL 18 `coaching_lifecycle` / `workout_plan_b04`,
random host ports and fresh migrations. No mutation targets the supplied QA database.
No emulator was started. Device/native/browser visual acceptance is NOT_RUN; component
tests are not evidence of a device or whole-app PASS. Shared AppCard/AppButton and tokens
are reused; no new screen, reusable design token or navigation destination was introduced.
