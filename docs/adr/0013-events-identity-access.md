# 0013. Events, audit, identity and access: implementation choices

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
ADRs 0005 (access control) and 0008 (outbox, events, audit) set the direction. Roadmap steps
0.7–0.9 implemented them, and several concrete choices are worth recording.

## Decision

### Events (0.7)
- `DomainEvents.publish(actor, event)` is the only way to emit an event. It **requires an active
  transaction** and writes to `outbox_event` in it.
- The same call delivers an `EventEnvelope` to **synchronous in-process listeners**, in the same
  transaction. They are reserved for work that must be atomic with the change: today only the
  audit log. Everything else consumes from Kafka.
- A **single topic** `plm.domain-events`, **keyed by aggregate id**, so events of one object
  stay ordered. Headers: `plm-event-id`, `plm-event-type`, `plm-tenant-id`. Delivery is
  at least once, so consumers deduplicate on `plm-event-id`. Per-aggregate-type topics can come later.
- The relay holds a Postgres **advisory lock**, so exactly one app instance relays at a time. It
  sends in `seq` order and **stops at the first failure**, so a failing event is never overtaken
  by later ones. Relayed rows are deleted after 7 days.
- Event types are versioned strings (`versioning.ObjectCheckedIn.v1`). A breaking change ships as
  `.v2`, never as an edit to `.v1`.

### Audit
- The audit log is written by an `@EventListener` running inside the business transaction
  (`Propagation.MANDATORY`). **No change commits without its audit row.**
- `audit_log` is append-only, enforced by triggers that reject UPDATE, DELETE and TRUNCATE.
- For now only site admins can read it.

### Identity (0.8)
- The API is an **OAuth2 resource server**. Tokens are validated for signature, expiry, issuer
  and **audience `plm-api`** (Keycloak audience mapper), so a token minted for some other app is rejected.
- A principal is identified by the token's **`sub`**, which is stable across username and email
  changes. `preferred_username`, `name` and `email` go into the `principal` directory, refreshed
  at most every 5 minutes per user.
- **Site admin** = Keycloak realm role `plm-admin`, carried on `Actor.siteAdmin`.
- All requests use the default tenant. Multi-tenant deployments will map a token claim (ADR 0009).

### Access (0.9)
- Roles are `VIEWER`, `MEMBER` and `MANAGER`, per context, inherited by sub-contexts.
- **Policies** grant permission bits (READ, CREATE, MODIFY, REVISE, MANAGE) to a role on a context
  (and below), optionally for one type and/or one lifecycle state. The defaults live on the site.
- Inheritance uses a **closure table** (`context_closure`), so permission checks and list filters
  are joins, not recursive queries.
- **One SQL rule, two uses**: the same join produces both the single-object check and the predicate
  injected into list queries. A test asserts they agree for every combination of user,
  permission, context, type and state.
- **Not readable ⇒ 404**, readable but not permitted ⇒ 403 `ACCESS_DENIED`. Existence never leaks.
- Contexts are visible along a principal's team path (ancestors and descendants of their teams),
  so users can navigate down to their work.
- Not yet: type-inheritance in policies (a policy on `Part` doesn't yet cover subtypes), an API
  for custom policies, groups.

### IDs
- `UuidV7` is now **strictly monotonic within a JVM** (RFC 9562 §6.2, method 1), so ordering by
  id equals creation order and keyset pagination is stable.

## Consequences
+ Every change is audited and published atomically. Consumers can be added without touching core code.
+ Authorization has one choke point, and lists stay fast because filtering happens in SQL.
− The relay is single-active. Throughput is bounded until it's sharded by aggregate.
− A Kafka outage doesn't block writes, but events queue up in the outbox until it recovers.
