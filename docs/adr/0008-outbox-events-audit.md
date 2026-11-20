# 0008. Transactional outbox, event bus, append-only audit

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Search, notifications, ERP sync and CAD conversion must react to changes without slowing or coupling core transactions. Regulated customers require tamper-evident audit trails.

## Decision
Every domain change writes an `outbox_event` row in the same transaction; a relay publishes to Redpanda/Kafka. All side effects are event consumers and must be idempotent. An append-only `audit_log` records actor, action, object, before/after.

## Consequences
+ Reliable, decoupled side effects; replayable history. − Eventual consistency for search/notifications; consumers must handle duplicates.
