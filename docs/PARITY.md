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
| **Context templates** (product, library, project, organization): team roles, folders, policies, preferences, seed objects. Create a context from a template; export a context as a template | 🟡 | Rules, team, shared teams ✅ · in config bundles ✅ · folders, preferences and seed objects [later] |
| Teams with roles per context, inherited down the tree | ✅ | Inherited members shown on each context |
| Custom team roles (e.g. Design Engineer, Change Admin) | ✅ | Roles are data, managed in the admin UI |
| Shared teams (one team reused by several contexts) | ✅ | Admin → Shared teams; attach on a context's Team tab |
| Access policies by role × type × lifecycle state | ✅ | Managed per context by its managers |
| Policy admin UI, policies that apply to subtypes | ✅ | Access tab on each context |
| Deny entries and absolute denies (override inherited grants) | ✅ | A deny always wins over any allow |
| Ad hoc access on one object (grant a person extra rights) | ✅ | Share one object with anyone, to view or edit, optionally until a date [3.10] |
| Delegated administrators: site, organization and context admins | ✅ | Organization managers: team, rules, preferences, own shared teams and templates; context managers: team, rules, preferences |
| One built-in site administrator, made at installation | ✅ | `plmadmin`, which chooses its password at the first sign-in; Administration › Site administrators lists everyone with the rights and warns while there's more than one; `PLM_ONLY_BUILT_IN_ADMIN=true` makes it a rule |
| Users, groups, organizations; sync with LDAP/AD/SCIM via the identity provider | 🟡 | Users from OIDC ✅ · groups and sync [9] |
| Profiles: what actions and UI a group of users sees (e.g. a "viewer" profile) | ✅ | Profiles hide actions, tabs and UI areas for their members [2.8] |
| **Preferences** with levels (site → organization → context → user) and locking at a level | ✅ | Declared by modules; Preferences tab on each context and My preferences |
| Folders (cabinets) inside contexts, folder-level defaults | 🟡 | Folders with paths, move between folders and contexts ✅ [3.4] · folder-level defaults [later] |
| Security labels (e.g. export control, proprietary) with clearance, and agreements that grant temporary access | ⬜ [9] | Combined with policy checks in the SQL predicate |
| Audit trail of every change; configurable audit events and reports | 🟡 | Append-only log ✅ · audit report by period, person, kind and context with Excel export ✅ [3.10] · choosing which events are audited [9] |
| Domain events for integrations | ✅ | Transactional outbox → Kafka |
| Localization of the UI and of admin-defined labels | ✅ | Every screen in English, French and German, a language per user ✅ [3.12] · server-written messages still in English |
| Multi-tenancy (SaaS) and single-tenant installs | 🟡 | `tenant_id` everywhere ✅ · tenant admin [9] |

## 2. Types and attributes (the foundation for customization)

| Capability | Status | Notes |
|---|---|---|
| Type hierarchy with admin-defined subtypes | ✅ | |
| Type properties: icon, display name, description, instantiable flag, hidden from create menus | ✅ | Abstract types group subtypes and are hidden from New object |
| Attribute definitions on types, inherited by subtypes | ✅ | Names unique along the hierarchy |
| Data types: text, long text, integer, real, **real with units**, boolean, date, timestamp, URL, enumeration, reference | ✅ | All, including references to other objects [4.12] |
| Units of measure and quantities (conversion, display unit) | ✅ | ~60 units out of the box; admins add units and dimensions |
| Reusable attributes shared across types (global attributes) | ✅ | Admin → Global attributes; a change applies to every type using it |
| Alias attributes (show a value from a related object, e.g. the part's source on a CAD document) | ✅ | Alias attributes show a related object's value [4.12] |
| Calculated attributes (expression over other attributes) | ✅ | CEL formula over the object and user, computed on save [2.2] |
| Enumerations / legal value lists, localized labels, dependent (cascading) lists | ✅ | Dependent lists: allowed values per parent value, enforced by the server |
| Constraints: required, range, length, regex, immutable, unique, state-dependent required | 🟡 | Required, range, length, regex, immutable ✅ (server-enforced) · unique and state-dependent required through validation rules (CEL or scripts) [2.5, 5.14], no dedicated setting |
| Multi-valued attributes | ✅ | |
| Layouts: attribute groups and order per type, for create / edit / info / table / search | ✅ | Create, edit, info ✅ · table columns and views [3.7] · search columns [3.6] |
| Initialization rules per type: numbering, revision scheme, defaults, lifecycle, folder, team | 🟡 | Numbering, revision, lifecycle, defaults ✅ · team via context templates ✅ · initial folder per type [later] |
| Numbering per context or per subtype; pluggable numbering strategies | ✅ | Per type, per context (preference) and scripted numbering [5.14] |
| Classification (class hierarchy with class-specific attributes) | ✅ | |
| Admin UI for all of the above, with live form preview | ✅ | Administration page for site admins |
| Move configuration between environments (dev → test → prod) | ✅ | One JSON bundle; plan with diffs, then all-or-nothing apply |

## 3. Out-of-the-box object types

Seeded with sensible OOTB attributes; every one can be extended, hidden or constrained by admins.

| Type | Status | OOTB attributes and behaviour (beyond number, name, version, state, created/modified) |
|---|---|---|
| **Part** | ✅ | source (make/buy/…), default unit, assembly mode, end item, phantom, trace code, gathering part, view, description |
| **Document** | ✅ | Attributes, primary content and attachments, previews [3.2–3.3] |
| Document templates (start a document from a template file) | ✅ | Templates per site or organization [3.3] |
| **CAD Document** | ✅ | Part, assembly and drawing models, dependencies, family tables [6.1–6.3] |
| **Problem Report** | ✅ | Built-in type PR-, Change lifecycle [5.8] |
| **Change Request** | ✅ | Built-in type CR-, change board review [5.8–5.9] |
| **Change Notice** | ✅ | Built-in type CN-, implementation plan, release on resolve [5.8–5.9] |
| **Change Task** | ✅ | Built-in type CT-, assignee and due date [5.8–5.9] |
| **Variance / Deviation / Waiver** | ✅ | Variances with limits [5.10] |
| **Promotion Request** | ✅ | Numbered requests (PRM-), review process, objects locked while under review [5.7] |
| **Baseline** and **managed baseline** | ✅ | Frozen sets of exact iterations, usable as a configuration spec [4.10] |
| **Managed collection** (a named, versionable set of objects) | ✅ | Collections [4.10] |
| **Package / Delivery** (controlled data set sent to a supplier or partner) | ⬜ [9] | |
| **Manufacturer part / Vendor part** | ⬜ [13] | manufacturer, vendor, sourcing status |
| **Option set, Option, Choice, Variant specification** | ✅ | |
| **Classification node** | ✅ | |
| **Representation / visualization data** | ✅ | glTF made from STEP on check-in [6.6] |
| **Discussion topic / post, Meeting, Action item** | 🟡 | Discussion topics and replies with mentions ✅ [3.10] · meetings and action items [11] |
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
| Revise (single and bulk), with options for which related objects follow | ✅ | Single and bulk, structure revise with what follows [4.14] |
| Revision schemes per type (letters, numbers, custom series) | ✅ | ASME letters, A–Z, 1…, 0…; kept per object |
| State-based revision series (e.g. numbers while In Work, letters once Released) | ✅ | Per state: revisions started there continue in its series |
| Set state, promote through lifecycle, admin override | ✅ | PROMOTE permission; override audited |
| Rename / renumber (with audit, master-level, all versions) | ✅ | Managers; More → Rename or renumber |
| Save As (copy to a new number), with or without structure and related objects | ✅ | Save As with a new number [3.3] |
| Move between contexts / folders (with access re-check) | ✅ | Move between folders and contexts, guarded by rules [3.4] |
| Delete: working iteration, latest iteration, revision, whole object (reference checks) | ✅ | Delete rules per type, state and role, reference checks [3.4] |
| Reassign lifecycle, reset team, change type (admin) | 🟡 | Change lifecycle (with state mapping) ✅ · reset team, change type [later] |
| New view version (design → manufacturing / service) | ⬜ [10] | |
| One-off version (a variant of a released revision for one order) | ✅ | |
| Ownership and reservation for long edits | ✅ | Owners and reservations that block others' check-out [3.4] |
| Compare iterations / revisions / any two objects (attributes, content, structure) | ✅ | Versions and any two objects: values, files, text diff [3.3] · structure compare [4.9] |
| Where-used and related objects (any link type) | ✅ | Where-used with config specs, related objects on every link type [4.6–4.8] |
| Clipboard: copy, paste as link, paste as new | ✅ | Clipboard: paste as move, copy or usage link [3.4, 4.4] |
| Mass operations: change attribute, set state, revise, move, many objects at once | ✅ | Set state, move, delete, change an attribute as a background job with per-object results [3.5] |
| Mass change of structures (replace a part everywhere it's used) | ✅ | Mass change through a change notice's redlines [5.11] |

## 5. Lifecycle

| Capability | Status | Notes |
|---|---|---|
| Lifecycle templates: states and allowed transitions | ✅ | Basic: In Work → Under Review → Released → Obsolete, Canceled |
| Transitions: promote, demote, revise, set state | ✅ | Revise only from revisable states |
| Lifecycle per type, inherited, kept per object | ✅ | |
| State-based access (e.g. Released is read-only) | ✅ | |
| **User-defined lifecycle templates**: create, edit, copy, version; in-use objects keep their version | ✅ | Admin → Lifecycles; objects move to a newer version on request |
| Phases and gates: criteria to pass a gate, roles per phase | 🟡 | Entry criteria and CEL gates ✅ [2.2] · roles per phase [later] |
| Workflow per phase / per transition | ✅ | Process assignments per type, state, transition and context [5.5] |
| Lifecycle templates scoped to a context (org or product) | ✅ | Assignments per organization or product, inherited down the tree [5.5] |
| Admin editor with a state diagram | ✅ | Diagram, transition matrix, versions |

## 6. Content, documents and visualization

| Capability | Status | Notes |
|---|---|---|
| Primary content + secondary attachments, URL links | ✅ | Primary content and attachments per iteration [3.2] |
| Upload/download straight to object storage, resumable large files | 🟡 | Presigned uploads and downloads straight to the vault ✅ [3.2] · resumable uploads for very large files [9] |
| Desktop / office integration (open, edit locally, check in) | ✅ | Desktop sync client with personal tokens [8.3] · Office properties ↔ attributes, written into downloads [8.4] | |
| Thumbnails and previews (PDF, images, office) | ✅ | Thumbnails, PDF and image previews [3.2] · Office files through LibreOffice [8.4] |
| Publishing: generate PDF / neutral formats on check-in or release, with watermarks and stamps | ✅ | Rules per type and state, watermarks and stamps, PDF data sheets [6.7] |
| Web 3D and 2D viewers, markups and annotations stored as objects | ✅ | 3D viewer with measure, section, saved views and 3D markups; 2D/PDF viewer with markups [6.8–6.11] |
| Augmented reality / mobile viewing | ⬜ [14] | Possibly via partners |

## 7. CAD data management

| Capability | Status | Notes |
|---|---|---|
| CAD documents with dependency structure (assemblies, drawings, references) | ✅ | Dependency structure, STEP import [6.1, 6.5] |
| Personal workspaces (local cache of files, sync status, conflicts) | ✅ | File states (in sync, out of date, modified, conflict…), all-or-nothing check-in, new files [8.1–8.2] | |
| CAD connectors (SolidWorks, Creo, NX, Inventor, CATIA, electrical CAD) | 🟡 | Connector protocol and clients ✅ [8.5] · FreeCAD ✅ [8.6] · KiCad [8.7] · commercial tools need their SDKs [8.8] | Each its own project |
| Family tables / generics and instances | ✅ | Generic models and instances from a parameter table [6.3] |
| Collector: gather dependents and drawings for an operation | ✅ | Dependents and drawings collected for check-in, revise and state changes [6.2] |
| Part ↔ CAD association (owner, content, image), auto-associate, build part structure from CAD | ✅ | Owning and contributing models, build part structure from CAD [6.4] |
| Configuration specs for CAD (latest, as-stored, baseline) | ✅ | Latest, released, as of a date, baseline [4.2, 6.1] |
| Model-based definition (3D annotations, PMI) carried through publishing | ✅ | Dimensions, tolerances, datums and notes on the 3D model, carried into publishing [6.12] |

## 8. Product structure

| Capability | Status | Notes |
|---|---|---|
| Part usage links to child **masters**: quantity, unit, find number, line number | ✅ | Quantity, unit, find and line numbers [4.1] |
| Reference designators / occurrences, occurrence-level attributes | ✅ | Occurrences with reference designators and their own attributes [4.5] |
| Configuration specs: latest, released (by state), baseline, effectivity, promotion request | 🟡 | Latest, state, as-of, baseline, effectivity ✅ · promotion request [later] |
| Multi-level BOM tree table, expand all, 50k lines < 2 s | ✅ | Lazy multi-level tree, expand all, virtualized rows [4.3] |
| Structure editing: insert existing/new, replace, remove, paste, reorder, bulk edit | ✅ | Add existing or new, copy and paste, replace, remove, reorder, edit quantities [4.4] |
| Find in structure, filter structure by attribute | ✅ | Find in structure ✅ · filter by attribute through table views [3.7, 4.3] |
| Where-used (single and multi-level, with config spec) | ✅ | Single and multi-level, under a config spec, up to end items [4.6] |
| BOM compare (two versions, two configurations, part vs CAD) | 🟡 | Two iterations, two parts, two configurations ✅ [4.9] · part vs CAD [later] |
| Alternates (global) and substitutes (per usage) | ✅ | Global alternates and per-line substitutes [4.8] |
| Part ↔ document links (describes / references) | ✅ | Described by and references [4.7] |
| Multi-level BOM reports, roll-ups (mass, cost, compliance) | ✅ | Roll-ups (mass, cost, counts) and multi-level reports [4.11] |
| BOM import/export (Excel/CSV) | ✅ | Excel/CSV with preview, validation and matching [4.13] |
| Redline a BOM (propose changes, review, apply) | ✅ | Redlines inside a change task, applied on release [5.11] |

## 9. Configuration: effectivity, options and variants

| Capability | Status | Notes |
|---|---|---|
| Effectivity: date, serial/unit, lot, block, on usage links and on versions | ✅ | |
| Effectivity contexts (effectivity relative to an end item) | ✅ | |
| Pending effectivity set through a change notice | ✅ | |
| Options and choices, option sets, rules (includes / excludes / requires) | ✅ | |
| Configurable (150%) structures with choice-based conditions on usages | ✅ | |
| Variant specifications: pick choices, filter the structure, generate variant parts, reuse existing variants | ✅ | |

## 10. Change management

| Capability | Status | Notes |
|---|---|---|
| Problem Report → Change Request → Change Notice → Change Tasks | ✅ | With links between them [5.8] |
| Fast track (change notice without a request) and full track | ✅ | A notice straight from a problem report [5.8] |
| Affected objects, resulting objects, affected end items | ✅ | Affected (with version), resulting and end items [5.8] |
| Release targets per object (released, obsolete, …) | 🟡 | Resulting objects released when the notice resolves ✅ [5.9, 5.13] · a different target state per object [later] |
| Implementation plan with change tasks and dependencies | ✅ | Tasks with assignees and due dates; a notice resolves only when they are done [5.9] |
| Change review board, decisions and dispositions (in-process, on-order, finished) | 🟡 | Change board review with a signed majority vote ✅ [5.9] · dispositions (in process, on order, finished) [later] |
| Change templates (pre-filled change objects) | ✅ | [5.10] |
| Rules: which objects can be affected by which change types | ✅ | Association rules as data [5.10] |
| Variances / deviations with limits | ✅ | [5.10] |
| Promotion requests (approve a state change without a full change process) | ✅ | [5.7] |
| Release on change notice completion, everything changes state together | ✅ | Release gate: Released only through an approved change notice [5.13] |
| Change dashboards and reports (open changes, cycle time) | ✅ | Open changes by state and age, my changes, cycle time, home widgets [5.12] |

## 11. Workflow

| Capability | Status | Notes |
|---|---|---|
| Durable, versioned workflow engine | ✅ | Temporal (ADR 0006), one generic interpreter, versioned templates [5.1] |
| Visual process template editor, template versions, import/export | ✅ | Visual editor, template versions [5.2] |
| Tasks and inbox, assignment by team role, group or person | ✅ | Users, team roles and groups; claim and answer [5.3] |
| Voting, routing choices, parallel/sequential reviews, conditional branches | ✅ | All, any or majority votes, parallel and sequential, conditions [5.2, 5.4] |
| Delegation, out-of-office, reassign, due dates, escalation and reminders | ✅ | Reassign, stand-ins with dates, due dates, reminders, escalation [5.4] |
| Automated steps (set state, send notification, call an extension, run a script) | ✅ | Set state, notify, call an extension, run a script; no-code automations [5.2, 5.16] |
| Electronic signatures with reason and re-authentication (21 CFR Part 11) | ✅ | Password asked again, immutable records with a hash [5.6] |
| Workflow history, process monitor, admin abort/restart | ✅ | History, process view, cancel [5.1] |

## 12. Collaboration and user experience

| Capability | Status | Notes |
|---|---|---|
| Configurable home page and dashboards | ✅ | Home with greeting, live counts and widgets in balanced columns; shared dashboards from report widgets [3.8] |
| Recently visited, favorites, pinned contexts | ✅ | Stars on objects, contexts and reports; recent visits; pins in the navigator [3.9] |
| Subscriptions / notifications (in-app, email, daily digest) | ✅ | Follow objects, folders and products; in-app bell, mail right away or as a daily digest [3.9] |
| Discussions on objects and contexts, mentions | 🟡 | Discussions on objects with mentions ✅ [3.10] · on contexts [later] |
| Meetings and action items | ⬜ [11] | |
| Share objects into a project context and send updates back | ⬜ [11] | |
| Table views: choose columns, filters, sort, save and share views, export to Excel | ✅ | Columns, filters, sort, saved and shared views, Excel export [3.7] |
| Role-based, task-focused apps (e.g. a simple "request a change" app) | ✅ | UI extension slots and endpoints [5.15] |
| Mobile and offline-friendly views | ⬜ [14] | Responsive web first |
| Keyboard shortcuts, command palette, dark mode, accessibility (WCAG 2.2 AA) | 🟡 | Dark mode ✅ · Ctrl+K search and jump to pages ✅ · keyboard tree navigation ✅ · full WCAG 2.2 AA audit [later] |

## 13. Search and reporting

| Capability | Status | Notes |
|---|---|---|
| Global search by number/name, permission-filtered | ✅ | Trigram index |
| Full-text search in attributes and file content, facets | ✅ | OpenSearch or Postgres engines; values, file names, text and PDF content; access-filtered facets [3.6] |
| Advanced search by type and attribute criteria; saved and shared searches | ✅ | [3.6] |
| Classification search, find similar parts | ✅ | |
| Report definitions (query, columns, grouping) without code; reports in any table | ✅ | Report builder: objects and links, all/any/none conditions, totals, questions at run time, live preview; access checked in the SQL |
| Scheduled reports and threshold alerts ("tell me when …") | ✅ | Mailed on a schedule; always, when anything, more than N, or something new is found |
| Charts and dashboards | ✅ | Report charts, totals and tables as dashboard widgets [3.8] |

## 14. Security and compliance

| Capability | Status | Notes |
|---|---|---|
| Security labels and agreements | ⬜ [9] | May move earlier for defense customers |
| Electronic signatures, signature manifest | ✅ | Signatures panel per object, immutable and hashed [5.6] |
| Retention policies, legal hold, purge and archive with restore | ⬜ [9] | |
| Audit reports and audit configuration | 🟡 | Audit report with filters and Excel export ✅ [3.10] · choosing audited events [9] |
| Access reports ("who can see this, and why") | 🟡 | Per person and target, with the deciding rules ✅ · "who can see this object" list [later] |

## 15. Data exchange and integration

| Capability | Status | Notes |
|---|---|---|
| Complete REST API (everything the UI does) with OpenAPI | ✅ | Contract committed and tested |
| Webhooks and event subscriptions | ✅ | Filters, signing, retries with backoff, dead letters [2.6] |
| Service accounts and OAuth client credentials for integrations | ✅ | Scoped API tokens [2.7] |
| Bulk load from spreadsheets / files with validation and a dry run | ⬜ [9] | |
| Migration from other PLM systems | ⬜ [9] | |
| Import/export of object sets between sites or companies (packages, deliveries) | ⬜ [9] | |
| ERP integration: release distribution targets, plant-specific data, acknowledgement | ⬜ [9] | Event-driven connectors |
| Standards: STEP AP242, ReqIF, IPC-1752, OSLC links | ⬜ [9] | |

## 16. Infrastructure and operations

| Capability | Status | Notes |
|---|---|---|
| File replication / caches near remote sites | ⬜ [9] | |
| Background job queues with admin monitoring and retry | ✅ | Admin → Jobs: progress, retries, cancel [3.1] |
| Scheduled jobs | ✅ | Cron schedules with time zones [3.1] |
| Monitoring, tracing, metrics, health | 🟡 | Health ✅ · OpenTelemetry tracing and Prometheus metrics [0.12, still open] |
| Horizontal scaling, zero-downtime upgrades | 🟡 | Stateless app ✅ · upgrade playbook [9] |
| Configuration promotion between environments | ✅ | Export / import on the Admin page and API |
| Guided install: checks and installs prerequisites, one built-in site administrator set at install | ✅ | `plm install`, `plm admin-password`; Linux (apt, dnf, pacman), macOS, WSL with Docker Desktop |
| Demo data sets to load for evaluation | ✅ | `plm load-data voltara` or `byd`: products, structures, CAD models, changes, reports |

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
| Object initialization rules, numbering | Type rules + numbering strategies (scripts for edge cases) | ✅ Type rules, numbering strategies and numbering scripts [1.5, 5.14] |
| Lifecycle and workflow templates | Admin editors, versioned templates | ✅ Lifecycle editor [1.15], visual process editor with versions [5.2] |
| Preferences | Typed preference registry with levels and locks | ✅ |
| Moving configuration between systems | Configuration bundles (diff, validate, apply) | ✅ |
| New actions in menus and toolbars | **Custom actions** declared in an extension manifest | ✅ Webhook, workflow, URL and panel handlers with forms [2.4, 5.15] |
| Rules that hide or disable actions | Action visibility conditions (expressions) | ✅ [2.1–2.2] |
| Server code that runs before an operation (validation, defaulting) | **Pre-operation rules**: expressions, then sandboxed scripts | ✅ CEL rules [2.5], JavaScript scripts [5.14] |
| Server listeners that react after an operation | Event subscriptions and webhooks | ✅ [2.6] |
| Code inside workflow steps | Automation steps calling extension handlers or scripts | ✅ Process steps and no-code automations [5.2, 5.16] |
| Custom UI pages, tabs and table columns | **UI extension slots**: declarative panels and sandboxed micro-frontends | ✅ Panels and tabs on object pages [5.15] |
| Custom web services / integration endpoints | Extension endpoints under `/api/ext/{extension}/…` | ✅ [5.15] |
| Custom reports and queries | Report builder (no code), access-filtered in the SQL | ✅ |
| Packaging and checking customizations for upgrades | Extension packages: versioned manifest, compatibility checks, signed | 🟡 Manifest, install/upgrade/disable, compatibility check ✅ [2.3] · package signing [9] |

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
