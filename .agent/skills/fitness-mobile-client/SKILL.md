---
name: fitness-mobile-client
description: Design or implement the React Native Expo application for Students and Trainers, including navigation, server state, forms, secure auth, workouts, schedules, chat, nutrition, camera/media, progress, and AI proposal review.
---

# Fitness Mobile Client

## F12 Coaching QR routing

For Student/PT QR discovery load the [approved Dev A Phase 2 plan](../../../docs/07-development/features/phase-2-dev-a-coaching-qr.md) (D08, 2026-11-25). Real per-capability QR generation, camera/image decode and authenticated backend resolution are required; no decorative/mock QR or fabricated profile. SH-07/08/09 replace name/email selection and reuse B03 invitation/decision commands. QR01–QR10 require real camera/device and saved-image round trips; emulator-only or mocked scanner tests cannot close camera acceptance. Implementation is PLANNED at the documentation checkpoint.

Read `digital-fitness-core` and the domain skill for the feature.

Use React Native, TypeScript, Expo, Expo Router, TanStack Query, Axios, Zustand, and React Hook Form. Treat server data as authoritative server state; keep local/global state focused on UI/session concerns.

## Product behavior

- Adapt navigation and actions to active Student/Trainer capabilities without creating separate identities.
- Make current Coaching Mode and decision authority clear where plans or recommendations are changed.
- Show Goal/current version and lifetime history separately.
- Keep planned workout, actual log, planned date, performed date, completion, and adherence visually distinct.
- Let Students continue Trainer-delivered plans after coaching ends while hiding unauthorized edit actions.
- Display measurement source, recency, quality, and missing state; never render missing as zero.
- Show nutrition target versus actual plus logging completeness and estimated/confirmed items.
- Present rule alerts differently from AI recommendations. AI screens need evidence, assumptions, uncertainty, and explicit accept/modify/reject actions.

## Forms and offline/error behavior

Use schema-aligned validation and accessible input for sets, reps, weight, RPE, body measurements, meals, consent, and proposals. Prevent duplicate submission and preserve safe drafts when network calls fail. Handle optimistic updates only when reversible and low risk; do not optimistically finalize approvals, role changes, or schedule conflict resolution.

Use secure storage for tokens, refresh safely, and clear sensitive state on logout/forced logout. Do not persist private health/chat/media data in insecure local storage.

## Media and realtime

Use camera/media flows for progress and food photos with explicit preview, consent, upload state, retry, and deletion policy. Use WebSocket for chat/presence and reconcile with paginated persisted history.

## Quality

Implement loading, empty, partial-data, stale, offline, permission-denied, and error states. Use stable query keys, targeted invalidation, pagination for histories, and tests for role changes, expired consent, interrupted log entry, timezone display, and AI approval flows.

## Phase 2 routing

For Phase 2 work, first follow [root agent instructions](../../../AGENTS.md#phase-2-execution) and the approved three-document reading order. Read the feature matrix and relevant actor screen/flow specs. INFORMATION is view-only; PROPOSAL uses authority-specific decisions; ESTIMATE uses Nutrition Confirm/Correct. Keep operation identity across retries and change it for a new intent; do not report a modified payload as applied using an old receipt.
