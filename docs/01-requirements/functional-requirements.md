# Functional requirements

## Identity and profiles

- **FR-ID-001** The system must provide one common User Account authenticated by email/password, with future support for external identity providers.
- **FR-ID-002** Authentication must issue access and refresh tokens and support refresh-token rotation and revocation.
- **FR-ID-003** A User may independently create Student and Trainer profiles without creating another account.
- **FR-ID-004** A Trainer Profile must not grant coaching authority until verification, activity, and policy conditions are satisfied.
- **FR-ID-005** Trainer Application and Trainer Verification must have explicit lifecycles and audit history.
- **FR-ID-006** Authorization must combine role/permission, capability state, object relationship, access scope, time scope, and purpose where applicable.
- **FR-ID-007** Trainer capacity and availability must be representable independently from Trainer verification and activity state.

## Goals and coaching

- **FR-GC-001** The only Coaching Modes are `SELF_DIRECTED` and `HUMAN_COACH`.
- **FR-GC-002** Coaching Mode must be recorded by Coaching Period rather than as a permanent Student Profile field.
- **FR-GC-003** A Fitness Goal may contain a primary goal, secondary goals, measurable targets, start date, target date or duration, and status.
- **FR-GC-004** Fitness Goal must support `DRAFT`, `ACTIVE`, `PAUSED`, `COMPLETED`, `ENDED`, `ABANDONED`, and `REPLACED` as applicable.
- **FR-GC-005** Significant target or timeline changes to the same journey must create an effective-dated Goal Version rather than overwrite history.
- **FR-GC-006** Starting a new journey must close/replace the previous Goal, create a new Goal, and record a Goal Transition.
- **FR-GC-007** Trainer- or AI-originated Goal changes must use a Proposal with `PENDING`, `ACCEPTED`, or `REJECTED`; only Student acceptance can make the change effective.
- **FR-GC-008** Goal Day may restart at Day 1 while lifetime fitness history remains continuous.
- **FR-GC-009** Goal progress must distinguish calendar time, active training time, pauses, and long inactivity.
- **FR-GC-010** Switching Trainer or Coaching Mode must create period history without deleting Goals, Workouts, Measurements, Nutrition, Progress, or plan versions.
- **FR-GC-011** Data Sharing Permission must support domain/resource scope, access level, and time scope, including explicit pre-coaching-history access.

## Measurements and progress

- **FR-MP-001** Measurements must be time-series data and preserve raw history.
- **FR-MP-002** The model must support extensible Metric Definitions and multiple sources, methods, units, and providers.
- **FR-MP-003** Each Measurement must retain source, provenance, measured time, validation status, quality/confidence where available, and deduplication context.
- **FR-MP-004** Required, recommended, and optional metrics must vary by Goal; missing optional metrics must not block the system.
- **FR-MP-005** Multi-source ingestion must normalize units, validate values, and resolve duplicates/conflicts without silently overwriting evidence.
- **FR-MP-006** Training Activity and Inactivity Periods must be recorded independently from Coaching Periods.
- **FR-MP-007** A long inactivity period must trigger return-to-training and nutrition-review logic before old progression assumptions are reused.
- **FR-MP-008** Progress Engine must calculate evidence-backed trends, adherence, completion, schedule adherence, continuity, and Goal progress before AI context is built.
- **FR-MP-009** Current Goal Progress and Historical/Lifetime Progress must remain separate views.
- **FR-MP-010** Progress Photos must use object storage with ownership, capture/upload time, visibility, and access metadata in PostgreSQL.

## Workout and schedule

- **FR-WS-001** Exercise Library must support exercises, variations, muscle groups, equipment, instructions, media, and archive/canonical mapping.
- **FR-WS-002** `SELF_DIRECTED` Students may create a plan, use a template, or request an AI plan proposal; AI cannot activate the plan.
- **FR-WS-003** In `HUMAN_COACH`, the eligible Trainer is responsible for the Workout Plan.
- **FR-WS-004** A Trainer-delivered plan may remain usable after coaching ends; later edits require appropriate ownership/version rules.
- **FR-WS-005** Significant program changes must create a Workout Plan Version; minor session adjustments must retain change history without unnecessary full versions.
- **FR-WS-006** Planned Workout and Actual Workout must be separate and linkable.
- **FR-WS-007** Planned date and performed date must be stored independently.
- **FR-WS-008** Completion and schedule adherence must be computed independently.
- **FR-WS-009** Actual Workout must support Workout, Exercise, and Set log levels, including weight, repetitions, duration, RPE, notes, and completion.
- **FR-WS-010** Missed sessions may be skipped, rescheduled, or performed later without deleting the original plan reference.
- **FR-WS-011** Appointment and Planned Workout must have independent lifecycles and only an optional reference.
- **FR-WS-012** Appointments must support recurrence, timezone, conflict detection, status, and change history.
- **FR-WS-013** Student and Trainer may initiate a Reschedule Request. Conflict validation must run again immediately before acceptance is committed.
- **FR-WS-014** Recurring changes must specify `THIS_SESSION_ONLY`, `THIS_AND_FOLLOWING`, or `ENTIRE_SERIES`; default is `THIS_SESSION_ONLY`.
- **FR-WS-015** Sessions must support `SELF_PERFORMABLE`, `COACH_OPTIONAL`, and `COACH_REQUIRED`. A `COACH_REQUIRED` session cannot silently become self-performed.

## Nutrition

- **FR-NU-001** Nutrition Goal, Nutrition Target, Daily Target, Meal, and Food Log Item must remain distinct concepts.
- **FR-NU-002** Nutrition Goal belongs to the Student and has its own lifecycle even when linked to a Fitness Goal.
- **FR-NU-003** Strategic Trainer/AI suggestions must use Nutrition Proposal and Student approval.
- **FR-NU-004** Strategic changes must create an effective-dated Nutrition Target Version; day-specific temporary changes use Daily Target Override.
- **FR-NU-005** Daily targets may resolve by `TRAINING_DAY`, `REST_DAY`, or `DEFAULT` rules.
- **FR-NU-006** Nutrition target and actual intake must not overwrite each other.
- **FR-NU-007** Logging completeness must distinguish not logged, partial, and complete. Missing food logs must never be treated as zero intake.
- **FR-NU-008** Nutrition history must survive Goal, Trainer, and Coaching Period changes.
- **FR-NU-009** Text food entry and image recognition must resolve food identity and quantity against a Nutrition Database before deterministic calculation.
- **FR-NU-010** AI food estimation must preserve `ESTIMATED`, `USER_CONFIRMED`, or `USER_CORRECTED` provenance and confidence where available.

## Communication and attention management

- **FR-CA-001** Chat must support conversations, membership, messages, attachments, read receipts, persistent history, and message states such as `SENT`, `DELIVERED`, `READ`, and policy-compliant deletion.
- **FR-CA-002** Notification must be a separate subsystem supporting in-app and push channels, with future email support, delivery state, reminders, and a Notification Preference for each category such as workout reminders, Trainer messages, AI Recommendations, and marketing.
- **FR-CA-003** Trainer experience must provide a Student Coaching Workspace and periodic Coaching Review Cycle.
- **FR-CA-004** Deterministic Attention Signals must be traceable to evidence and have type, severity/priority, detection time, and status.
- **FR-CA-005** System/Rule Alerts must remain distinct from AI Recommendations.

## AI Assistance

- **FR-AI-001** Clients must call Spring Boot; they must not call the AI service directly.
- **FR-AI-002** Context Builder must retrieve only authorized and request-relevant data.
- **FR-AI-003** Context must describe missing, stale, suspect, or unavailable data and include source, method, measured time, and quality where relevant.
- **FR-AI-004** Progression/recovery recommendations must consider last workout, inactivity duration, and training continuity.
- **FR-AI-005** AI must not infer unsupported outcomes, such as muscle gain from weight change without body-composition evidence.
- **FR-AI-006** Deterministic rules and validation must constrain model output.
- **FR-AI-007** RAG must use only eligible active Knowledge Versions.
- **FR-AI-008** Plan and recommendation output must be structured and schema validated.
- **FR-AI-009** Recommendations that change business data must follow `PENDING` to authorized `ACCEPTED`/`REJECTED` handling.
- **FR-AI-010** Important AI Runs must retain model, model version, prompt version, request type, input references, output, validation result, latency, token usage, and time.

## Administration and governance

- **FR-AD-001** Admin authorization must use permissions rather than an uncontrolled superuser assumption.
- **FR-AD-002** Admin must manage account lifecycle states without bypassing security and retention policy.
- **FR-AD-003** Trainer Applications must support review, more-information requests, approval, rejection, and audit.
- **FR-AD-004** Verification, Trainer activity, and certifications must have separate states and history.
- **FR-AD-005** Referenced Exercise or Knowledge content must be archived/versioned, not hard-deleted or overwritten.
- **FR-AD-006** Moderation and Support Cases must have explicit lifecycles and audited actions.
- **FR-AD-007** Sensitive data access must require a valid reason, scope, permission, and time-bound privileged record where applicable.
- **FR-AD-008** Data correction must pass domain validation and store before/after values, actor, reason, and case reference.
- **FR-AD-009** AI replay/evaluation must not apply output to Student business data.
- **FR-AD-010** Critical configuration and feature-flag changes must be permission checked, validated, and audited.
- **FR-AD-011** User deletion must follow disablement, retention, and deletion/anonymization policy instead of immediate uncontrolled hard deletion.
- **FR-AD-012** Background jobs and integrations must expose operational status and controlled retry/cancel behavior without exposing unrelated personal data.
