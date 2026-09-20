# Development setup

## 1. Configure environment

Copy `.env.example` to `.env` and replace development credentials. Note that `.env.example` is only a template; local `.env` is ignored by Git and must never be committed to the repository.

For any non-`dev` backend profile, set `JWT_SECRET` to an application secret containing at least 32 UTF-8 bytes. Do not commit a production JWT secret. `ACCESS_TOKEN_TTL` and `REFRESH_TOKEN_TTL` use ISO-8601 duration syntax, for example `PT15M` and `P30D`. The local `dev` profile supplies an explicitly development-only JWT secret so the quick-start command remains reproducible.

## 2. Install JavaScript dependencies

From the repository root:

```powershell
npm install
npm install --prefix apps/mobile
```

## 3. Start infrastructure

```powershell
docker compose up -d
docker compose ps
```

Local infrastructure services:
- PostgreSQL: `localhost:5433` (database: `digital_fitness`)
- Redis: `localhost:6379`
- MinIO API: `localhost:9000`, Console: `localhost:9001`
- Mailpit Web UI: `http://localhost:8025`, SMTP: `localhost:1025` (captures development verification emails safely)

## 4. Start Mobile

Configure API endpoint in `apps/mobile/.env` (or pass `EXPO_PUBLIC_API_BASE_URL`):
- Android Emulator: `EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:8080/api/v1`
- iOS Simulator / Web: `EXPO_PUBLIC_API_BASE_URL=http://127.0.0.1:8080/api/v1`
- Physical Device (same Wi-Fi): `EXPO_PUBLIC_API_BASE_URL=http://<YOUR_LAN_IP>:8080/api/v1`

Deep linking scheme `ai-fitness-coaching` routes email verifications:
`ai-fitness-coaching://verify-email?token=<token>`

```powershell
npm run dev:mobile
```

After moving the existing Expo application into the workspace, clear Metro once if dependency resolution is stale:

```powershell
cd apps\mobile
npx expo start --clear
```

## 5. Start Admin Web

```powershell
npm run dev:admin
```

## 6. Start Backend

Docker Compose automatically reads `.env` to configure PostgreSQL credentials. However, running Spring Boot directly via Maven does not automatically parse the root `.env` file, which can lead to authentication mismatches (`FATAL: password authentication failed for user "fitness_app"`).

To prevent this, use the recommended development launcher script, which reads the root `.env`, validates that required credentials exist, and securely exports only allowlisted variables (`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `BACKEND_PORT`) without printing secrets:

Windows (PowerShell) (Recommended):
```powershell
.\services\backend\run-dev.ps1
```

Unix / macOS (Recommended):
```bash
bash ./services/backend/run-dev.sh
```

The `dev` profile explicitly activates the development verification-email adapter (`DevelopmentVerificationEmailSender`), which dispatches verification emails via SMTP to Mailpit alongside a development-only JWT signing secret. The verification token is visible in Mailpit (`http://localhost:8025`) for development testing and deep linking, but plaintext tokens are strictly never written to backend application logs. Default and production profiles intentionally fail fast if `JWT_SECRET` is missing/short or if no production `VerificationEmailPort` is configured. Milestone M1A/M1B is not production-email-ready (durable outbox delivery, persistent retry queues, and third-party email provider integration are deferred to subsequent milestones).

## 7. Start AI Service

```powershell
cd services\ai-service
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\python.exe -m fastapi dev app\main.py
```
