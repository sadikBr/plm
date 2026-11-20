# 0012. Core implementation conventions

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
The first real modules (types, access, versioning) set patterns every later module will copy.
Deciding them once, explicitly, keeps 14+ modules consistent.

## Decision
1. **Plain SQL through Spring `JdbcClient`. No JPA or other ORM.** Immutable iterations, recursive
   BOM queries and access predicates injected into SQL (ADR 0005) all fight an ORM's
   identity-map/dirty-checking model. Each module's `infra` package owns its SQL. jOOQ may be
   introduced later for type-safe queries if hand-written SQL becomes a burden.
2. **IDs are UUIDv7**, generated in the application (`UuidV7`). They are time-ordered, so B-tree
   inserts stay append-mostly. In code they are wrapped in **typed value classes** (`MasterId`,
   `ContextId`…) so IDs of different kinds can't be mixed up. REST DTOs use raw UUIDs.
3. **Every service operation takes an explicit `Actor`** (tenant + principal) as its first
   parameter. There is no hidden thread-local "current user". Web controllers receive it through
   an argument resolver.
4. **Errors are `PlmException` subclasses with a stable `code`**, mapped by the app to RFC 9457
   problem details (`NotFound` 404, `Conflict` 409, `Validation` 422, `Forbidden` 403). Codes are
   public API and are never renamed.
5. **Module internals are Kotlin `internal`.** The compiler enforces module privacy, on top of
   Gradle dependencies and ArchUnit (ADR 0001, 0011).
6. **Invariants that matter are also enforced in the database**: check constraints, partial
   unique indexes, and triggers (e.g. checked-in iterations cannot be updated or deleted).
   The application is the first line of defence, never the only one.
7. **Mutations lock the aggregate root** (`SELECT … FOR UPDATE` on the master), so operations on
   one object are serialized and different objects proceed in parallel.
8. **Foreign keys may cross module boundaries** (e.g. `object_master.context_id → context`).
   Integrity is worth more than theoretical independence. A module extracted into its own service
   later drops its cross-module FKs as part of that extraction.
9. **Time comes from an injected `Clock`.** Never call `Instant.now()` directly.
10. **Test support lives in `backend/testing`** (package `plm.testing`, outside the `com.plm`
    component-scan root). `@PlmIntegrationTest` boots a module and its dependencies against a real
    Postgres.

## Consequences
+ Predictable SQL and performance. Simple transactions. Tests run against the real database.
− More SQL to write by hand. Mapping code lives in each repository.
