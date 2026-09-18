# Scope and roadmap

The roadmap defines implementation sequence. The architecture and domain boundaries describe the target product even when a capability is delivered later.

## Phase 1 Core fitness platform

The first usable product must work without AI. It includes:

- authentication, common account, User Profile, Student Profile, and Trainer Profile;
- Trainer Application and a minimal verification/activation workflow;
- Fitness Goal, Goal Target, Goal Proposal, Goal Version, and Goal Transition;
- Coaching Relationship, Coaching Period history, and scoped data sharing;
- Exercise Library and variations;
- self-directed plan creation or templates;
- Workout Plan, significant versioning, Planned Workout, Actual Workout, and logging;
- appointments, recurrence, conflict detection, changes, and supervision requirements;
- Measurements, Progress Photos, training continuity, and basic Progress Engine signals;
- chat and notifications;
- Nutrition Goal/Target foundations and basic food logging;
- audit and minimum Admin governance.

Phase 1 implementations may simplify workflows, but the schema must not reduce verification to an irreversible boolean, overwrite historical targets, merge Appointment with Workout, or store Coaching Mode permanently on Student Profile.

## Phase 2 AI intelligence

- AI Assistance and structured recommendations;
- Context Builder with data scope, availability, quality, and recency;
- deterministic Rule Engine and output validation;
- RAG with governed, versioned knowledge;
- AI Run, prompt/model versioning, audit, evaluation, and feedback;
- structured Workout Plan proposals;
- Nutrition AI, food recognition, portion estimation, and User confirmation.

## Phase 3 Product and commercial expansion

- Trainer discovery and profiles;
- Coaching Packages;
- subscriptions, payments, and invoices;
- Trainer reviews and ratings;
- calendar and email integrations.

Commercial capabilities must build on existing identity, authority, Coaching Relationship, audit, and history boundaries.

## Phase 4 Advanced fitness intelligence

- pose estimation, repetition counting, range-of-motion analysis, and movement feedback;
- wearable, Smart Scale, InBody, Apple Health, Health Connect, and gym-device integrations;
- multi-source measurement normalization, provenance, and quality management;
- advanced continuity and progress analytics.

Pose estimation is not part of the initial core product. Any future movement analysis must state safety limits and must not present itself as a replacement for qualified human supervision.

