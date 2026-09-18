# API conventions

Spring Boot exposes versioned REST APIs and WebSocket channels. Clients use DTO contracts; persistence entities are never external response models.

## Resource conventions

- Prefix public REST endpoints with `/api/v1`.
- Use nouns for resources and explicit action subresources when a lifecycle decision is not ordinary CRUD, for example `/goal-proposals/{id}/accept`.
- Use pagination, filtering, and stable sorting for collection endpoints.
- Use ISO 8601 timestamps with explicit offset or UTC transport semantics; display conversion happens per user timezone.
- Units must be explicit in request/response models.
- Mutating requests validate authority, source version, and current lifecycle state.
- Use optimistic locking or equivalent source-version checks for concurrent Goal, Plan, Target, and Recommendation application.

## API groups

Likely public groups include:

- `/api/v1/auth`, `/users`, `/student-profiles`, `/trainer-profiles`;
- `/trainer-applications`, `/trainer-verifications`;
- `/goals`, `/goal-proposals`, `/coaching-relationships`, `/coaching-periods`;
- `/exercises`, `/workout-plans`, `/planned-workouts`, `/actual-workouts`;
- `/appointments`, `/appointment-change-requests`;
- `/measurements`, `/progress`, `/attention-signals`;
- `/nutrition-goals`, `/nutrition-targets`, `/meals`, `/food-logs`;
- `/conversations`, `/messages`, `/notifications`;
- `/ai-runs` where permitted and `/ai-recommendations`;
- `/admin/...` resources governed by fine-grained permissions.

The generated OpenAPI artifact belongs in `contracts/openapi`. This directory explains cross-cutting behavior.

## Stable error envelope

```json
{
  "errorCode": "APPOINTMENT_TIME_CONFLICT",
  "message": "The proposed time is no longer available.",
  "timestamp": "2026-09-18T09:00:00Z",
  "requestId": "01K...",
  "fieldErrors": []
}
```

Do not expose stack traces, secrets, SQL, or sensitive authorization detail.

## WebSocket

WebSocket may carry chat messages, read receipts, notifications, and presence/typing signals where appropriate. Durable facts remain persisted in PostgreSQL. Event schemas are versioned when breaking changes occur and belong in `contracts`.

See [authentication and errors](authentication-and-errors.md).

