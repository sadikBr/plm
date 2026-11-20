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
- [x] 1.5 Initialization rules per type: numbering schemes (prefix, sequence, padding), revision
      schemes, default values, default lifecycle.
- [x] 1.6 Lifecycle templates: states, transitions (promote, demote, revise, set state), per-type
      assignment. **Revise allowed only from configured states (Released by default).** Default
      state-based policies: Released is read-only.
- [x] 1.7 Layouts per type and screen (create / edit / info, plus a default): attribute groups,
      order, one or two columns, hidden attributes; inherited along the type hierarchy. The UI
      renders forms and the attributes panel from them. (Table and search columns come with saved
      table views in Phase 3.)
- [x] 1.8 Custom team roles; access rules (allow and deny) managed per context by its managers;
      rules on a type apply to its subtypes; inherited team members; "check access" explains why
      someone can or can't do something. (Shared teams move to 1.12 with context templates.)
- [x] 1.9 Admin UI (site admins): types and subtypes, attributes (all data types and
      constraints, typed default values), value lists, units, numbering schemes, rules (numbering,
      revision scheme, manual numbers, lifecycle), layouts per screen with live preview, team roles,
      lifecycles (state diagram; new or copied templates with a transition matrix).
- [x] 1.10 Configuration as code: the whole configuration as one JSON bundle (by name, stable
      output for Git); import shows a plan with diffs and applies it in one transaction or not at
      all; adds and updates, never deletes. Admin page and API.
- [x] 1.11 Rename / renumber (master-level, all versions, needs MANAGE, not while checked out, audited with the old identity). (Set state with admin override shipped in 1.6.)
- [x] 1.12 Context templates for organizations, products, libraries and projects: access rules
      (types by name), team members and shared teams. Create a context from a template; save an
      existing context as a template; templates travel in configuration bundles. Shared teams:
      maintained once, attached to any number of contexts. (Folders, preferences and per-context
      numbering join templates when those features exist: Phase 3, 1.13.)
- [x] 1.13 Preferences framework: typed preferences declared by each module, set at site,
      organization/product/library/project and user level, lockable at any context level so
      nothing below (users included) can change them. Preferences tab on contexts, My preferences.
      First preferences: rows per page, check-in comment required, per-context numbering scheme.
- [x] 1.14 Delegated administration: an organization's managers administer it without being site
      administrators: its team, access rules and preferences (1.8, 1.13), plus its own shared teams
      and context templates, usable only inside it. Site-wide teams, templates and the data model
      (types, attributes, lifecycles, roles) stay with site administrators, since they apply to
      everyone. (Organization-specific types and lifecycles: see 1.15.)
- [x] 1.15 Lifecycle template management: copy, edit and version templates (editing saves a new
      version; types move to it, objects keep theirs until a site admin moves them, with a state
      mapping); entry criteria per state (attributes required to enter); state-based revision series
      (e.g. 1, 2, 3 then A, B). (Expression-based gate criteria come with the rule language in
      Phase 2; per-organization lifecycle assignments in Phase 5 with workflows; "reset team" applies
      once objects have teams, with workflows.)
- [x] 1.16 Attribute model extras: global attributes (defined once, used by several types), dependent
      value lists (allowed values per parent value, enforced on every write), units added by
      administrators, type properties (description, icon, translated names, abstract types that
      can't be created). (Translating the user interface itself moves to Phase 3.)

**Exit:** an admin creates a "Fastener" part subtype with a required enumerated `thread` attribute
and a numbering rule, entirely in the UI. A user creates one and sees the right form. Revising an
In Work part is refused, and revising a Released part works. An organization admin creates a
product from a template with its own lifecycle and team roles, without site admin rights.

## Phase 2 — Extensibility platform v1

**Goal:** customers add behaviour (actions, rules, integrations) without touching our code, and
our own later features are built on the same mechanisms. See [EXTENSIBILITY.md](EXTENSIBILITY.md).

- [x] 2.1 Action registry: every built-in action (check out, revise, set state…) is declared as
      data, with placement, applies-to (types, states), required permission and a visibility
      condition. The UI renders toolbars and menus from the registry. (Done: registry, placement,
      order, types, states, permission, admin screen. Visibility conditions use expressions, 2.2.)
- [x] 2.2 Expression language (CEL) for conditions, validations, calculated attributes and gates.
- [ ] 2.3 Extension packages: manifest (`plm-extension.yaml`), install / upgrade / disable per
      tenant, compatibility check against the platform contract version, audit.
- [ ] 2.4 Custom actions with handlers: webhook (signed), start a workflow (Phase 5), open a URL or
      panel. Parameter forms generated from a schema; results (message, refresh, navigate, download).
- [ ] 2.5 Pre-operation rules: synchronous validation hooks that can refuse an operation with a
      message (expressions first; scripts in Phase 5).
- [ ] 2.6 Event subscriptions and webhooks: filters, signing, retries with backoff, dead letters,
      delivery log in the admin UI.
- [ ] 2.7 Service accounts and OAuth client credentials, scoped API tokens for extensions.
- [ ] 2.8 Profiles: which actions and UI areas a group of users sees.

**Exit:** a sample extension, installed from a manifest, adds an "Send to ERP" action on Released
parts that calls a webhook, plus a rule that refuses check-in when a description is missing. No
platform code changes, and it survives a platform upgrade.

## Phase 3 — Documents, content, search and collaboration
User interface translations (all screens, per-user language) · primary content and attachments through the vault · document templates · thumbnails/previews ·
folders · Save As · move · delete rules · ownership/reservation · compare · clipboard · mass
operations as background jobs · OpenSearch indexing via events · full-text and attribute search
with facets, saved and shared searches · table views (columns, filters, saved views, Excel export) ·
report definitions, scheduled reports and alerts, dashboards and configurable home page · favorites,
recent items, subscriptions and digests · discussions · ad hoc access · audit reports ·
job queue and scheduler with admin monitoring.

**Exit:** a team manages real documents end-to-end with files, permissions and fast search.

## Phase 4 — Parts and product structure
Usage links to masters · occurrences and reference designators · config specs (latest, released,
baseline) · baselines, managed baselines and collections · multi-level BOM tree table · structure
editing and find in structure · where-used · BOM compare · alternates and substitutes ·
part↔document links · reference and alias attributes · roll-ups and multi-level reports ·
BOM import/export · bulk revise.

**Exit:** a 10-level, 50k-line BOM expands in < 2 s with access filtering applied.

## Phase 5 — Workflow, change management and extensibility v2
Temporal integration · process template editor · tasks and inbox, voting, delegation, escalation ·
lifecycle and workflow assignments per organization ·
e-signatures (21 CFR Part 11) · promotion requests · Problem Report → Change Request → Change
Notice → Change Tasks, fast and full track · affected/resulting objects and end items ·
implementation plans · change board · change templates and association rules · variances ·
redlines and mass change · change dashboards · **extensibility v2**: sandboxed server scripts
(rules, numbering, automation steps), UI extension slots (panels, tabs, micro-frontends),
extension endpoints, no-code automations (trigger → condition → actions).

**Exit:** a part can only reach Released through an approved change notice or promotion request.

## Phase 6 — CAD data and visualization
CAD document type, dependency structure, family tables, collector · part↔CAD association and
build rules · STEP → glTF conversion worker · publishing rules, watermarks, PDF generation ·
web 3D viewer (structure, measure, section, markups) · 2D/PDF viewer with markups · MBD/PMI.

## Phase 7 — Configuration
Effectivity (date, serial, lot, block; effectivity contexts; pending effectivity via change) ·
options and variants (option sets, rules, 150% structures, variant specifications, variant
generation and reuse) · one-off versions · classification with class attributes, classification
search, find similar.

## Phase 8 — Desktop and native CAD
Office/desktop integration · personal workspaces · native connectors (SolidWorks, Creo, NX,
Inventor, CATIA, electrical CAD), each its own project.

## Phase 9 — Enterprise integration and infrastructure
LDAP/AD/SCIM groups · bulk load and migration from other PLM systems · packages and deliveries ·
ERP connectors with distribution targets and plant data · STEP AP242, ReqIF, OSLC · multi-site
file replication · security labels and agreements · retention, legal hold, archive and purge ·
tenant administration · upgrade playbook.

## Phase 10 — Manufacturing process management
EBOM → MBOM and plant-specific structures · equivalence links and reconciliation · generate
downstream structures · process plans, operations, resources, work instructions · new view versions.

## Phase 11 — Projects and programs
Project plans, activities, milestones, Gantt · deliverables linked to PLM objects · resources ·
project templates · meetings and action items · share into projects and send back.

## Phase 12 — Quality and regulatory
Nonconformance · CAPA · customer complaints · audits · regulatory submissions · FMEA and control plans.

## Phase 13 — Supplier management and environmental compliance
Manufacturer and vendor parts · AML/AVL and sourcing rules · material declarations · RoHS, REACH,
conflict minerals · compliance roll-ups.

## Phase 14+ — Adjacent domains
Requirements traceability · service information, translation, service BOM and parts catalogues ·
software parts and builds · reliability analysis · mobile and AR.
