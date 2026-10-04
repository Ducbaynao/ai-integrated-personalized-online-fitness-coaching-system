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

- Project skills live under `.agent/skills/`. Read `.agent/skills/digital-fitness-core/SKILL.md` for every project task, then load the focused skill for the area being changed.
- For Student Mobile, Trainer Mobile, Admin Web, navigation, UX flows, design tokens, shared UI components, accessibility, or Figma-to-code work, also read `.agent/skills/digital-fitness-ui/SKILL.md` and the references it routes to.
- Follow `apps/mobile/AGENTS.md` before changing Expo code.
- Add component-specific `AGENTS.md` files only when the component needs additional rules.


## Phase 2 execution

For any Phase 2 task, including Backend, Database, AI, Mobile, Admin, testing, review, or documentation, read these approved documents in order before implementation:

1. [Release scope](docs/00-project-overview/phase-2-release-scope.md): F01–F11, release limits, D01–D07 and approval record.
2. [Feature data and authority matrix](docs/08-ai/phase-2-feature-data-authority-matrix.md): facts, scopes, result types, source guards, transaction and retry semantics.
3. [Implementation plan](docs/07-development/phase-2-implementation-plan.md): P2-M0–P2-M6, prerequisites, ownership and T01–T28.

- Version 1.0 and D01–D07 were approved by the project owner on 2026-10-04. Apply these settled decisions without requesting the same approval again. Approval covers scope/design, not test results, specialist review, deployment or Git operations.
- Then load the focused skills and applicable component `AGENTS.md`, and follow links relevant to the feature. Unrelated Phase 1 maintenance need not read all Phase 2 materials.
- Before changing a feature, identify its F-ID/P2 milestone, owning module, actor/subject, decision authority, lifecycle, relevant source dependencies, contracts and acceptance/test IDs. Do not invent implementation status from document approval.
- Inspect current code, Flyway and executable contracts. A missing planned feature is an implementation gap to address within the task scope, not an automatic reason to request design approval again. For a genuine conflict with approved invariants or a new scope/authority decision, describe it and obtain the needed decision before dependent changes; continue unaffected authorized work.
- Keep Phase 1 prerequisites and AI additions separate. Fixtures unblock development but do not establish real source-domain readiness. Record missing prerequisites and verification limits; never use model output as a replacement for a missing source of truth.
- INFORMATION has no Apply; PROPOSAL uses authorized recommendation decisions; ESTIMATE uses Student-owned Nutrition Confirm/Correct. AI Run status, result type, error reason and domain lifecycle are distinct.
- Implement approved atomic Accept-and-Apply and selected-item Food Confirmation through Spring domain commands; preserve provenance/history and mandatory audit. Guard source changes and retry identity, including competing Phase 1 writers. No model/provider call belongs inside the apply transaction.
- Extend OpenAPI/AI schemas with implementation, add only needed forward Flyway migrations, and test relevant T01–T28 scenarios. Model/retrieval evaluation remains separate from deterministic authorization/integrity tests. Do not claim all planned tests pass without running them.
- Record unresolved operational values, specialist rule/catalog review, dataset/model selection and release measurements as milestone work. Approval of the plan does not approve unknown production thresholds or prove portion-estimation coverage.
- Keep summaries, skills and relevant UI/API/domain documentation synchronized with an approved scope change. Assignee/reviewer ownership belongs to the task/issue; product roles do not identify developers.

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
