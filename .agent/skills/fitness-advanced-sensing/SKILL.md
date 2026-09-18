---
name: fitness-advanced-sensing
description: Design future pose estimation, rep counting, form analysis, camera feedback, wearable, smart-scale, InBody, Apple Health, Health Connect, and other sensor integrations for the Digital Fitness platform.
---

# Fitness Advanced Sensing

Read `digital-fitness-core`, `fitness-measurement-progress`, `fitness-workout-execution`, and `fitness-ai-assistance` first. These are Phase 4 capabilities and must remain optional extensions of the core product.

## Pose and form analysis

Use camera frames to estimate body keypoints such as shoulder, elbow, wrist, hip, knee, and ankle; derive joint angles, range of motion, rep events, and limited form signals. Keep raw observation, derived metric, model inference, confidence, and user-facing feedback distinct.

Do not present pose feedback as medical diagnosis or guaranteed injury prevention. Restrict unsupported claims, state confidence/visibility limitations, and stop or ask for a better camera setup when landmarks are unreliable. Require explicit camera permission and clear capture/retention controls.

Store model/version, exercise definition/version, thresholds, device conditions, timestamps, confidence, and output provenance for meaningful analyses. Human/Student authority remains unchanged; AI feedback does not directly modify a plan or log unless reviewed and confirmed.

## Device and health-platform integrations

Route smart scale, InBody, wearable, Apple Health, Health Connect, gym device, and future providers through the common Measurement Ingestion Pipeline. Provider-specific payloads must be mapped to canonical Metric Definitions, normalized units, measurement times, methods, source/provenance, validation, quality, and deduplication.

Do not create a provider-specific silo or treat provider data as automatically more accurate. Handle duplicate sync, delayed events, clock/timezone differences, revoked consent, partial history, corrections, and deletion requests. Preserve external IDs and synchronization checkpoints without making them business truth.

## Operational behavior

Run vision and bulk synchronization asynchronously where needed. Make ingestion idempotent, rate-limit aware, observable, retry-safe, and privacy-minimized. Secure raw media/payload retention and avoid keeping it when derived data is sufficient and policy permits deletion.

Progress Engine decides how accepted sensor data contributes to trends. Context Builder receives normalized signals plus quality/recency, not uncontrolled raw provider payloads.
