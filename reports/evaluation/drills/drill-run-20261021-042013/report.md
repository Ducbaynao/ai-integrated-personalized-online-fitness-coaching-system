# F01 Reindex & Rollback Drill Execution Summary

- **Run ID:** drill-f01-20261021-042013
- **Branch:** fix/f01-rollback-drill-safety
- **HEAD Commit:** 3a2d484518c6ddf6e64da65e26df1140be015e0d
- **Historical Baseline Reference:** 1f01da75bd83de3f37a6987b04264f805be75a20
- **Execution Window:** 2026-10-20T21:20:13.6638576Z to 2026-10-20T21:20:52.1627073Z (38.5s)
- **Timing Breakdown:**
  - Infra Setup: 5.77s
  - Flyway Migrations: 8.28s
  - Maven Command: 24.06s
  - Surefire Suite: 15.893s
  - Outbox Reindex Recovery (measured): 0.033s
  - Table-level Restore Recovery (measured): 0.016s
  - Continuous Retrieval Downtime: NOT_MEASURED
- **Isolation Target:** Port 5435, DB digital_fitness_drill, Container digital-fitness-postgres-drill
- **Execution Mode:** Offline simulated test double (0 calls to real OpenAI API, $0 spend)
- **Overall Status:** **PASS** (5 tests run, 0 failures, 0 errors, 0 skipped)

## Scenario Results

| Scenario | Description | Status | Observed Interruption | Recovery Mechanism | Measured Recovery Duration |
| :---: | :--- | :---: | :---: | :--- | :---: |
| **A** | Idempotent Reindex | **PASS** | Retrieval before/after verified; continuous downtime: NOT_MEASURED | Native outbox reindexing | N/A |
| **B1** | Boundary 2 Fault Injection (Simulated 503) | **PASS** | Retrieval before/after verified; continuous downtime: NOT_MEASURED | Automatic rollback, prior index intact | N/A |
| **B2** | Boundary 3 Claim Fencing Rejection | **PASS** | Retrieval before/after verified; continuous downtime: NOT_MEASURED | Transaction rollback, zero unready commit | N/A |
| **C** | Rollback after vector replacement (DEC-A13-05) | **PASS** | Availability lost until recovery; continuous downtime: NOT_MEASURED | Outbox reindex or Table-level restore | Reindex: 0.033s / Restore: 0.016s |
| **D** | Citation history & provenance preservation | **PASS** | N/A | Immutability guard, historical chunk retention | N/A |
