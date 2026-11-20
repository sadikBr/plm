# Roadmap

Each phase ends with explicit exit criteria. Don't start a phase until the previous one meets them.

## Phase 0 — Foundation (4–6 weeks)

**Goal:** a running skeleton where every foundational ADR is real code, not a slide.

- [x] 0.1 Monorepo + Gradle (Kotlin DSL) multi-module build, `.editorconfig`, ktlint (detekt deferred until it supports Kotlin 2.4)
- [x] 0.2 `deploy/docker-compose.yml`: Postgres, S3 (SeaweedFS), Keycloak, Redpanda, OpenSearch, Temporal
- [x] 0.3 CI (GitHub Actions): build, unit tests, Testcontainers integration tests, ArchUnit rules
- [ ] 0.4 `platform` kernel: typed IDs, error model, transaction helpers, clock abstraction
- [ ] 0.5 Flyway baseline: `tenant`, `context`, `type_def`, `object_master`, `object_iteration`
- [ ] 0.6 Generic versioning engine: create, check-out, check-in, undo check-out, revise (+ tests)
- [ ] 0.7 Outbox table + relay to Redpanda; audit log writer
- [ ] 0.8 OIDC login via Keycloak; `identity` module maps tokens → principals
- [ ] 0.9 `AccessService` with SQL-predicate filtering (minimal policy model)
- [ ] 0.10 OpenAPI spec + generated TypeScript client
- [ ] 0.11 React shell: login, context browser, object info page stub
- [ ] 0.12 OpenTelemetry tracing + Prometheus metrics wired

**Exit criteria:** `docker compose up` + one command boots the stack; a logged-in user can create a
generic versioned object, check it out/in, see its iteration history, and an unauthorized user
cannot see it (proven by an integration test). CI is green and blocks boundary violations.

## Phase 1 — Document management MVP (6–8 weeks)
Folders · document types · presigned upload/download through the vault · check-out/in/undo ·
iteration history · revise · basic lifecycle · access policies UI · OpenSearch indexing via events ·
search UI · object info pages.

**Exit:** a team can manage real documents end-to-end with permissions and search.

## Phase 2 — Parts & BOM (8–10 weeks)
Parts · usage links · config specs (latest, released, baseline) · multi-level BOM tree grid ·
where-used · BOM compare · baselines · part↔document links · CSV/Excel import.

**Exit:** a 10-level, 50k-line BOM expands in < 2 s with access filtering applied.

## Phase 3 — Workflow & change (8–10 weeks)
Temporal integration · review/approval tasks · inbox & notifications · PR → CR → CN → tasks ·
affected/resulting objects · release-on-CN-completion · promotion requests.

**Exit:** a part can only reach Released through an approved change notice.

## Phase 4 — CAD & visualization (10+ weeks)
CAD document type · CAD structure · STEP → glTF conversion worker · web 3D viewer · thumbnails ·
markups · part↔CAD association. Native CAD plugins are separate projects afterwards.

## Phase 5 — Enterprise
Date/serial/lot effectivity · options & variants · EBOM/MBOM views · ERP connectors via events ·
multi-site file replication · reporting · bulk import/export · multi-tenancy hardening.
