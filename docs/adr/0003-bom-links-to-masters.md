# 0003. BOM usage links point to child masters

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
If a parent iteration references a specific child version, every child revision forces a cascade of parent revisions up the tree. Baselines, effectivity and variants all need to choose child versions dynamically.

## Decision
`usage_link` connects a **parent iteration** to a **child master**, carrying quantity, unit, find number and attributes; occurrences carry reference designators and transforms. A **configuration spec** (Latest, Latest Released, Baseline, Effectivity) resolves which child iteration is shown. Expansion and where-used use Postgres recursive CTEs, with a cache layer added when needed.

## Consequences
+ No revision cascades; baselines/effectivity/variants fit naturally. − Every BOM read requires a config spec; resolution logic must be fast and well tested.
