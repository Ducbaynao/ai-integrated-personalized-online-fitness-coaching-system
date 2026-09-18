# Fitness Coaching Mobile

Expo and React Native application for Student and Trainer experiences in the AI-Integrated Personalized Online Fitness Coaching System. One User Account may activate Student, Trainer, or both capabilities. The UI adapts to authorized capability and current context; it must not model AI as a third Coaching Mode.

## Product responsibilities

- authentication, session recovery, and common-account onboarding;
- Student and Trainer profile/capability activation;
- Student goal, workout, schedule, measurement, nutrition, progress, chat, and recommendation experiences;
- Trainer verification status, Student Coaching Workspace, plan building, review cycle, scheduling, and Attention Signals;
- clear display of decision authority, data provenance, missingness, and history.

Mobile calls only the Spring Boot API. It never calls PostgreSQL or the FastAPI AI service directly.

## Technology baseline

- Expo SDK 57 and React Native 0.86
- TypeScript and Expo Router
- React 19
- Repository design tokens in `src/design-system/tokens`

Before changing Expo code, read `AGENTS.md` and the exact Expo 57 documentation linked there. Add state, data-fetching, form, validation, secure-storage, and test dependencies deliberately as Phase 1 features are implemented; do not assume a package is installed because it appears in architecture documentation.

## Run locally

From the repository root:

```bash
npm install --prefix apps/mobile
npm run dev:mobile
```

Or from this directory:

```bash
npm install
npm run start
```

Useful commands:

```bash
npm run android
npm run ios
npm run web
npm run lint
npm run typecheck
```

The Android/iOS commands require the corresponding local tooling. Expo Go may not support every native dependency added later; use a development build when required.

## Source layout

| Path | Responsibility |
| --- | --- |
| `src/app` | Expo Router routes and layouts; keep business/data logic out of route files |
| `src/features` | Feature-owned screens, hooks, models, and presentation logic |
| `src/components` | Truly shared presentation components |
| `src/design-system` | Shared tokens and reusable visual foundations |
| `src/services` | Backend API, secure session, upload, and realtime adapters |
| `src/config` | Validated environment and runtime configuration |
| `src/hooks` | Cross-feature React hooks |
| `src/types` | Shared client-only TypeScript types; API types should follow OpenAPI |

As features are implemented, prefer feature folders such as `features/auth`, `features/goals`, and `features/workouts`. Do not create a second design-token system or duplicate server state in global UI state.

## API and session rules

- The reviewed contract is `../../contracts/openapi/openapi.yaml`.
- Use `/api/v1` endpoints through the shared API client.
- Store access/refresh credentials only with platform-appropriate secure storage.
- Rotate refresh tokens through the Backend and clear local session state on forced logout.
- Backend authorization remains the security boundary; hiding a button is not authorization.
- Preserve safe drafts on recoverable network failures and prevent duplicate submissions.

When runtime API configuration is introduced, expose only non-secret public values to the client, for example an API base URL. Never bundle service credentials, database credentials, signing secrets, or provider secrets in the app.

## UI and UX rules

Read `../../docs/06-ui-ux/README.md` and `../../.agent/skills/digital-fitness-ui/SKILL.md` before screen work.

- Use repository tokens instead of hardcoded color, spacing, radius, shadow, or typography values.
- Implement loading, empty, error, offline, stale/partial-data, success, and permission-denied states where applicable.
- Missing Measurement or Nutrition data is unknown, never zero.
- System/Rule Alert and AI Recommendation use different labels, styling, provenance, and actions.
- Student owns Fitness Goal and Nutrition Goal decisions; Trainer/AI strategic changes use proposals.
- Touch targets must be at least 44 by 44 logical pixels and content must remain usable with text scaling.

## Testing expectations

Use the client testing stack selected during implementation to cover:

- registration, verification, login, refresh, logout, and role-intent onboarding;
- authorization-dependent navigation and action visibility;
- interrupted forms and duplicate-submit prevention;
- Goal Proposal decisions and history-preserving transitions;
- planned versus performed workout date;
- missing/partial measurement and nutrition states;
- realtime reconnect and persisted-history reconciliation when chat is introduced.

Run type checking and relevant tests before opening a pull request. Contract changes, generated API types, UI behavior, and backend implementation must remain synchronized.
