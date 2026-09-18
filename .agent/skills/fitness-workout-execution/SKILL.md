---
name: fitness-workout-execution
description: Design or implement exercise data, Workout Plans and versions, planned versus actual sessions, workout logs, RPE, training volume, missed workouts, adherence, supervision, and return-to-training behavior.
---

# Fitness Workout Execution

Read `digital-fitness-core` first.

## Exercise and plan structure

Use `WorkoutPlan -> WorkoutPlanVersion -> Week -> WorkoutSession -> Exercise`. Use canonical Exercise and Exercise Variation references rather than duplicated names.

Decision authority follows Coaching Mode. Trainer owns plan decisions in `HUMAN_COACH`; Student owns them in `SELF_DIRECTED`. Self-directed Students may create manually, use a template, or accept an AI-generated proposal. Backend validation and the authorized human decision are mandatory before activation.

## Plan continuity and versioning

- A mode change never automatically deletes or disables the current plan.
- After coaching ends, Student may continue using the plan the Trainer delivered; the former Trainer loses edit authority.
- A new Trainer may inspect permitted history but cannot edit historical versions. Create a new version or plan with `based_on`, `created_by`, Coaching Period, effective time, and recommendation reference.
- Create a version for strategic changes such as weekly split, lasting exercise selection, sets/reps, frequency, progression strategy, or phase.
- Record notes, one-session substitutions, planned-date changes, and appointment reschedules as session adjustments/history rather than noisy plan versions.

## Planned and actual execution

Keep Planned Workout and Actual Workout separate. Keep `planned_at` and `performed_at` separate while linking an actual execution back to its planned session. A Monday workout performed Tuesday may be completed with a schedule deviation.

Model logs as `WorkoutSessionLog -> ExerciseLog -> SetLog`; Set Log stores weight, reps, RPE, duration or relevant values. Preserve partial and failed sets rather than overwriting the plan.

Calculate training volume deterministically from logged sets. Treat RPE as subjective context, not a substitute for performance data.

## Missed sessions and continuity

Support skip, reschedule, or perform on another day. Do not shift an entire program mechanically; consider recovery, trained muscle groups, intensity/RPE, availability, remaining sessions, and Goal context.

Completion and schedule adherence are independent metrics. Track active and inactivity periods, days since last workout, frequency, streaks, and gaps. After long inactivity, block ordinary progressive-overload assumptions and require return-to-training assessment/recommendation.

Use supervision values `SELF_PERFORMABLE`, `COACH_OPTIONAL`, and `COACH_REQUIRED`. Never silently convert `COACH_REQUIRED` to independent execution.
