# UI Implementation Workflow

## Before coding

1. Name the actor, screen ID, navigation entry and business capability.
2. List data fields with source, optionality, authority and quality/provenance needs.
3. List user actions and backend transitions.
4. Map shared components and tokens.
5. Define loading, empty, error, permission, partial-data and success states.

## During coding

- Keep screen containers thin; place reusable visuals in the design system.
- Keep server state in query/mutation layer and local ephemeral state in the screen/form layer.
- Render backend status values explicitly; do not collapse distinct domain states into booleans.
- Gate actions by server-provided capability/permission, not only role labels in the client.
- Use locale-aware date/time and display timezone where ambiguity matters.

## Pull request evidence

Include:

- Screen IDs and requirement rows affected.
- Screenshots for default and non-happy states.
- Accessibility checks.
- Explanation of authority-sensitive actions.
- Updates to docs/tokens when a new pattern was introduced.

