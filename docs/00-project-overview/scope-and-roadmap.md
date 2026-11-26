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

The [Phase 2 release scope](phase-2-release-scope.md) expands this roadmap into F01–F11 and explicit release limits. The [data/authority matrix](../08-ai/phase-2-feature-data-authority-matrix.md) and [implementation plan](../07-development/phase-2-implementation-plan.md) define the approved implementation baseline (version 1.0, project-owner approval on 2026-10-04). D01–D07 are recorded in the release scope; technical prerequisites and release evidence remain to be completed. In particular, deferring AI-generated Fitness Goal and strategic Nutrition Target proposals is an approved release limit, not removal of the target-product proposal/Student-approval rules.

### Phase 2 extension approved 2026-11-25 — F12 Coaching QR

Dev A owns `P2-A-QR`: real per-account/per-capability QR → camera or image decode → minimal profile → coaching request/invitation → counterparty acceptance. This replaces name/email discovery UI, reuses B03 authority/history, and adds no friendship or marketplace. See [the approved F12 plan](../07-development/features/phase-2-dev-a-coaching-qr.md) and D08 in the release scope. Implementation is PLANNED; QR01–QR10 are required before P2-M6. Advanced commercial discovery/profile capabilities remain Phase 3.

## Phase 3 Product and commercial expansion

- advanced Trainer discovery, commercial profiles and marketplace browsing beyond Phase 2 F12 QR introduction;
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

