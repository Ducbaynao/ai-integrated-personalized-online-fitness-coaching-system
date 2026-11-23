# F06 food text estimate contract checkpoint

**Status:** Draft implementation contract for issue #164. OpenAPI and the AI output
schema in this branch describe proposed interfaces; they do not establish runtime,
database migration, live nutrition calculations, or release readiness.

## Existing source and gap

- `V7__nutrition.sql` has `food_analysis_requests` and
  `food_analysis_candidates`, but candidates point at the legacy `foods` table.
  No Spring analysis endpoint currently uses them.
- `V39__student_basic_nutrition_logging.sql` has actor-scoped command receipts;
  `NutritionBasicService` calculates catalog nutrients and writes one confirmed
  item per command. Repeating that public command cannot provide atomic
  selected-group confirmation.
- The current daily total includes non-deleted `food_log_items` regardless of
  `confirmed_by_user`. F06 estimates must remain outside that table until a
  successful Student confirmation transaction.
- `V41` catalog revisions and portions provide calculation evidence. A model
  result never supplies authoritative catalog IDs, conversion or nutrients.

## Contract decisions for review

1. Spring owns the public `/nutrition/food-analyses` contract. Only the
   authenticated owning Student can generate, read, correct, preview and confirm;
   Trainer nutrition-log sharing does not grant draft or image access.
2. Generate persists a request identity before provider I/O. The same actor,
   `commandKey` and normalized body resolve to the same draft; the owner-scoped
   `by-command-key` GET recovers a lost response without a draft ID. Different
   payload with the same key conflicts. Provider calls stay outside confirmation
   transactions.
3. F06 V1 creates a new meal at the first successful confirmation. Later
   selected groups from the same draft reuse that meal after ownership/state
   checks. The request's day, timezone, consumedAt and mealType are pinned in
   the draft and repeated at preview/confirm to detect changed client intent.
   Attaching an existing meal is not in this V1 contract.
4. A persisted preview is immutable and selection-specific. It records draft,
   item and catalog/portion revisions plus server-calculated nullable nutrient
   snapshots. Correction invalidates its use. Confirm contains the exact
   selected items and preview identity; Spring compares a canonical sorted
   fingerprint with any committed receipt.
5. An exact committed retry checks current read authority, then returns its
   stored receipt before expiry or current catalog guards. A new intent locks
   draft/items/catalog in stable order, validates every selected item, and writes
   all Food Logs, item mappings, provenance, receipt and mandatory audit in one
   transaction. Any failure rolls back the whole group. Unselected items remain
   draft and the day is not marked COMPLETE.
6. Unknown food, quantity, unit or conversion stays null. The AI output has
   names and optional quantity only; Spring matches the published catalog and
   computes nutrients. A catalog change affecting preview returns 409 and
   requires a new preview and a new Student confirmation intent. Confirmed
   historical snapshots are never recalculated on replay.

The matrix suggests 24 hours for draft expiry but does not prove a production
rollout setting. Expiry remains a configuration/rollout gate rather than a
hard-coded contract value.

## Implementation sequence and evidence boundary

1. Review the OpenAPI and AI schema shape, including error codes and the
   generation recovery path, from the Mobile consumer perspective.
2. Add a **new** forward Flyway migration for draft/item/preview identity,
   revisions, catalog references, confirmation mapping and constraints. Do not
   edit V7, V39 or V41.
3. Implement Spring ownership, matching, preview/calculation, atomic Confirm,
   receipt replay, erasure and audit handling. Add tests for T14/T15/T24/T26/T27
   and competing catalog/draft writers.
4. Implement and validate the F06 FastAPI internal route/provider; validate
   structured output before Spring accepts it. Run AI schema/fixture tests.
5. Implement the Mobile typed consumer and Correct/Confirm UI on its own branch
   after the executable DTO stabilizes. Client calls Spring only.

Passing contract lint or fixture validation proves neither backend behavior
nor catalog quality, HTTP/DB live operation, device QA or Phase 2 release gates.
