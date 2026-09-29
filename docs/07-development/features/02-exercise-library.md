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
- **EXISTING IMPLEMENTATION:** The backend `exercise` module provides the authenticated Student/Trainer read-only catalog foundation (search/filter/list, detail, and filter metadata); Admin mutations and historical archived access remain unimplemented.
- **EXISTING IMPLEMENTATION:** OpenAPI defines the three read-only Exercise catalog paths; mutation paths remain unimplemented.
- **EXISTING IMPLEMENTATION:** Mobile ST-21 provides authenticated Student/Trainer catalog browse, debounced search, metadata-driven filters, session query cache, pagination, pull-to-refresh, and explicit loading/empty/error/stale states.
- **EXISTING IMPLEMENTATION:** Mobile ST-22 provides an authenticated Student/Trainer detail route, typed detail query, Vietnamese loading/error/unavailable/stale states, optional metadata sections, variation/guidance content, and media availability placeholders without exposing storage references.
- **EXISTING IMPLEMENTATION:** Mobile provides a reusable controlled single-selection Exercise picker component for future B04 Plan Builder integration. It reuses ST-21 query/search/filter/pagination states, returns a typed Exercise summary only after explicit confirmation, supports excluded Exercise identifiers, and intentionally has no standalone production route. Admin Web still has no AD-05 implementation.

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
- **UNRESOLVED DECISION:** Domain documentation defines `DRAFT`, `ACTIVE`, and `ARCHIVED`, while V3 also permits `INACTIVE`; Database Review must approve the canonical lifecycle or mapping before backend status rules are finalized.
- **PROPOSED SOLUTION:** Raise a Schema Change Request only if the reviewed lifecycle, search indexes, or canonical mapping cannot be represented by the current schema.

## 7. Backend and API scope

- **PROPOSED SOLUTION:** Add module-owned domain/application services, persistence adapters, DTOs, and controllers; do not expose persistence entities or another module's repository.
- **PROPOSED SOLUTION:** Define OpenAPI resources for paginated Exercise search, Exercise detail, filter metadata, and permission-protected Admin create/update/archive/canonical-map actions.
- **PROPOSED SOLUTION:** Support stable filtering by text, status, muscle group, equipment, category, and variation/canonical relationship with deterministic sorting.
- **CONFIRMED REQUIREMENT:** Archive and merge/mapping actions require validation and audit; hard delete is not exposed.

## 8. Mobile scope

- **EXISTING IMPLEMENTATION:** ST-21 Exercise Library browse/search/filter uses the B01 API/auth client and a non-persisted TanStack Query cache that is cleared on logout or authenticated identity change.
- **EXISTING IMPLEMENTATION:** ST-22 detail is available from ST-21 at `/(app)/exercises/[exerciseId]` and reuses B01 authentication/query-cache lifecycle behavior.
- **EXISTING IMPLEMENTATION:** The reusable Exercise picker is available for plan-building consumers without a standalone route; B04 remains responsible for integrating it and persisting the selected Exercise identifier through its own plan commands.
- **PROPOSED SOLUTION:** Display instructions, muscles, equipment, variation relationships, media availability, and archived/unavailable states without breaking historical plan views.
- **PROPOSED SOLUTION:** Map pagination, query, filter, empty results, retryable errors, permission errors, and stale catalog state explicitly.

## 9. Admin Web scope

- **CONFIRMED REQUIREMENT:** Implement the basic Exercise management portion of AD-05 only.
- **PROPOSED SOLUTION:** Provide a permission-aware table, detail/editor, create flow, archive confirmation, duplicate indication, and canonical mapping impact summary.
- **CONFIRMED REQUIREMENT:** Admin manages platform content and does not obtain coaching authority through this feature.

## 10. UI/UX and Figma deliverables

- **CONFIRMED REQUIREMENT:** Reuse AD-05 behavior from [Admin screens](../../06-ui-ux/admin/admin-screens.md) and shared states/components.
- **PROPOSED SOLUTION:** Add Mobile frames for browse, filters, no results, detail, picker, unavailable historical Exercise, and media-unavailable states.
- **PROPOSED SOLUTION:** Add Admin frames for list, create/edit validation, archive, duplicate review, canonical mapping, permission denied, and audited success.
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
- **CONFIRMED REQUIREMENT:** Archived Exercises are excluded from new selection by default but remain readable from historical plans/logs.
- **CONFIRMED REQUIREMENT:** Unauthorized content mutations fail server-side and do not rely on hidden UI controls.
- **CONFIRMED REQUIREMENT:** Pagination and sorting return stable, non-duplicated results.

## 13. Testing requirements

- **CONFIRMED REQUIREMENT:** Unit-test lifecycle rules, canonical mapping, filters, validation, and archive behavior.
- **CONFIRMED REQUIREMENT:** Integration-test search indexes, pagination, historical references, authorization, and Admin audit emission.
- **CONFIRMED REQUIREMENT:** Contract-test all Exercise endpoints and stable errors.
- **CONFIRMED REQUIREMENT:** Test Mobile/Admin loading, empty, no-result, error, archived, permission, keyboard, and accessibility states.

## 14. Definition of Done

- **CONFIRMED REQUIREMENT:** OpenAPI, backend behavior, Mobile Exercise Library, Admin AD-05, tests, and documentation agree.
- **CONFIRMED REQUIREMENT:** No referenced Exercise can be physically deleted through the feature.
- **CONFIRMED REQUIREMENT:** Any schema change is delivered by the Database owner through a new reviewed Flyway migration.
- **CONFIRMED REQUIREMENT:** Media bytes remain in object storage and only authorized metadata/references enter PostgreSQL/API responses.

## 15. Decisions

- **CONFIRMED REQUIREMENT:** B02 includes basic Admin Exercise management; other Admin governance remains outside this feature.
- **CONFIRMED REQUIREMENT:** PostgreSQL catalog data is authoritative; AI output is not an Exercise source of truth.
- **PROPOSED SOLUTION:** Treat B02 as the reusable catalog/picker foundation for B04.
- **UNRESOLVED DECISION:** Canonical handling of V3 `INACTIVE` versus the documented lifecycle requires team/Database Review.

## 16. Delivery workflow

- **CONFIRMED REQUIREMENT:** Implement B02 on `feature/m3-exercise-library` under the shared [feature branch workflow](../feature-branch-workflow.md).
- **CONFIRMED REQUIREMENT:** Create the branch only when B02 implementation begins and after checking that B01 and required lifecycle decisions are ready.
- **CONFIRMED REQUIREMENT:** B02 reaches `main` only through a reviewed Pull Request, with Person A as a required reviewer.
