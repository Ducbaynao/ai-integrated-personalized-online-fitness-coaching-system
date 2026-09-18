---
name: fitness-commercial-platform
description: Design future commercial expansion for Trainer discovery, coaching packages, subscriptions, payments, invoices, ratings, reviews, and calendar integration without weakening core coaching authority or history rules.
---

# Fitness Commercial Platform

Read `digital-fitness-core`, `fitness-identity-access`, and `fitness-goal-coaching` first. This is a Phase 3 capability; preserve its boundaries in target design without forcing implementation into Phase 1.

## Trainer marketplace

Expose only verified, active Trainers who meet publication policy. Keep discovery/profile visibility separate from permission to access Student data. Search may use specialization, availability, capacity, language, delivery mode, package, and rating, but must not reveal private coaching or health records.

Model Trainer availability and capacity independently from actual Appointment records. Marketplace availability is an offer constraint, not a confirmed booking.

## Packages and subscriptions

Represent a Coaching Package with Trainer, title, delivery mode, duration, session allowance/frequency, session length, scope, price, currency, status, and terms/version. Purchased terms must remain historically reproducible even if the Trainer edits the public package later.

Keep commercial Subscription/Purchase lifecycle separate from Coaching Relationship and Coaching Period. Payment success may make a request eligible, but it must not silently create coaching authority, consent, Goal changes, or an active plan. Define explicit transitions and compensation/refund behavior.

## Payments and invoices

Use provider-hosted or tokenized payment flows; never store raw card data. Verify provider signatures/webhooks, enforce idempotency, reconcile asynchronous states, and preserve payment/refund/invoice audit. Use clear states such as pending, authorized, paid, failed, cancelled, partially refunded, and refunded as appropriate to the provider and product.

## Ratings and reviews

Permit reviews only from eligible completed/valid coaching transactions according to policy. Separate rating aggregate from individual review records. Support moderation without rewriting history; record review edits, reports, and moderation actions.

## Integrations

Calendar integrations mirror or synchronize Appointments; they do not become the system of record and do not merge Appointment with Workout. Handle timezone, duplicate events, revocation, provider errors, and conflict revalidation through the scheduling domain.

For every commercial flow, define ownership, money state, coaching state, cancellation/refund rules, authorization, audit, privacy, and failure recovery separately.
