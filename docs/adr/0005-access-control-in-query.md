# 0005. Context hierarchy and policy-based ACL enforced in SQL

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Filtering results in application memory collapses at scale: search and BOM expansion over millions of objects become unusable and leak counts.

## Decision
Containers form `Site → Organization → Product | Library | Project → Folder`. Every object belongs to exactly one context. Policies map (context, type, lifecycle state, principal/role) → permission bitmask; teams map users to roles per context. **Every read passes through `AccessService`, which contributes a SQL predicate** to the query. Direct repository access bypassing it is forbidden (ArchUnit rule).

## Consequences
+ Correct, scalable filtering everywhere including search and BOMs. − Policy model must stay SQL-expressible; complex rules need careful indexing.
