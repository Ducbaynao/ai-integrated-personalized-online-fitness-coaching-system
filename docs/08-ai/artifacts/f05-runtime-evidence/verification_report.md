# F05 Workout Rescheduling — HTTP Multi-Tier Runtime Verification Report

**Date**: 2026-11-16  
**Branch**: `feat/p2-m4-f05-rescheduling`  
**Milestone**: Phase 2 Milestone 4 (P2-M4) / Scenario T10 / Decision D05  
**Evidence Artifact**: [`runtime_verification_evidence.json`](./runtime_verification_evidence.json)  
**Verification Test**: `services/backend/src/test/java/com/fitnesscoaching/platform/AiWorkoutRescheduleRealFastApiIntegrationTest.java`

---

## 1. Executive Summary

This report documents the end-to-end multi-tier HTTP runtime verification for **F05 Workout Rescheduling**.
The verification exercised the full live service call chain across all layers:

$$\text{HTTP MockMvc Client} \longrightarrow \text{Spring Boot Application} \longrightarrow \text{Real FastAPI AI Service (Uvicorn)} \longrightarrow \text{Spring Boot Application} \longrightarrow \text{Workout Domain Module} \longrightarrow \text{PostgreSQL (Testcontainers)}$$

All **5 live runtime scenarios** passed without regression, validating proposal generation, contract serialization, proposal reading, atomic domain application, idempotent replay, proposal rejection, optimistic locking conflict detection, and role-based authority boundaries.

---

## 2. Architecture & Components Under Test

| Component | Technology | Role in Test | Verified Boundary |
| :--- | :--- | :--- | :--- |
| **HTTP Client** | Spring MockMvc | Generates HTTP REST requests with Bearer JWT / Internal service tokens | Endpoints `/api/v1/ai/reschedule-workout/*` |
| **Backend Core** | Spring Boot 3.3.4 (Java 21) | Orchestrates AI proposal lifecycle, validation, authority checks, and domain commands | Modular monolith boundary (`ai` $\to$ `workout` via `WorkoutScheduleChangeUseCase`) |
| **AI Microservice** | FastAPI / Python 3.12 (Uvicorn) | Real external process launched on dynamic port with `INTERNAL_SERVICE_KEY` | Schema serialization, contract mapping, reason generation |
| **Database** | PostgreSQL 16 (Testcontainers) | System of record executing real Flyway migrations (V1–V10) | Atomic row locks, status transitions, foreign keys, audit logs |

---

## 3. Verified Scenarios & Test Results

| # | Scenario | Tested Path & Invariants | Result |
| :---: | :--- | :--- | :---: |
| **1** | **Full E2E Flow** | `POST /generate` $\to$ `GET /{id}` $\to$ `POST /accept` $\to$ Domain DB Readback | **PASS** |
| **2** | **Idempotent Replay** | Identical `commandKey` on `POST /accept` returns cached receipt (`replayed=true`) without duplicate replacement | **PASS** |
| **3** | **Proposal Rejection** | `POST /reject` updates proposal to `REJECTED`, leaves original occurrence `SCHEDULED`, 0 replacements | **PASS** |
| **4** | **Optimistic Version Conflict** | Stale occurrence version triggers HTTP 409 Conflict with full rollback | **PASS** |
| **5** | **Authorization Guard** | Student in `HUMAN_COACH` mode attempting unilateral accept triggers HTTP 403 Forbidden | **PASS** |

### Execution Metrics
- **Test Class**: `AiWorkoutRescheduleRealFastApiIntegrationTest`
- **Total Tests**: 5
- **Failures / Errors**: 0
- **Duration**: ~31.7 seconds

---

## 4. Key Assertions & Proof of Invariants

### 4.1. Domain State Transition & Provenance
From `runtime_verification_evidence.json`:
- **Original Occurrence (`b1ec8cfe-...`)**: Status transitioned from `SCHEDULED` $\longrightarrow$ `RESCHEDULED`.
- **Replacement Occurrence (`f24cab2c-...`)**: Created with status `SCHEDULED`, planned at `2026-11-19T02:00:00Z`, linked back via `rescheduled_from_id`.
- **Application Record**: Recorded in `fitness.ai_recommendation_applications` with `resulting_entity_type = WORKOUT_OCCURRENCE` and `resulting_entity_id = f24cab2c-...`.
- **Audit Log**: Recorded in `fitness.audit_logs` tracking user `526e1cd8-...` and action `APPLY_RESCHEDULE`.

### 4.2. Contract & Schema Alignment
- AI microservice input schema `F05WorkoutRescheduleRequest` accepts Spring Boot payload (`ConfigDict(extra="ignore")`).
- Supervision requirement mapped deterministically from DB enum `SELF_ALLOWED` to AI contract enum `SELF_PERFORMABLE`.
- TTL for proposal set to 30 minutes (`expiresAt = createdAt + 30m`).

---

## 5. Conclusion & Handover Status

The F05 Workout Rescheduling capability has achieved full multi-tier HTTP runtime verification. The implementation guarantees:
1. No two-phase commit or cross-system inconsistency.
2. Complete auditability and provenance retention.
3. Strict adherence to coaching authority rules (`HUMAN_COACH` vs `SELF_DIRECTED`).
4. Robust optimistic locking and replay protection.
