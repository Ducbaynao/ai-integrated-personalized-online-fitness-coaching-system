# Security and operations

Security is a core product requirement because the platform stores health-adjacent fitness, nutrition, photo, chat, account, and coaching data.

## Security controls

- strong password hashing and secure credential flows;
- short-lived access tokens, refresh-token rotation, revocation, and session/device visibility;
- role/permission plus object-level and relationship authorization;
- scoped Data Sharing Permission and purpose-based privileged access;
- rate limiting, input validation, secure file validation, TLS, and secret management;
- mobile secure storage for sensitive tokens;
- Admin MFA/step-up authentication for high-risk actions;
- append-only/immutable-from-Admin-UI audit for privileged actions;
- no secrets, tokens, unnecessary personal data, or raw sensitive payloads in logs.

## Operations

Production operation covers:

- structured logs, metrics, traces, health checks, and alerts;
- background job visibility and controlled retry/cancel;
- notification delivery state;
- integration health, last synchronization, error rate, and provider failures;
- Measurement ingestion/quality incidents;
- AI model/provider failures, validation failures, latency, and usage;
- backup, retention, point-in-time recovery where supported, and restore tests;
- configuration and feature flags with validation and audit.

## Deployment

Docker Compose supports local PostgreSQL, Redis, and development dependencies. Production may use managed or self-hosted infrastructure on AWS, Azure, Google Cloud, DigitalOcean, VPS, or another suitable provider. Deployment choice must preserve TLS, secrets, backups, observability, least privilege, and network isolation.

Spring Boot and FastAPI may be containerized. Mobile is distributed through platform channels, while Admin Web is deployed as a protected web application.

See [governance and audit](governance-and-audit.md).

