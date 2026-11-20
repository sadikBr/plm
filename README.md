# PLM Platform

An open Product Lifecycle Management (PLM) platform: documents, parts, multi-level BOMs,
lifecycles, workflows and change management, built to scale to millions of objects.

> Status: **Phase 0: Foundation**, steps 0.1–0.9 done: versioning, Keycloak login, team-based access control, audit log and domain events. See the [roadmap](docs/ROADMAP.md).

## Quick start

Requires JDK 21 and Docker. Full guide: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

```bash
./gradlew build      # compile, lint, all tests (uses Testcontainers)
make up              # start postgres, s3, keycloak, redpanda
make run             # backend on http://localhost:8080
```

Then follow the walkthrough in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#4-walkthrough-login-teams-versioning-audit): sign in, set up a team, version a part, read the audit trail.

## Documentation

| Doc | Purpose |
|---|---|
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | How to build, run and test locally |
| [docs/PLAN.md](docs/PLAN.md) | Product scope, architecture, data model, pitfalls |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Phased delivery plan with exit criteria |
| [docs/adr/](docs/adr/) | Architecture Decision Records — the *why* behind every foundational choice |

## Core principles

1. **Modular monolith first.** Strict module boundaries, enforced in CI; extract services only with evidence.
2. **Master → Version → Iteration.** Iterations are immutable. Every versioned type shares one engine.
3. **BOM links point to child masters.** Config specs resolve which version you see.
4. **Access control lives in the query.** Never filter in application memory.
5. **Files never stream through the app.** Content-addressed object storage, presigned URLs.
6. **Everything emits events** through a transactional outbox; side effects are asynchronous.
7. **Audit everything**, append-only, from day one.

## Planned stack

Kotlin + Spring Boot 3 · PostgreSQL · Flyway · Temporal · Redpanda/Kafka · OpenSearch ·
S3 (SeaweedFS in dev) · Keycloak (OIDC) · React + TypeScript + AG Grid · three.js · OpenCascade
