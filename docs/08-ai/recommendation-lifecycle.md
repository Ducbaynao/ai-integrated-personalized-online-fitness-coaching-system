# AI recommendation lifecycle

## Creation

An AI Run records request type, model/provider, model version, prompt version, selected input references, retrieved Knowledge Versions, structured output, validation result, latency, token/usage data, status, and timestamps.

The database `ai_run_status` at the planning baseline uses `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `REJECTED_BY_VALIDATOR`, and `CANCELLED`. Labels such as `TIMEOUT`, `MODEL_ERROR`, `RULE_BLOCKED`, and `VALIDATION_FAILED` describe operational reasons; they are not additional DB enum values. The approved design mapping is in [matrix section 1.6](phase-2-feature-data-authority-matrix.md#16-ánh-xạ-trạng-thái-db-api-và-ui).

Only a validated PROPOSAL that can change business data becomes a pending recommendation. INFORMATION is a scoped explanation; ESTIMATE remains a Nutrition draft. Evaluation output cannot become an active recommendation. The result-type/API vocabulary is approved design; existing executable contracts must be extended before implementation.

A valid output that could change business data becomes an AI Recommendation with:

- target domain/resource and source version;
- proposed structured change;
- reasoning/evidence summary;
- missing context and uncertainty;
- validation metadata;
- responsible decision role/person;
- `PENDING` status and expiry when applicable.

## Decision and application

- A self-directed Student decides on Workout Plan Recommendations.
- In human coaching, the eligible Trainer decides on Workout Plan Recommendations.
- The Student decides on personal Fitness Goal and strategic Nutrition changes even when a Trainer or AI originated the proposal.
- Food-recognition estimates require Student confirmation/correction before confirmed nutrition calculation.

Acceptance does not directly trust the old output. Spring Boot reloads the target, verifies authority, checks the source version and relevant conflicts, applies the domain command transactionally, and records the resulting version/change/audit reference.

## Feedback loop

After later Actual Workouts, the system may compare plan versus performance using sets, repetitions, load, RPE, completion, continuity, and trend. Repeated low RPE with successful completion may support a load increase suggestion; repeated failure/high RPE may support maintaining or reducing load. These are recommendations, not automatic plan mutation.

Acceptance/rejection and later outcomes support evaluation. They must not be interpreted as universal labels without context, because a person may reject a good suggestion for preference or scheduling reasons.

## Evaluation and replay

AI Admin may replay authorized/sanitized AI Runs or evaluation datasets to compare prompt/model versions and regression behavior. Replay output is evaluation data only. It cannot create an active Recommendation or apply changes to Student records.

## Phase 2 V1 transaction design (APPROVED)

The database recommendation enum includes `PENDING`, `ACCEPTED`, `REJECTED`, `APPLIED`, `EXPIRED`, and `CANCELLED`; it does not include `APPLY_FAILED`. The approved V1 model records `PENDING → ACCEPTED → APPLIED` in one local Spring transaction with the domain change, application references and mandatory audit. On failure, all writes roll back; a failed operational attempt is recorded separately. `ACCEPTED` is not a committed job waiting for later application in this V1 design.

A retry of a committed operation returns its historical receipt only when actor/resource access and the normalized operation/revision/payload identity match. A different payload conflicts. Source guards and expiry protect new application attempts; they must not trigger a second application of a committed operation. Payload modifications require fresh validation and a reviewed preview. See [matrix sections 1.7–1.8](phase-2-feature-data-authority-matrix.md#17-accept-and-apply-rollback-và-retry-trong-v1) for the complete design, approved by the project owner on 2026-10-04 and still requiring implementation tests.

Food Confirmation is a separate Nutrition command: the selected item group commits atomically, unselected items stay draft, changed catalog/conversion requires a new reviewed preview, and failure rolls back the group. It does not automatically mark a day COMPLETE. See [matrix section 1.9](phase-2-feature-data-authority-matrix.md#19-food-confirmation-nguyên-tử-cho-f06f07).
