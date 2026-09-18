# Non-functional requirements

## Performance and workload separation

Normal business APIs should respond quickly under expected load. AI, image processing, notification delivery, and analytical aggregation are distinct workloads and may use asynchronous processing. Performance targets must be measured per endpoint class rather than hiding slow business operations behind one platform-wide number.

## Scalability

- Spring Boot should remain stateless where practical so multiple instances can run behind a load balancer.
- The backend starts as a modular monolith; extraction requires measured scaling, isolation, deployment-cadence, or ownership evidence.
- Read replicas, table partitioning, dedicated vector infrastructure, message brokers, and separate services are later options, not Phase 1 defaults.
- Historical tables such as logs, messages, notifications, AI Runs, and Audit Logs are candidates for time partitioning only when data volume justifies it.

## Reliability and integrity

- Important multi-record business changes must use transactions.
- PostgreSQL constraints must include primary keys, foreign keys, uniqueness, and domain-appropriate checks.
- Idempotency or deduplication is required for retried external ingestion and background processing where duplicates would corrupt history.
- Backup policy must include retention and periodic restore tests; a backup is not considered valid until restoration is proven.
- PostgreSQL is authoritative; Redis caches and temporary job state are disposable.

## Maintainability

- Code is organized by domain modules with enforced boundaries.
- Controllers must not contain business logic, external DTOs must not expose persistence entities, and modules must not directly use another module's repository.
- Database, API, Workout Plan, Goal, Nutrition Target, Knowledge, prompt, and model changes must retain appropriate version history.
- APIs are versioned from `/api/v1`.
- Structured logging and stable error codes must make production incidents diagnosable.

## Security and privacy

- Password hashing, JWT validation, refresh-token rotation, authorization, rate limiting, input validation, secure upload, TLS, and secret management are mandatory controls.
- Tokens on mobile must use platform-appropriate secure storage.
- Logs must not contain passwords, access tokens, unnecessary personal data, or raw sensitive payloads.
- Administrator access uses least privilege, step-up authentication for high-risk actions, and immutable audit records.
- Private chat, Progress Photos, detailed Measurements, and Nutrition records must not be broadly visible to Administrators.

## Observability and availability

Production architecture must support structured logs, metrics, tracing, health checks, and alerts. Suggested technologies include Spring Boot Actuator, Micrometer, Prometheus, Grafana, and OpenTelemetry, introduced according to phase needs.

Logs should carry `request_id`, actor/user reference where permitted, endpoint or operation, status, latency, and stable error code. Cross-service calls should preserve correlation identifiers.

## Data quality and explainability

- Every derived progress or attention signal must be traceable to source records or evidence.
- AI recommendations must expose relevant basis, uncertainty, missing context, and validation outcome.
- Measurements and AI food estimates must preserve provenance and quality.
- Missing data must remain distinguishable from measured zero.

## Accessibility and international behavior

- Timestamps must be stored consistently and displayed in the user's timezone.
- Units must be explicit and normalized; presentation may adapt to user preference.
- User-facing validation and error messages must be understandable without revealing sensitive system details.

