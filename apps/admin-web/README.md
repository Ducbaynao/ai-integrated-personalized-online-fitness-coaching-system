# Fitness Coaching Admin Web

React and Vite application for authorized platform administration. Admin is Platform Authority for identity, Trainer verification, content, moderation, support, configuration, security, audit, and operations. Admin is not Coaching Authority and must not replace Student or Trainer decisions.

## Product responsibilities

- permission-aware dashboard and operational queues;
- Trainer Application review and verification history;
- account lifecycle actions under policy;
- exercise and knowledge governance;
- moderation and support cases;
- privileged data-access and correction workflows;
- AI operations/evaluation without applying output to Student business data;
- feature/configuration governance, job/integration status, and audit visibility.

The Admin Web calls only the Spring Boot API. It does not access PostgreSQL or the FastAPI AI service directly.

## Technology baseline

- React 19
- TypeScript
- Vite 8
- Oxlint

Add routing, server-state, form, validation, component, and test dependencies deliberately as the first Admin milestone is implemented. Keep the README aligned with actually installed packages.

## Run locally

From the repository root:

```bash
npm install
npm run dev:admin
```

Or from this directory:

```bash
npm install
npm run dev
```

Quality commands:

```bash
npm run lint
npm run build
npm run preview
```

## Source layout

| Path | Responsibility |
| --- | --- |
| `src/features` | Admin workflows, screens, queries, mutations, and feature-specific components |
| `src/components` | Shared Admin presentation components only |
| `src/services` | Backend API and authenticated session adapters |
| `src/config` | Validated public runtime/build configuration |
| `src/assets` | Static presentation assets |

Keep route/layout composition thin. Business decisions, authority checks, and lifecycle validation belong in Spring Boot. Client-side permission checks improve UX but never replace backend authorization.

## API and security rules

- The reviewed REST contract is `../../contracts/openapi/openapi.yaml`.
- Use versioned `/api/v1` endpoints through a shared API client.
- Do not expose persistence entities in view models.
- Do not log access tokens, secrets, raw sensitive records, or unnecessary personal data.
- Privileged actions require backend permission checks, reason/context, step-up authentication when applicable, and immutable audit.
- Data correction must use the supported workflow and domain validation; never implement a generic direct-table editor.
- Audit records are read-only from the Admin UI.

Public frontend configuration may contain an API base URL and non-sensitive build metadata. Signing keys, database credentials, provider credentials, and other secrets must remain server-side.

## UI and UX rules

Read `../../docs/06-ui-ux/README.md`, `../../docs/06-ui-ux/admin/admin-flows.md`, and `../../.agent/skills/digital-fitness-ui/SKILL.md` before implementing Admin screens.

- Render actions from effective permissions, resource scope, lifecycle state, and step-up state.
- Make destructive or high-risk actions explicit and confirm the target and consequence.
- Show loading, empty, error, stale/partial-data, permission-denied, and success states.
- Separate platform status, deterministic system alerts, and AI evaluation results.
- Do not surface private health, nutrition, chat, or media data outside a valid scoped workflow.
- Tables require stable sorting, filtering, pagination, accessible labels, and safe empty states.

## Testing expectations

Cover at minimum:

- denied actions for missing permission, scope, reason, or step-up authentication;
- Trainer Application approve/reject/request-more-information transitions;
- account suspend/restore behavior and audit evidence;
- archive/version behavior for referenced content;
- moderation/support lifecycle and validated data correction;
- AI replay/evaluation isolation from Student business data;
- immutable audit presentation and sensitive-data redaction.

Run lint, build, and relevant tests before opening a pull request. Update the OpenAPI contract, backend tests, and Admin behavior together when an interface changes.
