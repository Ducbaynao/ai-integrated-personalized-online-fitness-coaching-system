# Acceptance criteria

The following scenarios are release gates for the target domain design. Phase-specific releases select the relevant subset but must not violate later boundaries.

## Identity and authority

1. A Student becomes a Trainer on the same User Account, creates a Trainer Profile, passes verification, and retains all Student history.
2. A person onboards directly as a Trainer without receiving an empty Student Profile.
3. A `PENDING`, `REJECTED`, `SUSPENDED`, `REVOKED`, or expired Trainer cannot create an active Coaching Relationship or use coaching authority.
4. A Trainer cannot access an unrelated Student even when the account has the `TRAINER` role.

## Goal and coaching continuity

1. A 90-day Goal is revised on day 41 to 120 days as the same journey: a Goal Version with `effective_from` is created and prior progress remains.
2. A Student starts a new 120-day program with a Trainer on day 41: the old Goal becomes `REPLACED`, a new Goal starts at Day 1, and a Goal Transition links them.
3. A Student moves `SELF_DIRECTED` to Trainer A, back to `SELF_DIRECTED`, then to Trainer B. Coaching Period history is complete and no fitness history is lost.
4. A Trainer-delivered plan remains readable/usable by the Student after coaching ends according to retention policy; the former Trainer cannot continue editing it.
5. A 100-day inactivity period causes return-to-training review; the system does not blindly continue previous progressive overload.

## Workout and scheduling

1. A workout planned for Monday and performed Tuesday records both dates, is completed, and produces schedule deviation rather than a false missed-workout conclusion.
2. Completion can be 100 percent while schedule adherence is lower.
3. Accepting a pending reschedule request rechecks conflicts inside the final update transaction.
4. Changing one recurring appointment defaults to `THIS_SESSION_ONLY`.
5. Cancelling a Coaching Appointment does not cancel a self-performable Planned Workout.
6. A `COACH_REQUIRED` session cannot be silently converted into an independent workout when the Trainer is absent.
7. A minor load adjustment records change history without creating a noisy full plan version; a strategic program change creates a new version.

## Measurements and nutrition

1. Missing body-fat data remains `unknown`; the system can still operate with required basic data.
2. Conflicting measurements from manual and device sources preserve provenance and are validated/deduplicated according to policy.
3. An increase of 3 kg without body-composition evidence cannot generate a statement that 3 kg of muscle was gained.
4. A day with no Food Log is not calculated as zero calories.
5. A partial log reduces confidence/completeness and is not treated as a complete day.
6. Training-day and rest-day targets resolve from the active Nutrition Target Version; a one-day override does not create a strategic version.
7. AI-recognized food remains estimated until the Student confirms or corrects identity and quantity.
8. Nutrition history remains available across Goal, Trainer, and Coaching Period changes subject to current access scope.

## AI and governance

1. An AI output that fails schema or business validation is not persisted as an applicable recommendation.
2. An AI Recommendation cannot update a Workout Plan, Goal, or Nutrition Target before authorized acceptance.
3. RAG retrieval excludes draft or archived knowledge versions.
4. AI replay produces evaluation data only and cannot change Student business data.
5. A System Alert identifies a deterministic missed-workout or conflict condition without calling an LLM.
6. Privileged Admin access fails without the required permission, reason, scope, and valid time window.
7. Admin data correction records before/after, reason, actor, and case reference and still enforces domain validation.
8. Active Exercise and Knowledge versions referenced by history cannot be destructively overwritten.
9. High-risk Admin actions require step-up authentication according to policy and create immutable audit entries.

