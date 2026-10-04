# B02 Exercise Library

## 1. Feature ID and objective

- **Feature ID:** B02.
- **CONFIRMED REQUIREMENT:** Provide a governed Exercise catalog for Mobile workout selection and basic Admin content management while preserving historical references.

## 2. Business requirements

- **CONFIRMED REQUIREMENT:** Implement `FR-WS-001` and `FR-AD-005` from [functional requirements](../../01-requirements/functional-requirements.md).
- **CONFIRMED REQUIREMENT:** Support exercises, variations, muscle groups, equipment, instructions, media references, archive behavior, and canonical replacement mapping.
- **CONFIRMED REQUIREMENT:** Referenced Exercise content must be archived or mapped, never destructively deleted or overwritten.

## 3. Current implementation status

- **EXISTING IMPLEMENTATION:** Flyway V3 contains Exercise catalog, category, equipment, muscle, variation, instruction, and media-reference structures.
- **EXISTING IMPLEMENTATION:** The backend `exercise` module provides authenticated Student/Trainer read-only catalog APIs plus permission-protected Admin list/detail and lifecycle mutations. Historical archived access from workout plans/logs remains unimplemented.
- **EXISTING IMPLEMENTATION:** OpenAPI defines the three public read-only Exercise catalog paths and the permission-protected Admin list/detail, draft create/edit, activate, archive, and canonical-replacement paths.
- **EXISTING IMPLEMENTATION:** V23 governs the nullable Exercise movement-pattern vocabulary with a reference table and foreign key. Admin draft create/edit accepts only active values, while historical references may remain after a vocabulary entry is deactivated.
- **EXISTING IMPLEMENTATION:** `GET /api/v1/admin/exercises/metadata` returns permission-protected, deterministic create/edit options for categories, muscle groups, active equipment, active tags, difficulties, and active movement patterns.
- **EXISTING IMPLEMENTATION:** Mobile ST-21 provides authenticated Student/Trainer catalog browse, debounced search, metadata-driven filters, session query cache, pagination, pull-to-refresh, and explicit loading/empty/error/stale states.
- **EXISTING IMPLEMENTATION:** Mobile ST-22 provides an authenticated Student/Trainer detail route, typed detail query, Vietnamese loading/error/unavailable/stale states, optional metadata sections, variation/guidance content, and media availability placeholders without exposing storage references.
- **EXISTING IMPLEMENTATION:** Mobile provides a reusable controlled single-selection Exercise picker component for future B04 Plan Builder integration. It reuses ST-21 query/search/filter/pagination states, returns a typed Exercise summary only after explicit confirmation, supports excluded Exercise identifiers, and intentionally has no standalone production route.
- **EXISTING IMPLEMENTATION:** Admin Web provides the authenticated, `CATALOG_MANAGE`-guarded AD-05 catalog workflow: Vietnamese list/search/lifecycle filter/pagination, stable backend ordering, detail, draft create/edit, activate/archive, and canonical replacement management with explicit loading/empty/no-result/permission/stale/conflict states.
- **CONFIRMED DECISION:** Canonical replacement is mapping-only in B02. It does not rewrite historical workout/template foreign keys or implement B04/B05 consumers. The read-only preview therefore reports usage impact as `NOT_AVAILABLE` with a `null` count, never a synthetic zero.
- **B02 CLOSURE SCOPE:** The Admin list projects deterministic muscle-group codes, equipment codes, and a safe media-availability flag without exposing object-storage references. Pagination selects Exercise rows before resolving many-to-many metadata so relation joins cannot duplicate rows or change totals.

## 4. Feature dependencies

- **CONFIRMED REQUIREMENT:** B02 uses B01 for authentication, API access, navigation, shared states, and design primitives.
- **CONFIRMED REQUIREMENT:** B04 consumes Exercise identifiers and active/archived catalog behavior.
- **PROPOSED SOLUTION:** Deliver B02 as the first complete B-owned backend/Mobile/Admin vertical slice before B04.

## 5. External subsystem dependencies

- **CONFIRMED REQUIREMENT:** Media upload and object-storage ownership are provided by the Media subsystem; B02 stores and returns authorized references only.
- **CONFIRMED REQUIREMENT:** Admin identity, content permission, audit, and privileged action checks are platform dependencies.
- **CONFIRMED REQUIREMENT:** Database changes require Database Review and a new forward-only migration.

## 6. Data model and schema requirements

- **EXISTING IMPLEMENTATION:** V3 already models the core Exercise catalog and V12 adds integrity/index support.
- **CONFIRMED REQUIREMENT:** Historical workout/template rows must continue resolving an Exercise after it is archived or superseded.
- **B02 HISTORY-SAFE FOUNDATION:** Archive and canonical replacement preserve the Exercise row, identifier, content relations, and Admin readability. Canonical replacement adds or changes mapping metadata only; it does not rewrite historical foreign keys.
- **DEFERRED INTEGRATION:** Authorized end-to-end resolution of archived Exercises from Workout Plan and Workout Log belongs to B04 and B05. Those consumers do not yet exist, so B02 does not claim that cross-feature acceptance criterion as implemented and does not add synthetic history data or a new public archived-detail API.
- **CONFIRMED DECISION:** V22 standardizes the Exercise lifecycle as `DRAFT`, `ACTIVE`, and `ARCHIVED`, migrates legacy `INACTIVE` rows to `ARCHIVED`, and adds optimistic versioning plus Admin query indexes.
- **EXISTING IMPLEMENTATION:** Canonical replacement maps an archived source directly to one active, non-deleted target. Self-reference, cycles, chains, and archiving a current canonical target are rejected.

## 7. Backend and API scope

- **EXISTING IMPLEMENTATION:** Module-owned domain/application services, persistence adapters, DTOs, and controllers implement Admin catalog lifecycle without exposing persistence entities or another module's repository.
- **EXISTING IMPLEMENTATION:** Admin APIs require an active `ADMIN` role and the database-backed `CATALOG_MANAGE` permission; mutations use optimistic version checks and immutable audit records.
- **PROPOSED SOLUTION:** Support stable filtering by text, status, muscle group, equipment, category, and variation/canonical relationship with deterministic sorting.
- **CONFIRMED REQUIREMENT:** Archive and merge/mapping actions require validation and audit; hard delete is not exposed.
- **CONFIRMED DECISION:** Public catalog and detail remain `ACTIVE`-only. Admin detail is the B02 read path that verifies an archived record remains addressable after archive or canonical mapping.

## 8. Mobile scope

- **EXISTING IMPLEMENTATION:** ST-21 Exercise Library browse/search/filter uses the B01 API/auth client and a non-persisted TanStack Query cache that is cleared on logout or authenticated identity change.
- **EXISTING IMPLEMENTATION:** ST-22 detail is available from ST-21 at `/(app)/exercises/[exerciseId]` and reuses B01 authentication/query-cache lifecycle behavior.
- **EXISTING IMPLEMENTATION:** The reusable Exercise picker is available for plan-building consumers without a standalone route; B04 remains responsible for integrating it and persisting the selected Exercise identifier through its own plan commands.
- **PROPOSED SOLUTION:** Display instructions, muscles, equipment, variation relationships, media availability, and archived/unavailable states without breaking historical plan views.
- **PROPOSED SOLUTION:** Map pagination, query, filter, empty results, retryable errors, permission errors, and stale catalog state explicitly.

## 9. Admin Web scope

- **CONFIRMED REQUIREMENT:** Implement the basic Exercise management portion of AD-05 only.
- **EXISTING IMPLEMENTATION:** The permission-aware table and read-only detail use effective permissions from `GET /users/me`; role or JWT inference is not used for UI access.
- **EXISTING IMPLEMENTATION:** Archived Exercise detail supports setting, changing, and clearing a direct active canonical target with reason, displayed-version guard, audited backend mutation, no-op controls disabled in the client, and a read-only impact preview that marks deferred usage data unavailable.
- **EXISTING IMPLEMENTATION:** Admin list rows include muscle groups, equipment, media availability, lifecycle, variation count, and stable pagination totals sourced by the backend without exposing media storage metadata.
- **DEFERRED REQUIREMENT:** Usage counting remains `NOT_AVAILABLE`/`null` until B04/B05 expose an authorized module-owned usage query. The Admin UI must not render zero for this unavailable value.
- **UNRESOLVED REQUIREMENT:** “Duplicate signal” has no approved detection semantics. `ARCHIVED` and canonical replacement are governance states, not evidence that two Exercises are duplicates. Before implementation, Product must define signal source, evidence/confidence, false-positive review behavior, and permitted action.
- **CONFIRMED REQUIREMENT:** Admin manages platform content and does not obtain coaching authority through this feature.

## 10. UI/UX and Figma deliverables

- **CONFIRMED REQUIREMENT:** Reuse AD-05 behavior from [Admin screens](../../06-ui-ux/admin/admin-screens.md) and shared states/components.
- **PROPOSED SOLUTION:** Add Mobile frames for browse, filters, no results, detail, picker, unavailable historical Exercise, and media-unavailable states.
- **PROPOSED SOLUTION:** Add Admin frames for list, create/edit validation, archive, canonical mapping, permission denied, and audited success. Duplicate review remains deferred until its signal semantics are approved.
- **VERIFIED REFERENCE:** The read-only SuperFit audit documents inspected nodes for search/filter, workout-detail composition, and an Exercise Playlist row. It has not found complete frames dedicated to `ST-21`, `ST-22`, the Exercise picker, or their non-happy states; those deliverables remain product requirements rather than verified Figma artifacts.

## 11. Implementation steps

1. **PROPOSED SOLUTION:** Confirm lifecycle/status mapping and current schema sufficiency with Database Review.
2. **PROPOSED SOLUTION:** Add and validate the OpenAPI Exercise resource contract.
3. **PROPOSED SOLUTION:** Implement backend domain/application behavior, persistence, authorization, and audit integration.
4. **PROPOSED SOLUTION:** Build Mobile browse/detail/picker flows and Admin AD-05 management.
5. **PROPOSED SOLUTION:** Add contract, domain, authorization, persistence, client, accessibility, and archive-history tests.

## 12. Acceptance criteria

- **CONFIRMED REQUIREMENT:** Mobile users can search and filter active Exercises and open a complete detail view.
- **CONFIRMED REQUIREMENT:** A permitted Admin can create, edit draft content, activate, archive, and map a replacement without hard-deleting history.
- **CONFIRMED REQUIREMENT:** Archived Exercises are excluded from new selection by default, and B02 preserves their IDs and records instead of deleting or rewriting them.
- **FUTURE B04/B05 ACCEPTANCE CRITERION:** An authorized viewer of a historical Workout Plan or Workout Log can resolve its archived Exercise reference without making that Exercise selectable for new content. B04/B05 own the consumer query, authorization, UI states, and end-to-end tests for this criterion.
- **CONFIRMED REQUIREMENT:** Unauthorized content mutations fail server-side and do not rely on hidden UI controls.
- **CONFIRMED REQUIREMENT:** Pagination and sorting return stable, non-duplicated results.
- **B02 CLOSURE ACCEPTANCE CRITERION:** Admin list rows expose deterministic muscle/equipment collections and media availability; relationship multiplicity cannot duplicate an Exercise or alter `totalItems`/page boundaries, and no storage reference is returned.

## 13. Testing requirements

- **CONFIRMED REQUIREMENT:** Unit-test lifecycle rules, canonical mapping, filters, validation, and archive behavior.
- **CONFIRMED REQUIREMENT:** Integration-test search indexes, stable non-duplicated pagination, history-safe archive/canonical persistence, authorization, and Admin audit emission.
- **DEFERRED TEST:** B04/B05 must integration-test their own authorized historical archived-Exercise read paths when their plan/log consumers exist.
- **CONFIRMED REQUIREMENT:** Contract-test all Exercise endpoints and stable errors.
- **CONFIRMED REQUIREMENT:** Test Mobile/Admin loading, empty, no-result, error, archived, permission, keyboard, and accessibility states.

## 14. Definition of Done

- **CONFIRMED REQUIREMENT:** OpenAPI, backend behavior, Mobile Exercise Library, Admin AD-05, tests, and documentation agree.
- **CONFIRMED REQUIREMENT:** No referenced Exercise can be physically deleted through the feature.
- **B02 CLOSURE DEFINITION:** B02 is complete at the history-safe foundation boundary when archive/canonical operations preserve ID, row and Exercise-owned relations; Admin can still read the archived record; public reads remain `ACTIVE`-only; and contracts/tests/docs agree on deferred usage availability.
- **CROSS-FEATURE DEFINITION:** Product-level historical readability is not complete until B04/B05 consumers resolve archived references under viewer authorization. This remains a tracked dependency rather than a reason to create placeholder consumers in B02.
- **CONFIRMED REQUIREMENT:** Any schema change is delivered by the Database owner through a new reviewed Flyway migration.
- **CONFIRMED REQUIREMENT:** Media bytes remain in object storage and only authorized metadata/references enter PostgreSQL/API responses.

## 15. Decisions

- **CONFIRMED REQUIREMENT:** B02 includes basic Admin Exercise management; other Admin governance remains outside this feature.
- **CONFIRMED REQUIREMENT:** PostgreSQL catalog data is authoritative; AI output is not an Exercise source of truth.
- **PROPOSED SOLUTION:** Treat B02 as the reusable catalog/picker foundation for B04.
- **CONFIRMED DECISION:** Legacy `INACTIVE` Exercise data is mapped forward to `ARCHIVED`; `INACTIVE` is no longer accepted by the Exercise contract or current schema constraint.
- **CONFIRMED DECISION:** Usage counts are unavailable until B04/B05 provide a module-owned query and are represented as `NOT_AVAILABLE` with `null`, never zero.
- **CONFIRMED DECISION:** Duplicate detection is outside B02 closure until its semantics and evidence model are approved; canonical replacement must not be reused as a duplicate-detection signal.

## 16. Delivery workflow

- **CONFIRMED REQUIREMENT:** Implement B02 on `feature/m3-exercise-library` under the shared [feature branch workflow](../feature-branch-workflow.md).
- **CONFIRMED REQUIREMENT:** Create the branch only when B02 implementation begins and after checking that B01 and required lifecycle decisions are ready.
- **CONFIRMED REQUIREMENT:** B02 reaches `main` only through a reviewed Pull Request, with Person A as a required reviewer.
