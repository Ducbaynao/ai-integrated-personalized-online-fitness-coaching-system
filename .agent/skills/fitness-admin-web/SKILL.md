---
name: fitness-admin-web
description: Design or implement the React TypeScript Admin Web for permission-scoped user, trainer, content, knowledge, AI operations, moderation, support, audit, configuration, jobs, integrations, and system-health workflows.
---

# Fitness Admin Web

Read `digital-fitness-core` and `fitness-admin-governance` first.

## Information architecture

Organize navigation around Dashboard; Users and Roles; Trainers and Verification; Moderation and Support; Exercise/Content; Knowledge; AI Operations; Data Operations and Integrations; Notifications/Jobs/System Health; Security/Audit; and Configuration/Feature Flags.

Render only capabilities allowed by the current permission, but assume the backend performs the real authorization. A hidden button is not a security boundary.

## Dashboard and workflows

Lead with Action Required: pending Trainer applications, reports, suspicious accounts, content review, failed AI runs/jobs, degraded integrations, incidents, and suspect measurement ingestion. Pair each item with severity, age, evidence, owner/status, and next action. KPI-only dashboards are insufficient.

Use searchable/filterable tables for repeated records, but use purpose-built detail/workflow screens for approvals, cases, corrections, privileged access, and publishing. Show state transitions and immutable history.

Require confirmation, reason, and step-up status for sensitive actions. Never add controls that let Admin directly edit Student Goals, plans, nutrition targets, workout logs, or measurements outside an authorized correction workflow.

## Sensitive access

Private data panels must require an active privileged-access grant with visible purpose, scope, and expiry. Avoid bulk export or broad data loading. Redact sensitive values in list views and logs where full detail is unnecessary.

## Operational UX

AI Run detail should expose model/prompt versions, request type, validation/rule outcome, latency, token use, error, and recommendation outcome without an Apply button. Replay/evaluation is isolated from live data changes.

Job retry/cancel, feature flag rollout, knowledge publish, trainer verification, moderation, and support correction need conflict/error handling, idempotency feedback, and audit reference. Cover loading, empty, partial, permission-denied, stale, and concurrent-update states.
