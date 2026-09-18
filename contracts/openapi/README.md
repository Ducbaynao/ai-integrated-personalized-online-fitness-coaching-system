# OpenAPI contracts

`openapi.yaml` is the checked-in REST contract reviewed by Backend, Mobile, Admin Web, and integration tests. Spring Boot implements the contract and remains the business authority; persistence entities are never external response models.

## Current coverage

Version `0.1.0` covers Phase 1 Milestone 1 Identity and Common Account:

- registration and email confirmation;
- login, refresh-token rotation, and logout;
- current User and settings;
- Student Profile activation;
- Trainer Profile activation without automatic coaching authority;
- Trainer Application submission and status.

Later milestones extend the same `/api/v1` contract. Do not add undocumented endpoints in a client or silently change a request, response, error code, enum, or authority rule.

## Change workflow

1. Identify the owning requirement and lifecycle rule.
2. Update `openapi.yaml` with the backend change.
3. Validate the specification in CI.
4. Add backend contract/integration tests.
5. Regenerate or update typed clients after the reviewed contract changes.
6. Treat incompatible changes as an explicit API-version decision.

Secrets, token hashes, persistence entities, stack traces, SQL, and internal authorization details must never appear in this contract's responses.
