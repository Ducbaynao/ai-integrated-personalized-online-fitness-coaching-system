# B07 Nutrition — approved basic and strategy slices

**Decision records:** Basic Student food logging was APPROVED by Person B on 2026-10-13; the separate Nutrition Strategy decisions below were APPROVED on 2026-10-15. Approval is policy, not proof of implementation, review, or the complete M6 Definition of Done. Strategic changes require Student confirmation and version history.

## First-flow authority and source

1. Student creates private foods with self-entered per-serving nutrients. Their source is `USER_REPORTED`; they are neither shared catalog entries nor verified data. Governed catalog acquisition and governance remain separate.
   A private food with all four nutrients unknown is `DRAFT`/awaiting reconciliation and cannot be used as a quantified serving. Once at least one per-serving nutrient is explicitly entered (including zero), it is `LOGGABLE`; missing others remain unknown. Neither state certifies the values. Editing the food does not rewrite previously logged nutrient snapshots.
2. An item with description only is unquantified. It contributes neither calories nor macros; its name never supplies inferred nutrient facts.
3. The initial quantity is a positive decimal number of servings. Nutrient amounts are per serving. No implicit gram, millilitre or other unit conversion is permitted without an explicitly stored authoritative conversion.
4. A missing nutrient is unknown, not zero. A day's nutrient total is unknown if any relevant item lacks that nutrient; a separately labelled known subtotal may be displayed. An explicit entered zero remains zero.
5. Student sees and confirms the IANA timezone for the log day; the backend stores that timezone with day intent. Device timezone is not silently substituted.
6. `COMPLETE` requires explicit Student confirmation. A day with no items is `NOT_LOGGED` unless Student explicitly confirms no intake. An unlogged day is never reported as zero calories.
7. Correction/removal increments an optimistic version, records actor/time and before/after history, and recalculates the affected day in the same transaction. Changing a private food later never rewrites already logged nutrient facts.
8. Student alone creates, edits and confirms. Trainer may read only with current `NUTRITION_LOGS VIEW` authority, subject to the granted history window. Trainer has no log mutation or implicit target authority; former Trainer has no general live access.

## Calculation and presentation convention for the first flow

Use decimal arithmetic. Persist nutrient facts to six decimal places with `HALF_UP` rounding after multiplying the confirmed number of servings by a per-serving value. Sum stored facts without intermediate floating-point conversion. Each nutrient has a nullable total and a separately labelled known subtotal; zero is a known value only when explicitly entered or when Student confirmed a no-intake day. This is a calculation convention for implementation, not evidence that the food is professionally verified.

Legacy meal rows without `log_date`/timezone remain visible when their stored instant falls within the explicitly requested local day. The response does not claim their timezone was historically frozen. Such rows count as unknown for any B07 nutrient that was not stored, including when mixed with new B07 items; they are not silently backfilled. A legacy item lacking day/timezone identity cannot be corrected by this first-flow command (`NUTRITION_LEGACY_SOURCE_UNKNOWN`), pending an audited legacy reconciliation workflow.

Mobile uses Vietnamese static copy and displays `USER_REPORTED` plainly. The Student Nutrition Today and Food Log Entry surfaces must distinguish target unavailable, actual unknown, partial/complete day, description-only item and corrected history. A Trainer view is read-only and must not infer authority from role alone. SuperFit is visual-language reference only; no Nutrition frame was verified in the inspected nodes.

## Explicit exclusions and verification

No AI food recognition, portion inference, strategic target mutation, Trainer log edit, automatic day completion or fabricated catalog data belongs to this first flow. OpenAPI, forward Flyway migration, backend, Mobile, PostgreSQL integration tests and UX documentation must agree before this slice can be called implemented. Independent review, device QA and GitHub CI remain separate gates.

## Nutrition Strategy — APPROVED product decisions (Person B, 2026-10-15)

These nine decisions authorize the separate Nutrition Goal/Target strategy slice. They do not assert that its API, migration, backend, or Mobile runtime exists. Basic food-log actuals remain separate facts and are never rewritten by target changes.

1. `NUTRITION_GOAL` is a separate coaching-sharing scope. A Trainer needs current `VIEW` to read and current `CONTRIBUTE` or higher to propose; a Trainer never edits a Student's Goal directly. Losing the grant or Coaching Period removes live proposal read. The Student retains read and `REJECT` access to their own proposal.
2. A Student has at most one `ACTIVE` Nutrition Goal. `PAUSED` issues no new targets; resume is explicit; terminal Goals cannot reopen. Already-frozen historical targets survive.
3. The Student explicitly chooses `NEW_GOAL` or `CHANGE_TARGET`. Goal type comes from the existing catalog. A Fitness Goal transition never automatically relinks a Nutrition Goal.
4. Every Target Version has at least one explicit nutrient target. An omitted nutrient has no target, not a zero target. Each `target_amount` uses a unit of the correct dimension; calories are not inferred from macros and no conversion is guessed.
5. The Student confirms an IANA timezone for the target calendar. The first version may start today only if today's target is not frozen. Subsequent changes start no earlier than tomorrow, or a later date. Effective periods are half-open `[effectiveFrom, effectiveUntil)` with no backdating or overlap.
6. A Student's direct strategic change requires confirmation and creates a new version. A Trainer proposal pins its source/base version. `ACCEPT` rechecks current authority and version; a stale accept returns 409 and leaves the proposal `PENDING`. The Student can always `REJECT`. No automatic remap or expiry occurs.
7. A confirmed planned or performed Workout supplies the `TRAINING_DAY` signal through a Workout-owned read port. Without that signal, resolve `DEFAULT`, never infer `REST_DAY`. The Student explicitly chooses `REST_DAY`. Schedule changes affect only days whose target has not been frozen.
8. The Student creates/corrects a per-nutrient, per-day override with a reason and audit. Trainer override proposals are excluded from this flow. Resolution priority is override, then day-type target, then `DEFAULT`; an override is tied to its exact Goal Version and never follows a later version implicitly.
9. `GET` is preview-only: it neither persists nor freezes a target. The Student's day confirmation or first Food Log item freezes the resolved target and provenance for that day. Later schedule or Goal Version changes do not rewrite it. Historical target correction requires a separate audited workflow.

Implementation must use Coaching-owned authority and Workout-owned read boundaries, actor-scoped command replay, optimistic versions, audit, and concealed errors. Existing V7/V13/V16 data requires forward-only migration review; this record does not reinterpret legacy effective dates or assert that old rows already satisfy these policies. Nutrition Goal/Target strategy is not all of M6, and AI has no strategic decision authority.

Implementation note (not an additional product decision): the explicit REST_DAY choice stores the IANA timezone supplied with the command. If a later version has a different target-calendar timezone, the old local-date choice is not silently reinterpreted as a choice in the new timezone; the Student may choose again. This is a data-identity safeguard for decisions 5 and 7, not an inferred REST_DAY policy.
