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
- **VERIFIED IMPLEMENTATION:** On 2026-09-26, the B01 flow passed on the `Pixel_7` Android emulator with Expo Go 57.0.9 and the real Spring Boot/PostgreSQL/Redis/Mailpit stack. Registration, email verification, login, native process restart, access-token refresh, revoked-session handling, retry after a temporary backend outage, logout, protected routing, and Student/Trainer capability switching were exercised through the Mobile UI.

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

- **VERIFIED IMPLEMENTATION:** Environment-based API configuration reaches the host backend from Android without a machine-specific value in source. Single-flight refresh, bootstrap/session restoration, logout cleanup, terminal session expiry, and retryable network restoration are implemented and covered by tests; the native paths also passed the emulator verification described below.
- **EXISTING IMPLEMENTATION:** Starter routes are replaced by a capability-aware B01 shell. The five-destination Student and Trainer navigation structures remain incremental deliverables of B02-B08 as those destinations become executable.
- **VERIFIED IMPLEMENTATION:** Vietnamese production copy, field validation, duplicate-submit prevention, offline messaging, expired-session handling, accessible controls, and keyboard-safe scrolling are present on B01 screens and passed the Pixel 7 smoke test.
- **VERIFIED IMPLEMENTATION:** DM Sans loads before the application shell renders; the session gate retains safe system typography while fonts initialize.

## 9. Admin Web scope

- **CONFIRMED REQUIREMENT:** No Admin Web implementation belongs to B01.
- **CONFIRMED REQUIREMENT:** Admin authentication and authorization remain a platform dependency, not part of this Mobile feature.

## 10. UI/UX and Figma deliverables

- **CONFIRMED REQUIREMENT:** Cover SH-01 Welcome/Sign In, SH-02 Register, session bootstrap, session-expired handling, and the authenticated shell from [screen inventory](../../06-ui-ux/screen-inventory.md).
- **VERIFIED REFERENCE:** Figma MCP inspection covered the supplied SuperFit file at root node `86:2234`, including Light Sign In `2885:580`, Light Sign Up `2886:694`, Dark Sign In `2896:11024`, and Dark Sign Up `2896:11055`.
- **VERIFIED IMPLEMENTATION:** B01 uses the same broad visual direction as the reference for DM Sans, purple primary actions, rounded inputs, and rounded cards. The Android render has correct safe-area spacing, focus behavior, readable state messages, and no remaining Expo starter screen inside the application.
- **DESIGN DECISION:** The SuperFit authentication frames use a workout image, bottom-sheet composition, English copy, and social-login controls. B01 follows the repository's Vietnamese product copy, documented screen states, and supported authentication contract, so copying that full template would be a product-wide redesign rather than a B01 bug fix.
- **DESIGN GAP:** The supplied reference does not contain confirmed matching frames for Verify Email, Session Gate, or the capability-aware Student/Trainer shell. No Code Connect mapping was available, so the shared UI/UX documentation and executable behavior remain authoritative for those surfaces.

## 11. Implementation steps

1. **EXISTING IMPLEMENTATION:** Authentication, refresh/replay, storage, restoration, route, error-mapping, and accessibility tests cover the final automated B01 behavior.
2. **EXISTING IMPLEMENTATION:** Environment-based API configuration, single-flight refresh, native secure storage, retryable bootstrap, and logout cleanup are implemented without replacing the working `fetch`/Context foundation.
3. **EXISTING IMPLEMENTATION:** Starter routes were removed and the authenticated shell is capability-aware.
4. **EXISTING IMPLEMENTATION:** Vietnamese copy, shared states, duplicate-submit protection, accessibility attributes, design tokens, and DM Sans loading are applied to the B01 screens.
5. **VERIFIED IMPLEMENTATION:** Registration, Mailpit verification, login, refresh, native process restart, forced revocation, logout, retry after a backend outage, protected routing, and both capability shells passed against Spring Boot M1 on the `Pixel_7` Android emulator.

## 12. Acceptance criteria

- **CONFIRMED REQUIREMENT:** A user can register, confirm email, sign in, restart the app, refresh an expired access token, and log out against the backend.
- **CONFIRMED REQUIREMENT:** Protected routes never render protected content to an unauthenticated session.
- **CONFIRMED REQUIREMENT:** Native credentials use SecureStore; web does not persist tokens in local or session storage.
- **CONFIRMED REQUIREMENT:** Refresh failure clears local credentials and returns the user to sign-in with a clear Vietnamese message.
- **CONFIRMED REQUIREMENT:** Loading, offline, validation, server-error, and duplicate-submit behavior is explicit and accessible.
- **VERIFIED IMPLEMENTATION:** All B01 acceptance criteria above passed through automated coverage and the Android E2E verification on 2026-09-26.

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
- **VERIFIED IMPLEMENTATION:** The full Mobile Jest suite, TypeScript check, Expo lint, UX documentation validator, contract validators, and `git diff --check` pass for the completion state.
- **VERIFIED IMPLEMENTATION:** Real-backend and native Android verification is complete; B01 is `READY_FOR_REVIEW`.

## 15. Decisions

- **CONFIRMED REQUIREMENT:** B01 is the first B feature to complete.
- **CONFIRMED REQUIREMENT:** The common account and backend-provided capabilities determine navigation; the client does not grant roles or coaching authority.
- **PROPOSED SOLUTION:** Retain the working `fetch`/Context foundation during B01 completion.
- **CONFIRMED REQUIREMENT:** Vietnamese is the Phase 1 production copy for the B01 authentication and shell surfaces.
- **EXISTING IMPLEMENTATION:** The documented DM Sans family is supplied by `@expo-google-fonts/dm-sans` and loaded during application bootstrap.
- **CONFIRMED DECISION:** B01 retains one capability-aware home shell and introduces the five documented Student/Trainer destinations incrementally with B02-B08, avoiding placeholder destinations with no executable feature behavior.
- **UNRESOLVED DECISION:** The team must reconcile the target architecture's Axios/TanStack Query/Zustand/React Hook Form statement with incremental dependency adoption before later features create competing patterns.

## 16. Delivery workflow

- **CONFIRMED REQUIREMENT:** B01 development uses `feature/m1-mobile-foundation` under the shared [feature branch workflow](../feature-branch-workflow.md).
- **EXISTING IMPLEMENTATION:** The automated B01 implementation checkpoint is isolated from `main` on the feature branch.
- **VERIFIED IMPLEMENTATION:** The checkpoint plus the documented native/backend verification satisfy the B01 Definition of Done and are ready for review.
- **CONFIRMED REQUIREMENT:** B01 reaches `main` only through a reviewed Pull Request, with Person A as a required reviewer.
