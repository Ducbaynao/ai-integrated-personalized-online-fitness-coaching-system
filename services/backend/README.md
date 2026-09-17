# Backend service

Spring Boot modular monolith containing the business authority and system-of-record API.

Each module owns its domain model, application services, API adapters, and persistence adapters. Modules communicate through public application interfaces or domain events, not by reaching into another module's repository.

Run on Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

Run tests:

```powershell
.\mvnw.cmd test
```
