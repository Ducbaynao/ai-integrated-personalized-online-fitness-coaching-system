# Project overview

The AI-Integrated Personalized Online Fitness Coaching System is a digital fitness coaching platform that brings coaching relationships, goals, workout programming, workout execution, scheduling, measurements, progress, nutrition, communication, platform governance, and AI assistance into one system.

Official Vietnamese name: **HỆ THỐNG HUẤN LUYỆN THỂ HÌNH TRỰC TUYẾN ĐƯỢC CÁ NHÂN HÓA TÍCH HỢP TRÍ TUỆ NHÂN TẠO**.

The platform supports people who train independently and people who work with a Personal Trainer. A Student can move between those situations without losing historical data. A Trainer can manage multiple Students through structured plans, schedules, progress signals, review cycles, and communication. An Administrator protects platform integrity, content, operations, and security without taking over coaching decisions.

## Product definition

The product has exactly two Coaching Modes:

- `SELF_DIRECTED`: the Student is responsible for the Workout Plan and decides whether to apply recommendations.
- `HUMAN_COACH`: an eligible Trainer is responsible for the Workout Plan within an active Coaching Period. The Student remains the owner of personal Fitness Goals and strategic Nutrition Goals.

AI Assistance works across both modes. It analyzes authorized, quality-aware context and produces recommendations, explanations, or structured proposals. It cannot activate goals, plans, nutrition targets, or business actions by itself.

## Problems addressed

Traditional coaching often separates chat, calendars, spreadsheets, workout logs, measurements, and nutrition records. This fragmentation makes it difficult to preserve history, coordinate schedules, evaluate progress, scale a Trainer's workload, and support a person who does not hire a Trainer.

The platform provides a single, traceable system for:

- accounts, roles, profiles, and Trainer verification;
- Fitness Goal and Nutrition Goal lifecycles;
- Coaching Relationships, Coaching Periods, and scoped data sharing;
- Exercise content, Workout Plans, Planned Workouts, and Actual Workouts;
- appointments, recurrence, conflicts, and reschedule requests;
- time-series measurements, progress photos, and training continuity;
- nutrition targets, meal logs, AI-assisted food entry, and data completeness;
- chat, notifications, review tasks, and attention signals;
- AI context, rules, RAG, recommendations, validation, and audit;
- administration, moderation, support, content governance, and platform operations.

Progress Photos and other private media are stored in object storage, referenced by PostgreSQL metadata, and protected by the same ownership, sharing, and privileged-access rules as their parent domain.

## Product difference

The differentiator is not a chatbot. AI is connected to normalized fitness context such as the Student Profile, Goals, Goal Versions, Workout History, Training Volume, RPE, Measurements, Schedule, Nutrition, data quality, and inactivity history. The Progress Engine and deterministic rules produce reliable signals before AI reasoning is used.

The long-term platform can serve self-directed people, coached Students, Personal Trainers, online coaches, gyms, and fitness organizations while preserving the same authority and history principles.

See [actors and responsibilities](actors-and-responsibilities.md), [scope and roadmap](scope-and-roadmap.md), and the [glossary](glossary.md).
