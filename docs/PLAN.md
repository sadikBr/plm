# PLM Platform — Master Plan

This document is the source of truth for scope and architecture. Foundational decisions are
recorded individually in [`adr/`](adr/); changes to them require a new ADR.

## 1. Scope map

| Domain | Capability | Priority |
|---|---|---|
| Document management | Versioned docs, files, check-in/out, folders | MVP |
| Part & BOM management | Parts, multi-level BOMs, where-used, baselines | MVP |
| Lifecycle | States (In Work → Under Review → Released), transitions | MVP |
| Access control | Contexts (Org/Product/Library), teams, roles, policies | MVP |
| Workflow | Review/approval processes, tasks, notifications | v1 |
| Change management | Problem Report → Change Request → Change Notice → Change Tasks | v1 |
| CAD data management | CAD documents, CAD structure, part↔CAD associations | v2 |
| Visualization | 3D/2D viewing, markup | v2 |
| Configuration | Effectivity, options & variants, EBOM/MBOM views | v3 |
| Enterprise | ERP integration, file replication, reporting, bulk import/export | v3 |

The detailed, capability-by-capability view lives in [PARITY.md](PARITY.md).

## 2. Foundational decisions

| # | Decision | ADR |
|---|---|---|
| 1 | Modular monolith with enforced module boundaries | [0001](adr/0001-modular-monolith.md) |
| 2 | Master / Version / Iteration object model, immutable iterations | [0002](adr/0002-master-version-iteration.md) |
| 3 | BOM usage links point to child masters; config specs resolve | [0003](adr/0003-bom-links-to-masters.md) |
| 4 | Hybrid type system: columns + validated JSONB, no EAV | [0004](adr/0004-type-system-jsonb.md) |
| 5 | Context hierarchy + policy-based ACL enforced in SQL | [0005](adr/0005-access-control-in-query.md) |
| 6 | Lifecycle (own state machine) separate from workflow (Temporal) | [0006](adr/0006-lifecycle-vs-workflow.md) |
| 7 | Content-addressed file vault on S3-compatible storage | [0007](adr/0007-content-addressed-vault.md) |
| 8 | Transactional outbox + event bus; append-only audit log | [0008](adr/0008-outbox-events-audit.md) |
| 9 | `tenant_id` on every table from day one | [0009](adr/0009-tenancy.md) |
| 10 | Technology stack | [0010](adr/0010-tech-stack.md) |
| 11 | Build layout, migrations, dev object storage | [0011](adr/0011-build-and-module-layout.md) |
| 12 | Core implementation conventions (SQL, IDs, errors, actors) | [0012](adr/0012-core-implementation-conventions.md) |
| 13 | Events, audit, identity and access implementation | [0013](adr/0013-events-identity-access.md) |
| 14 | API contract and web frontend | [0014](adr/0014-api-contract-and-frontend.md) |
| 15 | Typed attributes, enumerations and units | [0015](adr/0015-attributes-and-reference-data.md) |
| 16 | Workbench UI design system | [0016](adr/0016-workbench-ui.md) |
| 17 | Numbering, revision schemes and lifecycles | [0017](adr/0017-numbering-and-lifecycles.md) |

### 2.1 Architecture: modular monolith
One deployable, strictly separated modules: `identity`, `access`, `types`, `content`, `versioning`,
`documents`, `parts`, `bom`, `lifecycle`, `workflow`, `change`, `search`, `audit`, `integration`.
Modules expose a public `api` package; `domain` and `infra` are internal. Cross-module calls go
through `api` interfaces or domain events. No module reads another module's tables.
Enforced by ArchUnit tests in CI.

### 2.2 Master / Version / Iteration
```
Master        identity (number, name) — stable across all versions
 └─ Version   revision A, B, C — formal releases
     └─ Iteration  A.1, A.2 … — immutable snapshot per check-in
```
- Check-out creates a working copy; check-in creates a new iteration; nothing is updated in place.
- One generic versioning engine serves all versioned types.
- Masters keep `latest_iter_id` and `latest_released_id` pointers.

### 2.3 BOM structure
- `usage_link(parent_iteration → child_master, qty, unit, find_no, attrs)`.
- A **config spec** (Latest, Latest Released, Baseline, Effectivity) picks the child iteration.
- Revising a child never forces parent revisions.
- Where-used and recursive expansion via Postgres recursive CTEs; caching later.

### 2.4 Type system
- Core fields are columns; type-specific attributes in JSONB validated against `type_def.attr_schema`.
- Type inheritance tree (`Part → MechanicalPart → Fastener`); lifecycle, numbering, layouts inherit.
- GIN indexes on JSONB; hot attributes promoted to indexed generated columns.

### 2.5 Contexts & access control
- `Site → Organization → Product | Library | Project → Folders`; each object in exactly one context.
- Policy: (context, type, state, principal) → permission bitmask. Teams map users to roles per context.
- **All reads go through `AccessService`, which adds a SQL predicate.** No in-memory filtering.

### 2.6 Lifecycle vs workflow
- Lifecycle: declarative state machine per type, executed in-transaction.
- Workflow: long-running human processes on Temporal; workflows invoke lifecycle transitions.

### 2.7 File vault
- S3/MinIO, keyed by SHA-256 → automatic dedup.
- Presigned direct upload/download; app stores metadata only.
- Designed for multi-site replication later.

### 2.8 Events, audit, integration
- Domain changes write `outbox_event` rows in the same transaction; a relay publishes to the bus.
- Search indexing, notifications, ERP sync, CAD conversion are event consumers.
- `audit_log` is append-only with actor, action, before/after.

### 2.9 Tenancy
`tenant_id` on every table. Initial deployment model is single-tenant; the column keeps the SaaS
path open at near-zero cost.

## 3. Core data model (sketch)

```sql
context(id, tenant_id, parent_id, kind, name)
type_def(id, tenant_id, parent_id, name, base_class, attr_schema jsonb, lifecycle_id)
object_master(id, tenant_id, type_id, context_id, number, name, latest_iter_id, latest_released_id)
object_iteration(id, tenant_id, master_id, revision, iteration, state, attrs jsonb,
                 checked_out_by, created_by, created_at, folder_id)
content_item(hash pk, size, mime, storage_key)
iteration_content(iteration_id, content_hash, role, filename)
usage_link(id, tenant_id, parent_iter_id, child_master_id, qty, unit, find_no, attrs jsonb)
occurrence(id, usage_link_id, ref_designator, transform)
baseline(id, tenant_id, name) / baseline_member(baseline_id, iteration_id)
lifecycle(id, tenant_id, states jsonb, transitions jsonb)
access_policy(id, tenant_id, context_id, type_id, state, principal, permissions)
team_member(context_id, role, principal)
change_object(...)        -- a versioned type; links to affected/resulting iterations
outbox_event(id, tenant_id, aggregate, type, payload jsonb, created_at, published_at)
audit_log(id, tenant_id, actor, action, object_id, before jsonb, after jsonb, at)
```

## 4. Repository layout (target)

```
plm-platform/
├── backend/
│   ├── app/                 # Spring Boot entrypoint, wiring only
│   ├── platform/            # shared kernel: ids, events, outbox, tx, errors
│   └── modules/<module>/    # api/ · domain/ · infra/ · tests
├── frontend/                # React app, generated OpenAPI client
├── workers/
│   ├── cad-converter/       # STEP/IGES → glTF
│   └── indexer/             # events → OpenSearch
├── deploy/                  # docker-compose (dev), Helm charts
├── docs/                    # this plan, roadmap, ADRs
└── .github/workflows/       # CI
```

## 5. Pitfalls we explicitly avoid

1. Permission checks in application code instead of queries.
2. Mutable versions.
3. BOM links to versions instead of masters.
4. EAV tables for custom attributes.
5. Streaming files through the app server.
6. Building our own workflow engine.
7. Synchronous calls to search/notifications/integrations from core transactions.
8. Missing `tenant_id` or audit log at the start.
9. Premature microservices.

## 6. Branding

This is an independent product. We implement industry-standard PLM concepts; we do not copy
any vendor's name, UI, documentation or proprietary APIs.
