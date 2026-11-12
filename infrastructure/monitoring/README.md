# Infrastructure Health & Monitoring

This document details the minimal health check indicators, liveness/readiness probes, metrics endpoints, and failure detection signals for the Digital Fitness platform.

---

## 1. Spring Boot Actuator Endpoints

The backend exposes health and operational probes under `/actuator`:

| Endpoint | Purpose | Access Rule | Details |
| :--- | :--- | :--- | :--- |
| `/actuator/health` | Overall aggregate health | Public / Load Balancer | Aggregates DB, S3 Storage, Antivirus Scanner, and Disk. |
| `/actuator/health/liveness` | Kubernetes Liveness Probe | Public | Indicates whether the JVM is alive and processing requests. |
| `/actuator/health/readiness` | Kubernetes Readiness Probe | Public | Indicates whether the service is ready to accept user traffic. |
| `/actuator/info` | Application metadata | Public / Operations | Build version, environment, and commit details. |
| `/actuator/metrics` | Micrometer metrics | Operations / Prometheus | JVM memory, thread pool, HTTP latency, and custom metrics. |

Configuration in `application.yml`:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
```

---

## 2. Component Health Indicators

### A. Database Health (`db`)
- **Implementation:** Standard Spring Boot DataSource probe (`isValid()`).
- **Target:** PostgreSQL 18 with pgvector (`jdbc:postgresql://.../digital_fitness`).
- **Status Mapping:**
  - `UP`: Connection pool active, test query succeeds.
  - `DOWN`: PostgreSQL unreachable, credentials invalid, or pool exhausted.

### B. Object Storage Health (`storage`)
- **Implementation:** `com.fitnesscoaching.platform.common.health.StorageHealthIndicator`
- **Probe Logic:** Performs lightweight `headBucket` check on target S3 bucket using configured credentials and endpoint.
- **Status Mapping:**
  - `UP`: S3 endpoint reachable, bucket exists and credentials have read/write access.
  - `DOWN`: S3 service down, network timeout, bucket missing (404), or access denied (403).
  - `UNKNOWN`: Storage endpoint/bucket is not configured (e.g. in local mocked unit test profile).

### C. Antivirus Scanner Health (`scanner`)
- **Implementation:** `com.fitnesscoaching.platform.common.health.ScannerHealthIndicator`
- **Probe Logic:** Opens a direct TCP socket to ClamAV daemon (`clamd`), transmits `zPING\0`, and expects `PONG\0` within 3000ms.
- **Status Mapping:**
  - `UP`: ClamAV daemon responding with `PONG`.
  - `DOWN`: ClamAV daemon down, port closed, or connection timed out.
  - `UNKNOWN`: ClamAV host not configured.

---

## 3. Fail-Closed Security Posture

In accordance with platform security invariants:
1. **Malware Detection:** Files uploaded containing malicious signatures (e.g. EICAR test string) are rejected immediately with HTTP 400 `MALWARE_DETECTED`.
2. **Scanner Outage:** If ClamAV is down or unreachable, the system fails closed: document and media upload operations are blocked and return HTTP 503 `SCANNER_UNAVAILABLE`. Uploads are never permitted to bypass malware scanning.
3. **Storage Outage:** If S3 object storage is unavailable, operations return HTTP 503 `STORAGE_UNAVAILABLE`. Local storage is never used as an insecure in-memory fallback.

---

## 4. Verification & Testing

Health indicator functionality is covered by regression unit and integration tests:
- `com.fitnesscoaching.platform.common.health.StorageAndScannerHealthIndicatorUnitTest` (4/4 PASS)
- `com.fitnesscoaching.platform.InputErrorHandlingIntegrationTest` (Health endpoint component assertions PASS)
- `com.fitnesscoaching.platform.modules.trainer.application.service.LiveStorageAndScannerHardeningIntegrationTest` (Fail-closed live socket & S3 assertions PASS)
