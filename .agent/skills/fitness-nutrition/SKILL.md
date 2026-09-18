---
name: fitness-nutrition
description: Design or implement Nutrition Goals, proposals, target versions, daily targets, meals and food logs, deterministic nutrition calculation, food text/image assistance, confirmation, completeness, adherence, and history.
---

# Fitness Nutrition

Read `digital-fitness-core` first.

## Goal and target lifecycle

Keep Nutrition Goal separate from Nutrition Target and from linked Fitness Goal. Nutrition Goals may support muscle gain, deficit, surplus, maintenance, performance, or general health. A linked Fitness Goal supplies alignment context but never silently rewrites nutrition.

Student owns and confirms Nutrition Goal and strategic targets. In `HUMAN_COACH`, Trainer may create a Nutrition Proposal only when authorized to view/manage nutrition. AI may recommend but never activates or overwrites calories/macros. Admin is not nutrition authority.

Use lifecycle states such as `DRAFT`, `ACTIVE`, `PAUSED`, `COMPLETED`, `ENDED`, `REPLACED`, and optionally `ABANDONED`. Daily adherence alone does not complete a Goal.

Create immutable Nutrition Target Versions for strategic changes with effective periods and `based_on`. Use Daily Target Override for temporary changes. Resolve a day's target from active version, `TRAINING_DAY`/`REST_DAY`/`DEFAULT` rule, workout schedule, and valid override.

## Target versus actual

Never combine planned targets with actual Food Logs. Model Meal and Food Log Items separately. Missing log means `NOT_LOGGED`, not 0 kcal. Support `PARTIAL` and `COMPLETE`, plus counts of estimated and confirmed items.

Calculate calories and macros deterministically from a governed Nutrition Database once food identity, serving, and quantity are known. LLM or vision output is not the calculation authority.

## AI-assisted logging

For text or image input, use recognition/ingredient/portion estimation, database matching, user review, deterministic recalculation, and then persistence. Let the user correct identity and quantity. Store provenance such as `ESTIMATED`, `USER_CONFIRMED`, and `USER_CORRECTED` with confidence.

## Progress and continuity

Compute calorie, protein, and macro adherence, logging adherence, rolling averages, and trends only with completeness/quality context. Preserve nutrition history across Goals, Coaching Periods, and Trainer changes. A former Trainer loses access to new data; accepted targets remain usable by the Student.

After long inactivity, create a review signal rather than assuming the previous target remains suitable. A changed Fitness Goal should trigger an alignment check/proposal, not automatic target mutation.
