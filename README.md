# PLM Platform

An open Product Lifecycle Management (PLM) platform: documents, parts, multi-level BOMs,
lifecycles, workflows and change management, built to scale to millions of objects.

> Status: **Phase 5 complete**: processes on Temporal (templates drawn in the UI, tasks and an
> inbox, votes, deadlines and escalation, stand-ins, electronic signatures), promotion requests,
> change management (problem reports, change requests, change notices and tasks, a change board,
> implementation plans, templates, association rules, variances, redlines and mass changes, change
> dashboards) and a release gate; sandboxed server scripts, extension panels, tabs and endpoints,
> and no-code automations. On top of product structures (Phase 4), documents, search, reports and
> collaboration (Phase 3), the extensibility platform (Phase 2) and the core model (Phase 1).
> Next: Phase 6, CAD data and visualization. See the [roadmap](docs/ROADMAP.md).

![Object page: a checked-out part with its working copy](docs/images/ui-object.png) See the [roadmap](docs/ROADMAP.md).

## Quick start

Requires JDK 21 and Docker. Full guide: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

```bash
./gradlew build      # compile, lint, all tests (uses Testcontainers)
make up              # start postgres, s3, keycloak, redpanda
make run             # backend on http://localhost:8080
make ui-install && make ui   # web UI on http://localhost:5173 (sign in as alice / alice)
```

Then open http://localhost:5173, or follow the [UI tour](docs/DEVELOPMENT.md#4-run-the-web-ui) or the [curl walkthrough](docs/DEVELOPMENT.md#5-walkthrough-with-curl-login-teams-versioning-audit): sign in, set up a team, version a part, read the audit trail.

## Documentation

| Doc | Purpose |
|---|---|
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | How to build, run and test locally |
| [docs/PLAN.md](docs/PLAN.md) | Product scope, architecture, data model, pitfalls |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Phased delivery plan with exit criteria |
| [docs/PARITY.md](docs/PARITY.md) | Capability matrix against established enterprise PLM: what's done, what's planned, where we aim to be better |
| [docs/EXTENSIBILITY.md](docs/EXTENSIBILITY.md) | How customers customize the PLM: custom actions, rules, webhooks, scripts, UI slots |
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
