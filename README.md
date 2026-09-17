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

Start local infrastructure:

```bash
docker compose up -d
```

Run an application:

```bash
npm run dev:mobile
npm run dev:admin
```

```bash
cd services/backend
./mvnw spring-boot:run
```

```bash
cd services/ai-service
python -m venv .venv
python -m pip install -e ".[dev]"
fastapi dev app/main.py
```

On Windows, use `mvnw.cmd` instead of `./mvnw` and `.venv\\Scripts\\python.exe` for the virtual environment interpreter.

See `docs/07-development/setup.md` for the complete setup sequence.
