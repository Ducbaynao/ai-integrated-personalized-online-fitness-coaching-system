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
2. **Storage-First Backup Ordering:** S3 media objects are synchronized and checksummed *before* capturing the final PostgreSQL transaction snapshot. Any binary upload committed in the database snapshot is guaranteed to have its corresponding file already stored and checksummed in the backup archive, preventing dangling media pointers.
3. **Target Protection Guard:** `tools/restore-system.ps1` includes an automated destination guard. Destructive operations (`DROP DATABASE`) targeting the primary database of record (`digital_fitness`) are strictly blocked unless an explicit `-Force` authorization switch is provided.

---

## 3. Operational Tooling

Automated, deterministic operational scripts are located in `tools/`:

### A. Backup Script (`tools/backup-system.ps1`)
Dumps PostgreSQL database in PostgreSQL custom archive format (`-F c -b -v`), extracts schema structure SQL, syncs all objects from S3 media bucket, computes SHA-256 checksums, and outputs a signed manifest. If any step fails, the partial directory is cleanly purged to prevent corrupt backups.

```powershell
powershell -ExecutionPolicy Bypass -File tools/backup-system.ps1 `
    -ContainerName "digital-fitness-postgres" `
    -DbName "digital_fitness" `
    -DbUser "fitness_app" `
    -S3Endpoint "http://localhost:9000" `
    -S3Bucket "fitness-media"
```

**Artifacts Generated in `database/backup/backups/<timestamp>/`:**
- `database.dump`: Compressed PostgreSQL binary custom-format dump.
- `schema_structure.sql`: Plaintext DDL schema definitions for human inspection.
- `storage/<key>`: Mirrored directory tree of S3 object files.
- `manifest.json`: Metadata manifest containing:
  - Timestamp, consistency model (`storage-first-snapshot`)
  - Applied Flyway schema version
  - SHA-256 checksum of `database.dump`
  - List of all storage objects with file sizes and SHA-256 checksums.

### B. Restore Script (`tools/restore-system.ps1`)
Performs pre-restore manifest checksum verification, re-creates or initializes the target database, runs `pg_restore`, verifies Flyway schema history parity and table count, and uploads/verifies object storage files with round-trip SHA-256 validation.

```powershell
powershell -ExecutionPolicy Bypass -File tools/restore-system.ps1 `
    -BackupDir "database/backup/backups/<timestamp>" `
    -TargetDbName "digital_fitness_restore_test" `
    -TargetS3Bucket "fitness-media-restore-test"
```

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
