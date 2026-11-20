# Roadmap

Each phase ends with explicit exit criteria. Don't start a phase until the previous one meets them.

## Phase 0 — Foundation (4–6 weeks)

**Goal:** a running skeleton where every foundational ADR is real code, not a slide.

- [x] 0.1 Monorepo + Gradle (Kotlin DSL) multi-module build, `.editorconfig`, ktlint (detekt deferred until it supports Kotlin 2.4)
- [x] 0.2 `deploy/docker-compose.yml`: Postgres, S3 (SeaweedFS), Keycloak, Redpanda, OpenSearch, Temporal
- [x] 0.3 CI (GitHub Actions): build, unit tests, Testcontainers integration tests, ArchUnit rules
- [x] 0.4 `platform` kernel: typed IDs, error model, transaction helpers, clock abstraction
- [x] 0.5 Flyway baseline: `tenant`, `context`, `type_def`, `object_master`, `object_iteration`
- [x] 0.6 Generic versioning engine: create, check-out, check-in, undo check-out, revise (+ tests)
- [x] 0.7 Outbox table + relay to Redpanda; audit log writer
- [x] 0.8 OIDC login via Keycloak; `identity` module maps tokens → principals
- [x] 0.9 `AccessService` with SQL-predicate filtering (minimal policy model)
- [x] 0.10 OpenAPI spec + generated TypeScript client
- [x] 0.11 React shell: login, context browser, object info page stub
- [ ] 0.12 OpenTelemetry tracing + Prometheus metrics wired

**Exit criteria:** `docker compose up` + one command boots the stack; a logged-in user can create a
generic versioned object, check it out/in, see its iteration history, and an unauthorized user
cannot see it (proven by an integration test). CI is green and blocks boundary violations.

## Phase 1 — Types, attributes and lifecycles (the customization foundation)

**Goal:** the out-of-the-box Part and Document types arrive with their standard attributes, and
administrators can extend everything (types, attributes, rules, layouts, lifecycles) **without
code**. Every later phase builds on this. See [PARITY.md](PARITY.md) §2–§5.

- [x] 1.1 Attribute definitions: data types (text, long text, integer, real, real with units,
      boolean, date, timestamp, URL, enumeration, reference), multi-valued, inherited by subtypes,
      reusable across types. Validated on every write (API, not just UI).
- [x] 1.2 Units of measure and quantities; enumerations (legal value lists) with localized labels.
- [x] 1.3 Constraints: required, range, length, regex, immutable, unique. Clear per-field errors.
- [x] 1.4 OOTB types and attributes seeded: Part (source, default unit, assembly mode, end item,
      phantom, trace code, description…) and Document (title, description, category, department…).
- [ ] 1.5 Initialization rules per type: numbering schemes (prefix, sequence, padding), revision
      schemes, default values, default lifecycle.
- [ ] 1.6 Lifecycle templates: states, transitions (promote, demote, revise, set state), per-type
      assignment. **Revise allowed only from configured states (Released by default).** Default
      state-based policies: Released is read-only.
- [ ] 1.7 Layouts per type (create / edit / info / table): attribute groups and order. The UI
      renders forms and pages from layouts.
- [ ] 1.8 Custom team roles; policy admin; policies apply to subtypes.
- [ ] 1.9 Admin UI: types, attributes, enumerations, units, rules, layouts, lifecycles (state diagram).
- [ ] 1.10 Configuration as code: export/import all of the above as versioned files.
- [ ] 1.11 Rename / renumber, set state (admin), with audit.

**Exit:** an admin creates a "Fastener" part subtype with a required enumerated `thread` attribute
and a numbering rule, entirely in the UI. A user creates one and sees the right form. Revising an
In Work part is refused, and revising a Released part works.

## Phase 2 — Documents, content and search
Primary content and attachments through the vault · thumbnails/previews · folders · Save As ·
move · delete rules · iteration compare · OpenSearch indexing via events · full-text and attribute
search with facets and saved searches · table views (columns, filters, export to Excel) ·
favorites, recent items, subscriptions · webhooks.

**Exit:** a team manages real documents end-to-end with files, permissions and fast search.

## Phase 3 — Parts and product structure
Usage links to masters · occurrences and reference designators · config specs (latest, released,
baseline) · baselines · multi-level BOM tree table · structure editing · where-used · BOM compare ·
substitutes/alternates · part↔document links · BOM import/export.

**Exit:** a 10-level, 50k-line BOM expands in < 2 s with access filtering applied.

## Phase 4 — Workflow and change management
Temporal integration · process template editor · tasks and inbox · review/approval with
e-signatures · promotion requests · Problem Report → Change Request → Change Notice → Change Tasks ·
affected/resulting objects · release on change notice completion · redlines · variances ·
notifications.

**Exit:** a part can only reach Released through an approved change notice or promotion request.

## Phase 5 — CAD data and visualization
CAD document type and dependency structure · part↔CAD association · STEP → glTF conversion worker ·
web 3D viewer (structure, measure, section, markups) · 2D/PDF viewer with markups.

## Phase 6 — Configuration
Effectivity (date, serial, lot) · options and variants · design/manufacturing views (EBOM → MBOM)
with reconciliation · classification.

## Phase 7 — Desktop and native CAD
Office/desktop integration · native connectors (SolidWorks, Creo, NX, Inventor, CATIA), each its own project.

## Phase 8 — Enterprise
LDAP/AD groups · bulk import/export and migration from other PLM systems · ERP connectors ·
supplier packages · multi-site file replication · reporting and dashboards · multi-tenancy hardening.

## Phase 9+ — Adjacent domains
Projects (plans, deliverables) · manufacturing process planning · requirements · quality
(CAPA, nonconformance, FMEA) · service information.
