# Business and Data Invariants

- Only `HUMAN_COACH` and `SELF_DIRECTED` are coaching modes.
- AI is assistance, not a coaching mode or decision authority.
- Coaching mode belongs to a Coaching Period, not Student Profile.
- Fitness history is never reset or deleted when changing goal, plan or trainer.
- Trainer and AI goal changes require a Goal Proposal.
- Student owns and approves Fitness Goal and Nutrition Goal changes.
- Trainer controls Workout Plan only during a valid HUMAN_COACH period.
- Admin is platform authority, not coaching authority.
- Missing measurement and nutrition data means unknown, never zero.
- Planned workout date and actual performed date are separate.
- A COACH_REQUIRED session cannot silently become self-performed.
- Applied versions are immutable.
- A Student has at most one ACTIVE Workout Plan. Published plan versions and the source plan-session identity of materialized Planned Workouts are immutable.
- Significant Workout Plan changes create a new version without remapping existing Planned Workouts; one-occurrence changes are append-only adjustments.
- A Student continues a former Trainer-authored plan through an explicit Student-owned successor with source lineage, never implicit ownership transfer.
- Sensitive administrative actions require auditing and step-up authorization.
