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
| identity | User directory (from OIDC sign-ins) | — |
| audit | Append-only audit log, fed by every domain event | — |
| types | Type definitions, attribute schemas | — |
| integration | ERP and external connectors (event consumers) | — |
| events | Relays the outbox to Kafka (infrastructure) | — |
| access | Contexts, teams, policies, permission checks and SQL filters | identity, types |
| content | File vault metadata, presigned URLs | access |
| versioning | Master / Version / Iteration engine, lifecycle enforcement | types, access, identity, lifecycle |
| lifecycle | Lifecycle templates and their assignment to types | types |
| documents | Document types | versioning, content, lifecycle |
| parts | Part types | versioning, lifecycle |
| bom | Usage links, config specs, where-used | parts |
| workflow | Temporal workflows, tasks | lifecycle, identity |
| change | PR / CR / CN / change tasks | versioning, lifecycle, workflow |
| search | OpenSearch indexing (event consumer) | access |

Every module also depends on `backend/platform` (the shared kernel).
To add a dependency, edit the module's `build.gradle.kts` and update this table in the same PR.

## Writing a module

Copy the shape of `types`, `access` or `versioning`, and follow [ADR 0012](../../docs/adr/0012-core-implementation-conventions.md).
Tests use `@PlmIntegrationTest` from `backend/testing`.
