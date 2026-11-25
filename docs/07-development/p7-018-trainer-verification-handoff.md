# P7-018 Trainer Verification, Admin Review & Directory Handoff — 2026-11-25

- **Worktree:** `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\b06-phase1-schedule-appointment-closure`
- **Target Branch / Base:** `fix/p7-018-trainer-verification` branched from `feature/p2-dev-b-f06-mobile-client-20261124` (`da45ba4d218419010ccd9d0ca2135f8473c3b293`)
- **Code Checkpoint Status:** **Accepted by independent Codex review** (`30/30` file SHA-256 hashes verified against `p7018-verification.json`, zero remaining code-checkpoint blockers; only 1 trailing EOF blank line each in `V63__trainer_verify_permission.sql` and `TrainerHomeScreen.test.tsx` was trimmed afterward for `git diff --cached --check`, with `TrainerHomeScreen.test.tsx` `6/6 PASS`).
- **Dependency:** Depends on the F06 / P7-022 / P7-023 auth & storage epoch baseline on `feature/p2-dev-b-f06-mobile-client-20261124` (Draft PR `#169`, `da45ba4d`); P7-018 is **not** independent of that auth/storage checkpoint.
- **QA / Release Status:** **Device & live QA not yet closed** (native `DocumentPicker`/live upload and live Trainer -> Admin -> Student flow remain `NOT RUN` at code checkpoint; post-checkpoint QA is tracked separately and kept unstaged).

---

## 1. Root-Cause & Identifier Trace (`publicSlug` vs Name Directory)

1. Verified there is **no** Trainer QR or phone lookup flow in Mobile or Spring Boot.
2. `publicSlug` (`Định danh hồ sơ công khai`) in `TrainerOnboardingScreen.tsx` and `TrainerProfileScreen.tsx` is a profile URL identifier only — neither verification nor a Student lookup code. Onboarding also defaults `acceptingStudents = false`, keeping newly verified trainers hidden until they enable `acceptingStudents`.
3. Student directory (`GET /api/v1/coaching/trainers`, `TrainerDirectoryService.java`) searches `users.display_name` strictly across `VERIFIED` + `ACTIVE` + active account/role + `acceptingStudents=true`, never exposing `publicSlug`, email, or private verification documents.

---

## 2. Independent Codex Review Summary (4 Initial Findings + Upload Follow-Ups Resolved)

1. **P1 OpenAPI restore (`contracts/openapi/openapi.yaml`):** Restored full `12,935`-line baseline from `HEAD`, keeping only `GET /api/v1/admin/trainer-applications` `status` query schema update (`5 +++--`, `+3/-2`); `npm run validate:openapi` passed (`exit 0`). Pre-fix truncated FAIL evidence is preserved at `C:\Users\ADMIN\.codex\tmp\p7018-independent-harness.log`.
2. **P1 Upload session/capability/op-generation binding (`TrainerProfileScreen.tsx` -> `trainerApplicationApi.ts` -> `apiClient.ts`):**
   - `TrainerProfileScreen.tsx` captures `opId` (`uploadOpGenRef`), `initialBinding` (`{ authEpoch, capabilityEpoch }`), and `actorKey`/`actorEpoch` BEFORE opening `DocumentPicker.getDocumentAsync`, checks `isCurrentOp()` after picker and across `success`/`catch`/`finally`/`cancel`/`unmount`, and isolates UI state via `sessionFingerprint`.
   - `trainerApplicationApi.uploadDocument` accepts caller-captured `options.sessionBinding` + `options.shouldContinue`, verifies binding & continuation across `fetch(fileUri)` and `.blob()`, and passes `expectedAuthEpoch`, `expectedCapabilityEpoch`, `shouldContinue`, and `retryOn401: false` into `apiClient.request(...)`.
   - `AuthContext.tsx` calls `invalidateCapabilitySession?.()` in `setActiveCapability` alongside `invalidateAuthSession()` on login/logout/expiry.
3. **P2 `TrainerHomeScreen.tsx` stale mount response:** Unified mount/refresh/pull-to-refresh in `loadTrainerStatus` with request-generation & actor guards + actor-keyed `trainerProfileState`.
4. **P2 `TrainerProfileScreen.tsx` refresh vs dirty draft:** Unified server fetches in `loadProfileState` keeping `profileState` separate from editor draft via `isEditingRef` / `editorGenerationRef` / `profileRequestGenRef`.
5. **P2 Upload unmount during pending `tokenStorage.getAccessToken()`:** Added `shouldContinue` propagation through `RequestOptions` in `apiClient.ts` (`request()` checks `shouldContinue` at entry, **after `await tokenStorage.getAccessToken()` right before `fetch`**, and around 401 refresh paths), throwing `AbortError` instead of converting cancellation into `ApiError(401)` session loss or triggering retry.

---

## 3. Earlier P7-018 Implementation in Checkpoint

- Backend: `V63__trainer_verify_permission.sql` (`TRAINER_VERIFY` seed + `ADMIN` grant), `GetAdminTrainerApplicationsQuery.java`, `AdminTrainerApplicationController.java`, `AdminTrainerVerificationService.java` (`status=ALL` support + active `TRAINER_VERIFY` permission guard), plus integration/unit tests.
- Admin Web: `AdminLayout.tsx`, `App.tsx` (`TRAINER_VERIFY` permission guards), `TrainerApplicationListPage.tsx` (default `status: 'ALL'`), plus Vitest updates.
- Mobile & UX Docs: `TrainerOnboardingScreen.tsx`, `TrainerDirectoryScreen.tsx` (`RefreshControl` + verified/accepting copy), `displayLabels.ts`, `docs/06-ui-ux/trainer/trainer-flows.md` (`docs/07-development/phase-2-dev-b-f06-mobile-handoff.md` preserved untouched for F06 isolation).

---

## 4. Checkpoint Verification Evidence (By Turn — No Stale Claims)

| Evidence Source & Revision | Command / Scope | Result | Notes |
| --- | --- | --- | --- |
| Upload UI pre-fix reproduction | `src/__tests__/trainerProfileUploadSessionRace.test.tsx` (A–E before UI binding fix) | **FAIL `4 failed, 1 passed` (`exit 1`)** | Preserved at `p7018-upload-ui-regression-before.log.txt` |
| Unmount during pending token read pre-fix | `src/__tests__/trainerProfileUploadSessionRace.test.tsx` (ca `G` before `shouldContinue` passthrough) | **FAIL `1 failed, 6 passed` (`exit 1`)** | Preserved at `p7018-upload-unmount-token-read-before.log.txt` |
| Final Mobile affected run (Dev-B + **Codex independent review** `p7018-token-unmount-review-tests.log`) | 17 Mobile suites (`trainerProfileUploadSessionRace` A–G, `TrainerProfileScreen`, `TrainerHomeScreen`, `trainerApplicationUploadSessionRace`, `trainerApplicationApi`, `trainerProfileApi`, `TrainerOnboardingScreen`, `apiClient`, `authSessionRace`, `authNativeStorageRace`, `authProviderCapabilities`, `storage`, `authFlow`, `sessionRestorationNavigation`, `foodAnalysisScreen`, `coachingScreens`, `displayLabels`) + `typecheck` + `lint` + `git diff --check` | **PASS `17/17` suites, `188/188` tests (`exit 0`)** | Verified `30/30` file SHA-256 hashes match `p7018-verification.json` |
| Earlier turn Backend Maven (pre-UI-upload follow-up; Java/SQL unchanged since) | 10 Trainer/Coaching unit & Testcontainers PostgreSQL integration suites (with Flyway `V63`) | **PASS `186/186` (`exit 0`)** | Historical earlier-turn result; Backend untouched afterward |
| Earlier turn Admin Web & Contracts (pre-UI-upload follow-up; unchanged since) | `apps/admin-web` Vitest (`21` files / `151` tests) + `typecheck` + `lint` (`0` errors, `15` warnings) + `build`; `npm run validate:openapi`; `npm run validate:ux-docs` | **PASS (`exit 0`)** | Historical earlier-turn result; Admin Web & OpenAPI untouched afterward |
| **Native Picker / Live QA / Pixel_7 Device at Code Checkpoint** | Live Trainer -> Admin -> Student & native `DocumentPicker` upload | **`NOT RUN`** | Code checkpoint accepted with QA & release still open |

Manifest & SHA-256 inventory: [p7018-verification.json](../08-ai/artifacts/p7-018-verification-20261125/p7018-verification.json).
