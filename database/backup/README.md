# Database and Storage Backup and Recovery

This document defines the backup policy, recovery procedures, operational scripts, consistency model, and drill verification records for the Digital Fitness platform.

---

## 1. Objectives & Scope

| Objective | Target | Description |
| :--- | :--- | :--- |
| **RPO (Recovery Point Objective)** | < 1 hour | Maximum acceptable data loss period in an incident. |
| **RTO (Recovery Time Objective)** | < 30 minutes | Target timeframe to restore full database and object storage availability. |
| **Data Scope** | Database + S3 Storage | PostgreSQL relational database (`fitness` schema) and object storage bucket (`fitness-media`). |

---

## 2. Consistency & Ordering Model

In a distributed architecture with relational state in PostgreSQL and binary media in S3:
1. **Consistency Isolation:** `pg_dump` runs with transaction snapshot isolation, capturing an immutable point-in-time state of all relational tables and sequences.
2. **Storage-First Backup with Post-Dump Catch-Up Reconcile:**
   - **Step 1 (Initial Sync):** S3 media objects are synchronized and checksummed *before* capturing the PostgreSQL transaction snapshot.
   - **Step 2 (Snapshot Dump):** PostgreSQL is dumped via `pg_dump` capturing point-in-time relational state.
   - **Step 3 (Post-Dump Snapshot-Only Integrity Reconcile):** `tools/backup-system.ps1` extracts referenced storage keys **strictly from the captured database dump file** using `pg_restore -a -t media_files -f -`. Live database querying during reconcile is completely prohibited to eliminate race conditions with concurrent post-snapshot transactions.
   - **Strict Parser Integrity:** The parser validates the `pg_restore` exit code, enforces recognition of the exact `COPY fitness.media_files (...) FROM stdin;` header with all required columns (`object_key`, `scan_status`, `deleted_at`), and verifies the trailing terminating `\.` line to detect truncated blocks. Valid snapshots with 0 rows are parsed successfully without live DB querying.
   - **Default Fail-Fast on Missing Objects:** If any object referenced by the database snapshot is missing from storage, the script aborts immediately with a `REFERENTIAL INTEGRITY VIOLATION` error and non-zero exit code. Incomplete backups are only permitted when explicitly requested via `-AllowIncompleteStorage`, which tags the manifest with `status: "INCOMPLETE"` and records `missing_referenced_objects`.
   - **Metadata Verification:** For every referenced object with recorded size and checksum in the database snapshot, the script validates byte size and SHA-256 hash against the actual storage artifact. Any mismatch triggers an immediate `DATA INTEGRITY CORRUPTION` abort.
   - **Manifest Consistency Model:** Recorded as `storage-first-snapshot-with-post-dump-reconcile`.
3. **Atomic Two-Phase Publication, Exclusive Publication Lock & Scoped Staging:**
   - All intermediate operations execute in an isolated, unique staging directory (`.staging_<leaf>_<runId>`).
   - **Exclusive Publication Lock & Sentinel Lifecycle:** Prior to committing staging to `$OutputDir`, the process establishes an exclusive publication lock file (`.publish_lock_<leaf>.lock`) in the parent directory using `[System.IO.FileStream]` with `FileShare::None`. This lock is cross-platform, atomic, and held throughout destination re-validation, preexisting backup archiving, staging publication, structure verification, and any required rollback. Competing processes are immediately rejected with `PUBLICATION LOCK CONTENTION`.
     - *Lock Sentinel Preservation:* Upon backup completion or failure, the process releases exclusive access solely by disposing its own stream handle (`$lockStream.Dispose()`). The lock file on disk is **intentionally preserved** as a persistent, idempotent lock sentinel across runs. In POSIX/Linux systems, deleting/unlinking an active lock file creates a critical race condition where subsequent processes recreate separate inodes at the same path, breaking cross-process exclusion. Preserving the sentinel guarantees safe, deterministic concurrency across all runs on both Windows and Linux without manual cleanup.
   - **Preflight & Publication Re-Check:** `$OutputDir` is re-validated at both preflight and publication time while holding the lock. Reparse points (symlinks and junctions) are strictly rejected with `SAFETY ABORT`.
   - **Existing Directory Protection:** If `$OutputDir` already exists and contains files, the backup aborts immediately unless an explicit `-Force` authorization switch is provided.
   - **Anti-Nesting Directory Move:** Staging publication and directory archiving use `[System.IO.Directory]::Move`, which is atomic at the OS level and guaranteed never to nest the source staging directory as a child of the target folder if the destination exists.
   - **Old Backup Preservation & Deterministic Rollback:** When `-Force` is provided and `$OutputDir` exists, the preexisting backup is moved to a temporary holding archive (`.replace_old_<leaf>_<runId>`). If publishing the staging directory fails, the script attempts rollback to restore the original backup. If rollback is obstructed (e.g. target path occupied), the script strictly preserves the old backup archive on disk, reports the exact recovery path, and never purges un-restored data. The old backup archive is purged only after the new backup is fully published and in-place validated.
   - **In-Place Structure Validation:** After moving staging, the script verifies that `manifest.json`, `database.dump`, and `storage/` are present, confirms that `manifest.run_id` matches the current run ID, and verifies that no `.staging_*` directory exists inside `$OutputDir`.
   - **Scoped Failure Cleanup:** Upon any error, cleanup is strictly confined to the staging directory of the current run ID. Sibling directories, preexisting target files, and parent directories are never modified or purged. Publication lock streams are reliably disposed in the `finally` block while preserving the on-disk sentinel.
4. **Target Protection & Incomplete Restore Guard:**
   - `tools/restore-system.ps1` includes an automated destination guard. Destructive operations (`DROP DATABASE`) targeting the primary database of record (`digital_fitness`) are strictly blocked unless an explicit `-Force` authorization switch is provided.
   - Restoring from an archive marked `status: "INCOMPLETE"` is blocked by default with a `SAFETY ABORT` error unless `-AllowIncomplete` is explicitly specified.

---

## 3. Operational Tooling

Automated, deterministic operational scripts are located in `tools/`:

### A. Backup Script (`tools/backup-system.ps1`)
Dumps PostgreSQL database in PostgreSQL custom archive format (`-F c -b -v`), extracts schema structure SQL, syncs all objects from S3 media bucket, computes SHA-256 checksums, and outputs a signed manifest. Intermediate work occurs in a scoped staging folder, published atomically upon complete verification.

```powershell
powershell -ExecutionPolicy Bypass -File tools/backup-system.ps1 `
    -ContainerName "digital-fitness-postgres" `
    -DbName "digital_fitness" `
    -DbUser "fitness_app" `
    -S3Endpoint "http://localhost:9000" `
    -S3Bucket "fitness-media" `
    -OutputDir "database/backup/backups/20261113_example"
```

**Parameters:**
- `-ContainerName`: Name of Docker container running PostgreSQL.
- `-DbName`: Database name.
- `-DbUser`: Database user.
- `-S3Endpoint`: S3 endpoint URL.
- `-S3Bucket`: S3 bucket to back up.
- `-OutputDir`: Target directory for the final verified backup.
- `-AllowIncompleteStorage`: *(Optional switch)* If specified, allows backup to succeed even if storage objects referenced in DB snapshot are missing; tags manifest with `status: "INCOMPLETE"`. Default is strict fail-fast (`REFERENTIAL INTEGRITY VIOLATION`).
- `-Force`: *(Optional switch)* Overwrites an existing non-empty `OutputDir` by preserving it during staging and purging only upon verified publication. Default is fail-safe abort.
- `-PrePublishHook`: *(Testing/Synchronization)* PowerShell expression executed after publication lock acquisition and before target re-validation.
- `-PublishFaultInjection`: *(Testing)* Injects controlled publication failures (`FAIL_MOVE_STAGING` or `BLOCK_ROLLBACK`) to verify atomic rollback and archive preservation.

**Artifacts Generated in `database/backup/backups/<timestamp>/`:**
- `database.dump`: Compressed PostgreSQL binary custom-format dump.
- `schema_structure.sql`: Plaintext DDL schema definitions for human inspection.
- `storage/<key>`: Mirrored directory tree of S3 object files.
- `manifest.json`: Metadata manifest containing:
  - Timestamp, run ID, status (`VERIFIED` or `INCOMPLETE`), consistency model (`storage-first-snapshot-with-post-dump-reconcile`)
  - Applied Flyway schema version
  - SHA-256 checksum of `database.dump`
  - List of all storage objects with file sizes and SHA-256 checksums
  - List of missing referenced objects (if any).

### B. Restore Script (`tools/restore-system.ps1`)
Performs pre-restore manifest checksum verification, re-creates or initializes the target database, runs `pg_restore`, verifies Flyway schema history parity and table count, and uploads/verifies object storage files with round-trip SHA-256 validation.

```powershell
powershell -ExecutionPolicy Bypass -File tools/restore-system.ps1 `
    -BackupDir "database/backup/backups/<timestamp>" `
    -TargetDbName "digital_fitness_restore_test" `
    -TargetS3Bucket "fitness-media-restore-test"
```

**Parameters:**
- `-BackupDir`: Path to verified backup directory containing `manifest.json`.
- `-TargetDbName`: Name of target database to restore into.
- `-TargetS3Bucket`: Name of target S3 bucket to restore media into.
- `-AllowIncomplete`: *(Optional switch)* Required when restoring an archive with `status: "INCOMPLETE"`.
- `-Force`: *(Optional switch)* Authorizes dropping the primary database `digital_fitness`.
- `-VerifyOnly`: Validates checksums without altering any database or bucket.

To run checksum validation without modifying any database or bucket:
```powershell
powershell -ExecutionPolicy Bypass -File tools/restore-system.ps1 `
    -BackupDir "database/backup/backups/<timestamp>" `
    -VerifyOnly
```

---

## 4. Security, Encryption & Retention

1. **At-Rest Encryption:** Backups stored in production cloud environments must be encrypted with AES-256 using managed KMS keys before transmission or persistence.
2. **Access Control:** Backup files and S3 buckets contain sensitive student and trainer verification media and must be restricted to platform operational roles with audit logging enabled.
3. **Retention Policy:**
   - Hourly transaction log / WAL archives: Retained for 7 days.
   - Daily full snapshots: Retained for 30 days.
   - Monthly long-term snapshots: Retained for 12 months for compliance.
4. **Git Safety:** Real database dumps, sensitive media files, and production backups must never be committed to Git. All directories under `database/backup/backups/` and `*.dump` are gitignored.

---

## 5. Restore Drill Verification Records

### A. Initial Restore Drill (V58 Baseline)
- **Date:** 2026-11-12
- **Environment:** PostgreSQL 18.6 (Debian) + Adobe S3Mock (`local-s3`, port 9000).
- **Outcome:** 268 tables restored, V58 schema parity, round-trip SHA-256 file match.

### B. Forward Upgrade Restore Drill (V59 Schema & Sequence Verification)
- **Date:** 2026-11-12
- **Environment:** Isolated Testcontainers PostgreSQL 18.6 container with pgvector (`digital_fitness_v59_drill`) + live S3 (`http://localhost:9000`).
- **Seeded Data:** Representative student user, legacy support case (`SUP-880000`), and V59 sequence-generated case via `support_case_number_seq`.
- **Restore Target:** `digital_fitness_v59_restored` and `fitness-media-v59-restored`.
- **Verified Invariants Post-Restore:**
  - Flyway version: **V59** (100% parity).
  - Representative records: User and both support cases intact.
  - Sequence continuity: `nextval('fitness.support_case_number_seq')` operates normally after restore without collision.
  - S3 Object integrity: Byte-for-byte SHA-256 match.

### C. Concurrent Write & Post-Dump Reconcile Drill (`tools/test-concurrent-backup-consistency.ps1`)
- **Date:** 2026-11-12
- **Environment:** Local PostgreSQL container (`digital-fitness-postgres`, port 5433) + live S3Mock (`local-s3`, port 9000).
- **Target Databases:** Baseline `df_concurrent_backup_source` -> Restored `df_concurrent_restored`.
- **Injected Invariant Hazard:** File A uploaded before storage sync. File B committed to PostgreSQL *during* the window between storage sync and `pg_dump` completion via `-PostStorageHook`.
- **Observed Behavior:** Step [3b] successfully caught File B, performed catch-up sync from S3, and generated manifest with 2 storage entries.
- **Restore Verification:** Both File A and File B present in restored database and S3 bucket (`fitness-media-drill-restored`), zero dangling references, exact SHA-256 parity confirmed.

### D. Comprehensive Snapshot Integrity & Publication Regression Drill (`tools/test-backup-regression-drills.ps1`)
- **Date:** 2026-11-13
- **Environment:** Local PostgreSQL (`digital-fitness-postgres`, port 5433) + S3Mock (`http://localhost:9000`).
- **Scenarios Tested (18/18 Passed):**
  1. *Standard Backup & Restore:* Verifies full schema (268 tables) and byte-for-byte storage object parity.
  2. *Concurrent Catch-up:* Simulates upload during dump window via `-PostStorageHook`, verifies catch-up sync and parity.
  3. *Missing File Fail-Fast & Incomplete Handling:* Strict failure without flag (`REFERENTIAL INTEGRITY VIOLATION`), success with `-AllowIncompleteStorage` producing `status: "INCOMPLETE"`, and restore safety guard aborting incomplete restores without `-AllowIncomplete`.
  4. *Container & Controlled pg_restore Failures:*
     - 4A: Non-existent container rejected at preflight.
     - 4B: Controlled corrupted dump at extraction step causes `pg_restore` failure; script aborts with `CRITICAL SNAPSHOT EXTRACTION ERROR` and purges staging cleanly without touching target.
  5. *Zero Referenced Files:* Empty storage snapshot properly processed with valid header and terminator without falling back to live DB.
  6. *Deterministic Post-Dump DB Mutation:* Using `-PostDumpHook` to insert a committed record after snapshot completion; verified that the record exists in live DB but is strictly absent from the dump and manifest.
  7. *OutputDir Protection & Junction Guard:*
     - 7A: Non-empty target directories safely rejected without `-Force`, canary file preserved.
     - 7B: Junction/symlink target directory rejected with safety abort.
  8. *Scoped Failure Cleanup:* Error during backup cleans only the transient `.staging_*` directory, leaving parent and sibling files untouched.
  9. *Publication Concurrency:*
     - 9A: Concurrent backups running simultaneously to separate target folders use isolated staging without conflict.
     - 9B: True concurrent publication collision targeting the SAME `$OutputDir` with barrier synchronization (`-PrePublishHook`): exactly one process wins publication lock and commits cleanly (exit code 0); competing process is safely rejected (`PUBLICATION LOCK CONTENTION` or `SAFETY ABORT`, exit code != 0), no staging nesting occurs, loser staging is cleaned without corrupting winner's backup, and lock sentinel is preserved.
  10. *Special Characters & UTF-8 Unescaping:* Escaped COPY sequences, UTF-8 Vietnamese filenames (`hồ sơ & ảnh 2026.pdf`), and brackets (`[1]`) correctly processed and restored.
  11. *Metadata Corruption Detection:* Size or SHA-256 mismatch between database snapshot metadata and storage object immediately detected as `DATA INTEGRITY CORRUPTION` and halted.
  12. *Snapshot Parser Failure Cases (AST Unit Drill):*
     - 12A: Missing COPY header throws `Valid COPY header for media_files was not found`.
     - 12B: Truncated COPY block (missing `\.`) throws `COPY block for media_files is truncated: missing terminating`.
     - 12C: Missing required column throws `COPY header missing required columns`.
     - 12D: Row with fewer columns than header throws `Row data has fewer columns`.
     - 12E: Valid header followed by `\.` (0 rows) successfully returns empty object list.
  13. *Publication Failure Rollback & Obstructed Recovery (Real Script Drill):*
     - 13A: Real script execution with `-PublishFaultInjection "FAIL_MOVE_STAGING"` triggers atomic rollback, restoring preexisting backup to target directory with 100% SHA-256 checksum parity.
     - 13B: Real script execution with `-PublishFaultInjection "BLOCK_ROLLBACK"` verifies that when target is obstructed, preexisting backup archive is safely preserved in `.replace_old_*` without deletion, maintaining full byte parity and logging critical operational recovery path.
  14. *Publication Lock Lifecycle & OS Crash Recovery Integration:* Validates 3-process lock contention, orderly stream disposal, and operating system lock release after abrupt process termination.

### E. Cross-Platform Publication Lock Lifecycle & OS Crash Recovery Drill (`tools/test-lock-lifecycle.ps1`)
- **Date:** 2026-11-13
- **Environments Verified:**
  1. **Windows Native:** Microsoft Windows NT 10.0.26200.0, Windows PowerShell 5.1 (`powershell.exe`).
  2. **Linux Container:** Linux 6.6.87.2, PowerShell Core 7.4.6 (`pwsh`, `pr155-linux-check:latest` container).
- **Phases Verified (100% Passing on Both Platforms):**
  - **Phase 1 (3-Process Contention & Orderly Release):**
    - Process A acquires exclusive publication lock file (`.publish_lock_target.lock`) via `FileStream(FileShare::None)`.
    - Process B attempts acquisition while A holds lock -> Rejected with `PUBLICATION LOCK CONTENTION`.
    - Process C attempts acquisition while A holds lock -> Rejected with `PUBLICATION LOCK CONTENTION`.
    - Process A orderly releases lock by disposing stream handle. Lock file sentinel remains on disk (`Test-Path: True`).
    - Subsequent process immediately acquires lock successfully.
  - **Phase 2 (Abrupt Termination / OS Crash Recovery):**
    - Background process acquires lock.
    - Process is abruptly killed via `Stop-Process -Force` (equivalent to `SIGKILL`).
    - Operating system kernel (both Windows NT kernel and Linux kernel) automatically reclaims file descriptor / handle table.
    - Subsequent run acquires lock immediately without manual cleanup or lock removal. Persistent lock sentinel on disk verified intact.
