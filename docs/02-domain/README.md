# Domain boundaries

The Spring Boot modular monolith is divided into these business boundaries:

- Identity: `auth`, `user`, `student`, and `trainer`
- Coaching lifecycle: `goal`, `coaching`, `schedule`, and `workout`
- Fitness data: `exercise`, `measurement`, `progress`, `nutrition`, and `media`
- Collaboration: `chat` and `notification`
- Intelligence: backend `ai` orchestration plus the separate AI service
- Platform governance: `administration`, `audit`, `moderation`, `support`, and `content`

## Invariants

- A user account may have Student and Trainer profiles independently.
- Trainer profile existence does not grant coaching authority; verification and activity state are required.
- A goal belongs to the student. Trainer and AI suggestions use proposals.
- Coaching periods carry decision authority and data-sharing scope.
- Planned workouts, actual workouts, and coaching appointments have independent lifecycles.
- Measurements include source, method, provenance, validation, quality, and deduplication data.
- Progress Engine produces deterministic signals before AI context is created.
- Nutrition targets and actual food logs are separate; missing logs are not zero intake.
