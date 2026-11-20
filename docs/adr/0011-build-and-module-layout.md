# 0011. Build layout, migrations and dev object storage

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
ADR 0001 requires enforced module boundaries, and the build must stay manageable as the module
count grows. ADR 0007 named MinIO for object storage, but MinIO's community edition stopped
shipping prebuilt images in 2025.

## Decision
- **Gradle multi-project build**, Kotlin DSL. Shared configuration lives in **convention plugins**
  under `build-logic/` (`plm.kotlin`, `plm.module`, `plm.spring-app`), not in `subprojects {}` blocks.
  Versions come from `gradle/libs.versions.toml` and the Spring Boot BOM.
- **One Gradle project per domain module.** Allowed module dependencies are declared explicitly
  (see `backend/modules/README.md`). ArchUnit additionally keeps `domain`/`infra` packages private.
- **Compiler warnings are errors.** ktlint (official style) runs as part of `build`.
- **Flyway migrations** live in `db/migration/<module>/` and use timestamp versions
  (`V202611201200__create_x.sql`) so that modules never collide on version numbers.
- **Integration tests use Testcontainers** against the same Postgres major version as production.
- **Dev object storage is SeaweedFS** (Apache-2.0, S3 API). The application only depends on the
  S3 API, so production can use AWS S3, Azure Blob (via an S3 gateway), Ceph or any S3-compatible store.

## Consequences
+ Adding a module takes one folder, a three-line build file and a line in `settings.gradle.kts`.
+ Boundary violations fail CI instead of code review.
− Every new cross-module dependency needs an explicit build change. This is intentional friction.
