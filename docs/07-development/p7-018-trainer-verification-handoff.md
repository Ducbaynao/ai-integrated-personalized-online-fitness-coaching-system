# P7-018 Trainer Verification, Admin Review & Directory Handoff — 2026-11-25

- **Worktree:** `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\b06-phase1-schedule-appointment-closure`
- **Target Branch / Base:** `fix/p7-018-trainer-verification` branched from `feature/p2-dev-b-f06-mobile-client-20261124` (`da45ba4d218419010ccd9d0ca2135f8473c3b293`)
- **Code Checkpoint Status:** **Accepted by independent Codex review** (`30/30` file SHA-256 hashes verified against `p7018-verification.json`, zero remaining code-checkpoint blockers; only 1 trailing EOF blank line each in `V63__trainer_verify_permission.sql` and `TrainerHomeScreen.test.tsx` was trimmed afterward for `git diff --cached --check`, with `TrainerHomeScreen.test.tsx` `6/6 PASS`).
- **Dependency:** Depends on the F06 / P7-022 / P7-023 auth & storage epoch baseline on `feature/p2-dev-b-f06-mobile-client-20261124` (Draft PR `#169`, `da45ba4d`); P7-018 is **not** independent of that auth/storage checkpoint.
- **QA / Release Status:** **Device & live QA not yet closed** (native `DocumentPicker`/live upload and live Trainer -> Admin -> Student flow remain `NOT RUN` at code checkpoint; post-checkpoint QA is tracked separately below).

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

---

## 5. Post-Checkpoint QA Results (2026-11-25)

- **Checkpoint Tested:** `fix/p7-018-trainer-verification` @ `f1113fbbbdbd198ca4b602f1b4b6d084ac2f9775` (PR `#172` Draft -> `feature/p2-dev-b-f06-mobile-client-20261124`).
- **PR `#172` CI Status (`f1113fb`):** **5/6 `SUCCESS`** (`Client Admin Web`, `Client Mobile`, `AI Service`, `Contracts & Docs Validation`, `Database Migrations Check`), **1/6 `FAILURE`** (`Backend` run `45639754550` / job `139294802558`: `Tests run: 2128, Failures: 3, Errors: 0, Skipped: 5` — `AuthSessionIntegrationTest.loginAndRefreshExposeEffectivePermissionsThroughCurrentUserProjection`, `ContentManagementIntegrationTest.revokingContentAdminRoleImmediatelyDeniesApiAndRemovesEffectivePermissions`, and `CurrentUserIntegrationTest.revokedAdminPermissionDisappearsAndAdminApiRemainsDenied` still assert the pre-`V63` 10-permission `ADMIN` list without `TRAINER_VERIFY`).
- **Live Backend & Disposable DB:** Rebuilt `services/backend/target/backend-0.0.1-SNAPSHOT.jar` from `f1113fb` (`-XX:+UseSerialGC -Xmx384m` Maven opts), ran Spring Boot on `:18080` against existing container `f06-mobile-qa-20261124` (`127.0.0.1:15486/f06qa`), readiness `UP`.

| Area | Scope & Evidence | Outcome | Notes |
| --- | --- | --- | --- |
| Live DB & Flyway `V63` | Verified `flyway_schema_history` (`63:true`) and `ADMIN:TRAINER_VERIFY` grant on `f06-mobile-qa-20261124` | **PASS** | Evidence: [p7018-live-http-db-qa.json](../08-ai/artifacts/p7-018-verification-20261125/p7018-live-http-db-qa.json) |
| Live HTTP Journey (B) | Synthetic Trainer, Admin, Student (`qa.p7018.*.<tag>@example.invalid`): `POST /trainer-profiles` (`acceptingStudents=false`) -> `POST /trainer-applications` (`PENDING`, hidden in `/coaching/trainers`) -> non-admin `403` on admin queue -> Admin `GET ?status=ALL` + `REJECT` (`reviewNotes` hidden from Trainer) -> Trainer resubmit -> Admin `APPROVE` -> Trainer `/users/me` (`canCoach=true`) -> still hidden while `!acceptingStudents` -> `PATCH acceptingStudents=true` -> Student finds by `displayName` only (`trainerId`, `displayName`; `publicSlug` returns `0` matches) | **PASS** | `p7018-live-http-db-qa.json`, exit `0` |
| Live Document Upload (A) | Multipart `PDF`, `PNG`, `JPEG` to `POST /api/v1/trainer-applications/documents` | **`NOT RUN` (clean fixture absent) / `503 SCANNER_UNAVAILABLE` verified** | `CHAT_CLAMD_INTERNAL_HOST` & `CHAT_S3_ENDPOINT` unset in dev/QA runtime; backend enforced `503 SCANNER_UNAVAILABLE` without bypass |
| Pixel_7 Native Picker / UI Session Switch (A/C) | Native `DocumentPicker`, Expo Go Metro `:18081`, and QEMU `Pixel_7` (`:5580`) | **`NOT RUN`** | Windows host virtual commit memory exhausted alongside active `cvat_*` Docker containers (`DOS error 1455`, Metro V8 `ReportExternalAllocationLimitReached`, QEMU `Pixel_7` failed to attach `adb`) |

- **Cleanup & Runtime:** Stopped post-checkpoint Spring (`:18080`) and stalled `Pixel_7` emulator daemons cleanly; retained `f06-mobile-qa-20261124` disposable DB container without purging volumes. P7-018 device gate remains **open** until native `DocumentPicker`/ClamAV+S3 and emulator UI run cleanly.

---

## 6. Post-Checkpoint PR #172 Backend CI Test Alignment (2026-11-25)

- **Scope & Constraints:** Test-only fix reviewed independently by Codex for follow-up push to existing Draft PR `#172` (`OPEN`, targeting `feature/p2-dev-b-f06-mobile-client-20261124`). Production authorization code, `V63__trainer_verify_permission.sql`, OpenAPI, earlier migrations, Mobile/Admin code, and CRLF-only `docs/07-development/phase-2-dev-b-f06-mobile-handoff.md` remain untouched.
- **Root Cause Verified on `f1113fbbbdbd198ca4b602f1b4b6d084ac2f9775`:**
  - GitHub CI `Backend` run `45639754550` / job `139294802558` failed `3/2128` (`0` errors, `5` skipped; archived in `p7018-pr172-backend-ci-before.log.txt` (raw log retained locally)) because `V63` grants `TRAINER_VERIFY` to `ADMIN`, turning sorted active `ADMIN` permissions from 10 items into 11 (`"TRAINER_VERIFY"` between `"SUPPORT_MANAGE"` and `"USER_DISABLE"`).
  - Pre-fix local reproduction across the 3 suites (`AuthSessionIntegrationTest,ContentManagementIntegrationTest,CurrentUserIntegrationTest`) reproduced the exact 3 failures: `Tests run: 53, Failures: 3, Errors: 0, Skipped: 0`, exit `1` (`p7018-backend-3suites-before.log.txt` (raw log retained locally)).
- **Test Updates (Exact-Set & Revocation Semantics Preserved):**
  - `AuthSessionIntegrationTest.loginAndRefreshExposeEffectivePermissionsThroughCurrentUserProjection`: updated `expectedAdminPermissions` to include `"TRAINER_VERIFY"` while keeping exact equality checks across login, token refresh, and `/api/v1/users/me`.
  - `ContentManagementIntegrationTest.revokingContentAdminRoleImmediatelyDeniesApiAndRemovesEffectivePermissions`: updated post-revoke `permsAfter` `containsExactly(...)` to include `"TRAINER_VERIFY"` (only `CONTENT_ADMIN` is revoked; `ADMIN` remains active while `KNOWLEDGE_*` disappears and import API returns `403`).
  - `CurrentUserIntegrationTest`: updated pre-revoke `ADMIN` expected permission list in `revokedAdminPermissionDisappearsAndAdminApiRemainsDenied` (keeping post-revoke `[]` and `/api/v1/admin/exercises` `403`), updated `getCurrentUser_returnsSortedDistinctEffectivePermissions` (`ADMIN` + `AUDIT_VIEW_TEST`), and added `grantPermission("ADMIN", "TRAINER_VERIFY");` inside `getCurrentUser_adminWithoutPermissionAssignmentReturnsEmpty`'s `finally` restore block so DB state matches `V63` regardless of test execution order.
- **Verification Results (Local vs. GitHub CI):**
  - **Local Targeted 3-Suite Run (Maven + Testcontainers PostgreSQL, `-XX:+UseSerialGC`):** `Tests run: 53, Failures: 0, Errors: 0, Skipped: 0` (`AuthSessionIntegrationTest`: `26/26`, `ContentManagementIntegrationTest`: `5/5`, `CurrentUserIntegrationTest`: `22/22`), `BUILD SUCCESS`, exit `0` (`p7018-backend-3suites-after.log.txt` (raw log retained locally)).
  - **Local Full Backend Suite (`./mvnw -B test`, 2,128 tests):** **`NOT RUN`** locally due to Windows host virtual commit constraint (`FreeVirtualMemory ~1.73 GB`, `FreePhysicalMemory ~1.16 GB` with user `cvat_*` Docker containers preserved) and absent `services/ai-service/.venv`; targeted 3-suite PASS does **not** replace the full CI Backend run on PR `#172`.
  - **GitHub CI PR `#172` (Latest observed on `f1113fb`, prior to committing this test fix):** `5/6 SUCCESS`, `Backend` `FAILURE` (`3/2128`).

## 7. Independent Backend CI Review and Follow-Up

Codex reviewed exactly the three Java test diffs against V63. No finding: exact equality/containsExactly assertions remain, CONTENT_ADMIN revocation retains TRAINER_VERIFY through ADMIN, ADMIN revocation still yields empty permissions and HTTP 403, and finally restores all 11 seeded ADMIN permissions. No production authorization, migration, contract or Mobile changes are included in this follow-up.

Independent Maven/Testcontainers run: 53 tests, 0 failures, 0 errors, 0 skipped, exit 0 (AuthSession 26, ContentManagement 5, CurrentUser 22); all three Surefire XML reports verified. Java 21.0.8, Maven 3.9.16. git diff --check passed. Full Backend is deferred to the new CI run, not inferred from focused tests.

Published evidence: [credential-free review summary](../08-ai/artifacts/p7-018-verification-20261125/p7018-pr172-ci-review-summary.json). Raw before/after/old-CI logs stay local because they contain credential-shaped test output; original FAIL evidence is preserved. Live HTTP/DB JSON is historical QA evidence only. Clean document upload and native/device QA remain NOT RUN; 503 SCANNER_UNAVAILABLE is not upload success.

New CI status at commit preparation: PENDING; inspect the pushed SHA before claiming full Backend PASS. PR #172 stays Draft with its existing base; PR #169/#165 are unchanged.
