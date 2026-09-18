# Development guide

This section explains how to run, understand, change, and verify the monorepo.

- [Development setup](setup.md)
- [Project structure](project-structure.md)
- [Testing guide](testing-guide.md)

Before changing code, read the repository `AGENTS.md` and any component-specific instructions. A feature change is incomplete when it changes a business rule but leaves the corresponding documentation, contract, migration, or test outdated.

## Engineering rules

- Keep the backend modular monolith until measured evidence supports extraction.
- Keep business logic out of controllers.
- Do not expose persistence entities in external APIs.
- Do not access another module's repository directly.
- Use Flyway for every persistent schema change.
- Preserve history and effective versions instead of overwriting strategic data.
- Add tests for authority, lifecycle, history, missing data, and failure paths.
- Use structured logs and stable error codes without leaking sensitive data.

