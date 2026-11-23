# Domain modules

Each folder is a Gradle project and a top-level package `com.plm.<module>` with three sub-packages:

| Package | Visibility | Contents |
|---|---|---|
| `api` | public | Interfaces, DTOs and events other modules may use |
| `domain` | **module-private** | Entities, domain services, rules |
| `infra` | **module-private** | Repositories, SQL, adapters |

Three layers enforce this:
1. **Kotlin `internal`.** Classes outside `api` are `internal`, so other Gradle modules can't even compile against them.
2. **Gradle.** A module can only see modules listed in its `build.gradle.kts`. Cycles fail the build.
3. **ArchUnit** (`backend/app/src/test/.../ArchitectureTest.kt`). Code outside a module may not touch
   its `domain` or `infra` packages.

## Allowed dependencies

| Module | Responsibility | Depends on |
|---|---|---|
| identity | User directory (from OIDC sign-ins), service accounts and API tokens | jobs |
| bulk | Mass operations on many objects, as one background job | access, versioning, jobs |
| notifications | Outgoing mail; favorites, recent items, subscriptions, notifications and digests | identity, access, types, versioning, jobs |
| reports | Reports (scheduled, mailed, alerts), dashboards, home pages | access, types, versioning, search, jobs, notifications |
| bom | Product structure: usage links between parts | access, types, versioning |
| collaboration | Discussions on objects, with mentions; markups (saved views and annotations on models and sheets) | identity, access, types, versioning, notifications |
| jobs | Background jobs: queue, workers, schedules (handlers live in the modules that own the work) | — |
| audit | Append-only audit log, fed by every domain event; audit reports | access |
| types | Type definitions, attribute schemas | — |
| integration | ERP and external connectors (event consumers) | — |
| events | Relays the outbox to Kafka (infrastructure) | — |
| access | Contexts, teams, policies, permission checks and SQL filters | identity, types |
| content | File vault: blobs (S3 or disk), signed links, iteration content, thumbnails, previews | access, versioning, jobs |
| versioning | Master / Version / Iteration engine, lifecycle enforcement | types, access, identity, lifecycle, preferences |
| lifecycle | Lifecycle templates and their assignment to types | types |
| documents | Document templates, Save As, compare | access, types, versioning, content, extensions |
| parts | Part types | versioning, lifecycle |
| bom | Usage links, config specs, where-used | parts |
| workflow | Processes on Temporal: versioned templates run by one interpreter; tasks, history | access, identity, types, versioning, notifications |
| change | Promotion requests (and, next, PR / CR / CN / change tasks) | access, types, versioning, lifecycle, workflow |
| automation | No-code automations: an event, a condition, actions (set state or a value, notify, start a process, call a webhook, run a script), with a run history | access, types, versioning, workflow, notifications, extensions, identity |
| cad | CAD documents (part, assembly, drawing) and their versioned dependencies, the collector, family tables, part ↔ CAD association and build | access, types, versioning, bom |
| publishing | Publishing rules (PDFs on check-in or on reaching a state), stamping, download watermarks, data sheets | access, types, versioning, content, bom, jobs, identity |
| effectivity | When part versions and structure lines apply: end items, date, serial, lot and block ranges; effectivity configuration specs | access, types, versioning, bom |
| variants | Options and choices, option rules, 150% structures with choice conditions, variant specifications, variant generation and reuse | access, types, versioning, bom |
| classification | The class tree with inherited class attributes, parts' classes and values per version, classification search and find similar | access, types, versioning |
| search | Search: indexing from events (OpenSearch or Postgres), queries with facets, saved searches | access, types, versioning, content, jobs |
| configuration | Export/import of the whole configuration as one bundle (config as code) | types, lifecycle, access |
| preferences | Typed preferences set per site / organization / context / user, with locking; profiles | access, types |
| workspace | Personal workspaces: the objects a person works on, their files' states against the person's computer, check out / in for many at once, new files | access, types, versioning, content, cad |
| extensions | Extensibility platform: action registry, extension packages, rules, webhooks | types, access, lifecycle, versioning, configuration, identity, preferences |

Every module also depends on `backend/platform` (the shared kernel).
To add a dependency, edit the module's `build.gradle.kts` and update this table in the same PR.

## Writing a module

Copy the shape of `types`, `access` or `versioning`, and follow [ADR 0012](../../docs/adr/0012-core-implementation-conventions.md).
Tests use `@PlmIntegrationTest` from `backend/testing`.
