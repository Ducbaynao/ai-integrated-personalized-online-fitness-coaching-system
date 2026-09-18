# Context rules and RAG

## Context Builder

Context Builder selects the smallest authorized dataset needed for the request. Possible inputs include:

- age, gender where legitimately required, height, and current validated weight;
- Fitness Goal, active Goal Version, Targets, and Goal history references;
- current Coaching Period, decision authority, relationship, and sharing scope;
- experience, days per week, session duration, equipment, preferences, and exercises to avoid;
- self-reported movement limitations without converting them into medical diagnosis;
- active Workout Plan/Version and recent Planned/Actual Workouts;
- set performance, RPE, volume, completion, and schedule adherence;
- relevant Measurements and Progress Engine trends;
- last workout, days since last workout, active/inactive periods, and continuity signals;
- relevant Schedule and Nutrition data when permitted and needed.

Every metric entry should state availability, observation/effective time, source/method where useful, and quality/validation. Stale or suspect information must not appear as current certain truth.

## Insufficient context

If required information is missing, the system asks for it or returns a structured insufficiency result. Optional missing metrics do not block unrelated assistance. The model must not fill gaps with invented values or population averages presented as personal facts.

Examples of prohibited inference:

- weight increase equals muscle increase without body-composition evidence;
- no Food Log equals zero calorie intake;
- old best performance equals current capability after long inactivity;
- a Trainer role means the caller may read every Student;
- an AI estimate equals a confirmed Measurement or Food Log.

## Rule Engine

Deterministic rules protect constraints such as:

- beginner program complexity;
- available equipment;
- recovery between demanding muscle-group sessions;
- schedule availability and session duration;
- invalid/suspect Measurements;
- inactivity thresholds and return-to-training requirement;
- supervision requirements;
- plan/version conflicts and authority;
- valid exercise/content references.

Simple conditions such as missed workout, conflict, overdue check-in, or long inactivity should produce System/Rule Alerts without unnecessary LLM calls.

## Governed RAG

Knowledge is managed as Document to Version to Chunk to Embedding. Metadata includes title, source, author/publisher, version, published/review time, status, and evidence/review fields where applicable.

Only eligible `ACTIVE` versions are retrieved for production assistance. Updating knowledge creates a new `DRAFT` version, review, and publish event; it never edits the active version in place. Previous versions are archived according to policy and retained for AI Run traceability.

PostgreSQL with pgvector is sufficient initially. A specialized vector database is considered only after measured vector workload demands it.

