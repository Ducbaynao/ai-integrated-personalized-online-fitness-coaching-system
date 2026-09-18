---
name: digital-fitness-ui
description: Design, implement, or review Student Mobile, Trainer Mobile, and Admin Web interfaces for the AI Integrated Personalized Online Fitness Coaching System. Use whenever work touches screens, navigation, UX flows, design tokens, shared UI components, accessibility, or UI acceptance criteria for this product.
---

# Digital Fitness UI

Build interfaces that match the product's coaching, fitness, nutrition, AI, and governance rules. The product specification controls business behavior; the SuperFit reference controls only visual direction.

## Required workflow

1. Read `../../../docs/ux-ui/README.md`, `design-principles.md`, `design-tokens.md`, and `component-specifications.md`.
2. Identify the actor and surface: Student Mobile, Trainer Mobile, or Admin Web.
3. Read the matching actor flow and screen specification.
4. Locate the screen ID in `screen-inventory.md` and relevant row in `references/traceability-matrix.md`.
5. Read [domain invariants](references/domain-invariants.md) before changing authority, lifecycle, approval, data-quality, or AI behavior.
6. Reuse tokens and shared components. Do not hardcode a parallel visual system.
7. Implement all applicable states from `screen-states.md` and accessibility rules from `accessibility.md`.
8. Review the result with [review checklist](references/review-checklist.md).

## Routing

- Student work: read `../../../docs/ux-ui/student/student-flows.md` and `student-screens.md`.
- Trainer work: read `../../../docs/ux-ui/trainer/trainer-flows.md` and `trainer-screens.md`.
- Admin work: read `../../../docs/ux-ui/admin/admin-flows.md` and `admin-screens.md`.
- New navigation or screen: also update `screen-inventory.md` and `references/traceability-matrix.md`.
- New reusable component or token: update its specification and code token source in the same change.
- Implementation planning: read [implementation workflow](references/implementation-workflow.md).

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

Follow `../../../docs/ux-ui/references/superfit-reference.md`: DM Sans, purple brand, light canvas, rounded cards, generous spacing, and clear metric cards. Do not copy template branding, images, wording, navigation, or simplified goal model.
