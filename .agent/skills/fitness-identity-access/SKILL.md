---
name: fitness-identity-access
description: Design or implement common accounts, multi-role onboarding, Student and Trainer profiles, trainer verification, authentication, RBAC, permissions, consent, and object-level data access for the Digital Fitness platform.
---

# Fitness Identity and Access

Read `digital-fitness-core` first.

## Identity and onboarding

- Model a single `User` account with roles/capabilities; never create separate login identities for Student and Trainer.
- Create `StudentProfile` and `TrainerProfile` independently and only when the user activates that capability.
- Let a Student become a Trainer on the same account and let a Trainer later add a Student profile.
- A Trainer Profile is not coaching authority. Require verified application, `verification_status = VERIFIED`, `activity_status = ACTIVE`, and applicable policy/capacity checks.
- Model trainer application and verification as lifecycles, not booleans. Preserve application, review, certification, rejection, suspension, revocation, and expiry history.

## Authentication and authorization

- Use access and refresh tokens, refresh-token rotation, secure password hashing, server-side validation, and secure mobile token storage.
- Combine role/permission checks with capability state and object-level authorization.
- A `TRAINER` role never grants access to every Student. Check active Coaching Relationship, granted data type, access level, and time scope.
- Before relationship activation, expose only the Student-approved pre-coaching profile: experience, primary goal, availability, and permitted introduction data.
- Never rely on hidden frontend controls for authorization.

## Data sharing consent

Represent consent with at least `data_type`, `access_level`, `relationship_id`, `time_scope`, `granted_at`, and `revoked_at`. Consider Workout History, Body Metrics, Progress Photos, Nutrition, AI Recommendations, and conversations separately.

- New Trainers do not inherit old Trainer permissions.
- Historical read access does not imply access to private chat with a previous Trainer.
- Relationship end removes access to new data unless another valid grant exists.
- Admin access to private data requires a separate permission, purpose, scope, and privileged-access workflow.

## Account lifecycle

Distinguish `ACTIVE`, `SUSPENDED`, `DISABLED`, `LOCKED`, `PENDING_DELETION`, and `DELETED`. Use retention, anonymization, and audit-aware deletion rather than unconditional hard delete.

For every endpoint or use case, state the actor, required role/permission, capability state, resource relationship, consent scope, and denial behavior. Add tests for cross-account, cross-Trainer, expired/revoked consent, suspended Trainer, and pre-coaching access.
