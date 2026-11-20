# Domain modules

Each folder is a Gradle project and a top-level package `com.plm.<module>` with three sub-packages:

| Package | Visibility | Contents |
|---|---|---|
| `api` | public | Interfaces, DTOs and events other modules may use |
| `domain` | **module-private** | Entities, domain services, rules |
| `infra` | **module-private** | Repositories, SQL, adapters |

Two layers enforce this:
1. **Gradle.** A module can only see modules listed in its `build.gradle.kts`. Cycles fail the build.
2. **ArchUnit** (`backend/app/src/test/.../ArchitectureTest.kt`). Code outside a module may not touch
   its `domain` or `infra` packages.

## Allowed dependencies

| Module | Responsibility | Depends on |
|---|---|---|
| identity | Users, groups, principals from OIDC | — |
| audit | Append-only audit log | — |
| types | Type definitions, attribute schemas | — |
| integration | ERP and external connectors (event consumers) | — |
| access | Contexts, teams, policies, SQL predicates | identity, types |
| content | File vault metadata, presigned URLs | access |
| versioning | Master / Version / Iteration engine | types, access, audit |
| lifecycle | Lifecycle state machines | types, access |
| documents | Document types | versioning, content, lifecycle |
| parts | Part types | versioning, lifecycle |
| bom | Usage links, config specs, where-used | parts |
| workflow | Temporal workflows, tasks | lifecycle, identity |
| change | PR / CR / CN / change tasks | versioning, lifecycle, workflow |
| search | OpenSearch indexing (event consumer) | access |

Every module also depends on `backend/platform` (the shared kernel).
To add a dependency, edit the module's `build.gradle.kts` and update this table in the same PR.
