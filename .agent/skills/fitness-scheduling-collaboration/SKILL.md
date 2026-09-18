---
name: fitness-scheduling-collaboration
description: Design or implement appointments, recurring schedules, reschedule requests, conflict checks, timezone handling, chat, notifications, reminders, and collaboration between Students and Trainers.
---

# Fitness Scheduling and Collaboration

Read `digital-fitness-core` first.

## Appointment boundary

Model Coaching Appointment independently from Planned Workout. An optional reference may connect them, but cancellation, completion, and rescheduling have separate lifecycles. Respect the Workout's supervision requirement when deciding whether a Student may train without the Trainer.

Appointment states include `SCHEDULED`, `CONFIRMED`, `COMPLETED`, `CANCELLED`, and `ABSENT`. Store Trainer, Student, start/end, timezone, location, type, status, notes, and optional related Workout Session.

## Scheduling rules

- Support recurring schedules without copying business logic into the client.
- Before create or accepted change, check Trainer conflicts and any applicable availability/capacity rules.
- Store instants consistently and render in each user's timezone.
- Keep immutable change history.

Both Student and Trainer may initiate a reschedule request. Store appointment, initiator, current/proposed time, reason, state, creation, and response timestamps. Use `PENDING`, `ACCEPTED`, `REJECTED`, `CANCELLED`, and `EXPIRED`.

On acceptance, re-check conflicts inside the final transaction because availability may have changed while the request was pending. For recurring events require `THIS_SESSION_ONLY`, `THIS_AND_FOLLOWING`, or `ENTIRE_SERIES`; default to one session.

If a self-performable/coach-optional Workout is completed before a moved Appointment, do not force the same Workout again. Convert the meeting to review/check-in or cancel it explicitly. `COACH_REQUIRED` must be rescheduled or safely replaced.

## Chat and notifications

Model chat as Conversation, members, Message, Attachment, and Read Receipt. Use WebSocket for realtime delivery and PostgreSQL for message history. Keep message states such as sent, delivered, read, and deleted.

Build Notification as its own subsystem with in-app and push channels, later email. Events include coaching requests, schedules, reschedules, assigned plans, completed workouts, messages, AI recommendations, and nutrition-analysis completion. Honor per-user preferences by notification type; distinguish transactional/security messages from optional marketing.

Never expose previous Trainer conversations to a new Trainer merely because workout-history consent exists.
