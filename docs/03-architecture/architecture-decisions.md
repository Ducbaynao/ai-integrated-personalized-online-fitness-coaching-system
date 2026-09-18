# Architecture decisions

This file summarizes decisions that coding changes must preserve. If a decision changes, create a dated ADR rather than silently changing implementation behavior.

## AD 001 Modular monolith before microservices

Spring Boot begins as a modular monolith to keep transactions, debugging, deployment, and development manageable. FastAPI is separate because Python AI/vision workloads and dependencies differ. Further service extraction requires measured evidence.

## AD 002 PostgreSQL is the system of record

Normalized business data, lifecycle state, version history, and audit records belong in PostgreSQL. Redis and model output are not authoritative. Object bytes belong in object storage, with metadata in PostgreSQL.

## AD 003 AI has no business authority

AI produces structured, explainable recommendations. Spring Boot validates them, and the responsible human accepts them where a business change is involved. AI does not directly update Goal, Workout Plan, Nutrition Target, or authoritative logs.

## AD 004 Coaching Mode is period based

`SELF_DIRECTED` and `HUMAN_COACH` are recorded through Coaching Period history. This preserves transitions and prevents a mutable Student Profile flag from rewriting past authority.

## AD 005 History is continuous

Goal replacement, Trainer change, Coaching Mode change, pause/resume, and Workout Plan changes retain prior records. Current Goal Day is not lifetime fitness history.

## AD 006 Progress Engine before AI

Deterministic calculations normalize raw Workout, Measurement, Nutrition, continuity, and quality data. Context Builder gives AI these signals plus explicit evidence/missingness. The LLM is not the analytics authority.

## AD 007 Appointment is not Workout

Appointment models Trainer-Student time. Planned/Actual Workout models exercise intent/execution. Optional linking supports coaching without coupling their lifecycles.

## AD 008 Human approval for Student owned objectives

Fitness Goal and strategic Nutrition Goal belong to the Student. A Trainer has Workout Plan authority in `HUMAN_COACH`, but Goal and strategic Nutrition changes use proposals and Student confirmation.

## AD 009 Least privilege administration

Administrator is platform authority. Role plus permission, purpose, scope, step-up authentication, privileged access, and immutable audit prevent an uncontrolled superuser model.

## AD 010 Evolution through versioning

Schema migrations, versioned APIs, Goal Versions, Workout Plan Versions, Nutrition Target Versions, Knowledge Versions, prompt versions, and model versions preserve meaning as the product changes.

