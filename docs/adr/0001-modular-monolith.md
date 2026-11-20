# 0001. Modular monolith with enforced boundaries

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
PLM operations are highly transactional (e.g. releasing a change notice updates hundreds of objects atomically). Domain boundaries will shift while we learn the domain. A small team cannot afford the operational cost of many services.

## Decision
Build a single deployable Spring Boot application composed of modules (identity, access, types, content, versioning, documents, parts, bom, lifecycle, workflow, change, search, audit, integration). Each module exposes only its `api` package; `domain` and `infra` are internal. Modules never read each other's tables. ArchUnit tests in CI fail the build on violations. Extract a module into a service only with measured justification (likely first: CAD conversion, search indexing, file serving).

## Consequences
+ Local ACID transactions, simple deploys, easy refactoring. − Requires discipline; boundaries must be policed by tests, not convention.
