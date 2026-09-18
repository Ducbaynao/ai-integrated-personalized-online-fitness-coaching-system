---
name: digital-fitness-core
description: Preserve the product boundaries, terminology, authority rules, and evolutionary architecture of the AI-integrated personalized online fitness coaching system. Use for every feature, design, coding, review, or documentation task in this project; combine it with the relevant domain skill.
---

# Digital Fitness Core

Use the official Vietnamese project name: **HỆ THỐNG HUẤN LUYỆN THỂ HÌNH TRỰC TUYẾN ĐƯỢC CÁ NHÂN HÓA TÍCH HỢP TRÍ TUỆ NHÂN TẠO**. The product is a Digital Fitness Coaching Platform for Students, Personal Trainers, and platform Administrators.

## Product model

- Support exactly two coaching modes: `SELF_DIRECTED` and `HUMAN_COACH`.
- Treat AI as `AI Assistance`, never as a third coaching mode or business authority.
- Store coaching responsibility in time-bounded `Coaching Period` history, not as a permanent Student Profile field.
- Use one common User identity with independent Student and Trainer profiles/capabilities. One user may have both roles.
- Keep Fitness Goal, Coaching Relationship, Coaching Period, Workout Plan, Appointment, Actual Workout, Nutrition Goal, and Measurement as distinct lifecycles.

## Invariants that no implementation may break

1. Fitness history is continuous. New goals, new trainers, coaching-mode changes, pause/resume, and new plan versions never erase earlier workouts, measurements, nutrition, progress, RPE, or plan history.
2. Student owns personal Fitness Goals and Nutrition Goals. Trainer may propose changes; Student accepts or rejects. Admin never decides them. AI only recommends.
3. In `HUMAN_COACH`, Trainer controls Workout Plan decisions within an active authorized relationship. In `SELF_DIRECTED`, Student decides. AI output must be proposed, validated, and approved before application.
4. PostgreSQL is the system of record. Redis is temporary infrastructure. Object storage holds media. AI is not a source of truth.
5. Missing data is `NULL`/`NOT_AVAILABLE`, never zero. Do not infer body composition, calorie intake, or progress from absent, stale, suspect, or incomplete data.
6. Planned Workout and Actual Workout are separate. Planned date and performed date are separate. Completion and schedule adherence are separate.
7. Coaching Appointment is not a Workout Session. A cancelled/rescheduled appointment may leave a self-performable workout executable.
8. Admin is platform authority, not coaching authority. Apply least privilege, scoped privileged access, step-up authentication, and immutable audit for sensitive operations.
9. Prefer deterministic rules and Progress Engine signals before LLM reasoning. Explain evidence, recency, quality, and uncertainty.
10. Start as a Spring Boot modular monolith. Keep FastAPI AI Service isolated behind Spring orchestration. Split services only for demonstrated workload or organizational need.

## Technology baseline

- Mobile: React Native, TypeScript, Expo, Expo Router, TanStack Query, Axios, Zustand, React Hook Form.
- Admin web: React and TypeScript.
- Core backend: Java, Spring Boot, Spring Security, Spring Data JPA, JWT, WebSocket, Bean Validation, OpenAPI, Flyway.
- AI: Python and FastAPI with Context Builder, Rule Engine, RAG, structured output, validation, audit, and recommendation lifecycle.
- Data: PostgreSQL plus pgvector, Redis, and S3-compatible object storage.

## Routing

Load the focused skill for the requested area: identity/access; goals/coaching; workout execution; scheduling/collaboration; measurement/progress; nutrition; AI assistance; exercise/knowledge content; trainer workspace; admin governance; backend/data; mobile; admin web; security/operations; testing; commercial expansion; or advanced sensing.

When requirements conflict, preserve the invariants above and call out the conflict. Distinguish target-product design from roadmap order: a later implementation phase does not remove the domain boundary from the target architecture.
