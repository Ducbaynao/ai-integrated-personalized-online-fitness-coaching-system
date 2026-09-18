# Domain Invariants for UI Work

Read this reference when a UI change touches permissions, actors, approval, state transitions, history, AI or data quality.

## Identity and capability

- User Account is shared identity.
- Student Profile and Trainer Profile have independent lifecycles.
- Trainer Profile alone does not grant coaching authority; verification and activity policy must pass.

## Coaching

- `SELF_DIRECTED`: Student decides the Workout Plan; AI may propose.
- `HUMAN_COACH`: Trainer is responsible for the Workout Plan; AI assists Trainer/Student without replacing authority.
- Mode belongs to Coaching Period, not permanent Student Profile.
- Ending/switching coaching does not delete goals, plans, workout, nutrition or measurement history.

## Goals

- Student owns Fitness Goal and strategic Nutrition Goal decisions.
- Trainer and AI create proposals; pending proposals do not modify active data.
- Significant goal change creates Goal Version or a new Goal with Goal Transition.
- New Goal Day 1 does not reset lifetime fitness history.

## Workout and schedule

- Coaching Appointment is not Planned Workout.
- Planned Date is not Performed Date.
- Workout completion and schedule adherence are different metrics.
- A canceled appointment may leave a workout executable if supervision rules allow.

## Measurement and nutrition

- Measurement is time-series with source, method, provenance and quality.
- Optional missing metrics remain unknown; UI must not invent estimates.
- Nutrition Target is separate from Actual Intake.
- Missing/partial food log is not zero intake.
- AI food estimate requires Student confirmation/correction and provenance.

## Alerts and AI

- Rule/System Alert covers deterministic facts such as missed workout, conflict or overdue check-in.
- AI Recommendation covers context-dependent analysis and must show evidence, data gaps and decision state.
- Progress Engine/rules produce normalized metrics before AI interpretation.

## Administration

- Permissions, scope and purpose govern admin access.
- Admin cannot accept coaching proposals or modify goals/plans as a coach.
- Sensitive access uses a privileged workflow with reason, scope, expiry and audit.
- Audit records are immutable from Admin UI.

