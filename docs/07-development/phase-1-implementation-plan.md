# Phase 1 Implementation Plan

Phase 1 delivers a usable fitness coaching platform without depending on AI. Work is organized as vertical milestones so Mobile, Admin Web, Backend, database, contracts, and tests evolve together. The target-product boundaries remain valid even when a milestone exposes only a smaller workflow.

## Delivery principles

- Implement one end-to-end workflow at a time instead of generating all persistence entities first.
- Treat Spring Boot as the business authority and PostgreSQL as the system of record.
- Keep `HUMAN_COACH` and `SELF_DIRECTED` as the only Coaching Modes. AI is not required for Phase 1.
- Preserve history, effective dates, source versions, authority, and audit from the first implementation.
- Update OpenAPI, Flyway, tests, UI states, and documentation in the same feature change.
- Do not let Mobile or Admin call the AI service or database directly.
- A milestone is complete only when its happy path, authorization failures, lifecycle conflicts, missing-data behavior, and migrations are tested.

## Milestone overview

| Milestone | Outcome | Primary modules | Depends on |
| --- | --- | --- | --- |
| M0 Foundation | Reproducible local and CI baseline | common, infrastructure | none |
| M1 Identity and Common Account | A person can authenticate and activate Student or Trainer capabilities on one account | auth, user, student, trainer, audit | M0 |
| M2 Goals and Coaching Authority | Student-owned goals and period-based coaching authority work end to end | goal, coaching, trainer, student | M1 |
| M3 Exercise and Workout Planning | Authorized users can create and assign versioned plans | exercise, workout, coaching | M2 |
| M4 Workout Execution and Schedule | Planned and actual workouts plus coaching appointments work without lifecycle coupling | workout, schedule | M3 |
| M5 Measurements and Progress | Time-series measurements and deterministic progress signals are available | measurement, progress | M3-M4 |
| M6 Nutrition Foundation | Student-owned targets and actual food logging remain separate | nutrition, goal, progress | M2, M5 |
| M7 Communication and Attention | Coaching communication and rule-based attention workflows work | chat, notification, progress | M2-M6 |
| M8 Admin Governance and Phase 1 Release | Minimum platform governance, audit, support, and release hardening are complete | administration, audit, moderation, support | M1-M7 |

## M0 Foundation

### Scope

- Confirm Java 21, Node 22.13+, Python 3.12, Docker Compose, PostgreSQL, Redis, and MinIO setup.
- Run Flyway migrations from `services/backend/src/main/resources/db/migration` against a clean PostgreSQL database.
- Establish stable error envelopes, request/correlation identifiers, clock/timezone conventions, and test fixtures.
- Add contract validation and migration checks to CI as contracts become executable.

### Exit criteria

- A new developer can follow `docs/07-development/setup.md` on a clean machine.
- Backend, Mobile, Admin Web, and AI health checks build or start independently.
- All existing migrations apply to an empty database.
- CI reports separate failures for client, backend, AI, contract, and migration checks.

## M1 Identity and Common Account

M1 implements `FR-ID-001` through `FR-ID-007` at the minimum depth required for account creation and capability activation. The executable REST contract is `contracts/openapi/openapi.yaml`.

### Included workflows

1. Register with email and password.
2. Confirm email using a one-time token.
3. Login and receive access and rotating refresh tokens.
4. Refresh a session, revoke the replaced token, and detect invalid/revoked tokens.
5. Logout the current session.
6. Read and update the current User profile and settings.
7. Create a Student Profile without creating a second account.
8. Create a Trainer Profile without automatically granting coaching authority.
9. Submit and view the current Trainer Application.
10. Derive coaching eligibility from verification, activity, and policy state rather than the existence of a Trainer Profile.

### Explicitly deferred

- External identity providers.
- Password reset UI, device/session management UI, and Admin Trainer review UI.
- Coaching Relationship creation and Student data access.
- Media upload for certificates; the contract may accept already-created media identifiers when that capability is added.

### Backend work

- `auth`: password hashing, authentication, access-token issuance, refresh rotation/revocation, email verification, logout.
- `user`: current-user query/update and settings.
- `student`: Student Profile activation and update.
- `trainer`: Trainer Profile activation, application submission, application status query, and eligibility policy.
- `audit`: security and lifecycle events for registration, verification, login failure, refresh reuse, logout, profile activation, and application submission.

### Client work

- Shared authentication/session bootstrap and forced-logout handling.
- Registration, verification, login, role-intent selection, Student onboarding, Trainer onboarding, and application-status screens.
- Loading, duplicate-submit prevention, validation, offline/network error, token expiry, permission denied, and rejected/suspended states.
- Store tokens only in platform-appropriate secure storage on Mobile. Do not persist health, chat, or credential data in insecure storage.

### Required tests

- Email uniqueness is case-insensitive.
- Passwords and one-time/refresh tokens are never stored in plaintext.
- Refresh rotation revokes the replaced token; replay is rejected and audited.
- One User can own both Student and Trainer Profiles without losing either history.
- Creating a Trainer Profile does not grant Trainer coaching authority.
- A non-approved, inactive, expired, revoked, or suspended Trainer cannot coach.
- A rejected request exposes a safe status/reason without leaking internal review notes.
- API DTOs do not expose password hashes, token hashes, or persistence entities.

### Exit criteria

- OpenAPI M1 paths and schemas validate.
- Backend contract/integration tests cover all M1 endpoints and stable errors.
- Mobile can complete registration, login, session refresh, and capability onboarding against Backend.
- Admin-facing application review may remain deferred, but application state is persisted and auditable.

## M2 Goals and Coaching Authority

### Scope

- Fitness Goal, Goal Target, Goal Proposal, Goal Version, Goal Transition.
- Coaching Relationship, Coaching Period, and scoped Data Sharing Permission.
- `SELF_DIRECTED` to `HUMAN_COACH` transitions without resetting history.
- Student acceptance/rejection of Trainer-originated Goal Proposals.

### Exit criteria

- Same-journey changes create a new effective Goal Version.
- New journeys create a new Goal and a traceable Goal Transition.
- Coaching Mode is recorded on Coaching Period, never as a permanent Student Profile flag.
- Former Trainers lose edit authority while retained history remains readable according to scope.

## M3 Exercise and Workout Planning

### Scope

- Exercise catalog, variations, equipment, muscle groups, instructions, media references, and archive/canonical mapping.
- Self-directed plan/template creation.
- Trainer plan authority during a valid `HUMAN_COACH` period.
- Workout Plan Versions and minor adjustment history.

### Exit criteria

- Significant plan changes create a version; minor changes do not create noisy full versions.
- Historical sessions retain their source plan version.
- A Trainer-delivered plan remains available to the Student after coaching ends according to the retention rule, while the former Trainer cannot edit it.

## M4 Workout Execution and Schedule

### Scope

- Planned Workout, Actual Workout, Exercise Log, Set Log, RPE, completion, and adherence.
- Coaching Appointment, recurrence, timezone, conflict detection, change request, and change history.
- `SELF_PERFORMABLE`, `COACH_OPTIONAL`, and `COACH_REQUIRED` supervision behavior.

### Exit criteria

- Planned date and performed date are stored separately.
- Completion and schedule adherence are calculated separately.
- Accepting a reschedule request rechecks conflicts in the final transaction.
- Cancelling an Appointment does not silently cancel an executable Planned Workout.

## M5 Measurements and Progress

### Scope

- Metric Definitions, units, manual Measurement entry, provenance, validation, and time-series history.
- Progress Photos through object-storage references.
- Training Activity/Inactivity Periods, return-to-training review, and deterministic Progress Engine signals.
- Current Goal and lifetime progress views.

### Exit criteria

- Missing optional data remains unknown, never zero.
- Conflicting observations retain provenance and resolution history.
- Progress and Attention Signals reference their evidence and calculation version.
- Long inactivity prevents blind reuse of old progression assumptions.

## M6 Nutrition Foundation

### Scope

- Nutrition Goal, Proposal, Target Version, day-type rules, Daily Override, Meal, Food Log Item, and completeness.
- Deterministic nutrient calculation from confirmed food and quantity.

### Exit criteria

- Strategic changes require Student approval and an effective-dated target version.
- A one-day override does not create a strategic version.
- Target and actual intake are never stored as the same fact.
- No food log and partial logging are distinguishable from zero intake and complete logging.

## M7 Communication and Attention

### Scope

- Persisted conversations, membership, messages, attachments, receipts, and policy-compliant deletion.
- In-app/push notification preferences and delivery state.
- Deterministic Attention Signals and Coaching Review Cycle.

### Exit criteria

- Durable chat facts survive reconnects and are authorized by relationship/data scope.
- Repeated delivery is idempotent.
- System/Rule Alerts remain distinct from future AI Recommendations.

## M8 Admin Governance and Phase 1 Release

### Scope

- Permission-based Admin access, Trainer Application review, account lifecycle, exercise/content governance, audit, basic moderation/support, and data correction workflow.
- Phase 1 security, accessibility, observability, backup/restore, and release verification.

### Exit criteria

- Admin is Platform Authority, not Coaching Authority.
- Sensitive actions require permission, reason, appropriate step-up, and immutable audit.
- Referenced content is archived/versioned instead of destructively deleted.
- Restore testing and production health verification are documented and exercised.

## Feature delivery checklist

For every feature inside a milestone:

1. Select the `FR-*` requirements and acceptance scenarios.
2. Identify owning module, actor, authority, lifecycle, and history behavior.
3. Update OpenAPI/event schemas before or with client/backend work.
4. Add a new Flyway migration only when the existing physical model is insufficient; never edit an applied migration.
5. Implement domain/application logic before transport adapters.
6. Add unit, authorization, integration, migration, and contract tests.
7. Implement all relevant UI states and accessibility requirements.
8. Update traceability and documentation in the same pull request.

## Phase 1 definition of done

Phase 1 is complete when M0-M8 exit criteria pass for the selected MVP scope, all executable contracts match deployed behavior, clean-database migration and supported upgrade tests pass, critical workflows have authorization and history-preservation tests, and the product can operate without AI. Phase 2 may add AI Assistance only through Spring Boot orchestration and human approval rules.
