# System architecture

## Runtime components

1. Expo Mobile serves Student and Trainer workflows.
2. React Admin Web serves platform operations and governance workflows.
3. Spring Boot is the business authority, authorization boundary, and system-of-record API.
4. FastAPI creates AI recommendations from explicitly authorized context.
5. PostgreSQL with pgvector stores relational business data and approved knowledge embeddings.
6. Redis stores cache and temporary state, never authoritative business data.
7. Object storage stores photos, images, videos, and documents.

## Request rules

- Mobile and Admin call Spring Boot over versioned REST APIs and WebSocket channels.
- Spring Boot calls the AI service internally.
- The AI service cannot access or mutate business tables directly.
- Spring Boot validates and persists every AI run and recommendation.
- Actions requiring human authority remain pending until the Student or Trainer with the correct authority accepts them.

## Evolution strategy

The backend starts as a modular monolith. A module may become a service only after a measured need for separate scaling, isolation, deployment cadence, or team ownership appears.
