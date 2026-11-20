# 0010. Technology stack

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Large enterprise domain, long lifetime, need for mature transaction handling and a broad hiring pool.

## Decision
Backend: Kotlin on JVM 21, Spring Boot 3, Gradle (Kotlin DSL). DB: PostgreSQL 16+, Flyway. Workflow: Temporal. Events: Redpanda (Kafka API). Search: OpenSearch. Files: MinIO/S3. Auth: OIDC via Keycloak. API: REST + OpenAPI 3, generated TS client. Frontend: React + TypeScript, TanStack Query, AG Grid. 3D: three.js; conversion via OpenCascade worker. Ops: Docker, Kubernetes/Helm, OpenTelemetry, Prometheus/Grafana. Tests: JUnit 5, Testcontainers, ArchUnit, Playwright.

## Consequences
+ Proven, well-supported components. − JVM memory footprint; many infra components for local dev (mitigated by docker-compose).
