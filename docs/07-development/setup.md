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

To prevent this, use the recommended development launcher script, which reads the root `.env`, validates that required credentials exist, and securely exports only allowlisted variables (database, backend port and the SMTP variables documented below) without printing secrets:

Windows (PowerShell) (Recommended):
```powershell
.\services\backend\run-dev.ps1
```

Unix / macOS (Recommended):
```bash
bash ./services/backend/run-dev.sh
```

The `dev` profile defaults to Mailpit and a development-only JWT secret. Mailpit is allowed only in `dev`. Non-dev deployments require `JWT_SECRET` and explicitly configured real SMTP; never enable `dev` in production. The test profile uses its test-only capturing adapter and cannot create a duplicate network sender.

### Real SMTP verification email

In the ignored root `.env` (loaded by both development launchers), or the process environment for a non-dev deployment:

```dotenv
VERIFICATION_EMAIL_MODE=smtp
VERIFICATION_EMAIL_FROM=your-authorized-sender@example.com
SPRING_MAIL_HOST=smtp.your-provider.example
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=your-smtp-username
SPRING_MAIL_PASSWORD=replace-locally-never-commit
SMTP_AUTH=true
SMTP_STARTTLS=true
SMTP_SSL=false
SMTP_CONNECTION_TIMEOUT_MS=5000
SMTP_READ_TIMEOUT_MS=5000
SMTP_WRITE_TIMEOUT_MS=5000
```

Both launchers allowlist these variables without printing values or placing passwords in Maven arguments. Maven alone does not read `.env`. Keep secrets in the environment or an ignored local file, never in tracked YAML, PRs or CI logs. Real SMTP requires an explicit host, port and authorized From; authenticated SMTP also requires username/password. Invalid/missing configuration fails startup. Incorrect credentials, unreachable hosts and provider rejections fail during delivery. STARTTLS is required when enabled; implicit SSL uses `SMTP_STARTTLS=false` and `SMTP_SSL=true` (usually port 465). Insecure real SMTP and simultaneous STARTTLS/SSL are rejected. The three positive timeouts are milliseconds, default 5000. Provider authorization/connectivity require a live send.

For personal Gmail use `smtp.gmail.com`, port `587`, STARTTLS, and your Gmail address for From and username. Enable Google 2-Step Verification and create an App Password, entered directly into the ignored local file; never paste it in chat. See [Google SMTP settings](https://developers.google.com/workspace/gmail/imap/imap-smtp) and [Google App Password instructions](https://support.google.com/mail/answer/185833?hl=en).

To return to Mailpit set `VERIFICATION_EMAIL_MODE=mailpit`, remove the real SMTP variables, and run the existing `dev` launcher. Defaults: localhost:1025, no auth/TLS, From `no-reply@fitnesscoaching.local`. Existing `SPRING_MAIL_HOST`/`SPRING_MAIL_PORT` overrides still work.

Email contains the existing manual token and optional `ai-fitness-coaching://` link. Enter the token on the current form; custom links depend on the installed app and are not guaranteed in Expo Go. Token generation/hash/storage, 24-hour expiry, single use, account lifecycle and sessions are unchanged.

Delivery remains synchronous **after registration commits**. The adapter reports SMTP acceptance only after `send` returns. Failure propagates to the existing application-service handler, which increments `auth.email.verification.delivery.failures` and warns; registration still returns its existing response and the account/token remain pending. HTTP 201 is not a delivery receipt. Do not automatically repeat registration: duplicate-email guards remain active. No retry, resend, queue or outbox is introduced. Provider exceptions are sanitized before that handler logs them because failed-mail exceptions can retain the raw token/message.

For live verification use a separate disposable database/runtime and a fresh QA account at an explicitly approved recipient. Record SMTP acceptance separately from the recipient's Inbox/Spam confirmation, then confirm through the existing API/form. Never publish the token. Without credentials or mailbox confirmation, real inbox delivery and end-to-end verification remain unverified.


## 7. Start AI Service

```powershell
cd services\ai-service
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\python.exe -m fastapi dev app\main.py
```
