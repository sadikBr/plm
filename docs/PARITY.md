# Capability parity matrix

**Goal:** match the capabilities engineering organizations rely on in established enterprise PLM
systems (Windchill is the reference point), and do better on UI, configurability and openness.

**Ground rules**
- We match **capabilities and workflows**, not implementations. We never copy a vendor's product
  names, internal class names, screens, documentation or proprietary APIs. Our names are generic
  industry terms (Part, Document, Change Notice…). Importing customers' data from other PLM
  systems is in scope (Phase 8).
- "Out of the box" (OOTB) means it works after install with sensible defaults, and every default
  can be changed by an administrator **without writing code**.

**Status:** ✅ done · 🟡 partial · ⬜ planned (phase in brackets)

---

## 1. Platform and access

| Capability | Status | Notes |
|---|---|---|
| Context hierarchy: Site → Organization → Product / Library / Project | ✅ | |
| Teams with roles per context, inherited down the tree | ✅ | VIEWER / MEMBER / MANAGER today |
| Custom team roles (e.g. Design Engineer, Change Admin) | ⬜ [1] | Roles become data, not an enum |
| Access policies by role × type × lifecycle state | ✅ | Default policies only; no admin UI yet |
| Policy admin UI, ad-hoc (per-object) permissions | ⬜ [1] | |
| Policies that apply to subtypes (type inheritance) | ⬜ [1] | |
| User groups, LDAP/AD sync via the identity provider | ⬜ [8] | |
| Folders (cabinets) inside contexts | ⬜ [2] | |
| Preferences at site / organization / context level | ⬜ [1] | Typed preference registry |
| Audit trail of every change | ✅ | Append-only, before/after values |
| Domain events for integrations | ✅ | Transactional outbox → Kafka |

## 2. Types and attributes (the foundation for customization)

| Capability | Status | Notes |
|---|---|---|
| Type hierarchy with admin-defined subtypes | ✅ | Layouts and rules still to come |
| Attribute definitions on types, inherited by subtypes | ✅ | Names unique along the hierarchy |
| Data types: text, long text, integer, real, **real with units**, boolean, date, timestamp, URL, enumeration, reference | 🟡 | All but reference (needs object links, Phase 3) |
| Units of measure and quantities (conversion, display unit) | ✅ | ~60 units, 17 dimensions; admin-defined units later |
| Reusable attributes shared across types (global attributes) | ⬜ [1] | |
| Enumerations / legal value lists, localized labels | ✅ | Values deactivated, never removed |
| Constraints: required, range, length, regex, immutable after creation, unique | 🟡 | All but unique; enforced by the server on create, edit and check-in |
| Calculated / derived attributes | ⬜ [1] | Expression language |
| Multi-valued attributes | ✅ | |
| Classification (class hierarchy with class-specific attributes) | ⬜ [6] | |
| Layouts: which attributes, groups and order per type, for create / edit / info / table | ⬜ [1] | UI renders from layouts. No code |
| Initialization rules per type: numbering scheme, revision scheme, default values, default lifecycle, default folder | ⬜ [1] | |
| Localization of type and attribute labels | 🟡 | Attribute and value labels per language; type labels later |
| Admin UI for all of the above | ⬜ [1] | Live preview of layouts |
| Import/export type definitions between environments (dev → test → prod) | ⬜ [1] | Configuration as code (JSON/YAML) |

## 3. Out-of-the-box object types

Seeded with sensible OOTB attributes; every one can be extended, hidden or constrained by admins.

| Type | Status | OOTB attributes (beyond number, name, version, state, created/modified) |
|---|---|---|
| **Part** | ✅ | source (make/buy/…), default unit, assembly mode (separable/inseparable/component), end item, phantom, trace code (serial/lot/none), gathering part, view (design/manufacturing), description |
| **Document** | 🟡 | title, description, document category, department ✅ · primary content, secondary attachments (Phase 2) |
| **CAD Document** | ⬜ [5] | authoring application, file name, CAD type (part/assembly/drawing), generic/instance (family tables) |
| **Problem Report** | ⬜ [4] | category, priority, need date, steps to reproduce, affected objects |
| **Change Request** | ⬜ [4] | category, priority, complexity, recurring cost, non-recurring cost, need date, affected objects |
| **Change Notice** | ⬜ [4] | complexity, need date, change tasks, affected and resulting objects |
| **Change Task** | ⬜ [4] | assignee, reviewer, need date, affected and resulting objects |
| **Variance / Deviation** | ⬜ [4] | category, effectivity, affected objects |
| **Promotion Request** | ⬜ [4] | target state, promotion objects, reviewers |
| **Baseline / Managed baseline** | ⬜ [3] | baseline members, locked flag |

## 4. Versioning and object operations

| Capability | Status | Notes |
|---|---|---|
| Master / revision / iteration (A.1, A.2, B.1) | ✅ | History immutable, enforced by the database |
| Check out / check in / undo check-out | ✅ | |
| Revise | 🟡 | Works; **lifecycle rules (e.g. only from Released) come in Phase 1** |
| Revision schemes per type (letters, numbers, custom series, prefix) | ⬜ [1] | ASME-style letters exist as the default |
| Set state (admin override), promote through lifecycle | ⬜ [1] | |
| Rename / renumber (with audit) | ⬜ [1] | |
| Save As (copy to a new number), with or without structure | ⬜ [2] | |
| Move between contexts / folders | ⬜ [2] | |
| Delete: latest iteration, revision, whole object (with reference checks) | ⬜ [2] | |
| New view version (design → manufacturing) | ⬜ [6] | |
| Ownership and reservation for long edits | ⬜ [2] | |
| Iteration and revision compare (attributes, content, structure) | ⬜ [2] | |

## 5. Lifecycle

| Capability | Status | Notes |
|---|---|---|
| Lifecycle templates: states and allowed transitions | ⬜ [1] | Default: In Work → Under Review → Released → Canceled/Obsolete |
| Transitions: promote, demote, revise, set state, change | ⬜ [1] | Revise allowed only from configured states |
| Lifecycle per type (via initialization rules) | ⬜ [1] | |
| State-based access (e.g. Released is read-only) | 🟡 | The policy model supports it; default lifecycle policies come in [1] |
| Workflow per lifecycle phase | ⬜ [4] | |
| Admin editor with a state diagram | ⬜ [1] | |

## 6. Content, documents, search

| Capability | Status | Notes |
|---|---|---|
| Primary content + secondary attachments, URL links | ⬜ [2] | Content-addressed vault already designed (ADR 0007) |
| Upload/download straight to object storage, large files | ⬜ [2] | |
| Desktop / office integration (check out a file, edit locally, check in) | ⬜ [7] | |
| Thumbnails and previews (PDF, images, office) | ⬜ [2] | |
| Full-text and attribute search, facets, saved searches | ⬜ [2] | OpenSearch, permission-filtered |
| Table views: choose columns, filters, sort, save views, export to Excel | ⬜ [2] | |
| Favorites, recently visited, subscriptions/notifications | ⬜ [2] | |

## 7. Product structure

| Capability | Status | Notes |
|---|---|---|
| Part usage links to child **masters**, quantity, unit, find number, line number | ⬜ [3] | ADR 0003 |
| Reference designators / occurrences | ⬜ [3] | |
| Configuration specs: latest, released, baseline, effectivity | ⬜ [3] | |
| Multi-level BOM tree table, expand all, 50k lines < 2 s | ⬜ [3] | |
| Structure editing: insert existing/new, replace, remove, paste, reorder | ⬜ [3] | |
| Where-used (single and multi-level) | ⬜ [3] | |
| BOM compare (two versions or two configurations) | ⬜ [3] | |
| Substitutes and alternates | ⬜ [3] | |
| Part ↔ document links (describes / references) | ⬜ [3] | |
| BOM import/export (Excel/CSV) | ⬜ [3] | |

## 8. Workflow and change management

| Capability | Status | Notes |
|---|---|---|
| Workflow engine (durable, versioned processes) | ⬜ [4] | Temporal (ADR 0006) |
| Visual process template editor | ⬜ [4] | |
| Tasks, inbox, assignment by team role, delegation, due dates | ⬜ [4] | |
| Review / approval with voting and signatures (e-signature for regulated industries) | ⬜ [4] | |
| Promotion requests (approve a state change without a full change process) | ⬜ [4] | |
| Problem Report → Change Request → Change Notice → Change Tasks | ⬜ [4] | |
| Affected / resulting objects, release on change notice completion | ⬜ [4] | |
| Change board review, implementation plan | ⬜ [4] | |
| Redline (proposed BOM changes reviewed before applying) | ⬜ [4] | |
| Variances / deviations | ⬜ [4] | |
| Notifications and subscriptions (email, in-app) | ⬜ [4] | Event consumers |

## 9. CAD and visualization

| Capability | Status | Notes |
|---|---|---|
| CAD documents with dependency structure | ⬜ [5] | |
| Part ↔ CAD association (owner, content, image) | ⬜ [5] | |
| Neutral formats: STEP import, conversion to glTF | ⬜ [5] | OpenCascade worker |
| Web 3D viewer: structure, measure, section, markups | ⬜ [5] | |
| 2D viewer for drawings/PDF with markups | ⬜ [5] | |
| Native CAD connectors (SolidWorks, Creo, NX, Inventor, CATIA) | ⬜ [7] | Each a separate project |

## 10. Configuration and downstream

| Capability | Status | Notes |
|---|---|---|
| Effectivity: date, serial (unit), lot | ⬜ [6] | |
| Options & variants: option sets, choices, rules, variant specifications | ⬜ [6] | |
| Design and manufacturing views (EBOM → MBOM) with reconciliation | ⬜ [6] | |
| Process plans, operations, resources | ⬜ [9] | |

## 11. Enterprise and integration

| Capability | Status | Notes |
|---|---|---|
| Complete REST API (everything the UI does) with OpenAPI | ✅ | Contract committed and tested |
| Webhooks and event subscriptions | ⬜ [2] | |
| Bulk import/export, migration from other PLM systems | ⬜ [8] | |
| ERP integration (parts, BOMs, change notices) | ⬜ [8] | Event-driven connectors |
| Packages for suppliers (export a controlled data set) | ⬜ [8] | |
| Multi-site file replication / caches | ⬜ [8] | |
| Reporting and dashboards | ⬜ [8] | |
| Projects: plans, activities, deliverables | ⬜ [9] | |
| Requirements, quality (CAPA, NC, FMEA), service information | ⬜ [10+] | |

---

## Where we aim to be better

| Area | How |
|---|---|
| **No-code customization** | Types, attributes, constraints, layouts, numbering, lifecycles and policies are all admin-configurable in the UI, with live preview. No Java customization for everyday needs. |
| **Upgrade-safe extensions** | Customizations are data plus a stable public API and events, never patches to internals. Upgrades don't break them. |
| **Configuration as code** | Every admin setting can be exported to and imported from version-controlled files (dev → test → prod). |
| **Modern UI** | Fast, keyboard-friendly, inline editing, saved table views, dark mode, accessible (WCAG 2.2 AA). |
| **API-first** | Every UI action is a documented, typed REST call. Integrations use the same API as the UI. |
| **Events built in** | Every change is a domain event on Kafka: integrations subscribe instead of polling. |
| **Search that feels instant** | Permission-aware full-text and attribute search with facets. |
| **Operations** | Containers, horizontal scaling, observability out of the box, single-tenant or SaaS. |
