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
- Sensitive administrative actions require auditing and step-up authorization.