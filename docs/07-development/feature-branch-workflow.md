# Feature branch workflow

This workflow applies to B-owned feature delivery and supplements the repository Git safety rules in `AGENTS.md`.

## Branch policy

- Implement each feature on its own branch. Do not implement or commit feature work directly on `main`.
- Create a feature branch only when implementation of that feature begins.
- Use `feature/<milestone>-<feature-name>` with the milestone defined by the Phase 1 roadmap. Do not invent an unapproved sub-milestone suffix.
- Prefer a verified, current `origin/main` as the base. Fetch or update Git references only when that Git operation is explicitly authorized.
- If a feature depends on unmerged work, record the blocker and agree on the integration base before branching. Do not merge another owner's branch or branch from it without an explicit dependency plan.
- Never reset, rewrite, force-push, or discard shared work to prepare a feature branch.

## Feature branch map

| Feature | Roadmap milestone | Branch name |
| --- | --- | --- |
| B01 Mobile Foundation | M1 Identity and Common Account | `feature/m1-mobile-foundation` |
| B02 Exercise Library | M3 Exercise and Workout Planning | `feature/m3-exercise-library` |
| B03 Coaching | M2 Goals and Coaching Authority | `feature/m2-coaching` |
| B04 Workout Plan | M3 Exercise and Workout Planning | `feature/m3-workout-plan` |
| B05 Workout Logging | M4 Workout Execution and History | `feature/m4-workout-logging` |
| B06 Schedule | M5 Scheduling and Supervision | `feature/m5-schedule` |
| B07 Nutrition | M6 Nutrition Foundation | `feature/m6-nutrition` |
| B08 Chat and Notification | M7 Communication and Attention | `feature/m7-chat-notification` |

The map assigns branch names; it does not change feature dependency order. Do not create B02-B08 branches until their implementation starts.

## Starting a feature

1. Read the feature document and its linked requirements, domain, API, UI/UX, database, and architecture sources.
2. Read the local execution backlog and relevant `.local.md` notes when available.
3. Inspect the current branch, working tree, staged files, local branches, remote-tracking branches, and feature dependencies.
4. Confirm the intended base branch and that prerequisite work is merged or explicitly coordinated.
5. Create or switch to the feature branch without losing working-tree changes.
6. Recheck the branch and working tree before implementation.

## Checkpoint commits

- A feature may use multiple reviewable checkpoint commits.
- Stage only files owned by the checkpoint. Review the staged diff before committing.
- Keep `.local.md`, session notes, personal backlog content, secrets, generated credentials, and unrelated feature planning out of the index.
- Run the checks required by the feature document and record any remaining manual or end-to-end verification.
- A checkpoint commit does not make a feature Done. Definition of Done and outstanding verification still apply.
- Do not push a checkpoint unless the user explicitly authorizes the push.

## Pull requests and merge

1. Complete the required code, contract, documentation, and test checks.
2. Confirm no local-only or unrelated files are tracked or staged.
3. Push the feature branch only when explicitly authorized.
4. Open a Pull Request against the agreed base branch.
5. Request review from affected owners. Person A is a required reviewer for Person B feature Pull Requests.
6. Address review findings and rerun affected checks.
7. Merge only after the team criteria and repository protections pass and merge authorization is given.

Direct pushes to `main`, review bypasses, autonomous merges, and history rewrites are prohibited.
