# Module boundaries

The package names may evolve, but the following responsibilities and dependencies must remain explicit.

| Module | Owns | Must not do |
| --- | --- | --- |
| `auth` | credentials, tokens, sessions, authentication events | own Student/Trainer business state |
| `user` | identity, base profile, role/permission assignment | infer coaching capability from role alone |
| `student` | Student Profile and declared preferences/limitations | own Goal or Workout lifecycle |
| `trainer` | Trainer Profile, application, verification, certification, capacity | access arbitrary Student repositories |
| `goal` | Goal, Targets, Proposals, Versions, Transitions | store Coaching Mode permanently on Student |
| `coaching` | Relationship, Period, authority, data-sharing scope | merge relationship history with Goal history |
| `exercise` or `content` | Exercise catalog, variations, canonical references, media metadata | hard-delete referenced content |
| `workout` | plans, versions, planned/actual sessions, logs, RPE, volume | treat Appointment as Workout |
| `schedule` | Appointment, recurrence, conflicts, changes, timezone | mutate Workout completion implicitly |
| `measurement` | definitions, observations, provenance, validation, deduplication | invent missing values |
| `progress` | deterministic trends, adherence, continuity, Attention Signals | delegate analytics truth to an LLM |
| `nutrition` | goals, proposals, target versions, daily targets, meals, logs, calculation | treat missing log as zero or AI estimate as final |
| `chat` | conversations, membership, messages, receipts | bypass relationship/privacy authorization |
| `notification` | preferences, events, delivery state | become source of truth for domain transitions |
| `ai` | orchestration, Runs, Recommendations, validation, approval link | grant models direct business authority |
| `administration` | Admin permissions and platform workflows | edit coaching data outside validated support workflows |
| `audit` | immutable security/business audit records | permit Admin UI mutation of audit history |
| `moderation` and `support` | reports, cases, actions, data correction coordination | directly update domain tables without application services |

## Collaboration rules

- Cross-module reads use a published query/service contract or read model.
- Cross-module changes use an application service, command, or domain/application event.
- A module does not import another module's persistence repository.
- Events include stable identifiers and enough context to process safely; sensitive payloads are minimized.
- Transactions remain local to the modular monolith where correctness requires atomicity.
- Background handlers are idempotent when delivery can repeat.

## Important dependency direction

- Progress consumes normalized outputs from Workout, Measurement, Nutrition, Goal, and Coaching.
- AI Context consumes authorized Progress signals and selected domain facts; domain modules do not depend on model output for their core invariants.
- Administration invokes normal domain application services with elevated, audited permissions; it does not bypass validation.
- Notification reacts to committed business events; notification failure does not roll back an otherwise valid domain transition unless explicitly required.

