---
name: fitness-goal-coaching
description: Design or implement Fitness Goal, Goal Target, proposal, version, transition, Coaching Relationship, Coaching Period, mode changes, trainer changes, pause/resume, and history-preserving progress semantics.
---

# Fitness Goal and Coaching

Read `digital-fitness-core` first.

## Fitness Goal model

Treat a Fitness Goal as a domain aggregate, not a string on Student Profile. Support primary and secondary goal types such as muscle gain, fat loss, weight change, strength, fitness improvement, and maintenance. Store measurable Goal Targets with metric, start value, target value, and unit.

Use lifecycle states `DRAFT`, `ACTIVE`, `PAUSED`, `COMPLETED`, `ENDED`, `ABANDONED`, and `REPLACED`. Inactivity alone never auto-abandons a goal.

## Authority and proposals

- Student owns and finally confirms the Goal.
- In `SELF_DIRECTED`, Student creates or edits it.
- In `HUMAN_COACH`, Trainer proposes target/timeline changes through `GoalProposal`; never directly edits the active Goal.
- AI may create or support a proposal but never activates a Goal.
- Admin does not participate in Goal decisions.

Validate accepted proposals in Spring Boot before applying them. Track `PENDING`, `ACCEPTED`, and `REJECTED` with reason and actor.

## Version versus new Goal

Create a new Goal Version when the same journey receives a meaningful target or timeline adjustment. Store effective-from date and retain all earlier versions. Create a new Goal when the parties intentionally end the old journey and restart from Day 1. Mark the prior Goal `REPLACED` and store a Goal Transition with reason, timestamp, actor/proposal, and previous/new Goal references.

Goal Day is not lifetime fitness history. A new Goal can be Day 1 while the Student retains years of history.

## Coaching lifecycles

- `CoachingRelationship` links a Trainer and Student with `PENDING`, `ACTIVE`, `PAUSED`, `ENDED`, or `REJECTED`.
- `CoachingPeriod` records who had decision authority over a specific interval. `HUMAN_COACH` periods reference the applicable Trainer/relationship; `SELF_DIRECTED` periods do not require one.
- Mode or Trainer changes create/close periods; they do not automatically replace Goals or plans.
- A Goal may span several Coaching Periods and Trainers.

## Time and interruption

Separate calendar elapsed time, active training time, and inactivity time. On long inactivity, offer resume, version adjustment, end, or new Goal. Returning requires current-state assessment; never mechanically resume old progressive overload.

Progress views must distinguish current Goal/version progress from lifetime progress. Preserve the exact decisions, effective dates, permissions, and historical references in API, schema, UI, and tests.
