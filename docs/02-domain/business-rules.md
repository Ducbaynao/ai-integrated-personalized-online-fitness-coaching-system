# Business rules

## Identity and capability

1. Every person uses one User Account.
2. Student Profile and Trainer Profile are optional, independent profiles linked to that account.
3. Creating a Trainer Profile starts or supports an application; it does not grant coaching authority.
4. Coaching eligibility requires an approved/verified Trainer, active Trainer activity state, an active Coaching Relationship, and applicable policy/capacity checks.
5. Authorization is evaluated at the resource, relationship, scope, and purpose level, not only from a role name.

## Coaching authority

1. `SELF_DIRECTED` and `HUMAN_COACH` are the only Coaching Modes.
2. Coaching Mode belongs to a Coaching Period.
3. In `SELF_DIRECTED`, the Student controls the Workout Plan and may accept an AI-generated proposal.
4. In `HUMAN_COACH`, the eligible Trainer controls Workout Plan programming within the relationship.
5. The Student owns Fitness Goal and Nutrition Goal decisions in both modes.
6. Trainer and AI changes to Student-owned strategic goals use proposals and Student confirmation.
7. AI has no authority in any mode.
8. Administrator protects the platform and cannot substitute for Student or Trainer coaching authority.

## Fitness Goal and history

1. A Goal may have a primary type, secondary types, multiple typed targets, and a timeline.
2. A change is a Goal Version when the Student continues the same journey with revised target/timeline.
3. A change is a new Goal when the previous journey ends and a new journey begins. The previous Goal is retained and linked by Goal Transition.
4. A new Goal may restart Goal Day at 1; it never resets lifetime history.
5. Calendar duration and active training time are distinct. Pauses/inactivity remain visible in progress analysis.
6. Changing Trainer or Coaching Mode does not automatically close the active Goal.
7. A Trainer-delivered Workout Plan may remain available after coaching ends; authority to modify it does not remain with the former Trainer.

## Workout programming and execution

1. Planned and performed facts are stored separately.
2. Planned date and performed date are independent; late performance can still complete the planned session.
3. Completion and schedule adherence are separate metrics.
4. Actual Workout is recorded down to set level, including load, repetitions, RPE, and completion.
5. Training volume may be derived from set facts; derived values must retain a clear formula/version when materialized.
6. Significant changes such as program structure, strategic volume, frequency, or phase create a new Workout Plan Version.
7. Minor changes such as a single-session load or exercise adjustment may use session/change history without creating a full plan version.
8. After long inactivity, prior loads cannot be treated as current capability without return-to-training assessment.

## Schedule

1. Coaching Appointment represents collaboration time. Planned Workout represents intended exercise content. Neither owns the other's lifecycle.
2. An Appointment may optionally reference a Planned Workout.
3. Appointment creation and accepted rescheduling must pass conflict validation.
4. A pending Reschedule Request does not reserve the proposed slot indefinitely; validation runs again at acceptance.
5. Recurring changes carry an explicit scope and default to one occurrence.
6. A cancelled Appointment does not automatically cancel a `SELF_PERFORMABLE` or `COACH_OPTIONAL` Workout.
7. A `COACH_REQUIRED` session must be rescheduled or replaced safely if the Trainer cannot attend.
8. Time is stored consistently and rendered in each user's timezone.

## Measurements and progress

1. Measurements are append-oriented time-series observations; corrections do not erase provenance.
2. Every Measurement identifies metric, value, unit, measured time, source, method, and validation/quality state as applicable.
3. Manual, Trainer, device, and health-platform data enter one normalized measurement model.
4. Duplicate or conflicting observations are resolved by policy without silently discarding source evidence.
5. A missing optional metric remains `NULL`/unavailable and must not be converted to zero or an invented default.
6. Progress Engine, not the LLM, calculates normalized trends, adherence, continuity, and deterministic signals.
7. A single raw observation is not automatically a trend.
8. Attention Signals contain evidence and status so a Trainer can understand and resolve the condition.

## Nutrition

1. Nutrition Goal and Fitness Goal are independently versioned; linkage expresses alignment, not ownership.
2. Strategic target changes create a Nutrition Target Version with effective dates.
3. Temporary day-specific changes create a Daily Target Override.
4. A Daily Nutrition Target is resolved from target version, day-type rule, schedule, and valid override.
5. Target and actual Food Log facts never overwrite each other.
6. Absence of a log means unknown intake. Partial logs must reduce completeness/confidence.
7. Nutrition Database plus deterministic calculation is the authority for calories and macros after food identity/quantity are established.
8. Vision/LLM output is an estimate until the Student confirms or corrects it.
9. Nutrition history survives Coaching Period and Trainer transitions; access follows the current permission scope.

## AI and governance

1. Context Builder selects the minimum authorized context required by the request.
2. Context states availability, staleness, quality, confidence, source, and training continuity where relevant.
3. Deterministic rules may block, constrain, or request more data before model execution or application.
4. RAG retrieves only governed Knowledge Versions eligible for use.
5. Structured output is schema validated, then domain validated by Spring Boot.
6. A recommendation remains pending until the responsible human accepts or rejects it.
7. AI Run and important decisions retain enough metadata for debugging, evaluation, and audit.
8. Admin replay/evaluation is isolated from business application.

