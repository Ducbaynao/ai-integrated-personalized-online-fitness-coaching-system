# Product glossary

| Term | Meaning |
| --- | --- |
| User Account | Common identity that may own independent Student and Trainer profiles and one or more roles. |
| Student Profile | Fitness participant profile. It is not required for every Trainer account. |
| Trainer Profile | Coaching profile and application data. Its existence does not grant coaching authority. |
| Coaching Mode | `SELF_DIRECTED` or `HUMAN_COACH`; identifies who is responsible for the Workout Plan in a period. |
| Coaching Relationship | Long-lived relationship record between a Trainer and Student. |
| Coaching Period | Time-bounded context that records Coaching Mode, responsible Trainer when applicable, authority, and data scope. |
| Fitness Goal | Student-owned desired fitness outcome with timeline and one or more measurable Goal Targets. |
| Goal Version | Effective-dated revision of the same ongoing Goal. |
| Goal Transition | Traceable relationship between a closed/replaced Goal and a newly started Goal. |
| Goal Proposal | Trainer- or AI-originated suggestion that requires Student acceptance before changing a Goal. |
| Workout Plan | Program structure owned by the authorized planner for the current Coaching Mode. |
| Planned Workout | Intended session content and planned date. |
| Actual Workout | What the Student performed, including performed date and set-level facts. |
| Coaching Appointment | Trainer-Student meeting. It may reference a Planned Workout but has an independent lifecycle. |
| Supervision Requirement | `SELF_PERFORMABLE`, `COACH_OPTIONAL`, or `COACH_REQUIRED`. |
| Measurement | Time-series metric value with source, method, provenance, validation, quality, and measurement time. |
| Training Activity Period | Period describing whether the Student is actively training; independent from Coaching Period. |
| Nutrition Goal | Student-owned nutrition objective that may align with a Fitness Goal. |
| Nutrition Target Version | Effective-dated strategic calorie/macro target. |
| Daily Target Override | Temporary day-specific adjustment that does not create a strategic target version. |
| Progress Engine | Deterministic layer that normalizes history and produces trends, adherence, continuity, and evidence-backed signals. |
| Attention Signal | Traceable rule/system condition requiring Trainer or Admin attention. It is not an AI Recommendation. |
| AI Recommendation | Validated AI output awaiting the authorized human's decision where application changes business data. |
| Context Builder | Selects only authorized and request-relevant data, including missingness, quality, recency, and continuity. |
| RAG | Retrieval-Augmented Generation using approved, active Knowledge Versions and pgvector retrieval. |
| Business Authority | Permission to make a domain decision. AI does not possess it. |
| System of Record | Authoritative data store. PostgreSQL holds normalized business truth. |

