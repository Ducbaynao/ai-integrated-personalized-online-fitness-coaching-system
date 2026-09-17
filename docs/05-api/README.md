# API conventions

- Prefix public endpoints with `/api/v1`.
- Use request and response DTOs rather than persistence entities.
- Return a stable error envelope containing `errorCode`, `message`, `timestamp`, `requestId`, and optional `fieldErrors`.
- Enforce role, permission, capability state, object relationship, scope, and purpose at the backend.
- Publish the generated OpenAPI contract in `contracts/openapi`.
- Version breaking WebSocket event changes.
