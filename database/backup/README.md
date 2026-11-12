# Database and Storage Backup and Recovery

This document defines the backup policy, recovery procedures, operational scripts, and drill verification records for the Digital Fitness platform.

---

## 1. Objectives & Scope

| Objective | Target | Description |
| :--- | :--- | :--- |
| **RPO (Recovery Point Objective)** | < 1 hour | Maximum acceptable data loss period in an incident. |
| **RTO (Recovery Time Objective)** | < 30 minutes | Target timeframe to restore full database and object storage availability. |
| **Data Scope** | Database + S3 Storage | PostgreSQL relational database (`fitness` schema) and object storage bucket (`fitness-media`). |

---

## 2. Operational Tooling

Automated, deterministic operational scripts are located in `tools/`:

### A. Backup Script (`tools/backup-system.ps1`)
Dumps PostgreSQL database in PostgreSQL custom archive format (`-F c -b -v`), extracts schema structure SQL, syncs all objects from S3 media bucket, computes SHA-256 checksums, and outputs a signed manifest.

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
  - Timestamp & target environments
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

## 3. Security, Encryption & Retention

1. **At-Rest Encryption:** Backups stored in production cloud environments must be encrypted with AES-256 using managed KMS keys before transmission or persistence.
2. **Access Control:** Backup files and S3 buckets contain sensitive student and trainer verification media and must be restricted to platform operational roles with audit logging enabled.
3. **Retention Policy:**
   - Hourly transaction log / WAL archives: Retained for 7 days.
   - Daily full snapshots: Retained for 30 days.
   - Monthly long-term snapshots: Retained for 12 months for compliance.
4. **Git Safety:** Real database dumps, sensitive media files, and production backups must never be committed to Git. All directories under `database/backup/backups/` are gitignored.

---

## 4. Restore Drill Verification Record (2026-11-12)

A full backup and restore drill was executed on 2026-11-12 in an isolated test environment.

### Test Environment
- **Database Engine:** PostgreSQL 18.6 (Debian) with pgvector extension (`digital-fitness-postgres`, port 5433).
- **Object Storage:** Adobe S3Mock (`local-s3`, port 9000).
- **Tooling:** `tools/backup-system.ps1`, `tools/restore-system.ps1`.

### Execution Summary
1. **Backup Phase:**
   - Database dumped: 268 tables in `fitness` schema, Flyway version V58.
   - Object storage dumped: `verification-docs/trainer_101/cert.pdf` (54 bytes, SHA-256: `fc3258b9904960bee6193e6db3560bcc78349531e8214b6587677c7cfaf43909`).
   - Manifest generated and validated.
2. **Verification & Restore Phase:**
   - Restored into isolated database `digital_fitness_restore_test`.
   - Verified Flyway schema version: V58 matches backup.
   - Verified schema table count: 268 tables verified.
   - Restored into bucket `fitness-media-restore-test`.
   - Re-downloaded restored file from bucket and verified SHA-256 hash match (`fc3258b9904960bee6193e6db3560bcc78349531e8214b6587677c7cfaf43909`).
3. **Outcome:** PASSED with 100% data parity and zero corruption.
