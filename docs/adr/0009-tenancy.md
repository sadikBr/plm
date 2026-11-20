# 0009. tenant_id on every table

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Deployment model may evolve from single-tenant to SaaS. Retrofitting tenancy is extremely costly.

## Decision
Initial deployments are single-tenant, but every table carries `tenant_id` and every query is tenant-scoped through the platform layer. Postgres row-level security can be enabled later as defense in depth.

## Consequences
+ SaaS path stays open at near-zero cost. − Slight verbosity in schema and indexes.
