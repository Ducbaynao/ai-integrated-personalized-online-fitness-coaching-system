# Development setup

## 1. Configure environment

Copy `.env.example` to `.env` and replace development credentials.

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

Windows:
```powershell
cd services\backend
$env:SPRING_PROFILES_ACTIVE="dev"
.\mvnw.cmd spring-boot:run
```

Unix:
```bash
cd services/backend
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

The `dev` profile explicitly activates the development verification-email adapter (`DevelopmentVerificationEmailSender`), which dispatches emails via SMTP to Mailpit, alongside a development-only JWT signing secret. Default and production profiles intentionally fail fast if `JWT_SECRET` is missing/short or no real `VerificationEmailPort` is configured. The development adapter masks recipient email addresses and does not expose or log plaintext verification tokens. Milestone M1A/M1B is not production-email-ready (durable outbox delivery, persistent retry queues, and third-party email provider integration are deferred to subsequent milestones).

## 7. Start AI Service

```powershell
cd services\ai-service
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\python.exe -m fastapi dev app\main.py
```
