# 0002. Master / Version / Iteration object model

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Engineering data needs full traceability: every released state must be reproducible forever, and identity (part number) must remain stable across revisions.

## Decision
Every versioned type uses three levels: **Master** (stable identity: number, name), **Version** (revision letter A, B, C), **Iteration** (A.1, A.2 — immutable snapshot per check-in). Check-out creates a working copy; check-in creates a new iteration; iterations are never updated in place. A single generic versioning engine in the `versioning` module serves all versioned types. Masters hold `latest_iter_id` and `latest_released_id` pointers.

## Consequences
+ Complete history, simple audit, reproducible baselines. − Storage grows with iterations (mitigated by content dedup, ADR 0007); queries must distinguish latest vs. historical.
