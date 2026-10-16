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

## M6 integration and closeout inventory (pre-catalog approval, 2026-10-16)

The basic logging and Nutrition Strategy slices are merged on `main` (V39 and V40 respectively). This is an implementation inventory, not a new product decision or a declaration that all M6 exit criteria have passed. The merged backend and Mobile paths cover private `USER_REPORTED` foods, quantified and description-only items, day completeness and corrections, Student-owned Goals and target versions, Trainer proposals with Student decisions, day-type resolution, overrides, and frozen target provenance. Target and actual remain separate facts; an absent log is not zero intake. Automated backend HTTP/PostgreSQL tests can verify these paths, but mock Mobile QA does not establish a live Mobile-to-backend run.

| Remaining closeout work | Status / authority | Completion evidence needed |
| --- | --- | --- |
| Basic and Strategy cross-flow verification | Approved and implemented; integration verification remains a separate gate | Exercise Student and Trainer HTTP paths against isolated PostgreSQL, including authority loss, replay/conflict, timezone, unknown versus zero, and target-snapshot independence. Run contract, migration and relevant client gates on the final base. |
| Mobile-to-backend device run | No live device run established by mock QA | Use an emulator or device pointed at a disposable backend/database; verify real authentication, basic log, proposal decision, target preview and frozen day. Do not use the local `digital_fitness` database. |
| Governed food identity and quantity resolution for FR-NU-009 | No approved nutrition database source, catalog stewardship or conversion evidence in the basic `USER_REPORTED` flow | Select and review a source/catalog, ownership, provenance and serving/conversion rules before claiming text/image resolution or verified nutrient data. The existing private-food flow must not be relabelled as catalog-backed. |
| AI food estimate provenance for FR-NU-010 | Separate Phase 2 F06/F07 work, not part of the deterministic M6 core | Review data/model coverage and specialist rules, then implement the approved confirm/correct path and its evaluation gates; never treat an estimate as an authoritative log. |
| Historical target correction | Explicitly deferred by Strategy decision 9 | Define who may correct a frozen target, the permissible window, audit/provenance and how corrections are displayed before adding a mutation. No existing Goal Version or schedule change may rewrite a snapshot. |

M6 exit criteria must be assessed against the merged implementation plus the verification evidence, not inferred from approval or a passing mock screen. The next review should verify the cross-flow tests and the remaining data-source and device-integration gaps together, rather than marking the entire Nutrition feature done from the two merged slices alone.

## Governed catalog and frozen-target correction — APPROVED product decisions (Person B, 2026-10-16)

These decisions authorize a new B07 slice; they do not claim that OpenAPI, Flyway, backend, Mobile, device integration, or M6 closeout is implemented. The existing V39 private `USER_REPORTED` foods remain separate from governed catalog entries, and V40 frozen target/actual facts remain historical evidence.

1. The first governed catalog is a small Admin-selected subset of USDA FoodData Central **Foundation Foods**. Pin its release and retain FDC ID, data type, source, and source version/date. Do not import the entire dataset or claim Vietnamese-food coverage. If the USDA API is used, its key stays server-side and tests must not depend on a live API. Student private foods never promote themselves into the catalog.
2. Use existing Admin platform authority to review, publish, revise, and retire catalog foods, with an audit trail. Do not create a Nutritionist role or grant catalog management to Trainers. An Admin catalog decision is not a Student Nutrition Goal decision.
3. Serving-to-gram conversion requires an approved per-food/portion mass factor with source and revision. Millilitre conversion requires separate evidence; never assume 1 ml equals 1 g or infer a factor from a serving label. Preserve source precision and use decimal arithmetic without intermediate rounding. When evidence is absent, offer only confirmed units and display missing data as unknown.
4. A Student may correct one nutrient target for one frozen day with a mandatory reason, optimistic version, actor-scoped command key, and append-only before/after/provenance audit. Keep the original target fact and Food Log actual unchanged; do not correct other dates implicitly. The Student retains this ability after a Coaching Period ends. Trainer correction mutation/proposals are excluded.
5. There is no fixed age window for a frozen-target correction. Mobile presents the original and current corrected targets and reason/actor history; progress comparisons state that they use the corrected target. Trainer read requires current `NUTRITION_GOAL VIEW`; former Trainers have no live access.

For implementation, compare USDA's official [API guide](https://fdc.nal.usda.gov/api-guide/) and [release downloads](https://fdc.nal.usda.gov/download-datasets/) when pinning source evidence. The decision does not approve Open Food Facts, AI-estimated values, inferred conversions, or a live USDA dependency during tests. The M6 closeout inventory above predates this approval and is not runtime evidence for the new slice.
