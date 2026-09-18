---
name: fitness-admin-governance
description: Design or implement Admin governance, user and trainer operations, moderation, support, content publishing, AI operations, privileged access, data correction, configuration, feature flags, and permission-scoped audit workflows.
---

# Fitness Admin Governance

Read `digital-fitness-core` first.

## Authority boundary

Admin protects platform integrity, policy, content, trust, support, and operations. Admin does not edit a Student's Fitness/Nutrition Goal, accept proposals or AI recommendations, or act as Trainer. Technical corrections must use a validated, audited support workflow.

## Permission model

Use permissions rather than one omnipotent `ADMIN`, for example user suspend, trainer verify, exercise manage, knowledge publish, AI-run view, audit view, support correction, and system-config manage. Compose roles such as Super Admin, User Admin, Verification Admin, Content Admin, AI Admin, Support Admin, or Audit Viewer.

Apply MFA/step-up authentication to permission changes, account suspension, verification revocation, knowledge publication, deletion/anonymization, and critical configuration. Audit logs are immutable from Admin UI.

## Governance workflows

- Account operations: search/filter, suspend/unsuspend, disable, force logout, reset workflow, role/permission management.
- Trainer applications: draft/submitted/review/needs-information/approved/rejected/withdrawn; keep verification, activity, and certification separate.
- Content: draft/active/archive exercises; merge duplicates without breaking historical references.
- Knowledge: import/process/review/publish version; never edit an active version in place.
- Moderation: open/review/action-required/resolved/dismissed/escalated with evidence, reason, actor, and action history.
- Support: open/in-progress/waiting-user/resolved/closed. Data Correction records before/after, reason, case, actor, validation, and audit.

## Sensitive data and operations

Private chat, photos, detailed fitness, measurements, and nutrition are not visible by default. Create time-bound Privileged Data Access with reason, scope, target, opening/expiry, and audit for support, security, moderation, or legal purpose.

Provide AI Run Explorer and evaluation/replay without granting authority to apply outputs. Monitor jobs, notifications, integrations, and measurement ingestion failures. Retry/cancel only when rules allow.

Validate and audit feature flags and system configuration. Use staged/beta/percentage rollout for risky capabilities. Keep runtime telemetry in monitoring systems and business/audit records in PostgreSQL.
