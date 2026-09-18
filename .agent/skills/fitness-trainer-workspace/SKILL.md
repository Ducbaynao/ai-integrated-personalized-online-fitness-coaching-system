---
name: fitness-trainer-workspace
description: Design or implement the Trainer experience, Student Coaching Workspace, attention queue, review cycles, coaching requests, progress review, plan delivery, and scalable management-by-exception workflows.
---

# Fitness Trainer Workspace

Read `digital-fitness-core` first, then the relevant domain skill for any data being edited.

## Workspace

Provide one Student Coaching Workspace with Overview, Program, Workouts, Progress, Nutrition, Schedule, Messages, AI Insights, and History. Every tab must enforce the current relationship and Data Sharing Permission; do not preload private domains merely because the Trainer opened a Student.

Show current Goal/version, Coaching Period, plan, recent execution, measurement trends/quality, nutrition completeness, upcoming appointments, permissions, and open proposals without collapsing their lifecycles.

## Management by exception

The main Trainer dashboard should surface work requiring action rather than forcing inspection of every Student. Use deterministic Progress/Rule Engine signals for missed workouts, low adherence, high RPE, long inactivity, overdue check-in, off-track Goal, weight trend, and plan-review due.

Allow filters such as Needs Attention, On Track, Inactive, Review Due, and severity. Each signal needs status, evidence, timestamps, and a clear next action. Keep System/Rule Alert visibly distinct from AI Recommendation.

## Review cycle

Support per-Student weekly, biweekly, or monthly review cadence. Generate review tasks/reminders; factual check-ins do not require proposal approval. Trainer may provide feedback, request measurements, create a Goal/Nutrition proposal, adjust the plan within authority, or request more data.

## Authority and continuity

- Trainer manages Workout Plan only in an active authorized `HUMAN_COACH` period.
- Trainer proposes personal Goal and strategic nutrition changes for Student approval.
- Trainer cannot see denied/out-of-scope data or conversations with a previous Trainer.
- When coaching ends, revoke edit/access to new data as policy requires; Student retains delivered plans and history.
- AI recommendations are review inputs, not automatic actions.

Design screens and APIs around a Trainer handling many Students: compact summaries, stable filters, traceable evidence, batched low-risk actions only where authority is unambiguous, and no bulk acceptance of high-impact AI/Goal decisions.
