# F03 draft editing, revision diff and controlled sharing

Users with workout decision authority can edit a pending F03 proposal, review its revision and field changes, then accept it or intentionally share it with the Student. Student readers in HUMAN_COACH receive the shared snapshot while subsequent Trainer edits remain private.

## Changes

- Add edit/share endpoints, source validation, audit and frozen shared snapshots with Flyway V60.
- Serialize decisions and edits with a pessimistic recommendation-row lock and an expectedRevision check. Preserve atomic Accept-and-Apply and receipt-based replay.
- Validate load code–ID pairs against Measurement references, retain nullable historical unit IDs and handle load conversion before direction/delta checks.
- Integrate Mobile editing, catalog selection, share/reshare, read-only Student views and revision conflicts.
- Correct Mobile revision diff rendering to consume the Backend originalValue/customizedValue fields while retaining before/after compatibility. Wrap the diff header and rows on narrow screens.

## Verification

- Reproduced the diff rendering failure with the Backend response shape, then passed the regression after the fix.
- Re-ran three Mobile suites with 89/89 passing tests, including F03 component/API and F04 Student A → B → A delayed-response scenarios.
- Mobile typecheck and lint passed after removing an unused test import and stabilizing the prescription-list dependency.
- Existing Surefire reports record 105 F03, 58 F02 and 47 Workout Plan tests with zero failures/errors. These Backend suites were inspected during this evidence repair and were not re-run in this pass.
- See the [runtime evidence record](artifacts/f03-mobile-runtime-acceptance-20261115/verification.md) for newly captured PNGs, provenance and the exact GUI scenarios observed.

## Open items

- F04 Student A → B → A with intentionally delayed responses has passing component tests. The equivalent live Android GUI scenario remains NOT RUN.
- Specialist review D06/OD-3, real-provider quality/latency/cost evaluation and milestone sign-off remain open.

## Packaging

Review only the F03 application, contracts, migration, tests, handover/checklist and verified artifact files. Local scratch scripts include authentication helpers and development credentials and require separate review before any staging. Keep local credentials and tokens out of the PR.
