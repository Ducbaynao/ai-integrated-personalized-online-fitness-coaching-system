# Requirements

This section converts the product description into implementation requirements. Requirements apply to the target product unless a roadmap phase is stated explicitly.

## Requirement groups

- [Functional requirements](functional-requirements.md)
- [Non-functional requirements](non-functional-requirements.md)
- [Acceptance criteria](acceptance-criteria.md)

## Interpretation rules

- `must` indicates a required invariant or behavior.
- `should` indicates the preferred target-product behavior unless a documented architecture decision supersedes it.
- Future-phase placement controls delivery order, not the validity of a boundary.
- Authorization requirements are enforced by the backend, never only by hidden client controls.
- Missing optional data must degrade gracefully and remain explicitly unknown.

Requirements should be traceable to domain rules, API contracts, migrations, and tests. When a requirement changes, update all affected artifacts in the same pull request.

