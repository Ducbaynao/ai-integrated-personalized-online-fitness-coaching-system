# Progress photo QA readiness

This is Phase 1 M5 maintenance of ST-09 (Student Progress Dashboard), owned by
the Measurement module. The Student owns the photo; Trainer reads/writes still
depend on the existing PROGRESS_PHOTOS sharing scope and time window.

## Failure and diagnosis

The 2026-11-27 QA launcher omitted media configuration and no scanner/S3 service
was running. An authenticated JPEG POST reproduced HTTP 503
`SCANNER_UNAVAILABLE`, request ID `02b635ce-67ff-4a0d-9499-0f85ee279610`.
The scanner was unconfigured, rather than rejecting the image. Storage could
not yet be reached because scanning precedes storage. Restoring both dependencies
and configuring the launcher made the same synthetic JPEG upload succeed.
No backend change was required.

Trace: `UploadProgressPhotoModal` → `measurementApi.uploadProgressPhoto` →
`ProgressPhotoController` → `ProgressPhotoStorageService`: authorize, validate
JPEG/PNG bytes and 10 MiB limit, ClamAV INSTREAM, S3 PUT, then transactional
`media_files` / `progress_photos` metadata. Metadata is PRIVATE/CLEAN only after
a real clean verdict and successful storage. Reads use the authorized backend
file endpoint, including sharing time windows.

`chat.attachments.*` is deliberately shared by progress photos, Trainer
verification and other media. Its environment inputs are:

| Input | Host-backend QA value / handling |
| --- | --- |
| `CHAT_CLAMD_INTERNAL_HOST` | `127.0.0.1` |
| `CHAT_CLAMD_INTERNAL_PORT` | `13310` |
| `CHAT_S3_ENDPOINT` | `http://127.0.0.1:18333` |
| `CHAT_S3_PRIVATE_BUCKET` | `progress-photo-qa-private` |
| `CHAT_S3_ACCESS_KEY` | Read from the local QA secret file; never log |
| `CHAT_S3_SECRET_KEY` | Read from the local QA secret file; never log |

A containerized backend needs the QA network/service hostname and container
port instead of the host loopback bindings. Do not change shared production
media configuration to fix a local launcher.

Distinguish failures before changing code:

- Blank host, wrong host/port, connection refusal, timeout or unexpected scanner
  reply: fail closed with `SCANNER_UNAVAILABLE`. A running container alone does
  not establish readiness. Startup logs may show database loading/updating.
- A real `FOUND` verdict: validation rejection, not service unavailability.
- A clean scan followed by missing endpoint/bucket/credentials, access denial,
  missing bucket or storage connection failure: `STORAGE_UNAVAILABLE`.
- Capture status, structured error code and request ID. Keep operational detail,
  credentials, object keys, raw files and sensitive URLs out of public evidence.

## Start only isolated QA media

Check host/Docker free RAM, disk and existing listeners first (`Get-PSDrive`,
`Get-CimInstance Win32_OperatingSystem`, `docker stats --no-stream`,
`Get-NetTCPConnection -State Listen`). Leave room for ClamAV's loaded database,
backend and the current test process. Run heavy checks sequentially. Never
prune, remove volumes, start the whole compose stack, or touch `cvat_*`/other DBs.

The existing `minio/minio:latest` compose image could not be pulled in this QA
session. The available SeaweedFS image was used as a real S3 service instead.
The tested image digests were:

- ClamAV: `clamav/clamav@sha256:3bba6dd5ac6663d55b80f86a3126e146612d93b593cc2617188d13f637d91260`
- SeaweedFS: `chrislusf/seaweedfs@sha256:c2658820e763294a9e9de7b36c81e59b879e3459148351aee4e31a126ca64860`

Use a local state directory outside Git. Generate independent random QA S3
credentials and write `s3.local.json` with this structure (substitute secrets
locally, never paste populated JSON into evidence):

```json
{"identities":[{"name":"photo-qa","credentials":[{"accessKey":"LOCAL_QA_ACCESS_KEY","secretKey":"LOCAL_QA_SECRET_KEY"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
```

This identity administers only this isolated QA S3 instance. Do not configure
an anonymous identity, public bucket policy or public ACL. Create `storage-data`
in that local directory. Example PowerShell startup, after confirming names and
ports are free (do not remove an existing container to reuse its name):

```powershell
$qaState = (Resolve-Path -LiteralPath 'D:/codex-work/progress-photo-qa').Path
docker run -d --name progress-photo-qa-clamd-20261127 --label codex.task=progress-photo-upload --memory=1800m -p 127.0.0.1:13310:3310 clamav/clamav@sha256:3bba6dd5ac6663d55b80f86a3126e146612d93b593cc2617188d13f637d91260
docker run -d --name progress-photo-qa-s3-20261127 --label codex.task=progress-photo-upload --memory=256m -p 127.0.0.1:18333:8333 --mount "type=bind,source=$qaState/s3.local.json,target=/etc/seaweedfs/s3.json,readonly" --mount "type=bind,source=$qaState/storage-data,target=/data" chrislusf/seaweedfs@sha256:c2658820e763294a9e9de7b36c81e59b879e3459148351aee4e31a126ca64860 server -dir=/data -ip=127.0.0.1 -ip.bind=0.0.0.0 -s3 -s3.iam=false -s3.config=/etc/seaweedfs/s3.json -s3.port.iceberg=0 -s3.port.lance=0 -master.volumeSizeLimitMB=64 -volume.max=16
```

The volume limit allows SeaweedFS's normal collection growth, including its
internal metadata collection. A limit of two volumes caused `No writable
volumes` during setup. Only S3 is published to host loopback; the filer, volume
and master endpoints are not published. Authentication remains enabled by the
S3 identity configuration (`s3.iam=false` disables the separate IAM API).

Before any photo POST:

1. Wait for database loading to finish. Require `clamdscan --ping 1` to return
   PONG, then send a real JPEG/PNG through INSTREAM and require `stream: OK`.
2. Using the configured credentials, explicitly create the private bucket.
   Require authenticated PUT/GET with exact byte comparison and require
   anonymous GET of that same object to return 403. A listening port is
   insufficient; test the data plane after every service restart.
3. Set all six variables above in the actual backend launch environment.
   Confirm the port owner PID and jar, and confirm the datasource points to
   `f06-mobile-qa-20261124` / `f06qa` on `127.0.0.1:15486` before restarting
   only backend QA. Retain DB credentials, schema and existing data.
4. Repeat the authenticated HTTP POST and authorized file GET. Check the
   inserted rows and S3 bytes, not only the UI message.

## Client behavior and verification limits

`progressPhotoErrors.ts` maps photo scanner/storage errors separately from
Trainer verification's `displayLabels.ts` copy. Unknown server details are
not rendered. Failure keeps the selected image, view, weight and notes in the
same open form, releases loading and permits explicit retry. A synchronous
submission guard blocks repeated presses. Closing the modal drops its draft;
account/capability/subject changes remount dashboard state. Pending picker and
upload completions cannot close or update a newer form. Upload POST is bound
to the session before reading the local file and is not replayed on 401.

The existing POST contract has no idempotency key. Known pre-persistence 503
failures create zero photos; one explicit successful retry creates one photo.
This does not establish deduplication after an ambiguous lost success response.

Sanitized HTTP evidence is in [progress-photo-upload-20261127](evidence/progress-photo-upload-20261127/).
Real tests used non-person generated JPEG/PNG images. An embedded ZIP containing
the standard EICAR test signature produced a real ClamAV FOUND verdict and HTTP
400 with no new photo. A text string merely appended to a JPEG is not an
equivalent antivirus rejection fixture.

Jest uses mocks; backend integration tests use real PostgreSQL/Testcontainers
with scanner/S3 mocked. These are distinct from the real ClamAV/S3/HTTP/QA DB
checks. Backend QA ran the existing `5049b16` jar: the relevant Measurement,
error handling and configuration source is identical to this task's `cc8d1e9`
base. No backend source or authorization/media lifecycle changes are included.

Browser validation did not complete: the first localhost navigation occurred
before the dedicated Metro listener was ready (`ERR_CONNECTION_REFUSED`);
subsequent control was blocked by Browser Use URL policy on its generated
`data:text/html` error page. No alternate control tool was used to bypass this.
Selecting/uploading in browser, gallery display and browser console/network
inspection are therefore unverified. Android/emulator was not run. ST-09's
existing gallery currently renders photo metadata cards; thumbnail rendering
was outside this upload/error change.

## Local handoff and stop procedure

Runtime/evidence/secret files remain outside Git in
`D:/codex-work/progress-photo-qa`. Do not publish `*.local.json`, raw fixtures or
backend logs. The wrapper `run-backend.ps1` loads the six media variables and
calls the existing QA launcher; it keeps the original Mailpit/AI/CORS/DB values.
The original launcher itself is unchanged.

Services retained for manual QA:

| Resource | Scope |
| --- | --- |
| `progress-photo-qa-clamd-20261127` | host loopback 13310 → clamd 3310 |
| `progress-photo-qa-s3-20261127` | host loopback 18333 → S3 8333; persistent local state |
| Backend QA PID 8592 | port 18080, existing QA jar, database `f06qa` |
| Dedicated Metro | port 15173, this checkout, `run-web.ps1` |
| Existing Metro | port 18081, separate QR patch, unchanged |

When the owner finishes checking, stop just the two named media containers with
`docker stop progress-photo-qa-clamd-20261127 progress-photo-qa-s3-20261127`.
For backend/Metro, re-read the current port owner and command line before
stopping the exact matching PID; the PID can change after restart. Do not stop
18081, delete state/volumes, or stop the QA DB. Restart with the same local
state/credentials and repeat scanner and storage data-plane readiness checks.
