# 0018. Extensibility platform

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Every PLM deployment is customized. In established systems most customization is server-side code
loaded into the application: XML action definitions, validator and listener classes, form
processors, delegates and code inside workflow templates. It is powerful, but it reaches into
internals, so each upgrade becomes a re-test and re-port project. Their own SaaS offerings now
forbid most of it for that reason.

We want the same reach (custom actions, validations, reactions to changes, integrations, UI,
reports) while keeping upgrades safe, tenants isolated (SaaS) and behaviour auditable. The full
design is in [EXTENSIBILITY.md](../EXTENSIBILITY.md).

## Decision
- **Layers, from least to most powerful; use the lowest that works.**
  1. *Configuration* (types, attributes, layouts, lifecycles, preferences, policies), exported as
     configuration bundles.
  2. *Declarative extensions*: actions, rules and automations declared in a versioned manifest,
     with conditions in one expression language (CEL).
  3. *Out-of-process handlers*: webhooks and event subscriptions; extensions are separate services
     calling our public API with a scoped service account.
  4. *Sandboxed scripts* in the server (JavaScript on GraalJS, isolated contexts, CPU/memory/time
     limits, capability-scoped API, no file or network access unless granted).
  5. *UI extension slots*: declarative panels, or sandboxed iframes talking to the host through a
     small SDK with a scoped token.
- **No in-process plugins by default.** Extensions never load JVM code into the platform. A
  Kotlin SPI may be offered later for self-hosted installs only, marked unsupported for upgrades.
- **One contract, versioned.** Manifests, script APIs, event payloads and UI SDK share a platform
  contract version (semver). Installing or upgrading checks compatibility; breaking changes need a
  new major version with a deprecation period.
- **Built-ins use the same mechanisms.** Our own actions, validations and reactions are declared
  in the action registry, rule engine and event bus, so the extension points are exercised every day.
- **Everything is audited and attributable.** Calls made by an extension run as its service
  account; scripts run as the triggering user, limited to the capabilities the extension was granted.
- **Per tenant.** Extensions are installed, configured and disabled per tenant, and move between
  environments like configuration bundles.

## Consequences
+ Upgrades don't break customizations that stay on the contract; incompatibilities show up at install time, not in production.
+ Safe for SaaS: a broken script or webhook can't take down the server or read another tenant's data.
+ One expression language for visibility, validation, calculated attributes and gate criteria.
− Some deep customizations possible with in-process code are not possible; we close gaps by
  adding extension points, which takes product work.
− Webhook handlers add latency, so synchronous hooks must be fast and have strict timeouts.
− We have to keep the contract stable and documented. That's a permanent cost.
