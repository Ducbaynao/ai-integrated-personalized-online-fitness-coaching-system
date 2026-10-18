# B08 Chat and In-App Notification — approved first slice

**Decision status:** APPROVED by Person B on 2026-10-17. This record defines product policy; it does not claim the OpenAPI contract, Flyway migration, backend, Mobile, delivery service, or tests are already implemented. M7 remains broader than this slice.

1. A Coaching Period has at most one direct conversation between exactly that period's Student and Trainer. The first message creates it; a later period uses a new conversation. No messaging outside the relationship.
2. Sending requires an ACTIVE Coaching Period and eligible accounts. The Student retains access to their own message history. The Trainer loses send and read access while the period is paused or ended, or current authority is otherwise lost. After resume, fresh authority checks apply to the **new** human Coaching Period and its new conversation. V8 membership alone grants no former-Trainer history access.
3. The first slice supports text only. Attachments, linked resources, message edits/deletion, and message bodies in notification previews are excluded.
4. A committed message is SENT. DELIVERED means the recipient device explicitly acknowledges receipt; READ is an explicit command from the recipient. Per-user receipts advance monotonically and idempotently. GET never marks a message READ.
5. PostgreSQL/backend is authoritative. WebSocket only signals an update after commit and carries no sensitive body; clients refetch through authorized REST reads. Authenticate and authorize connection and every operation; loss of authority blocks further access and clears related client cache.
6. A new message creates an in-app notification only for a recipient eligible at commit time. Its generic preview is “Bạn có tin nhắn mới”, without the message body. Message and notification commit atomically. Push, email, and other event types are excluded.
7. The in-app inbox cannot be disabled. No quiet-hours, push, or email settings UI; no TTL deletion or export/delete command in this slice. Retention and account erasure remain separate release-gate decisions, not operational policy claimed here.
8. Attention Signals and Coaching Review Cycle require separate rules, evidence, and cadence in later checkpoints. This slice does not complete M7.

Implementation must preserve B03's current authority checks and concealment, module boundaries, durable history, idempotent writes, and stable pagination. V8 provides historical tables, not a substitute for these policies; use forward-only Flyway changes where necessary. SuperFit is visual reference only.

## Reconciliation with B03 period lifecycle

Previously approved B03 closes the old `HUMAN_COACH` period on pause, opens a `SELF_DIRECTED` period, and creates a **new** `HUMAN_COACH` period on resume (`docs/07-development/features/03-coaching.md`, lines 16 and 88–89; `CoachingLifecycleService`, lines 130–140 and 220–226). Therefore even when both actors are unchanged, resume uses a new conversation keyed by the new period. The old conversation is not reopened or shared. Student may read their own old conversation; Trainer may read only the conversation attached to their current effective period while currently authorized. Notification reads, realtime events, and client caches must not reveal the old conversation to that Trainer.

This is the direct consequence of B08's per-period conversation identity and B03's existing lifecycle, not a change to B03 or a new product policy. V8's relationship-only reference requires a forward migration to persist period identity. Attention/Review, attachments and push remain deferred for their separately stated reasons.

## Post-merge M7 remainder audit (2026-10-18)

`origin/main@66227790427be8b3d81716c1013ab14b5ec03a6c` contains PR #85: OpenAPI, V43, Spring Chat/Notification and Expo Student/Trainer routes. GitHub CI run 39042940387 completed successfully. PostgreSQL is authoritative; Mobile WebSocket signals an invalidation after commit and refetches REST data. Message and generic in-app notice share one database transaction. Merge and CI do not prove a device connected to a disposable backend or complete the M7 Definition of Done.

| M7/FR-CA obligation | Status after PR #85 | Evidence and limit |
| --- | --- | --- |
| Period-scoped text conversation, persistent messages and monotonic recipient receipts | `MERGED_VERIFIED` | OpenAPI Chat paths, V43, `ChatService`, `ChatUpdatesSocket`, Expo Chat screens and isolated PostgreSQL tests. Attachments and deletion are excluded. |
| Generic in-app notice for a newly committed message | `MERGED_VERIFIED` | `InAppNotificationService`, notification REST and V43. Push, reminders, per-category preference commands and delivery service are absent. |
| Attachment and policy-compliant deletion/export | `BLOCKED_DECISION` | FR-CA-001 includes them; approved B08 decision 3 excludes them from the first slice, and decision 7 leaves retention/account erasure unresolved. V8 table shape alone is not a deletion policy. |
| Push/preference/reminder delivery | `BLOCKED_DECISION` | FR-CA-002 and M7 include these; B08 decisions 6–7 explicitly defer external channels and other events. V8 preference/delivery tables have no first-flow policy or executable read/write workflow. |
| Deterministic Attention Signal and Coaching Review Cycle | `BLOCKED_DECISION` | FR-CA-003–005 and M7 require these. V8 has `attention_signals`, but approved B08 decision 8 defers rule/evidence/cadence and no production application flow exists. |
| Retention and account erasure | `BLOCKED_DECISION` | Approved B08 decision 7 names a release gate; no operational duration, deletion/anonymization scope, or legal-hold policy has been approved. |

The rows marked `BLOCKED_DECISION` are not implementation approvals. Source schemas and UX sketches do not supply severity thresholds, review cadence, notification recipients, Trainer authority, retention period or erasure behavior. The grouped product-decision table in the B08/M7 integration handoff must be approved before dependent contract or code is written.

## M7 Attention and Review decisions — APPROVED 2026-10-18

The following are product decisions approved by Person B, **not** claims that an API, migration, scheduler, Mobile screen, or test already runs. The first review task is anchored to the start of an active human Coaching Period. Tasks recur every 14 **calendar days** in the Student-confirmed IANA timezone and each is due 7 calendar days after its own cycle start. A Trainer authorized for the current period completes a task with a summary, evidence references, and explicit missing/unknown-data information. The Student can read the result and acknowledge viewing it; acknowledgement does not gate completion. Review does not mutate Goal, Workout Plan, or Nutrition; strategic changes still use their existing commands/proposals.

`MISSED_PLANNED_V1` is a deterministic MEDIUM signal only for a still-`SCHEDULED` occurrence with known `plannedEndAt`, known absence of execution, and an end more than 24 hours in the past. Evidence identifies occurrence/version and its time interval. Null end, unknown execution state, and `RESCHEDULED`/`CANCELLED`/`SKIPPED`/other terminal occurrences produce no signal. Dedupe the same source/rule; source correction closes or supersedes it with audit, never silent deletion.

`REVIEW_OVERDUE_V1` comes only from a real review task with a due date: LOW after one calendar day, MEDIUM after seven calendar days on the *same signal*, retaining severity history. Completion or period-lifecycle cancellation resolves it with audit. An absent task or due date produces no signal. Calendar-day boundaries use the task's confirmed IANA timezone; no fixed 09:00 local-time rule was approved.

The Student reads their own signals and history. A Trainer reads or acts only on signals for an `ACTIVE` `HUMAN_COACH` period currently assigned to them, with current VIEW authority for the evidence source. Denial conceals count and detail. Trainer actions are ACK, RESOLVE and DISMISS; the latter two require a reason. Mutations use version and actor-scoped command keys, transactionally rechecking authority. Pause/end/revoke conceals immediately. Resume opens a new period and re-evaluates for that period; it never restores access to the old period's signals. Admin has no implicit coaching authority.

The observed Pixel_7 defect in which the Chat title can overlap the Android status bar is in this checkpoint's UI scope. The policies above do **not** approve the proposed 100-day return rule, attachments/linked files, push/reminders/preferences, retention/export/account erasure, or backup-deletion timing. These remain open decisions or release gates; M7 is not Done on completion of this slice.
