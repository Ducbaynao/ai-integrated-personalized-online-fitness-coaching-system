# B04 Workout Plan

## Domain and persistence checkpoint

V28 establishes the Workout-owned persistence authority for plan lifecycle, version publication, materialized occurrences, and Student successor lineage. Controllers, OpenAPI, Mobile flows, workout logging, appointment workflows, and AI-provider integration are separate checkpoints.

The exact lifecycle is `DRAFT -> ACTIVE|ARCHIVED`, `ACTIVE -> PAUSED|COMPLETED|ARCHIVED`, `PAUSED -> ACTIVE|COMPLETED|ARCHIVED`, and `COMPLETED -> ARCHIVED`. `ARCHIVED` is terminal and same-state commands conflict. A partial unique index permits at most one `ACTIVE` plan per Student; commands never transition another plan implicitly.

`workout_plans.version` is the aggregate concurrency token. `workout_plan_versions.version_number` is the business content sequence. Initial activation locks version 1. Significant publication locks the current plan/version, rechecks authority and the expected aggregate version, locks active Exercise references in UUID order, obtains one `clock_timestamp()`, closes the old version and opens the new version on that same boundary, locks the new version, and commits history/audit/receipt atomically. Existing `planned_workouts` are not updated.

Decision ownership is explicit. A Trainer-owned historical aggregate remains Trainer-authored evidence after human coaching ends. In a current `SELF_DIRECTED` period, its Student may deep-copy a selected locked version into a new Student-owned DRAFT plan with paired `based_on_plan_id` and `based_on_plan_version_id`; the source is unchanged and `ownership_transferred_to_student_at` is not used.

MINOR is strictly one already-materialized occurrence. It is represented by append-only `workout_session_adjustments`; an exercise swap retains the original prescription and stores the active replacement variation separately. Template, future-occurrence, and multi-occurrence intent requires significant version publication.

Workout consumes Coaching-owned `CurrentCoachingContextQuery` and `CoachingAuthorityQuery`; it never reads Coaching persistence. Trainer plan mutation requires `WORKOUT_PLAN` + `MANAGE`, current Trainer read requires `VIEW`, and historical read uses `WORKOUT_PLAN_HISTORY` with the resource timestamp. Missing current Coaching context fails closed. Workout also consumes the Exercise-owned `ExerciseReferenceQuery`: authoring accepts and locks only active references, while historical resolution preserves the original ID and may display archived/unavailable state plus optional canonical metadata.

Every mutation uses an actor-scoped stable command key and normalized SHA-256 intent fingerprint. A matching committed receipt replays a privacy-minimal result; mismatched reuse conflicts. Receipts, status history, audit, and domain writes share the transaction. The B05 snapshot and B06 scheduling interfaces are Workout-owned boundaries; neither downstream module may import Workout persistence.
