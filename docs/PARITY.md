# Capability parity matrix

**Goal:** match the capabilities engineering organizations rely on in established enterprise PLM
systems (Windchill is the reference point), and do better on UI, configurability and openness.

**Ground rules**
- We match **capabilities and workflows**, not implementations. We never copy a vendor's product
  names, internal class names, screens, documentation or proprietary APIs. Our names are generic
  industry terms (Part, Document, Change Notice…). Importing customers' data from other PLM
  systems is in scope (Phase 9).
- "Out of the box" (OOTB) means it works after install with sensible defaults, and every default
  can be changed by an administrator **without writing code**.
- Anything that cannot be configured is extensible through the
  [extensibility platform](EXTENSIBILITY.md) (custom actions, rules, automations, UI slots,
  reports), never by patching internals.

**Status:** ✅ done · 🟡 partial · ⬜ planned (phase in brackets, see [ROADMAP.md](ROADMAP.md))

**How this list was built.** We inventoried the public capability descriptions of the reference
system (help center topics, release notes, module overviews) and grouped them by area in our own
words. The vendor's help pages could not be fetched directly, so a few fine details came from
search summaries; those are marked *(to confirm)*. The list is about **what** users can do, never
**how** the reference system implements it.

---

## 1. Platform, contexts and administration

| Capability | Status | Notes |
|---|---|---|
| Context hierarchy: Site → Organization → Product / Library / Project | ✅ | Closure table, inherited teams and policies |
| **Context templates** (product, library, project, organization): team roles, folders, policies, preferences, seed objects. Create a context from a template; export a context as a template | 🟡 | Rules, team, shared teams ✅ · in config bundles ✅ · folders, preferences and seed objects [3] |
| Teams with roles per context, inherited down the tree | ✅ | Inherited members shown on each context |
| Custom team roles (e.g. Design Engineer, Change Admin) | ✅ | Roles are data, managed in the admin UI |
| Shared teams (one team reused by several contexts) | ✅ | Admin → Shared teams; attach on a context's Team tab |
| Access policies by role × type × lifecycle state | ✅ | Managed per context by its managers |
| Policy admin UI, policies that apply to subtypes | ✅ | Access tab on each context |
| Deny entries and absolute denies (override inherited grants) | ✅ | A deny always wins over any allow |
| Ad hoc access on one object (grant a person extra rights) | ⬜ [3] | |
| Delegated administrators: site, organization and context admins | ✅ | Organization managers: team, rules, preferences, own shared teams and templates; context managers: team, rules, preferences |
| Users, groups, organizations; sync with LDAP/AD/SCIM via the identity provider | 🟡 | Users from OIDC ✅ · groups and sync [9] |
| Profiles: what actions and UI a group of users sees (e.g. a "viewer" profile) | ⬜ [2] | Combined with action visibility rules |
| **Preferences** with levels (site → organization → context → user) and locking at a level | ✅ | Declared by modules; Preferences tab on each context and My preferences |
| Folders (cabinets) inside contexts, folder-level defaults | ⬜ [3] | |
| Security labels (e.g. export control, proprietary) with clearance, and agreements that grant temporary access | ⬜ [9] | Combined with policy checks in the SQL predicate |
| Audit trail of every change; configurable audit events and reports | 🟡 | Append-only log ✅ · admin reports and filters [3] |
| Domain events for integrations | ✅ | Transactional outbox → Kafka |
| Localization of the UI and of admin-defined labels | 🟡 | Attribute and value labels ✅ · UI strings, type labels [1.16] |
| Multi-tenancy (SaaS) and single-tenant installs | 🟡 | `tenant_id` everywhere ✅ · tenant admin [9] |

## 2. Types and attributes (the foundation for customization)

| Capability | Status | Notes |
|---|---|---|
| Type hierarchy with admin-defined subtypes | ✅ | |
| Type properties: icon, display name, description, instantiable flag, hidden from create menus | 🟡 | Name ✅ · icon, instantiable, hidden [1.16] |
| Attribute definitions on types, inherited by subtypes | ✅ | Names unique along the hierarchy |
| Data types: text, long text, integer, real, **real with units**, boolean, date, timestamp, URL, enumeration, reference | 🟡 | All but reference (needs object links, [4]) |
| Units of measure and quantities (conversion, display unit) | ✅ | ~60 units, 17 dimensions; admin-defined units [1.16] |
| Reusable attributes shared across types (global attributes) | ⬜ [1.16] | |
| Alias attributes (show a value from a related object, e.g. the part's source on a CAD document) | ⬜ [4] | Needs links |
| Calculated attributes (expression over other attributes) | ⬜ [2] | Same expression language as rules |
| Enumerations / legal value lists, localized labels, dependent (cascading) lists | 🟡 | Lists ✅ · dependent lists [1.16] |
| Constraints: required, range, length, regex, immutable, unique, state-dependent required | 🟡 | All but unique and state-dependent; server-enforced |
| Multi-valued attributes | ✅ | |
| Layouts: attribute groups and order per type, for create / edit / info / table / search | 🟡 | Create, edit, info ✅ (inherited, hidden attributes, 1–2 columns) · admin editor with live preview ✅ · table/search columns [3] |
| Initialization rules per type: numbering, revision scheme, defaults, lifecycle, folder, team | 🟡 | Numbering, revision, lifecycle, defaults ✅ · folder [3] · team via context templates ✅ |
| Numbering per context or per subtype; pluggable numbering strategies | 🟡 | Per type ✅ · per context ✅ (preference) · scripted [5] |
| Classification (class hierarchy with class-specific attributes) | ⬜ [7] | |
| Admin UI for all of the above, with live form preview | ✅ | Administration page for site admins |
| Move configuration between environments (dev → test → prod) | ✅ | One JSON bundle; plan with diffs, then all-or-nothing apply |

## 3. Out-of-the-box object types

Seeded with sensible OOTB attributes; every one can be extended, hidden or constrained by admins.

| Type | Status | OOTB attributes and behaviour (beyond number, name, version, state, created/modified) |
|---|---|---|
| **Part** | ✅ | source (make/buy/…), default unit, assembly mode, end item, phantom, trace code, gathering part, view, description |
| **Document** | 🟡 | title, description, category, department ✅ · primary content, attachments [3] |
| Document templates (start a document from a template file) | ⬜ [3] | |
| **CAD Document** | ⬜ [6] | authoring application, file name, CAD type (part/assembly/drawing), generic/instance |
| **Problem Report** | ⬜ [5] | category, priority, need date, steps to reproduce, affected objects |
| **Change Request** | ⬜ [5] | category, priority, complexity, recurring / non-recurring cost, need date |
| **Change Notice** | ⬜ [5] | complexity, need date, change tasks, affected and resulting objects |
| **Change Task** | ⬜ [5] | assignee, reviewer, need date, affected and resulting objects |
| **Variance / Deviation / Waiver** | ⬜ [5] | category, duration or quantity limit, affected objects |
| **Promotion Request** | ⬜ [5] | target state, objects, reviewers |
| **Baseline** and **managed baseline** | ⬜ [4] | members, locked flag |
| **Managed collection** (a named, versionable set of objects) | ⬜ [4] | |
| **Package / Delivery** (controlled data set sent to a supplier or partner) | ⬜ [9] | |
| **Manufacturer part / Vendor part** | ⬜ [13] | manufacturer, vendor, sourcing status |
| **Option set, Option, Choice, Variant specification** | ⬜ [7] | |
| **Classification node** | ⬜ [7] | |
| **Representation / visualization data** | ⬜ [6] | |
| **Discussion topic / post, Meeting, Action item** | ⬜ [3] | |
| **Process plan, Operation, Resource (plant, workcenter, tooling, skill)** | ⬜ [10] | |
| **Quality objects**: nonconformance, CAPA, customer complaint, audit, FMEA, control plan | ⬜ [12] | |
| **Requirement / Specification** | ⬜ [14] | |
| **Information element / Publication structure / Service BOM** | ⬜ [14] | |
| **Software part / build / release** | ⬜ [14] | |

## 4. Versioning and object operations

| Capability | Status | Notes |
|---|---|---|
| Master / revision / iteration (A.1, A.2, B.1) | ✅ | History immutable, enforced by the database |
| Check out / check in / undo check-out | ✅ | |
| Revise (single and bulk), with options for which related objects follow | 🟡 | Single ✅ · bulk and "collect related" [4] |
| Revision schemes per type (letters, numbers, custom series) | ✅ | ASME letters, A–Z, 1…, 0…; kept per object |
| State-based revision series (e.g. numbers while In Work, letters once Released) | ⬜ [1.15] | *(to confirm details)* |
| Set state, promote through lifecycle, admin override | ✅ | PROMOTE permission; override audited |
| Rename / renumber (with audit, master-level, all versions) | ✅ | Managers; More → Rename or renumber |
| Save As (copy to a new number), with or without structure and related objects | ⬜ [3] | |
| Move between contexts / folders (with access re-check) | ⬜ [3] | |
| Delete: working iteration, latest iteration, revision, whole object (reference checks) | ⬜ [3] | |
| Reassign lifecycle, reset team, change type (admin) | ⬜ [1.15] | |
| New view version (design → manufacturing / service) | ⬜ [10] | |
| One-off version (a variant of a released revision for one order) | ⬜ [7] | *(to confirm)* |
| Ownership and reservation for long edits | ⬜ [3] | |
| Compare iterations / revisions / any two objects (attributes, content, structure) | ⬜ [3] | Structure compare [4] |
| Where-used and related objects (any link type) | ⬜ [4] | |
| Clipboard: copy, paste as link, paste as new | ⬜ [3] | |
| Mass operations: change attribute, set state, revise, move, many objects at once | ⬜ [3] | Run as background jobs with a report |
| Mass change of structures (replace a part everywhere it's used) | ⬜ [5] | Through a change notice |

## 5. Lifecycle

| Capability | Status | Notes |
|---|---|---|
| Lifecycle templates: states and allowed transitions | ✅ | Basic: In Work → Under Review → Released → Obsolete, Canceled |
| Transitions: promote, demote, revise, set state | ✅ | Revise only from revisable states |
| Lifecycle per type, inherited, kept per object | ✅ | |
| State-based access (e.g. Released is read-only) | ✅ | |
| **User-defined lifecycle templates**: create, edit, copy, version; in-use objects keep their version | 🟡 | Create via API ✅ · edit, copy, versions, admin UI [1.15] |
| Phases and gates: criteria to pass a gate, roles per phase | ⬜ [1.15] | Gate criteria are rules (expressions) |
| Workflow per phase / per transition | ⬜ [5] | |
| Lifecycle templates scoped to a context (org or product) | ⬜ [1.15] | |
| Admin editor with a state diagram | 🟡 | Diagram, create and copy ✅ · editing templates in use [1.15] |

## 6. Content, documents and visualization

| Capability | Status | Notes |
|---|---|---|
| Primary content + secondary attachments, URL links | ⬜ [3] | Content-addressed vault (ADR 0007) |
| Upload/download straight to object storage, resumable large files | ⬜ [3] | Pre-signed URLs |
| Desktop / office integration (open, edit locally, check in) | ⬜ [8] | |
| Thumbnails and previews (PDF, images, office) | ⬜ [3] | Conversion workers |
| Publishing: generate PDF / neutral formats on check-in or release, with watermarks and stamps | ⬜ [6] | Publishing queue with rules per type/state |
| Web 3D and 2D viewers, markups and annotations stored as objects | ⬜ [6] | |
| Augmented reality / mobile viewing | ⬜ [14] | Possibly via partners |

## 7. CAD data management

| Capability | Status | Notes |
|---|---|---|
| CAD documents with dependency structure (assemblies, drawings, references) | ⬜ [6] | |
| Personal workspaces (local cache of files, sync status, conflicts) | ⬜ [8] | |
| CAD connectors (SolidWorks, Creo, NX, Inventor, CATIA, electrical CAD) | ⬜ [8] | Each its own project |
| Family tables / generics and instances | ⬜ [6] | |
| Collector: gather dependents and drawings for an operation | ⬜ [6] | |
| Part ↔ CAD association (owner, content, image), auto-associate, build part structure from CAD | ⬜ [6] | |
| Configuration specs for CAD (latest, as-stored, baseline) | ⬜ [6] | |
| Model-based definition (3D annotations, PMI) carried through publishing | ⬜ [6] | |

## 8. Product structure

| Capability | Status | Notes |
|---|---|---|
| Part usage links to child **masters**: quantity, unit, find number, line number | ⬜ [4] | ADR 0003 |
| Reference designators / occurrences, occurrence-level attributes | ⬜ [4] | |
| Configuration specs: latest, released (by state), baseline, effectivity, promotion request | ⬜ [4] | Effectivity [7] |
| Multi-level BOM tree table, expand all, 50k lines < 2 s | ⬜ [4] | |
| Structure editing: insert existing/new, replace, remove, paste, reorder, bulk edit | ⬜ [4] | |
| Find in structure, filter structure by attribute | ⬜ [4] | |
| Where-used (single and multi-level, with config spec) | ⬜ [4] | |
| BOM compare (two versions, two configurations, part vs CAD) | ⬜ [4] | Part vs CAD [6] |
| Alternates (global) and substitutes (per usage) | ⬜ [4] | |
| Part ↔ document links (describes / references) | ⬜ [4] | |
| Multi-level BOM reports, roll-ups (mass, cost, compliance) | ⬜ [4] | Roll-ups are reports with expressions |
| BOM import/export (Excel/CSV) | ⬜ [4] | |
| Redline a BOM (propose changes, review, apply) | ⬜ [5] | |

## 9. Configuration: effectivity, options and variants

| Capability | Status | Notes |
|---|---|---|
| Effectivity: date, serial/unit, lot, block, on usage links and on versions | ⬜ [7] | |
| Effectivity contexts (effectivity relative to an end item) | ⬜ [7] | |
| Pending effectivity set through a change notice | ⬜ [7] | |
| Options and choices, option sets, rules (includes / excludes / requires) | ⬜ [7] | |
| Configurable (150%) structures with choice-based conditions on usages | ⬜ [7] | |
| Variant specifications: pick choices, filter the structure, generate variant parts, reuse existing variants | ⬜ [7] | |

## 10. Change management

| Capability | Status | Notes |
|---|---|---|
| Problem Report → Change Request → Change Notice → Change Tasks | ⬜ [5] | |
| Fast track (change notice without a request) and full track | ⬜ [5] | Choice per change template |
| Affected objects, resulting objects, affected end items | ⬜ [5] | |
| Release targets per object (released, obsolete, …) | ⬜ [5] | |
| Implementation plan with change tasks and dependencies | ⬜ [5] | |
| Change review board, decisions and dispositions (in-process, on-order, finished) | ⬜ [5] | |
| Change templates (pre-filled change objects) | ⬜ [5] | |
| Rules: which objects can be affected by which change types | ⬜ [5] | Association rules as data |
| Variances / deviations with limits | ⬜ [5] | |
| Promotion requests (approve a state change without a full change process) | ⬜ [5] | |
| Release on change notice completion, everything changes state together | ⬜ [5] | |
| Change dashboards and reports (open changes, cycle time) | ⬜ [5] | |

## 11. Workflow

| Capability | Status | Notes |
|---|---|---|
| Durable, versioned workflow engine | ⬜ [5] | Temporal (ADR 0006) |
| Visual process template editor, template versions, import/export | ⬜ [5] | |
| Tasks and inbox, assignment by team role, group or person | ⬜ [5] | |
| Voting, routing choices, parallel/sequential reviews, conditional branches | ⬜ [5] | |
| Delegation, out-of-office, reassign, due dates, escalation and reminders | ⬜ [5] | |
| Automated steps (set state, send notification, call an extension, run a script) | ⬜ [5] | Replaces in-template code |
| Electronic signatures with reason and re-authentication (21 CFR Part 11) | ⬜ [5] | |
| Workflow history, process monitor, admin abort/restart | ⬜ [5] | |

## 12. Collaboration and user experience

| Capability | Status | Notes |
|---|---|---|
| Configurable home page and dashboards | ⬜ [3] | Built from report widgets |
| Recently visited, favorites, pinned contexts | ⬜ [3] | |
| Subscriptions / notifications (in-app, email, daily digest) | ⬜ [3] | Event consumers |
| Discussions on objects and contexts, mentions | ⬜ [3] | |
| Meetings and action items | ⬜ [11] | |
| Share objects into a project context and send updates back | ⬜ [11] | |
| Table views: choose columns, filters, sort, save and share views, export to Excel | 🟡 | Sort ✅ · columns, saved views, export [3] |
| Role-based, task-focused apps (e.g. a simple "request a change" app) | ⬜ [5] | Built on UI extension slots |
| Mobile and offline-friendly views | ⬜ [14] | Responsive web first |
| Keyboard shortcuts, command palette, dark mode, accessibility (WCAG 2.2 AA) | 🟡 | Dense workbench ✅ · palette, dark mode [3] |

## 13. Search and reporting

| Capability | Status | Notes |
|---|---|---|
| Global search by number/name, permission-filtered | ✅ | Trigram index |
| Full-text search in attributes and file content, facets | ⬜ [3] | OpenSearch via events |
| Advanced search by type and attribute criteria; saved and shared searches | ⬜ [3] | |
| Classification search, find similar parts | ⬜ [7] | |
| Report definitions (query, columns, grouping) without code; reports in any table | ⬜ [3] | Always access-filtered |
| Scheduled reports and threshold alerts ("tell me when …") | ⬜ [3] | |
| Charts and dashboards | ⬜ [3] | |

## 14. Security and compliance

| Capability | Status | Notes |
|---|---|---|
| Security labels and agreements | ⬜ [9] | May move earlier for defense customers |
| Electronic signatures, signature manifest | ⬜ [5] | |
| Retention policies, legal hold, purge and archive with restore | ⬜ [9] | |
| Audit reports and audit configuration | ⬜ [3] | |
| Access reports ("who can see this, and why") | 🟡 | Per person and target, with the deciding rules ✅ · "who can see this object" list [3] |

## 15. Data exchange and integration

| Capability | Status | Notes |
|---|---|---|
| Complete REST API (everything the UI does) with OpenAPI | ✅ | Contract committed and tested |
| Webhooks and event subscriptions | ⬜ [2] | Extensibility platform |
| Service accounts and OAuth client credentials for integrations | ⬜ [2] | |
| Bulk load from spreadsheets / files with validation and a dry run | ⬜ [9] | |
| Migration from other PLM systems | ⬜ [9] | |
| Import/export of object sets between sites or companies (packages, deliveries) | ⬜ [9] | |
| ERP integration: release distribution targets, plant-specific data, acknowledgement | ⬜ [9] | Event-driven connectors |
| Standards: STEP AP242, ReqIF, IPC-1752, OSLC links | ⬜ [9] | |

## 16. Infrastructure and operations

| Capability | Status | Notes |
|---|---|---|
| File replication / caches near remote sites | ⬜ [9] | |
| Background job queues with admin monitoring and retry | ⬜ [3] | Publishing, mass operations, imports |
| Scheduled jobs | ⬜ [3] | |
| Monitoring, tracing, metrics, health | 🟡 | Health ✅ · OTel/Prometheus [0.12] |
| Horizontal scaling, zero-downtime upgrades | 🟡 | Stateless app ✅ · upgrade playbook [9] |
| Configuration promotion between environments | ✅ | Export / import on the Admin page and API |

## 17. Manufacturing process management

| Capability | Status | Notes |
|---|---|---|
| Downstream structures: EBOM → MBOM (and service BOM), plant-specific views | ⬜ [10] | |
| Equivalence links and reconciliation (what changed upstream, what's missing downstream) | ⬜ [10] | |
| Generate downstream structure from upstream | ⬜ [10] | |
| Process plans, operations, sequences, resources, work instructions | ⬜ [10] | |
| Consumption of parts per operation, time and cost per operation | ⬜ [10] | |
| Plant data and plant-specific attributes | ⬜ [10] | |

## 18. Quality and reliability

| Capability | Status | Notes |
|---|---|---|
| Nonconformance, CAPA, customer complaints, audits | ⬜ [12] | |
| Regulatory submissions (e.g. medical device reports) | ⬜ [12] | |
| FMEA, control plans, characteristics | ⬜ [12] | |
| Reliability: prediction, Weibull, fault trees, block diagrams, FRACAS, life-cycle cost | ⬜ [14] | Specialist area, maybe via partners |

## 19. Requirements

| Capability | Status | Notes |
|---|---|---|
| Requirements and specifications as versioned objects | ⬜ [14] | |
| Trace links (allocated to, satisfied by, verified by) with suspect flags on change | ⬜ [14] | |
| Import from requirement tools (ReqIF) | ⬜ [14] | |

## 20. Supplier management and environmental compliance

| Capability | Status | Notes |
|---|---|---|
| Manufacturer and vendor parts, approved manufacturer/vendor lists | ⬜ [13] | |
| Sourcing contexts, sourcing status, preference rules | ⬜ [13] | |
| Material declarations, substance lists, RoHS / REACH / conflict minerals | ⬜ [13] | |
| Compliance roll-ups up the BOM | ⬜ [13] | Uses BOM roll-ups |

## 21. Projects and programs

| Capability | Status | Notes |
|---|---|---|
| Project plans, activities, milestones, Gantt, dependencies | ⬜ [11] | |
| Deliverables linked to PLM objects, status roll-up | ⬜ [11] | |
| Resources and assignments, project templates | ⬜ [11] | |
| Import/export to scheduling tools | ⬜ [11] | |

## 22. Service information and parts

| Capability | Status | Notes |
|---|---|---|
| Information structures and publication structures | ⬜ [14] | |
| Translation management (export/import for translators) | ⬜ [14] | XLIFF |
| Service BOM, parts lists, illustrated parts catalogues | ⬜ [14] | |
| Supersession and interchangeability, service effectivity | ⬜ [14] | |

## 23. Software and electronics

| Capability | Status | Notes |
|---|---|---|
| Software parts and builds tied to hardware structures | ⬜ [14] | |
| Electronic CAD data (schematics, boards) and component libraries | ⬜ [8] | |

## 24. Customization and extensibility

Full design: [EXTENSIBILITY.md](EXTENSIBILITY.md) and [ADR 0018](adr/0018-extensibility-platform.md).

| What customers customize in the reference system | Our equivalent | Status |
|---|---|---|
| Types, attributes, constraints, enumerations, layouts | Admin configuration (no code) | ✅ Types, attributes, layouts, all in the admin UI |
| Object initialization rules, numbering | Type rules + numbering strategies (scripts for edge cases) | 🟡 [1.5 ✅, 5] |
| Lifecycle and workflow templates | Admin editors, versioned templates | 🟡 [1.15, 5] |
| Preferences | Typed preference registry with levels and locks | ✅ |
| Moving configuration between systems | Configuration bundles (diff, validate, apply) | ✅ |
| New actions in menus and toolbars | **Custom actions** declared in an extension manifest | ⬜ [2] |
| Rules that hide or disable actions | Action visibility conditions (expressions) | ⬜ [2] |
| Server code that runs before an operation (validation, defaulting) | **Pre-operation rules**: expressions, then sandboxed scripts | ⬜ [2, 5] |
| Server listeners that react after an operation | Event subscriptions and webhooks | ⬜ [2] |
| Code inside workflow steps | Automation steps calling extension handlers or scripts | ⬜ [5] |
| Custom UI pages, tabs and table columns | **UI extension slots**: declarative panels and sandboxed micro-frontends | ⬜ [5] |
| Custom web services / integration endpoints | Extension endpoints under `/api/ext/{extension}/…` | ⬜ [5] |
| Custom reports and queries | Report definitions (no code), access-filtered | ⬜ [3] |
| Packaging and checking customizations for upgrades | Extension packages: versioned manifest, compatibility checks, signed | ⬜ [2] |

---

## Where we aim to be better

| Area | How |
|---|---|
| **No-code customization** | Types, attributes, constraints, layouts, numbering, lifecycles and policies are all admin-configurable in the UI, with live preview. |
| **Upgrade-safe extensions** | Customizations use a versioned, public contract (manifests, API, events, sandboxed scripts), never patches to internals. Upgrades check compatibility before they install. |
| **Configuration as code** | Every admin setting can be exported to and imported from version-controlled files (dev → test → prod), with a diff before applying. |
| **Modern UI** | Fast, keyboard-friendly, inline editing, saved table views, dark mode, accessible (WCAG 2.2 AA). |
| **API-first** | Every UI action is a documented, typed REST call. Integrations and extensions use the same API as the UI. |
| **Events built in** | Every change is a domain event: integrations subscribe instead of polling. |
| **Explainable access** | "Why can (or can't) this person see this?" answered by the system, not by an expert. |
| **Search that feels instant** | Permission-aware full-text and attribute search with facets. |
| **Operations** | Containers, horizontal scaling, observability out of the box, single-tenant or SaaS. |
