# Extensibility: how customers customize the PLM

Decision record: [ADR 0018](adr/0018-extensibility-platform.md). Roadmap: Phase 2 (v1) and
Phase 5 (v2). Status: **design**, nothing below is built yet unless marked.

## Principles

1. **Upgrade-safe.** Extensions use a published, versioned contract, never internals.
2. **Declarative first.** Most needs are met by configuration and manifests; code is the exception.
3. **Sandboxed.** Scripts and UI extensions can't crash the server, leak data or exceed limits.
4. **Same as built-ins.** Our own features are declared through the same registries.
5. **Testable and promotable.** Extensions are files in version control, with a local test
   harness, moved dev → test → prod like configuration.
6. **Auditable.** Every change an extension makes is in the audit log, attributed to it.

## The layers

| Layer | For | Example |
|---|---|---|
| Configuration | Shape of the data and process | A "Fastener" subtype with a `thread` attribute, its numbering and lifecycle |
| Declarative extension | New actions, rules, automations | "Send to ERP" action on Released parts; refuse check-in without a description |
| Out-of-process handlers | Integrations, heavy logic in any language | A service that receives the webhook and calls the ERP |
| Sandboxed scripts | Small server-side logic | Number = plant code + sequence; set a default from the context |
| UI extension slots | Custom screens | A "Cost" tab on parts showing data from a costing service |

## Extension packages

An extension is a directory (or a signed archive) with a manifest:

```yaml
# plm-extension.yaml
id: acme.erp-sync
name: ERP sync
version: 1.3.0
platform: ">=1.0 <2.0"          # contract versions it supports
permissions:                    # what it may do; shown to the admin at install
  - objects:read
  - objects:set-state
  - events:subscribe
config:                         # settings the admin fills in at install
  erpUrl: { type: url, required: true }
  apiKey: { type: secret, required: true }

actions:
  - id: send-to-erp
    label: { en: Send to ERP, fr: Envoyer à l'ERP }
    icon: send
    placement: [object.toolbar, object.more, table.row, table.bulk]
    appliesTo:
      types: [Part]             # includes subtypes
      states: [RELEASED]
    requires: READ              # permission on the object
    visibleWhen: "object.attributes.source == 'MAKE'"
    form:                       # optional parameter form (JSON Schema)
      properties:
        plant: { type: string, enum: [P100, P200] }
    handler:
      webhook: { url: "${config.erpUrl}/plm/send", timeout: 10s }

rules:
  - id: description-required
    on: [object.check-in]
    appliesTo: { types: [Part] }
    when: "object.attributes.description == ''"
    reject: { en: "Add a description before checking in." }

subscriptions:
  - id: released-parts
    events: [object.state-changed]
    filter: "event.to == 'RELEASED' && 'Part' in object.types"
    deliver:
      webhook: { url: "${config.erpUrl}/plm/events", retries: 8 }

automations:                    # no-code "when … if … then …" (Phase 5)
  - id: notify-quality
    trigger: object.state-changed
    condition: "event.to == 'UNDER_REVIEW'"
    steps:
      - notify: { role: QUALITY, template: review-requested }

ui:                             # Phase 5
  - slot: object.tabs
    id: cost
    label: { en: Cost }
    appliesTo: { types: [Part] }
    frame: { url: "https://cost.acme.example/plm-tab", height: auto }

reports: []                     # report definitions shipped with the extension
seed: []                        # types, attributes, enumerations it needs (configuration bundle)
```

**Built (roadmap 2.3):** `id`, `name`, `version`, `description`, `publisher`, `platform`,
`permissions`, `config` (types `string`, `url`, `secret`, `number`, `boolean`, `choice`), `actions`
with a `url` or `webhook` handler and an optional `form` (roadmap 2.4; webhook contract in
`extensions/README.md`), and `seed` (a configuration bundle). `rules`, `subscriptions`, `automations`, `ui` and `reports` come with later steps; a
manifest using them is refused for now (unknown fields are errors, so typos don't pass silently).
The platform contract version is `1.0` (`GET /api/v1/platform`). Samples: `extensions/samples`.

Install, upgrade, disable and uninstall are per tenant, by an administrator, and audited. On
install the platform validates the manifest, checks `platform` against its contract version,
shows the requested permissions, and creates the extension's **service account**. Upgrading
shows a diff of actions, rules and permissions before applying.

## Custom actions

- **Registry** (built: roadmap 2.1, module `extensions`). Every action, built-in or not, is an entry: id, label, icon, placement,
  applies-to (types including subtypes, states), required permission, visibility condition,
  handler. The UI asks the server which actions are available for an object (`GET
  /api/v1/objects/{id}/actions`), with a reason for each disabled one, so menus are never hard-coded.
- **Placement:** object toolbar, object "More" menu, table row menu, table bulk actions, context
  toolbar, global "Create" menu.
- **Handlers:** webhook (request signed with HMAC, includes object snapshot and the user), start a
  workflow, run a script, open a URL or a UI slot.
- **Results** a handler can return: a message (info / warning / error), refresh, navigate to an
  object, download a file, or a follow-up form.
- **Profiles** hide actions from groups of users without changing access rights.

## Rules (synchronous hooks)

Run inside the operation's transaction, before it commits: `object.create`, `object.check-in`,
`object.revise`, `object.set-state`, `object.delete`, `link.add`, … A rule can:

- **reject** with a localized message (and a field, for forms),
- **set defaults** on create (scripts, Phase 5),
- **require** a condition for a transition (gate criteria in lifecycles use the same rules).

Conditions are [CEL](https://cel.dev) expressions (built: roadmap 2.2; variables in DEVELOPMENT.md, *Expressions*) over a documented object model (`object`,
`user`, `context`, `event`). CEL is side-effect free and fast, with a bounded execution cost.
Webhook rules are allowed but must answer within 2 s; on timeout the operation fails closed or
open as the rule declares.

## Event subscriptions and webhooks

After commit, from the transactional outbox. Filters are CEL. Delivery is at-least-once with an
idempotency key, exponential backoff, a dead-letter queue and a delivery log the admin can replay.
Extensions that prefer streaming can read the Kafka topics with their service account.

## Sandboxed scripts (Phase 5)

JavaScript on GraalJS, one isolated context per execution, limits on CPU time, memory and
statements, no file system, no network unless the manifest grants specific hosts. The script API
is a typed facade (`plm.objects.get`, `plm.objects.update`, `plm.numbering.next`, …) that enforces
the user's access and the extension's permissions. Used for: defaulting and numbering strategies,
complex validations, automation steps, calculated attributes too complex for CEL.

## UI extension slots (Phase 5)

- **Declarative panels:** a property panel or table defined in the manifest, filled from a report
  or an extension endpoint. Rendered by us, so it looks native.
- **Frames:** a sandboxed iframe in a slot (object tab, side panel, context page, dashboard
  widget, full page). It talks to the host with a small SDK (`postMessage`): current object,
  theme, navigation, toast, and a short-lived token limited to the extension's permissions.

## Extension endpoints (Phase 5)

Extensions can expose operations under `/api/ext/{extension}/…`, implemented by a webhook or a
script, protected by our authentication and access checks. Used for custom integrations and for
data that UI slots need.

## Reports (Phase 3)

Report definitions (type, criteria, columns, grouping, parameters) built in the UI or shipped
in an extension. Always access-filtered. Usable as tables, dashboard widgets, exports, scheduled
emails and threshold alerts.

## Testing and promotion

- A CLI (`plm ext validate | test | pack | install`) validates manifests and runs rule and
  script tests against fixtures locally.
- Extensions and configuration bundles are plain files, so they live in the customer's Git
  repository and move through environments with the same review process as code.
- Every platform release runs a compatibility suite on the sample extensions.

## How common customization needs map to this

| Need | Where it goes |
|---|---|
| Add an attribute, a subtype, a list of values | Configuration |
| Change which fields a form shows | Layouts (configuration) |
| Custom numbering (`PLANT-YYYY-####`) | Numbering scheme; script strategy for unusual cases |
| Hide an action for some users | Profile, or the action's `visibleWhen` |
| New button that does something | Custom action + handler |
| Validate before check-in / release | Rule |
| Tell another system when something is released | Event subscription |
| Do something automatically in a workflow | Automation step (script or webhook) |
| A new tab with external data | UI slot |
| A custom report or dashboard widget | Report definition |
| A new integration API | Extension endpoint, or an external service on our REST API |
