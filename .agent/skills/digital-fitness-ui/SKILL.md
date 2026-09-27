---
name: digital-fitness-ui
description: Design, implement, or review Student Mobile, Trainer Mobile, and Admin Web interfaces for the AI Integrated Personalized Online Fitness Coaching System. Use whenever work touches screens, navigation, UX flows, design tokens, shared UI components, accessibility, or UI acceptance criteria for this product.
---

# Digital Fitness UI

Build interfaces that match the product's coaching, fitness, nutrition, AI, and governance rules. The product specification controls business behavior; the SuperFit reference controls only visual direction.

## Required workflow

1. Load `digital-fitness-core` before making product or implementation decisions.
2. Read `../../../docs/06-ui-ux/README.md`, `design-principles.md`, `design-tokens.md`, and `component-specifications.md`.
3. Identify the actor, feature, surface, screen ID, navigation entry, and relevant traceability row.
4. Read the matching actor flow/screen specification and [domain invariants](references/domain-invariants.md) when authority, lifecycle, approval, data quality, or AI behavior is involved.
5. For design-to-code or visual comparison work, follow [implementation workflow](references/implementation-workflow.md) to inspect the relevant Figma frame/component, distinguish verified design from gaps, and map it to existing code.
6. Reuse project tokens and shared components. Do not hardcode a parallel visual system or copy generated Figma code directly.
7. Implement all applicable states from `screen-states.md` and accessibility rules from `accessibility.md`.
8. Compare the implementation with the Figma screenshot and emulator/browser at supported sizes, then review with [review checklist](references/review-checklist.md).
9. Update UI/UX documentation when a verified token, reusable component, navigation contract, or screen inventory entry changes.

## Routing

- Student work: read `../../../docs/06-ui-ux/student/student-flows.md` and `student-screens.md`.
- Trainer work: read `../../../docs/06-ui-ux/trainer/trainer-flows.md` and `trainer-screens.md`.
- Admin work: read `../../../docs/06-ui-ux/admin/admin-flows.md` and `admin-screens.md`.
- New navigation or screen: also update `screen-inventory.md` and `references/traceability-matrix.md`.
- New reusable component or token: update its specification and code token source in the same change.
- Figma inspection, implementation planning, or visual verification: read [implementation workflow](references/implementation-workflow.md).

## Non-negotiable decisions

- Coaching modes are only `SELF_DIRECTED` and `HUMAN_COACH`.
- AI Assistance is not a mode and never silently changes business data.
- Student owns Fitness Goal and Nutrition Goal decisions.
- Trainer proposes goal/strategic nutrition changes and controls Workout Plan only within valid coaching authority.
- Coaching Period history and lifetime fitness history are preserved.
- Appointment and workout execution are separate lifecycles.
- Missing or partial data is never rendered as zero.
- System Alert and AI Recommendation use distinct components and actions.
- Admin is Platform Authority, not Coaching Authority.

If a request conflicts with these rules, explain the conflict and request a product decision before implementing the conflicting behavior.

## Visual direction

Follow `../../../docs/06-ui-ux/references/superfit-reference.md`: DM Sans, purple brand, light canvas, rounded cards, generous spacing, and clear metric cards. Do not copy template branding, images, wording, navigation, or simplified goal model.
