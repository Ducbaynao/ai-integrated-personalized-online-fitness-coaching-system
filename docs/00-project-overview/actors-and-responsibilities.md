# Actors and responsibilities

The platform uses one common User Account. Student, Trainer, and Administrator are roles or capabilities attached to that identity; they are not permanently separate account types.

## Student

The Student is the owner of personal fitness and nutrition objectives. A Student can:

- create and confirm Fitness Goals and Nutrition Goals;
- accept or reject Goal Proposals and Nutrition Proposals;
- use `SELF_DIRECTED` training or enter a `HUMAN_COACH` Coaching Period;
- view assigned Workout Plans and continue an accepted plan after coaching ends when policy allows;
- record Actual Workouts, sets, repetitions, weight, duration, RPE, notes, and completion;
- record Measurements, Progress Photos, Meals, and Food Log Items;
- request schedule changes and respond to Trainer-initiated requests;
- control data-sharing scope for a Coaching Relationship;
- view progress, data quality, recommendations, notifications, and history;
- correct AI-assisted food recognition before nutrition values become confirmed.

The Student remains the final authority for personal Goal changes and strategic Nutrition Target changes in both Coaching Modes.

## Trainer

A Trainer manages coaching only when the Trainer Profile is eligible, verification and activity state permit coaching, and the relevant Coaching Relationship is active. A Trainer can:

- accept or manage Coaching Relationships within capacity and policy limits;
- create, assign, review, and adjust Workout Plans;
- make minor plan/session adjustments without creating meaningless versions;
- create a significant Workout Plan Version when program strategy changes;
- propose Fitness Goal or strategic Nutrition changes for Student approval;
- plan Coaching Appointments and initiate reschedule requests;
- review permitted Measurements, Workouts, Nutrition, Progress, and history;
- use the Student Coaching Workspace and Coaching Review Cycle;
- respond to deterministic Attention Signals and AI Recommendations;
- communicate with Students and request missing check-in information.

A Trainer cannot access every Student merely because the account has the `TRAINER` role. Access requires object-level relationship, scope, time, and purpose checks.

## Administrator

An Administrator is a platform authority. Depending on granted permissions, an Administrator can:

- manage account state, roles, permissions, and security actions;
- review Trainer Applications, verification, certifications, and activity state;
- govern exercises, media, knowledge, and published content;
- process reports, moderation cases, support cases, and audited data corrections;
- inspect AI operations, validation failures, model/prompt versions, and evaluations;
- monitor jobs, notifications, integrations, data-quality incidents, and system health;
- manage feature flags and validated system configuration;
- inspect immutable Audit Logs within permission scope.

Administrator access follows least privilege. Sensitive personal data requires an explicit permission and, where applicable, a time-bound Privileged Data Access record with a reason and audit trail.

Administrators do not approve Student Goals, edit Workout Plans as a Trainer, or apply AI Recommendations on behalf of the responsible Student or Trainer.

## AI Assistance

AI is a system capability, not an actor with business authority. It may:

- create structured recommendations and proposals;
- explain exercise technique or recommendation reasoning;
- propose workout splits, exercise replacements, load, volume, or schedule adjustments;
- summarize progress using validated context;
- help recognize food and estimate portions;
- support a Trainer or a self-directed Student.

Spring Boot validates AI output. The authorized human decides whether an applicable recommendation is accepted.

