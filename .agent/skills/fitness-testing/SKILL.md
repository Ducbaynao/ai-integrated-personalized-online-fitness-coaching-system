---
name: fitness-testing
description: Plan, implement, or review unit, integration, contract, authorization, migration, AI, mobile, and end-to-end tests for the Digital Fitness platform, emphasizing lifecycle and authority invariants.
---

# Fitness Testing

Read `digital-fitness-core` and every domain skill touched by the behavior under test.

Use JUnit, Mockito, Spring Boot Test, and Testcontainers for backend; pytest for AI; Jest and React Native Testing Library for mobile. Prefer observable state transitions, authorization outcomes, persisted history, and derived metrics over tests that mirror implementation details.

## Mandatory cross-domain scenarios

- Common account adds Student and Trainer profiles independently; Trainer authority activates only after verified/active state.
- Cross-Trainer and pre-coaching access is denied unless relationship, data type, level, and time scope permit it.
- Goal proposal approval creates the correct version or new Goal/transition; prior history remains intact.
- Mode/Trainer changes create Coaching Period history without resetting Goal, plan, workout, measurement, or nutrition history.
- Student can continue a delivered plan after coaching ends while former Trainer cannot edit it.
- Significant plan changes version; minor session adjustments do not.
- Planned versus performed date, missed/rescheduled workout, completion, and schedule adherence remain independent.
- Reschedule acceptance re-checks conflicts transactionally; recurring scope is honored; cancelled Appointment does not cancel an eligible Workout.
- `COACH_REQUIRED` cannot become self-performed silently.
- Measurement normalization, outlier state, provenance, deduplication, optional metrics, and `NULL` handling are correct.
- Missing/partial Food Log never becomes zero intake; target/actual and target versions remain separate.
- AI structured output fails closed on invalid schema, unavailable/suspect data, unsupported inference, equipment/recovery rules, or inactivity.
- AI acceptance applies only through the authorized actor and backend; evaluation/replay cannot mutate live business data.
- Admin permission denial, step-up, privileged scope/expiry, immutable audit, and correction before/after are enforced.

## Test layers

Test domain state machines and calculations in isolation. Use repository/transaction integration tests for constraints, effective ranges, concurrency, and Flyway. Add API contract/error tests and WebSocket/realtime reconciliation. Use end-to-end tests for high-value Student, Trainer, and Admin journeys.

Include timezone/DST behavior, retries/idempotency, upload authorization, forced logout, notification preferences, stale clients, concurrent approval, rollback, backup restore, and backward compatibility when relevant.

For defects, first add a failing regression test that expresses the violated product invariant, then apply the narrowest fix.
