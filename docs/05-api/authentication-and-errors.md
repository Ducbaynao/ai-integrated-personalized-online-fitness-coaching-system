# Authentication authorization and errors

## Authentication

The initial identity flow uses email/password and returns short-lived Access Token plus rotatable Refresh Token. Refresh tokens are stored, rotated, revoked, and bound to session/device policy as appropriate. Future Google, Apple, or Facebook login must map to the same User Account model.

### M1B session behavior

- `POST /auth/sessions` accepts an email, password, and optional device name. Only an `ACTIVE` account can create a session.
- Access tokens are signed HS256 JWTs containing the issuer, User ID subject, issue/expiry timestamps, token ID, email, and the roles currently available to the account. The default access-token lifetime is 15 minutes.
- Refresh tokens are opaque 256-bit random values. The raw value is returned only in the successful response; PostgreSQL stores only its SHA-256 hash. The default lifetime is 30 days.
- `POST /auth/token-refreshes` atomically revokes the presented token and creates a linked replacement. A concurrent loser or replay of an already-revoked token revokes all still-active refresh tokens for that User and records a critical security event.
- Unknown email and wrong password return the same `401 INVALID_CREDENTIALS` response. A dummy BCrypt comparison is performed for an unknown account to reduce timing-based account discovery.
- Correct credentials for a non-active account return `403 ACCOUNT_UNAVAILABLE`. Invalid, expired, or revoked refresh credentials return `401 INVALID_REFRESH_TOKEN`.
- M1B's token-pair response includes the contract's current-user projection published by the `user` module:
  - Active roles come from PostgreSQL (`fitness.user_roles` where `revoked_at IS NULL`).
  - Capabilities are derived from active roles, profile existence (`hasStudentProfile`, `hasTrainerProfile`), and the canonical coaching eligibility policy (`canCoach` evaluates account active status, active TRAINER role, trainer profile existence, active status, verification status `VERIFIED`, activity status `ACTIVE`, and policy restriction).
  - User settings come from PostgreSQL (`fitness.user_settings`); missing optional settings fall back to defaults according to the API contract (`weekStartsOn = 1`, `measurementSystem = 'METRIC'`, empty preferences).
  - Malformed or non-object persisted settings JSON is treated as a server/data integrity failure, not silently accepted as missing settings.
- Logout is implemented in Milestone M1C.

### M1C session logout behavior

- `DELETE /auth/sessions` accepts a `LogoutRequest` (`refreshToken`) and requires an authenticated Bearer JWT access token. Unauthenticated or invalid token requests return `401 UNAUTHORIZED` (or `AUTH_TOKEN_EXPIRED`). The User ID is derived exclusively from the verified JWT subject.
- **Idempotent Revocation**: Atomically revokes the presented active refresh token belonging to the authenticated User by setting `revoked_at` and `revoke_reason = 'LOGOUT'`. Successful revocation returns HTTP `204 No Content`.
- **Safe Ownership & Non-Enumeration**: If the presented refresh token does not exist, belongs to another user, is already revoked, or is expired, the endpoint returns HTTP `204 No Content`. The server never revokes another user's token and never reveals whether an unknown or cross-user token exists. When a presented token belongs to another user, a security event (`LOGOUT_TOKEN_OWNER_MISMATCH`, severity `MEDIUM`) is recorded for the authenticated caller without exposing cross-user identifiers or token details.
- **Repeated & Concurrent Idempotency**: Subsequent or concurrent logout requests for the same token return HTTP `204 No Content`. Only the first successful revocation mutates the database row and emits the `SESSION_REVOKED` audit record and `SESSION_LOGOUT` security event; subsequent calls perform 0 row updates and omit duplicate audit entries.
- **Targeted Scope & Session Isolation**: The logout operation itself revokes strictly the presented session (the single refresh token passed in `LogoutRequest`). Other sessions and devices belonging to the user remain active immediately after logout. Multi-device logout and Admin force-revocation remain deferred.
- **Replay Protection vs. Immediate Session State**: Refresh tokens revoked via logout cannot be refreshed. If an adversary or faulty client later attempts to use that already-revoked token at `POST /auth/token-refreshes`, that subsequent request triggers the existing M1B token-reuse policy: all remaining active refresh tokens for that user are revoked with reason `REUSE_DETECTED`, and a critical security event `REFRESH_TOKEN_REUSE` is recorded. In normal operation where the revoked token is discarded, other sessions remain active indefinitely until their own expiration or logout.
- **Stateless Access Token Lifecycle**: Access tokens are stateless HS256 JWTs that remain cryptographically valid until expiration; the platform intentionally does not maintain a server-side token blacklist. Clients are strictly responsible for clearing locally stored access and refresh tokens upon receiving HTTP `204 No Content`.

### Current User profile and settings (M1)

- `GET /users/me` requires an authenticated Bearer JWT access token. Unauthenticated requests or invalid tokens return `401 UNAUTHORIZED`. The User ID is derived exclusively from the verified JWT subject.
- **Contract Projection**: Returns `CurrentUserResponse` combining user account identity (`id`, `email`, `displayName`, `status`, `preferredLocale`, `timezone`, `emailVerifiedAt`, `createdAt`, `phoneNumber`), active roles (`fitness.user_roles` where `revoked_at IS NULL`), capabilities (`hasStudentProfile`, `hasTrainerProfile`, and `canCoach` derived from the canonical coaching eligibility policy), and user settings.
- **Default Settings Fallback**: If the user does not yet have a record in `fitness.user_settings`, the query returns default contract settings (`weekStartsOn = 1`, `measurementSystem = 'METRIC'`, empty maps for `accessibilityPreferences` and `privacyPreferences`).
- `PATCH /users/me` accepts `UpdateCurrentUserRequest` with partial updates to mutable account profile attributes and settings. Requires at least one property (`minProperties: 1`).
- **Partial Update Semantics**:
  - Omitted fields in the request payload are preserved without modification.
  - An explicit `"phoneNumber": null` clears the phone number; omitting `phoneNumber` leaves the existing phone number unchanged.
  - Partial nested `settings` updates only specified fields while preserving existing persisted settings fields. If no prior settings record exists, missing fields are initialized with contract defaults.
  - Profile and settings updates are executed in a single atomic database transaction (`@Transactional`); any failure triggers a complete rollback.
  - Updates `updated_at` timestamps on modified rows.
  - Read-only fields cannot be altered: `email`, `status`, `roles`, `capabilities`, and coaching authority cannot be modified through this endpoint.
  - Returns the updated `CurrentUserResponse`.
- **Sensitive Data Redaction**: `phoneNumber` and `privacyPreferences` are treated as sensitive data and redacted (`[REDACTED]`) in all DTO, model, command, view, and logging string representations (`toString()`).

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
- **Post-Commit Delivery & Deferrals**: M1A dispatches verification emails post-commit to ensure transaction integrity. If dispatch fails, the system increments the Micrometer metric `auth.email.verification.delivery.failures` and logs a structured warning. External durable outbox delivery, persistent retry queues, and user-initiated resend workflows remain deferred beyond M1B. M1A/M1B are explicitly **not production-email-ready**.

## Admin Trainer Verification behavior (M1J)

Milestone M1J implements administrative review, approval, and rejection of trainer verification applications.

### Endpoints
- `GET /api/v1/admin/trainer-applications`:
  - Lists applications with optional `status` filter (defaults to `PENDING` queue).
  - Deterministic ordering: `submitted_at ASC, id ASC` (FIFO queue for administrative attention).
  - Pagination: `page` (0-indexed, default `0`), `size` (default `20`, maximum `100`).
  - Response contains page metadata (`items`, `page`, `size`, `totalItems`, `totalPages`).
- `GET /api/v1/admin/trainer-applications/{applicationId}`:
  - Detailed view of a single trainer application, exposing administrative review notes (`reviewNotes`), reviewer identity (`reviewedBy`), timestamps, attached certificates, and verification documents.
- `POST /api/v1/admin/trainer-applications/{applicationId}/decisions`:
  - Records verification decision (`APPROVE` or `REJECT`).
  - Validation: When `decision` is `REJECT`, `rejectionReason` is required and non-blank (max 2000 characters). When `decision` is `APPROVE`, `rejectionReason` must be null or omitted. `reviewNotes` is optional (max 2000 characters).

### Security and Authorization
- All endpoints enforce `@PreAuthorize("hasRole('ADMIN')")` at the controller layer.
- At the domain/service layer, `AdminTrainerVerificationService` verifies both:
  1. The authenticated account is in `ACTIVE` state in PostgreSQL (`AccountUnavailableException` -> `403 ACCOUNT_UNAVAILABLE`).
  2. The authenticated user has an active, non-revoked `ADMIN` role in `fitness.user_roles` (`AccessDeniedException` -> `403 ACCESS_DENIED`).
- Stale tokens with revoked roles or suspended accounts are rejected against the system of record.

### Concurrency and Transaction Boundary
- State transitions are strictly atomic within a `@Transactional` boundary.
- Concurrency protection combines row-level locking (`SELECT ... FOR UPDATE`) with conditional update (`UPDATE fitness.trainer_applications SET ... WHERE id = ? AND status = 'PENDING'`).
- If an application is not in `PENDING` state or another administrator decides it concurrently, the loser receives `409 TRAINER_APPLICATION_ALREADY_DECIDED`.
- State updates mutate `trainer_applications`, update `trainer_profiles` (`verification_status = VERIFIED` with `verified_at`/`verified_by`, or `REJECTED` with null verification timestamp), record immutable `trainer_application_status_history`, and emit `audit_logs` (`TRAINER_APPLICATION_APPROVED` or `TRAINER_APPLICATION_REJECTED`).
- Any failure during audit logging or downstream operations triggers a complete transaction rollback.

### Boundary and Decoupling Invariants
- Admin verification sets profile verification status only. It **never** creates coaching relationships and **never** directly grants coaching authority.
- `canCoach` remains derived from the canonical coaching eligibility policy: even an approved trainer cannot coach if their profile is inactive, their activity status is not `ACTIVE`, or their account is suspended.
- Administrative review notes (`reviewNotes`) are strictly confidential and never exposed to trainers via self-service endpoints (`GET /trainer-applications/me/current`).
