# AI-Integrated Personalized Online Fitness Coaching System

Monorepo for a personalized online fitness coaching platform that supports two coaching modes: `HUMAN_COACH` and `SELF_DIRECTED`. AI is an assistance layer that creates explainable recommendations; it is not a coaching mode and has no business authority.

## Repository layout

| Path | Purpose |
| --- | --- |
| `apps/mobile` | Expo and React Native application for students and trainers |
| `apps/admin-web` | React and Vite administration application |
| `services/backend` | Spring Boot modular monolith and system-of-record API |
| `services/ai-service` | FastAPI service for context building, rules, RAG, and recommendations |
| `packages` | TypeScript packages shared by client applications |
| `contracts` | OpenAPI, WebSocket, and AI structured-output contracts |
| `database` | Database design notes, seed data, and recovery guidance |
| `infrastructure` | Local containers and deployment configuration |
| `docs` | Product, domain, architecture, API, UX, and development documentation |

## Prerequisites

- Node.js 22.13 or later for Expo SDK 57
- Java 21
- Python 3.12 or later
- Docker Desktop

## Quick start

Install JavaScript dependencies for the Admin workspace and the separately pinned Expo application:

```bash
npm install
npm install --prefix apps/mobile
```

Start local infrastructure (PostgreSQL, Redis, MinIO, Mailpit):

```bash
docker compose up -d
```

Mailpit web UI is accessible at `http://localhost:8025` (SMTP port 1025) to review verification emails and tokens.

Start Mobile and Admin Web:

```bash
npm run dev:mobile
npm run dev:admin
```

For Mobile, configure `EXPO_PUBLIC_API_BASE_URL` in `apps/mobile/.env` (defaults to `http://10.0.2.2:8080/api/v1` on Android emulator, `http://127.0.0.1:8080/api/v1` on iOS simulator / Web). Email verification deep link is `ai-fitness-coaching://verify-email?token=<token>`.

Start Backend:

Note: `.env.example` is only a template. Local `.env` contains your development credentials and must never be committed. Docker Compose automatically reads `.env` for PostgreSQL, but Maven does not automatically load root `.env`. To ensure required database credentials (`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `BACKEND_PORT`) are reliably passed to Spring Boot and avoid authentication failures (`FATAL: password authentication failed for user "fitness_app"`), use the recommended development launcher script (which safely exports only allowlisted variables without printing secrets):

Windows (PowerShell) (Recommended):
```powershell
.\services\backend\run-dev.ps1
```

Unix / macOS (Recommended):
```bash
bash ./services/backend/run-dev.sh
```

The `dev` profile activates the development verification-email adapter (`DevelopmentVerificationEmailSender`), which dispatches verification emails via SMTP to Mailpit alongside a development-only JWT signing secret. The verification token is visible in Mailpit (`http://localhost:8025`) for development testing and mobile deep link verification, but plaintext tokens are strictly never written to backend application logs. Default and production profiles intentionally fail fast if `JWT_SECRET` is missing/short or if no production `VerificationEmailPort` is configured. Milestone M1A/M1B is not production-email-ready (durable outbox delivery and external provider integration are deferred).

Start AI Service:
```bash
cd services/ai-service
python -m venv .venv
python -m pip install -e ".[dev]"
fastapi dev app/main.py
```

On Windows, use `.venv\Scripts\python.exe` for the virtual environment interpreter.

See `docs/07-development/setup.md` for the complete setup sequence.
