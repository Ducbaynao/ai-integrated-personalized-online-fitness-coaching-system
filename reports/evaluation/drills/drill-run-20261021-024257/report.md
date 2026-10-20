# F01 Reindex & Rollback Drill Execution Summary

- **Run ID:** drill-f01-20261021-024257
- **Branch:** chore/f01-reindex-rollback-drill
- **Base Commit:** 1f01da75bd83de3f37a6987b04264f805be75a20
- **Execution Window:** 2026-10-20T19:43:11.4598516Z to 2026-10-20T19:43:36.0458547Z (24.59s)
- **Isolation Target:** Port 5435, DB digital_fitness_drill, Container digital-fitness-postgres-drill
- **Execution Mode:** Offline simulated test double (0 calls to real OpenAI API, $0 spend)
- **Overall Status:** **PASSED** (5/5 scenarios verified)

## Scenario Results

| Scenario | Description | Status | Observed Interruption | Recovery Mechanism |
| :---: | :--- | :---: | :---: | :--- |
| **A** | Idempotent Reindex | **PASS** | Retrieval before/after verified; continuous downtime: NOT_MEASURED | Native outbox reindexing |
| **B1** | Boundary 2 Fault Injection (Simulated 503) | **PASS** | Retrieval before/after verified; continuous downtime: NOT_MEASURED | Automatic rollback, prior index intact |
| **B2** | Boundary 3 Claim Fencing Rejection | **PASS** | Retrieval before/after verified; continuous downtime: NOT_MEASURED | Transaction rollback, zero unready commit |
| **C** | Rollback after vector replacement (DEC-A13-05) | **PASS** | Availability lost until recovery; continuous downtime: NOT_MEASURED | Outbox reindex (~0.12s) or Table-level restore (~0.03s) |
| **D** | Citation history & provenance preservation | **PASS** | N/A | Immutability guard, historical chunk retention |
