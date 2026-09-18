---
name: fitness-security-operations
description: Harden or operate the Digital Fitness platform across authentication, least privilege, secure uploads, privacy, audit, logging, observability, backup, deployment, CI/CD, rate limiting, and incident-safe operations.
---

# Fitness Security and Operations

Read `digital-fitness-core` and `fitness-identity-access` first.

## Security controls

Use password hashing, short-lived access tokens, refresh rotation/revocation, rate limiting, Bean Validation, parameterized persistence, HTTPS, secret management, secure mobile storage, and server-side authorization. Check both role/permission and object/relationship/consent scope.

For uploads, validate type, size, content, ownership, storage key, and download authorization. Treat progress photos, food images, chat attachments, certifications, and health data as sensitive.

Apply least privilege to Admin. Require MFA/step-up for high-impact actions and create time-bound privileged-access records for private data. Never log passwords, tokens, raw prompts containing unnecessary personal data, or sensitive health/chat content.

## Audit and privacy

Audit actors, action, target, reason, before/after or immutable references, time, request/case, and authorization context for important business and privileged actions. Admin UI cannot modify audit history. Separate audit events from ordinary application logs.

Support account deletion through disable, retention, legal/policy checks, then delete or anonymize. Preserve referential and audit obligations without retaining unnecessary identifiable data.

## Observability and reliability

Use structured logs with request ID, safe user/resource identifiers, endpoint, status, latency, and error code. Add health checks, metrics, traces, and alerts using Spring Actuator/Micrometer and an appropriate monitoring stack. Keep raw operational telemetry outside PostgreSQL unless it is a business/audit record.

Make background jobs idempotent and observable. Track retries, dead/failed state, and external integration health without exposing raw Student data to operators.

## Delivery and recovery

Containerize development components with Docker/Compose. CI should run tests, static checks, build artifacts/images, migration checks, and deployment gates. Keep environment secrets out of Git.

Back up PostgreSQL automatically with retention, point-in-time recovery when available, and regular restore tests. A backup is not valid until restoration is proven. Define rollback for application and compatible forward/repair strategy for migrations.

Treat privacy, authority, data integrity, and recoverability as release criteria, not later enhancements.
