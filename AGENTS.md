# Project instructions

## Architecture

- Keep the repository as a monorepo.
- Keep the Spring Boot backend as a modular monolith until a measured scaling or ownership need justifies extracting a service.
- Treat PostgreSQL as the system of record. Redis and AI outputs are never sources of truth.
- Route Mobile and Admin requests through the Spring Boot backend. Clients must not call the AI service directly.
- Store binary media in object storage and keep only metadata and object references in PostgreSQL.

## Business invariants

- The only coaching modes are `HUMAN_COACH` and `SELF_DIRECTED`.
- AI assistance is not a coaching mode and has no business authority.
- Coaching mode belongs to a coaching period, not permanently to a student profile.
- Fitness history must survive goal, plan, trainer, and coaching-period transitions.
- Trainer or AI changes to a student's fitness goal must use a goal proposal and student confirmation.
- Strategic nutrition target changes require proposal, approval, and version history.
- Missing measurement or nutrition data is unknown, never zero.
- Progress Engine produces validated signals before AI context is built.
- Administrator is platform authority, not coaching authority.

## Code rules

- Do not put business logic in controllers.
- Do not expose persistence entities in external API responses.
- Enforce module boundaries; do not access another module's repository directly.
- Use Flyway for schema changes and never rely on Hibernate schema generation outside tests.
- Add tests for changed business rules and update relevant documentation or contracts.
- Preserve existing user changes and avoid unrelated refactors.

## Component instructions

- Follow `apps/mobile/AGENTS.md` before changing Expo code.
- Add component-specific `AGENTS.md` files only when the component needs additional rules.


## Git safety

- Coding agents may inspect Git using read-only commands such as
  `git status`, `git diff`, `git log`, and `git show`.
- Do not run `git add`, `git commit`, `git commit --amend`, `git push`,
  `git pull`, `git fetch`, `git merge`, `git rebase`, `git cherry-pick`,
  `git revert`, `git stash`, `git switch`, `git checkout`, `git reset`,
  `git restore`, `git clean`, or create/delete branches or tags unless the
  user explicitly requests that exact Git operation.
- Do not modify `.git`, Git hooks, Git configuration, remotes, credentials,
  branch tracking, submodules, or worktrees.
- Never rewrite Git history or force-push.
- Never discard, overwrite, stage, or commit existing user changes.
- Implementation requests authorize working-tree file changes only. They do
  not authorize staging, committing, pushing, pulling, or changing branches.
- Before making changes, inspect `git status --short`.
- After making changes, report `git status --short`, changed files, tests run,
  and any unrelated pre-existing changes.
- The user remains responsible for reviewing, staging, committing, and
  pushing changes unless they explicitly delegate one of those operations.