# Design

Use apps/web React strict TypeScript + Vite and apps/api Java21 Spring Boot + Maven.
Use an official supported Spring Boot version, pinned. Establish GET /api/system/capabilities
returning generationAvailable=false and the planned project-type vocabulary; no secrets or fake provider status.
GET /actuator/health is liveness. Vite proxies /api and /actuator to localhost:8080.
Render Chinese Atoms-inspired sidebar/composer/output tabs and visible development state.
No mocked generation, simulated timelines or fabricated recent projects. Prepare profiles for later
MySQL/Flyway integration without requiring a database for this foundation's health endpoints.
