# B07 Basic Nutrition — approved first slice

**Decision record:** APPROVED by Person B on 2026-10-13. This record defines the first Student food-logging slice; it is not evidence that B07 or all of M6 is implemented or Done. M6 still includes Nutrition Goal, Proposal, Target Version, day-type rules and Daily Override in the Phase 1 plan. Those strategic workflows are later checkpoints, and strategic changes still require Student confirmation and version history.

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
