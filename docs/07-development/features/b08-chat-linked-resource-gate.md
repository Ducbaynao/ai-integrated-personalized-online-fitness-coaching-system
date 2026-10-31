# B08 Chat linked resources — source inventory and decision gate

Status: `APPROVED` by Person B on 2026-10-29 for the three choices below; this
does not itself verify implementation or complete M7. PR #119 is merged at
`519c0c8a2890e299db893f6d41a509eb0af5e4c0`; its merge-commit CI run
`40887200159` completed 6/6 jobs successfully. The linked-resource runtime is
implemented in this worktree only. The project owner reports Person A approved
V52 through a direct message (no GitHub approval evidence); Antigravity
REVIEW_PASS covered the 21-file implementation before this A01-status-only
documentation update. The integration base is `origin/main@d8105eefb9156c68194d761f939c455d5ec8dfef`,
which already contains AI migration V51; the Chat candidate therefore uses V52.
This document is not evidence of a merged feature.

**Post-merge V52 collision (2026-10-31):** PR #122 merged the linked-resource
feature at `70a500134d195b630bc87eec08ac2a356fbfe035`, after PR #123
introduced a separate AI V52. The merge-SHA CI failed Database Migrations and
Backend because Flyway found two V52 scripts. The isolated hotfix candidate
keeps AI V52 intact and moves the byte-identical Chat migration to V53.
The earlier V52 statements in this inventory describe the pre-merge candidate,
not a working migration order on current main. The project owner reports
Person A approved both the Chat schema and AI V52 → Chat V53 order through
direct conversations; shared dev/staging/production Flyway histories and
production rollout values are still unknown. See the
[collision packet](v52-flyway-collision-hotfix-plan.md) and updated
[A01 packet](b08-chat-linked-resource-a01-schema-review.md).

## Executable baseline

- B08 permits direct messaging only within the active human Coaching Period.
  A Student may read their own conversation history; a Trainer can read only
  while the matching current period and account/eligibility authority remain
  valid. Pause/end closes the period; resume creates a new one (B03/B08).
- V1's `message_type` includes `WORKOUT_SHARE` and `SCHEDULE_SHARE`; V8 has
  nullable `messages.shared_entity_type` and `shared_entity_id`. These are
  schema possibilities, **not** an approved allowlist or authorization rule.
  At the merged V50 baseline, `ChatService` wrote only `TEXT` and attachment
  messages; its `Message` response and OpenAPI had no linked-entity fields.
  The nullable columns had no source-object FK or type-specific constraint;
  their presence did not prove that an executable flow needed no migration.
- A Chat message or notification cannot grant source-resource access. Any
  destination must go through the owner module's authenticated API and recheck
  its current authorization. No presigned or direct object URL is appropriate.
  The existing generic Chat notification preview must not gain resource data.

## Resource inventory

| Candidate | Existing source read API | Authority and lifetime to preserve |
| --- | --- | --- |
| Workout Plan and immutable version | `GET /api/v1/workout-plans/{planId}` and `/versions/{versionId}` | Workout-owned query enforces actor and Student scope; a published version ID, not mutable current-plan identity, would be needed for the recommended immutable reference. Chat membership does not expand plan access. |
| Planned Workout occurrence | `GET /api/v1/planned-workouts/{occurrenceId}` | Workout-owned detail has schedule, supervision and lineage semantics. A rescheduled predecessor and successor are distinct IDs; a link must not silently redirect to the successor. |
| Coaching Appointment | `GET /api/v1/appointments/{appointmentId}` and `/history` | Schedule-owned participant/period checks apply. Appointment and planned workout have independent lifecycles. |
| Proposal, Nutrition, Attention/Review | Multiple source-specific detail/read routes | Actor and sharing rules differ by domain; no generic `shared_entity_type` can safely authorize them. They are outside the approved first allowlist. |

## Approved linked-resource policy

| Decision | Approved behavior | Boundary |
| --- | --- | --- |
| Exact first allowlist and identity | **Published** Workout Plan version, Planned Workout occurrence, and Coaching Appointment IDs only. Reject unrecognized types and arbitrary URLs. | Do not add Goal, Nutrition, Attention/Review or generic file links by analogy. |
| Send-time actor and recipient authority | Student or Trainer may send only in the active conversation when **both** can read the same source object and belong to the current Coaching Period. Recheck at commit and on replay. | A message cannot grant source permission. Conceal invalid or unauthorized IDs. |
| Message presentation and later access | Store only type, stable source ID and a minimal non-sensitive label. GET and tap recheck source authority. After loss of source access, hide source detail and show a generic unavailable state; never serve a cached title/detail. | No full source snapshot or direct URL in Chat. |
| Period closure, revoke, account disable | Student retains only authorized Chat history; historical Chat alone does not grant source read. Former Trainer cannot read the old conversation. Disabled accounts cannot authenticate. | B03/B08 existing lifecycle and Auth cutoff still govern. |

The approved choices above resolve the earlier product decision gate. Use
module-owned source authorization ports rather than querying another module's
tables from Chat; test send/open races, replay, pause/end/resume, revoke,
disabled accounts, resource transition, and direct-ID concealment on disposable
PostgreSQL.
