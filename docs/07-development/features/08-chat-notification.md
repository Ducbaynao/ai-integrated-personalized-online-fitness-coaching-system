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

## Implementation status in this branch

The B08 first slice is being implemented in this branch through OpenAPI, V43, Spring Chat/Notification, and Expo Student/Trainer routes. This section is not a merge or release claim. PostgreSQL is authoritative; the Mobile WebSocket only invalidates cached REST data, with REST polling/reconnect as fallback. A message and its generic in-app notice share one database transaction. No retention/erasure operational policy, push/email, attachments, Attention Signal, or Review Cycle is implemented here.
