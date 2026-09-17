# Development setup

## 1. Configure environment

Copy `.env.example` to `.env` and replace development credentials.

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

```powershell
cd services\backend
.\mvnw.cmd spring-boot:run
```

## 7. Start AI Service

```powershell
cd services\ai-service
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\python.exe -m fastapi dev app\main.py
```
