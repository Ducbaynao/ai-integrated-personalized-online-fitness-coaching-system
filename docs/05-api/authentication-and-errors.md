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

