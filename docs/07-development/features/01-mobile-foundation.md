# B01 Mobile Foundation and Authentication Integration

## 1. Feature ID and objective

- **Feature ID:** B01.
- **CONFIRMED REQUIREMENT:** Provide the shared Mobile API, authentication, session, and protected-navigation foundation used by every Student and Trainer feature.
- **CONFIRMED REQUIREMENT:** Mobile communicates only with the Spring Boot backend and stores credentials only in platform-appropriate secure storage.

## 2. Business requirements

- **CONFIRMED REQUIREMENT:** Implement `FR-ID-001`, `FR-ID-002`, and the client-facing portions of `FR-ID-003` through `FR-ID-006` from [functional requirements](../../01-requirements/functional-requirements.md).
- **CONFIRMED REQUIREMENT:** Preserve the common-account model; Student and Trainer are capabilities/profiles, not separate accounts.
- **CONFIRMED REQUIREMENT:** Follow the authentication and stable-error behavior in [API authentication and errors](../../05-api/authentication-and-errors.md).
- **CONFIRMED REQUIREMENT:** Use the shared loading, validation, conflict, permission, offline, and success treatments from [screen states](../../06-ui-ux/screen-states.md).

## 3. Current implementation status

- **EXISTING IMPLEMENTATION:** Expo Router routes exist for registration, email verification, sign-in, an authenticated home route, and authenticated/unauthenticated route guards.
- **EXISTING IMPLEMENTATION:** `apps/mobile/src/services/apiClient.ts` calls the real M1 endpoints, performs one refresh-and-retry cycle for eligible `401` responses, and clears the session when refresh fails.
- **EXISTING IMPLEMENTATION:** `apps/mobile/src/services/storage.ts` uses Expo SecureStore on native platforms and memory-only storage on web.
- **EXISTING IMPLEMENTATION:** `AuthContext` restores the current user, exposes registration/login/logout/verification operations, handles forced session expiry, and distinguishes terminal authentication failure from retryable restoration failure.
- **EXISTING IMPLEMENTATION:** Retryable restoration errors retain credentials and present a Vietnamese retry state; terminal `401`/`403` restoration errors clear credentials and present a Vietnamese sign-in notice.
- **EXISTING IMPLEMENTATION:** Unit tests cover API calls, refresh behavior, authentication flow, secure storage, restoration retry, accessibility, and token usage.
- **EXISTING IMPLEMENTATION:** The public starter Explore route and unused starter tab components have been removed. The authenticated home routes use backend capabilities to present Student/Trainer shells and preserve the rule that the client does not grant coaching authority.
- **EXISTING IMPLEMENTATION:** Registration, verification, sign-in, session states, capability switching, and the B01 Student/Trainer shell use Vietnamese copy. DM Sans is loaded through the Expo font integration before the application shell renders.
- **PROPOSED SOLUTION:** Real-device restart and end-to-end checks against a running Spring Boot environment remain the final B01 verification step.

## 4. Feature dependencies

- **CONFIRMED REQUIREMENT:** B01 precedes B02-B08 because those screens require an authenticated API boundary and route protection.
- **PROPOSED SOLUTION:** Complete B01 without replacing the current `fetch` and React Context implementation solely to match target-architecture library names.
- **PROPOSED SOLUTION:** Re-evaluate server-state and form libraries when the first data-heavy feature is implemented, using one shared convention rather than parallel stacks.

## 5. External subsystem dependencies

- **CONFIRMED REQUIREMENT:** Backend Authentication and Current User endpoints must remain available and aligned with OpenAPI.
- **CONFIRMED REQUIREMENT:** Student/Trainer Profile activation, Trainer eligibility, role/capability projections, and onboarding workflows are supplied by the Identity/Profile subsystem.
- **CONFIRMED REQUIREMENT:** B01 consumes capability information but does not create coaching authority on the client.

## 6. Data model and schema requirements

- **EXISTING IMPLEMENTATION:** Identity, role, token, settings, and profile tables already exist through Flyway migrations.
- **CONFIRMED REQUIREMENT:** B01 requires no new Mobile-owned database schema.
- **CONFIRMED REQUIREMENT:** Tokens and credential material must never be added to client logs or insecure persistent storage.

## 7. Backend and API scope

- **EXISTING IMPLEMENTATION:** The executable contract includes registration, email confirmation, login, refresh, logout, current-user read/update, and profile/application endpoints in `contracts/openapi/openapi.yaml`.
- **PROPOSED SOLUTION:** Keep the Mobile client typed against the executable contract and normalize stable backend error envelopes into field, authentication, permission, conflict, and retryable failure states.
- **PROPOSED SOLUTION:** Ensure concurrent protected requests share a single refresh operation and never replay a mutation more than once.

## 8. Mobile scope

- **PROPOSED SOLUTION:** Verify environment-based API base URL handling, refresh concurrency, bootstrap/session restoration, logout cleanup, and redirect behavior on native and web targets.
- **PROPOSED SOLUTION:** Replace starter routes with a capability-aware shell and destinations defined in [navigation architecture](../../06-ui-ux/navigation-architecture.md).
- **PROPOSED SOLUTION:** Complete Vietnamese production copy, field validation, duplicate-submit prevention, offline messaging, expired-session handling, and accessible focus/error behavior.
- **PROPOSED SOLUTION:** Load the documented typeface correctly or use the approved fallback token; do not reference an unloaded font family.

## 9. Admin Web scope

- **CONFIRMED REQUIREMENT:** No Admin Web implementation belongs to B01.
- **CONFIRMED REQUIREMENT:** Admin authentication and authorization remain a platform dependency, not part of this Mobile feature.

## 10. UI/UX and Figma deliverables

- **CONFIRMED REQUIREMENT:** Cover SH-01 Welcome/Sign In, SH-02 Register, session bootstrap, session-expired handling, and the authenticated shell from [screen inventory](../../06-ui-ux/screen-inventory.md).
- **PROPOSED SOLUTION:** Produce Figma frames for default, loading, validation error, network error, email-verification success/failure, session restoration, and forced logout.
- **PROPOSED SOLUTION:** Map `AppButton`, `AppTextField`, `StatusBadge`, `EmptyState`, and `MobileBottomNavigation` to code components and design tokens.
- **EXISTING IMPLEMENTATION:** No Figma file URL, node ID, or Code Connect mapping was available during this audit; no Figma inspection is claimed.

## 11. Implementation steps

1. **EXISTING IMPLEMENTATION:** Authentication, refresh/replay, storage, restoration, route, error-mapping, and accessibility tests cover the final automated B01 behavior.
2. **EXISTING IMPLEMENTATION:** Environment-based API configuration, single-flight refresh, native secure storage, retryable bootstrap, and logout cleanup are implemented without replacing the working `fetch`/Context foundation.
3. **EXISTING IMPLEMENTATION:** Starter routes were removed and the authenticated shell is capability-aware.
4. **EXISTING IMPLEMENTATION:** Vietnamese copy, shared states, duplicate-submit protection, accessibility attributes, design tokens, and DM Sans loading are applied to the B01 screens.
5. **PROPOSED SOLUTION:** Validate registration, verification, login, refresh, native restart, forced expiry, and logout against a running Spring Boot M1 environment on at least one native target.

## 12. Acceptance criteria

- **CONFIRMED REQUIREMENT:** A user can register, confirm email, sign in, restart the app, refresh an expired access token, and log out against the backend.
- **CONFIRMED REQUIREMENT:** Protected routes never render protected content to an unauthenticated session.
- **CONFIRMED REQUIREMENT:** Native credentials use SecureStore; web does not persist tokens in local or session storage.
- **CONFIRMED REQUIREMENT:** Refresh failure clears local credentials and returns the user to sign-in with a clear Vietnamese message.
- **CONFIRMED REQUIREMENT:** Loading, offline, validation, server-error, and duplicate-submit behavior is explicit and accessible.

## 13. Testing requirements

- **CONFIRMED REQUIREMENT:** Unit-test storage, login, logout, restoration, refresh, replay prevention, and error mapping.
- **CONFIRMED REQUIREMENT:** Integration-test Mobile against the current OpenAPI/Backend behavior for success, `400`, `401`, `403`, and `409` responses.
- **CONFIRMED REQUIREMENT:** Run Mobile Jest, lint, and TypeScript checks; include accessibility assertions for labels, focus, disabled state, and touch targets.

## 14. Definition of Done

- **CONFIRMED REQUIREMENT:** Authentication and session flows work end to end without mock data when Backend M1 is available.
- **CONFIRMED REQUIREMENT:** Starter routes and placeholder copy no longer form the signed-in experience.
- **CONFIRMED REQUIREMENT:** Tests and client checks pass, OpenAPI remains aligned, and shared UI documentation is updated only for confirmed changes.
- **CONFIRMED REQUIREMENT:** No credential or sensitive current-user data is written to application logs.
- **EXISTING IMPLEMENTATION:** Mobile Jest, lint, TypeScript, and whitespace checks pass for the B01 implementation checkpoint.
- **UNRESOLVED DECISION:** Real-backend and native-device verification is still required before B01 can be reported as fully Done.

## 15. Decisions

- **CONFIRMED REQUIREMENT:** B01 is the first B feature to complete.
- **CONFIRMED REQUIREMENT:** The common account and backend-provided capabilities determine navigation; the client does not grant roles or coaching authority.
- **PROPOSED SOLUTION:** Retain the working `fetch`/Context foundation during B01 completion.
- **CONFIRMED REQUIREMENT:** Vietnamese is the Phase 1 production copy for the B01 authentication and shell surfaces.
- **EXISTING IMPLEMENTATION:** The documented DM Sans family is supplied by `@expo-google-fonts/dm-sans` and loaded during application bootstrap.
- **UNRESOLVED DECISION:** The team must reconcile the target architecture's Axios/TanStack Query/Zustand/React Hook Form statement with incremental dependency adoption before later features create competing patterns.

## 16. Delivery workflow

- **CONFIRMED REQUIREMENT:** B01 development uses `feature/m1-mobile-foundation` under the shared [feature branch workflow](../feature-branch-workflow.md).
- **EXISTING IMPLEMENTATION:** The automated B01 implementation checkpoint is isolated from `main` on the feature branch.
- **CONFIRMED REQUIREMENT:** A checkpoint commit does not mark B01 Done; real-backend and native-device verification must still satisfy the Definition of Done.
- **CONFIRMED REQUIREMENT:** B01 reaches `main` only through a reviewed Pull Request, with Person A as a required reviewer.
