---
name: fitness-measurement-progress
description: Design or implement body measurements, metric definitions, ingestion and deduplication, data quality, check-ins, progress photos, Progress Engine calculations, trends, continuity, and coaching attention signals.
---

# Fitness Measurement and Progress

Read `digital-fitness-core` first.

## Measurement platform

Treat measurements as append-only time-series lifetime data. Do not store the latest value as an independently editable source of truth on Student Profile. Derive Current Valid Value from accepted measurements.

Use extensible Metric Definition, Measurement, Unit, Method, Source, Provenance, and Quality concepts rather than a column per metric. Distinguish:

- direct/user-measurable: weight, height, circumferences;
- method/device-dependent: body fat, muscle mass, water, protein, visceral fat, bone mass;
- derived: BMI, moving average, rate of change, trend, variance.

Define required/core, recommended, and optional metrics by Goal type. Missing optional data never blocks the product or reduces Goal completion. Represent absence as `NULL`/`NOT_AVAILABLE`, not zero.

## Ingestion and quality

Route manual input, Trainer recording, smart scales, InBody, wearables, Apple Health, Health Connect, gym devices, and clinical imports through one pipeline: ingest, normalize units, validate, deduplicate/resolve conflict, persist, then calculate progress.

Store source, actor, measured time, recorded/imported time, device/external ID, method, confidence, and validation state such as `ACCEPTED`, `SUSPECT`, or `REJECTED/EXCLUDED`. Do not promote a newest outlier to Current Value automatically. Preserve rejected data for provenance/audit.

Use dynamic check-ins based on Goal, tracked metrics, equipment, Trainer request, and available data. Create a baseline check-in for a new Goal. Trainer-recorded values must identify the Trainer source.

Store Progress Photos in object storage with metadata, view type, owner, and taken time. Apply consent and sensitive-media access rules.

## Progress Engine

Calculate normalized signals before AI use: current valid value, rolling trend, rate, variance, Goal progress, completion, schedule adherence, training frequency/continuity, inactivity, and nutrition adherence with completeness.

Separate raw readings from trends and current-Goal progress from lifetime progress. One point is not a trend. Do not infer muscle/fat change from weight alone.

Produce traceable Coaching Attention Signals with type, Student, severity/priority, detection time, status, and evidence. Deterministic alerts such as missed workout, overdue check-in, inactivity, and conflict are not AI recommendations.
