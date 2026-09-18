# AI recommendation lifecycle

## Creation

An AI Run records request type, model/provider, model version, prompt version, selected input references, retrieved Knowledge Versions, structured output, validation result, latency, token/usage data, status, and timestamps.

Operational statuses may include `SUCCESS`, `VALIDATION_FAILED`, `MODEL_ERROR`, `TIMEOUT`, `RULE_BLOCKED`, and `PROCESSING_ERROR`.

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

