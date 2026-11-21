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
| notifications | Outgoing mail: an outbox sent over SMTP in the background | identity |
| reports | Reports (scheduled, mailed, alerts), dashboards, home pages | access, types, versioning, search, jobs, notifications |
| jobs | Background jobs: queue, workers, schedules (handlers live in the modules that own the work) | — |
| audit | Append-only audit log, fed by every domain event | — |
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
| workflow | Temporal workflows, tasks | lifecycle, identity |
| change | PR / CR / CN / change tasks | versioning, lifecycle, workflow |
| search | Search: indexing from events (OpenSearch or Postgres), queries with facets, saved searches | access, types, versioning, content, jobs |
| configuration | Export/import of the whole configuration as one bundle (config as code) | types, lifecycle, access |
| preferences | Typed preferences set per site / organization / context / user, with locking; profiles | access, types |
| extensions | Extensibility platform: action registry, extension packages, rules, webhooks | types, access, lifecycle, versioning, configuration, identity, preferences |

Every module also depends on `backend/platform` (the shared kernel).
To add a dependency, edit the module's `build.gradle.kts` and update this table in the same PR.

## Writing a module

Copy the shape of `types`, `access` or `versioning`, and follow [ADR 0012](../../docs/adr/0012-core-implementation-conventions.md).
Tests use `@PlmIntegrationTest` from `backend/testing`.
