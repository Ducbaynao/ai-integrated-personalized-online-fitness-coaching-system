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

## 4. Start Mobile

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
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

Unix:
```bash
cd services/backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile explicitly activates the simulated verification-email adapter (`DevelopmentVerificationEmailSender`) and a development-only JWT signing secret. Default and production profiles intentionally fail fast if `JWT_SECRET` is missing/short or no real `VerificationEmailPort` is configured. The simulated adapter masks recipient email addresses and does not expose or log plaintext verification tokens. Milestone M1A/M1B is not production-email-ready (durable outbox delivery, persistent retry queues, and third-party email provider integration are deferred to subsequent milestones).

## 7. Start AI Service

```powershell
cd services\ai-service
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\python.exe -m fastapi dev app\main.py
```
