# Authentication authorization and errors

## Authentication

The initial identity flow uses email/password and returns short-lived Access Token plus rotatable Refresh Token. Refresh tokens are stored, rotated, revoked, and bound to session/device policy as appropriate. Future Google, Apple, or Facebook login must map to the same User Account model.

## Authorization layers

Endpoints enforce all relevant layers:

1. authenticated identity and valid session;
2. coarse role or permission;
3. capability state, such as verified/active Trainer;
4. object-level relationship or ownership;
5. Data Sharing Permission and time scope;
6. purpose/privileged access for sensitive Admin reads;
7. current domain state and decision authority;
8. source version/concurrency guard.

A frontend control is not authorization. Every request is checked on the server.

## Recommended status mapping

| Condition | HTTP status |
| --- | --- |
| Invalid input/field validation | `400` |
| Missing/invalid authentication | `401` |
| Authenticated but unauthorized | `403` |
| Resource not found or intentionally concealed | `404` |
| State/version/conflict violation | `409` |
| Business rule rejected with valid syntax | `422` where adopted consistently |
| Rate limit | `429` |
| Unexpected server failure | `500` |
| Upstream AI/provider unavailable | `502` or `503` according to failure |

## Stable error codes

Examples include:

- `AUTH_TOKEN_EXPIRED`, `AUTH_SESSION_REVOKED`;
- `TRAINER_NOT_ELIGIBLE`, `COACHING_RELATIONSHIP_NOT_ACTIVE`;
- `DATA_SCOPE_NOT_GRANTED`, `PRIVILEGED_ACCESS_REQUIRED`;
- `GOAL_PROPOSAL_NOT_PENDING`, `SOURCE_VERSION_CHANGED`;
- `APPOINTMENT_TIME_CONFLICT`, `RESCHEDULE_REQUEST_EXPIRED`;
- `COACH_REQUIRED`, `WORKOUT_ALREADY_COMPLETED`;
- `MEASUREMENT_REJECTED`, `UNSUPPORTED_UNIT`;
- `NUTRITION_LOG_INCOMPLETE` when relevant as a warning/business condition;
- `AI_OUTPUT_INVALID`, `AI_RULE_BLOCKED`, `AI_CONTEXT_INSUFFICIENT`.

Errors should state what the caller can do next without revealing unrelated data. The `requestId` must correlate API, backend, AI-service, and operational logs.

## Rate limiting status

Rate limiting (`429 Too Many Requests`) and its accompanying `Retry-After` response header are fully defined in `contracts/openapi/openapi.yaml`. Active enforcement is assigned to Milestone M8 / infrastructure reverse proxy and Redis token-bucket rate limiter. For the M1A identity and email verification slice, 429 rate limiting remains deferred while the error contract and status mapping remain reserved.

## Email verification delivery status

In Milestone M1A, email verification delivery is implemented as follows:
- **Local Development**: The simulated email sender bean `DevelopmentVerificationEmailSender` requires explicit activation via the `dev` profile (`spring.profiles.active=dev`). It simulates verification dispatch without logging plaintext tokens.
- **Testing**: Test suites explicitly activate the `test` profile (`@ActiveProfiles("test")`), which provides a dedicated in-memory capturing adapter (`TestVerificationEmailSender`).
- **Production & Default Environments**: The default/production configuration does not register a fallback or dummy email sender. If a production-ready mail provider is not configured, Spring Boot will fail fast at startup to prevent silent message loss.
- **Post-Commit Delivery & Deferrals**: M1A dispatches verification emails post-commit to ensure transaction integrity. If dispatch fails, the system increments the Micrometer metric `auth.email.verification.delivery.failures` and logs a structured warning. External durable outbox delivery, persistent retry queues, and user-initiated resend workflows are deferred to Milestone M1B/M2. M1A is explicitly **not production-email-ready**.
