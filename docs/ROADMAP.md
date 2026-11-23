# Roadmap

Each phase ends with explicit exit criteria. Don't start a phase until the previous one meets them.

## Phase 0 — Foundation (4–6 weeks)

**Goal:** a running skeleton where every foundational ADR is real code, not a slide.

- [x] 0.1 Monorepo + Gradle (Kotlin DSL) multi-module build, `.editorconfig`, ktlint (detekt deferred until it supports Kotlin 2.4)
- [x] 0.2 `deploy/docker-compose.yml`: Postgres, S3 (SeaweedFS), Keycloak, Redpanda, OpenSearch, Temporal
- [x] 0.3 CI (GitHub Actions): build, unit tests, Testcontainers integration tests, ArchUnit rules
- [x] 0.4 `platform` kernel: typed IDs, error model, transaction helpers, clock abstraction
- [x] 0.5 Flyway baseline: `tenant`, `context`, `type_def`, `object_master`, `object_iteration`
- [x] 0.6 Generic versioning engine: create, check-out, check-in, undo check-out, revise (+ tests)
- [x] 0.7 Outbox table + relay to Redpanda; audit log writer
- [x] 0.8 OIDC login via Keycloak; `identity` module maps tokens → principals
- [x] 0.9 `AccessService` with SQL-predicate filtering (minimal policy model)
- [x] 0.10 OpenAPI spec + generated TypeScript client
- [x] 0.11 React shell: login, context browser, object info page stub
- [ ] 0.12 OpenTelemetry tracing + Prometheus metrics wired

**Exit criteria:** `docker compose up` + one command boots the stack; a logged-in user can create a
generic versioned object, check it out/in, see its iteration history, and an unauthorized user
cannot see it (proven by an integration test). CI is green and blocks boundary violations.

## Phase 1 — Types, attributes and lifecycles (the customization foundation)

**Goal:** the out-of-the-box Part and Document types arrive with their standard attributes, and
administrators can extend everything (types, attributes, rules, layouts, lifecycles) **without
code**. Every later phase builds on this. See [PARITY.md](PARITY.md) §2–§5.

- [x] 1.1 Attribute definitions: data types (text, long text, integer, real, real with units,
      boolean, date, timestamp, URL, enumeration, reference), multi-valued, inherited by subtypes,
      reusable across types. Validated on every write (API, not just UI).
- [x] 1.2 Units of measure and quantities; enumerations (legal value lists) with localized labels.
- [x] 1.3 Constraints: required, range, length, regex, immutable, unique. Clear per-field errors.
- [x] 1.4 OOTB types and attributes seeded: Part (source, default unit, assembly mode, end item,
      phantom, trace code, description…) and Document (title, description, category, department…).
- [x] 1.5 Initialization rules per type: numbering schemes (prefix, sequence, padding), revision
      schemes, default values, default lifecycle.
- [x] 1.6 Lifecycle templates: states, transitions (promote, demote, revise, set state), per-type
      assignment. **Revise allowed only from configured states (Released by default).** Default
      state-based policies: Released is read-only.
- [x] 1.7 Layouts per type and screen (create / edit / info, plus a default): attribute groups,
      order, one or two columns, hidden attributes; inherited along the type hierarchy. The UI
      renders forms and the attributes panel from them. (Table and search columns come with saved
      table views in Phase 3.)
- [x] 1.8 Custom team roles; access rules (allow and deny) managed per context by its managers;
      rules on a type apply to its subtypes; inherited team members; "check access" explains why
      someone can or can't do something. (Shared teams move to 1.12 with context templates.)
- [x] 1.9 Admin UI (site admins): types and subtypes, attributes (all data types and
      constraints, typed default values), value lists, units, numbering schemes, rules (numbering,
      revision scheme, manual numbers, lifecycle), layouts per screen with live preview, team roles,
      lifecycles (state diagram; new or copied templates with a transition matrix).
- [x] 1.10 Configuration as code: the whole configuration as one JSON bundle (by name, stable
      output for Git); import shows a plan with diffs and applies it in one transaction or not at
      all; adds and updates, never deletes. Admin page and API.
- [x] 1.11 Rename / renumber (master-level, all versions, needs MANAGE, not while checked out, audited with the old identity). (Set state with admin override shipped in 1.6.)
- [x] 1.12 Context templates for organizations, products, libraries and projects: access rules
      (types by name), team members and shared teams. Create a context from a template; save an
      existing context as a template; templates travel in configuration bundles. Shared teams:
      maintained once, attached to any number of contexts. (Folders, preferences and per-context
      numbering join templates when those features exist: Phase 3, 1.13.)
- [x] 1.13 Preferences framework: typed preferences declared by each module, set at site,
      organization/product/library/project and user level, lockable at any context level so
      nothing below (users included) can change them. Preferences tab on contexts, My preferences.
      First preferences: rows per page, check-in comment required, per-context numbering scheme.
- [x] 1.14 Delegated administration: an organization's managers administer it without being site
      administrators: its team, access rules and preferences (1.8, 1.13), plus its own shared teams
      and context templates, usable only inside it. Site-wide teams, templates and the data model
      (types, attributes, lifecycles, roles) stay with site administrators, since they apply to
      everyone. (Organization-specific types and lifecycles: see 1.15.)
- [x] 1.15 Lifecycle template management: copy, edit and version templates (editing saves a new
      version; types move to it, objects keep theirs until a site admin moves them, with a state
      mapping); entry criteria per state (attributes required to enter); state-based revision series
      (e.g. 1, 2, 3 then A, B). (Expression-based gate criteria come with the rule language in
      Phase 2; per-organization lifecycle assignments in Phase 5 with workflows; "reset team" applies
      once objects have teams, with workflows.)
- [x] 1.16 Attribute model extras: global attributes (defined once, used by several types), dependent
      value lists (allowed values per parent value, enforced on every write), units added by
      administrators, type properties (description, icon, translated names, abstract types that
      can't be created). (Translating the user interface itself moves to Phase 3.)

**Exit:** an admin creates a "Fastener" part subtype with a required enumerated `thread` attribute
and a numbering rule, entirely in the UI. A user creates one and sees the right form. Revising an
In Work part is refused, and revising a Released part works. An organization admin creates a
product from a template with its own lifecycle and team roles, without site admin rights.

## Phase 2 — Extensibility platform v1

**Goal:** customers add behaviour (actions, rules, integrations) without touching our code, and
our own later features are built on the same mechanisms. See [EXTENSIBILITY.md](EXTENSIBILITY.md).

- [x] 2.1 Action registry: every built-in action (check out, revise, set state…) is declared as
      data, with placement, applies-to (types, states), required permission and a visibility
      condition. The UI renders toolbars and menus from the registry. (Done: registry, placement,
      order, types, states, permission, admin screen. Visibility conditions use expressions, 2.2.)
- [x] 2.2 Expression language (CEL) for conditions, validations, calculated attributes and gates.
- [x] 2.3 Extension packages: manifest (`plm-extension.yaml`), install / upgrade / disable per
      tenant, compatibility check against the platform contract version, audit.
- [x] 2.4 Custom actions with handlers: webhook (signed), start a workflow (Phase 5), open a URL or
      panel. Parameter forms generated from a schema; results (message, refresh, navigate, download).
      (Done: signed webhooks, URLs, forms, results, call log. Starting a workflow comes with the
      workflow engine in Phase 5, panels with UI extension slots in Phase 5.)
- [x] 2.5 Pre-operation rules: synchronous validation hooks that can refuse an operation with a
      message (expressions first; scripts in Phase 5).
- [x] 2.6 Event subscriptions and webhooks: filters, signing, retries with backoff, dead letters,
      delivery log in the admin UI.
- [x] 2.7 Service accounts and OAuth client credentials, scoped API tokens for extensions. Webhook
      calls carry a short-lived token that acts as the user who clicked (their access rules, their
      name in the audit trail), limited to the extension's permissions, so a service can read and
      write through the API on the user's behalf (e.g. build a document from a query and attach it).
      (Done: service accounts, API tokens and client secrets with scopes and expiry, the OAuth
      token endpoint, `api:read` / `api:write` delegated tokens in webhook calls, an `api:account`
      service account per extension, admin screen.)
- [x] 2.8 Profiles: which actions and UI areas a group of users sees. (Done: profiles with users
      and shared teams as members, hidden actions (built-in and extension) and interface areas;
      someone in several profiles sees what any of them shows. Profiles shape the interface;
      access rules still decide what anyone may do.)

**Exit:** a sample extension, installed from a manifest, adds an "Send to ERP" action on Released
parts that calls a webhook, plus a rule that refuses check-in when a description is missing. No
platform code changes, and it survives a platform upgrade. (Met: the `erp-sync` and
`quality-rules` samples in `extensions/samples`, covered by the e2e suite.)

## Phase 3 — Documents, content, search and collaboration

**Goal:** teams manage real documents (files, folders, permissions) and find anything fast; the
platform gets the background machinery (jobs, schedules, notifications) later phases rely on.

- [x] 3.1 Job queue and scheduler: background jobs with progress, retries and cancel, recurring
      schedules, an admin monitor. Mass operations, reports, indexing and long actions run on it.
      (Done: `jobs` module with a handler SPI, leased workers that recover crashed jobs, retries
      with growing waits, cron schedules with time zones, Admin → Jobs.)
- [x] 3.2 Vault content: primary content and attachments stored in the vault (S3), uploaded and
      downloaded through presigned URLs, kept per iteration; thumbnails and previews.
      (Done: `content` module, S3 or local-disk stores, signed upload and file links, content
      carried over at check-out and revise, thumbnails as background jobs, PDF pages rendered for
      previews, nightly vault cleanup.)
- [x] 3.3 Documents: document templates (a new document starts from a template's file), Save As
      (a copy with a new number), compare two versions (attributes and content).
      (Done: templates are ordinary objects registered per site or organization; Save As and
      Compare in the action registry; compare covers values, files and a line diff of text files.)
- [x] 3.4 Folders and organizing: folders in contexts, move between folders and contexts, delete
      rules (what may be deleted, by whom, in which states), ownership and reservation, a clipboard.
      (Done: folders with paths, move and delete guarded by extension rules too, delete rules in
      Admin, owners, reservations that block others' check-out, a clipboard to paste as move or copy.)
- [x] 3.5 Mass operations: select many objects and set state, move, delete or change an
      attribute in one background job, with a per-object result.
      (Done: `bulk` module, up to 1000 objects per job, run as the person who started it; table
      selection, a bulk toolbar and live results.)
- [x] 3.6 Search: OpenSearch indexing fed by events, full-text and attribute search with facets
      and access filtering, saved and shared searches.
      (Done: `search` module with OpenSearch or built-in Postgres full-text engines; values, file
      names and the text of text files and PDFs are indexed; results and facet counts only cover
      what the person may read.)
- [x] 3.7 Table views: choose columns, filter and sort, save and share views, export to Excel.
      (Done: built-in and attribute columns, filters by state, type and attribute, saved views
      per person or shared, an .xlsx export of the table as shown.)
- [x] 3.8 Reports and home page: report definitions (a query and columns), scheduled reports and
      alerts by email, dashboards, a home page each user arranges.
      (Done: `reports` module with charts by state, type, context, owner or attribute; cron
      schedules mailed through a new `notifications` module (Mailpit in development) as alerts
      when anything, more than N or something new is found; shared dashboards of widgets; the home
      page is the dashboard each person picks.)
- [x] 3.9 Personal tracking: favorites, recent items, subscriptions to objects and folders,
      notifications and digests.
      (Done: stars on objects, contexts and reports, listed in the navigator; recently viewed
      objects; follow an object, folder or product to be told of check-ins, revisions, state
      changes, moves and deletions by others, only while you may read them; job owners hear when
      their jobs end; a bell with the unread count; mail right away, as a daily digest job, or
      never; new home widgets for favorites, recent visits and notifications.)
- [x] 3.10 Collaboration and access: discussions on objects, ad hoc access (share one object with
      someone), audit reports.
      (Done: a `collaboration` module with discussion topics and replies on objects, @mentions
      and reply notifications (only to people who may read the object), resolve, edit and delete;
      an object's owner or its context's managers share it with anyone, to view or also edit,
      optionally until a date (honoured by every access check and list filter, and the person sees
      the object's context but nothing else in it); an audit report by period, person, kind of change
      and context, with counts by kind and person and an Excel export, for site administrators and
      context managers.)
- [x] 3.11 Long-running extension actions: a webhook action can start a background job that
      reports progress; the user is notified when it's done (e.g. a document generated from a long
      query).
      (Done: `background: true` webhook actions run as a job of the person who started them, for up
      to an hour; the service reports progress and may answer 202 and post its answer later, both
      with a per-job token; the object page follows the job, can cancel it, and offers its file; the
      notification carries the service's message and a link back. Sample: erp-sync 2.1's cost
      roll-up with the mock ERP.)
- [x] 3.12 Interface translations: every screen translated, each user picks a language.
      (Done: every screen in English, French and German, gettext-style catalogs per area with a
      test that every text has both translations; a `ui.language` preference (automatic, or a
      language) for the site and each user; dates in the user's language; the built-in types,
      attributes, value lists, layout groups and lifecycle states translated. Messages written by
      the server (errors, notifications, mails) are still in English.)

Content follows the document model: only documents hold files, one primary file and any number
of attachments per iteration; parts link to documents (Phase 4, part↔document links).

**Exit:** a team manages real documents end-to-end with files, permissions and fast search.
(Met: documents with primary content and attachments, previews and templates; folders, sharing
and access; full-text search with facets, saved searches, views and reports; covered by the e2e
suite.)

## Phase 4 — Parts and product structure (complete)

**Goal:** parts form multi-level product structures that people build, configure, compare,
baseline and report on, fast at the scale of real products.

- [x] 4.1 Usage links: a parent part iteration uses child part masters, with quantity, unit, line
      and find numbers; structure is edited on the parent's working copy and frozen at check-in.
      (Done: a `bom` module; lines carried forward on check-out and revise, dropped with an undone
      check-out; only parts, no cycles, units from the unit-of-use list; children the reader can't
      see stay as placeholder lines; a part used in a structure can't be deleted; Structure tab.)
- [x] 4.2 Configuration specifications: latest, latest released, latest in a state, as of a date;
      chosen per user in the structure view, with a default per context.
      (Done: latest, latest in any lifecycle state, as of a date; a `bom.configSpec` preference
      for the default, per site, context or user.)
- [x] 4.3 Multi-level BOM tree table: lazy expansion, columns and views (3.7), Excel export,
      access filtered per line (unreadable children show as placeholders).
      (Done: tree table that loads levels as they're opened or all at once, draws only the rows in
      view, exports to Excel with levels; access applied per line in SQL. Exit target met: a
      10-level structure of 45k+ lines expands in well under 2 s in the test suite.)
- [x] 4.4 Structure editing: add existing or new children, copy and paste lines, replace, remove,
      reorder, edit quantities, find in structure; all in the checked-out parent.
      (Done: add an existing part or create one in place, edit, move up and down, replace,
      remove; copy lines from any structure you can read and paste them into your working copy;
      find in structure shows matches with the lines above them.)
- [x] 4.5 Occurrences and reference designators: individual instances under a usage line (R1,
      R2…, ranges), checked against the quantity.
      (Done: designators entered as ranges (R1-R4, R7), one per piece of a whole quantity in units
      of each, unique within a structure, carried with the line, shown and exported as ranges.)
- [x] 4.6 Where-used: single and multi-level, under a configuration specification, up to end
      items; exportable.
      (Done: one level or all levels up, under any configuration specification, end items
      flagged, unreadable users shown as placeholders and not followed; Where used tab, Excel.)
- [x] 4.7 Part↔document links: "described by" (a document iteration) and "references" (a
      document master, always its latest), on both sides, carried on revise, allowed type pairs.
      (Done: links on the part's iteration, carried forward; "newer exists" with an update to the
      latest; a Documents tab on parts, a Parts tab on documents; admin rules limiting document
      types per part type, inherited down the type tree.)
- [x] 4.8 Alternates and substitutes: a part's global alternates; substitutes on one usage line,
      shown in the BOM and where-used.
      (Done: alternates on the part (one or both ways, needing MODIFY); substitutes on a line,
      edited on the working copy and carried with it; marked in the structure, a Replacements tab,
      and "also allowed as a substitute in" with where-used.)
- [x] 4.9 BOM compare: two iterations, two parts, or one part under two configuration
      specifications; added, removed and changed lines side by side; exportable.
      (Done: all levels, parts matched by where they sit and summed across lines; version,
      quantity, unit, find number and designator differences highlighted; compare with an earlier
      version, the released structure or another part; Excel export.)
- [x] 4.10 Baselines and collections: frozen sets of exact iterations (also a configuration
      specification), managed baselines with a lifecycle, collecting a structure with its documents
      for bulk actions.
      (Done: baselines (open, then frozen for good) of exact iterations, visible to those who can
      read their context; "as in baseline" as a configuration specification; collecting a part
      with its structure under any specification and its linked documents, into a baseline or
      onto the clipboard; a Baselines page.)
- [x] 4.11 Roll-ups and multi-level reports: values summed up the tree (mass, cost, counts), a
      flattened BOM with total quantities, multi-level reports in the report engine (3.8).
      (Done: roll-ups of any numeric attribute (quantities converted to base units) for one piece
      of the top, with own values for assemblies and parts missing a value flagged; a flattened
      structure with total quantities and levels; searches and reports limited to a part's
      structure ("within the structure of"), so the report engine's charts and schedules apply.)
- [x] 4.12 Reference and alias attributes: attributes that point to another object, or show a
      value of a related object; filterable and reportable.
      Done: a REFERENCE data type (optionally limited to a type and its subtypes) checked on save
      (exists, readable, right type, not itself); alias attributes (`reference.property`: number,
      name, state, version or any attribute) read live from the referenced object, never stored, and
      only for those who can read it; the search index refreshes referencing objects when the
      referenced one changes; "Referenced by" on the object page; referenced objects can't be
      deleted; object pickers in forms, search and report filters; reference columns show number
      and name.
- [x] 4.13 BOM import and export: Excel/CSV with preview, validation and matching (create or
      update), as a background job.
      Done: the structure exports to Excel or CSV in a layout that imports back (Level, Find no.,
      Number, Name, Quantity, Unit, Reference designators); import reads CSV (comma or semicolon,
      decimal commas) and .xlsx (no spreadsheet library; no DTDs), headers in English, French or
      German, units by key or label. The preview matches parts by number (else creates them),
      checks levels, quantities, units, designator counts, cycles and whether each assembly can be
      checked out, line by line; the job creates parts, then sets each assembly's lines exactly
      (matched lines updated, others added or removed), checks in, and reports per object.
- [x] 4.14 Bulk revise and structure operations: revise a structure or a selection in one go,
      mass replace across where-used; background jobs with per-object results.
      Done: Revise is a mass operation on any selection; "Revise…" on a structure lists its parts
      (released ones ticked) and revises the chosen ones. "Replace everywhere…" on where-used puts
      another part on every line using this one in the chosen parents (line, find number,
      quantity and unit kept), revising released parents first when asked; each parent in its
      own transaction, so a failure leaves it untouched and the others go on.

**Exit:** a 10-level, 50k-line BOM expands in < 2 s with access filtering applied; the e2e suite
builds, compares, baselines and imports a structure.
**Met:** StructureTreeIT expands a 10-level, 45k+ line structure in under 2 s with access applied;
`e2e/structure-flow.spec.ts` builds a structure, imports one from a file, compares it with its
earlier version and baselines it.

## Phase 5 — Workflow, change management and extensibility v2 (complete)

**Goal:** changes to released data are governed: people review, approve and sign in processes
that survive restarts; problems flow into change requests and notices that revise and release the
affected objects; and the platform can be extended with scripts, UI slots and no-code automations.

- [x] 5.1 Workflow engine: processes run on Temporal (ADR 0006) through one generic interpreter of
      versioned process templates; start, cancel, history; processes survive restarts and deploys.
      Done: templates are JSON graphs (tasks, votes, actions, conditions, parallel branches, timers,
      ends), checked and versioned; one Temporal workflow interprets the version a process started
      with, routing only, while activities do the work (work items, state changes, notifications,
      history) each in a transaction; answers reach it as signals after commit. Processes on an
      object show their steps and tasks; a built-in "Review and release" template. Runs on the
      Temporal service (PLM_TEMPORAL_TARGET) or its in-process server (tests, trying out).
- [x] 5.2 Process template editor: a visual editor for templates made of tasks, votes, automatic
      steps (set state, set attribute, run an action, notify), conditions, parallel branches and
      timers; versioned, validated, and exported with the configuration.
      Done: Admin › Processes draws a template as boxes and labelled arrows; steps are added from a
      palette, dragged, and edited in an inspector (who, answers, vote rule, due date, state, CEL
      condition, branches, waits). The server checks it as you go (missing targets, unreachable
      steps, bad durations or conditions); saving makes a new version. Process templates are part
      of the configuration bundle (export, dry-run import).
- [x] 5.3 Tasks and inbox: work items for users, team roles or groups; claim, complete with an
      outcome and comment; an inbox with filters and counts, notifications and mail.
      Done: a step goes to users, the members of a team role in the object's context, the starter
      or the owner; each gets a task (notified, mailed per their settings, linking to it). A task
      given to several people can be taken on by one (the others wait) and given back. The Tasks
      page lists open, overdue and done tasks with a filter, and shows the selected one with its
      instructions, object, the process so far and the answer buttons; the top bar counts open
      tasks (red when some are overdue).
- [x] 5.4 Voting, delegation and escalation: reviewer sets with voting rules (all, any, majority);
      reassign and delegate, out-of-office stand-ins; due dates, reminders and escalation.
      Done: votes decide by rule (all, any, majority) and show their tally to the voters; a task is
      passed on by its assignee, the process starter or an administrator, keeping whom it's for;
      everyone can name a stand-in (with optional dates) who gets their new tasks meanwhile; due
      dates remind the assignees once overdue, and a task can escalate to others (a role, users)
      who can then answer it too.
- [x] 5.5 Process assignments: which process runs for which type, lifecycle state or transition,
      per organization or product, inherited down the context tree.
      Done: an assignment names a context (the site, an organization, a product), a type, a
      template and when it starts: on creation, on entering a state, or offered to start by hand.
      It applies below the context and to subtypes; the nearest context and most specific type win.
      Automatic starts never come from a process's own steps and never fail the change that
      triggered them. Objects show what starts by themselves; the start dialog offers assigned
      templates first. Managing them needs MANAGE in the context (site: administrators).
- [x] 5.6 Electronic signatures (21 CFR Part 11): signing tasks ask for the password again; the
      signature (who, when, meaning) is recorded immutably, shown on the object and audited.
      Done: a TASK or VOTE step can need a signature; answering it asks for the password, checked
      with the identity provider at that moment. The record (signer's name and username, meaning,
      comment, time, object, exact version and iteration, task and process) is written once into a
      table the database refuses to change or delete, with a SHA-256 hash shown as "intact" on the
      object's Signatures panel. Signatures and refused attempts are domain events, so audited.
- [x] 5.7 Promotion requests: ask for a set of objects to move to a target state; a review process
      approves or rejects; objects are locked while under review and promoted on approval.
      Done: "Request promotion…" on any selection makes a numbered request (PRM-00001) with the
      versions and states asked; the built-in "Promotion review" (a signed manager approval, or
      another template) runs on it. Meanwhile a guard refuses check-out, state changes, revisions,
      moves, renames and deletion of its objects; approval promotes them all (along the lifecycle,
      or overriding it on the approval's authority), rejection or cancelling leaves them as they
      were. Processes can now be about something other than one object (a context, a label, a
      link), which tasks show.
- [x] 5.8 Change objects: problem reports, change requests, change notices and change tasks with
      their own types, lifecycles and numbers, linked to each other; affected and resulting objects
      and end items; fast track (notice directly) and full track.
      Done: a CHANGE base class with four built-in types (numbered PR-, CR-, CN-, CT-) and a Change
      lifecycle (Open, Under Review, Approved, Implementation, Resolved, Rejected, Cancelled); being
      versioned objects they get attributes, access, search, processes and history like any other.
      Links: affected (with the version then), resulting and end items; a request addresses
      reports, a notice implements requests and has tasks. "Create a change request / notice / task"
      carries the affected objects and end items along; a notice can come straight from a report
      (fast track). The Changes page lists them; parts and documents show the changes they're in.
- [x] 5.9 Implementation plans and change board: a notice's tasks with assignees and due dates;
      a change board reviews requests and votes; completing a notice releases its resulting objects.
      Done: a change notice's implementation plan gives each task an assignee (told) and a due date
      (flagged when overdue). The built-in "Change board review" (offered on every change request:
      submit, a signed majority vote of the product managers, approve or reject) decides requests.
      A notice can't be resolved while a task is open; resolving it releases its resulting objects
      and resolves the requests it implements and the problem reports they address.
- [x] 5.10 Change templates, association rules and variances: templates prefill changes; rules say
      which types may be affected or linked; variances (deviations, waivers) with a limited scope.
      Done: administrators keep change templates (type, title, description, tasks created with the
      change) and association rules (per change type and role, the types it may link; none means any)
      under Administration › Changes. A variance is a deviation or waiver with an end date, a quantity
      and a reason; once approved it shows as a banner on each affected part or document.
- [x] 5.11 Redlines and mass change: proposed structure edits inside a change task, applied when
      it is implemented; mass changes (replace, set value) across a change's affected objects.
      Done: a change notice or task proposes edits (add, remove, replace a part, change a quantity,
      set an attribute) without checking anything out. Resolving it applies them: each object is
      revised if released, checked out, edited, checked in, and becomes a resulting object of the
      task and its notice; an edit that can't apply (object checked out, part no longer used)
      refuses the resolution. A mass change proposes one edit on every affected object it fits and
      says why it skipped the others.
- [x] 5.12 Change dashboards: open changes by state and age, my changes, cycle time; widgets for
      the home dashboards.
      Done: Changes › Dashboard shows, for every product the reader can read (or one), open changes
      by kind and state, how long they have been open, changes opened and resolved per week, cycle
      times (median and average, last 180 days) and the reader's own open changes (owned, or a task
      planned for them). "Open changes by state" and "My open changes" are dashboard widgets. A
      small read model, kept from the change objects' events, makes it cheap.
- [x] 5.13 Release gate: parts and documents reach Released only through an approved change notice
      or promotion request (administrators can override, audited).
      Done: a release gate (Administration › Changes, on by default, states configurable) refuses to
      move a part or document to Released except when a change notice is resolved or a promotion
      request approved. A site administrator can still override from the Set state dialog; the
      override is recorded in the object's history and the audit trail.
- [x] 5.14 Sandboxed server scripts: JavaScript scripts for validation rules, numbering and
      automation steps, run with time, memory and API limits.
      Done: Administration › Scripts holds JavaScript run in an embedded interpreter (Rhino) with no
      Java, file or network access: validate(ctx) refuses operations, number(ctx) names new
      objects ahead of numbering schemes, run(ctx) is an automation step. Each run is limited in
      wall-clock time (per script, at most 2 s), instructions (5 million), allocated memory (64 MB),
      calls to plm.object/plm.find (25, reading as the user) and output; a limit stops it in a way
      the script can't catch. A script that fails refuses rather than lets through. Scripts can be
      tried on sample input, and each run is kept (result, logs, time).
- [x] 5.15 UI extension slots and extension endpoints: extensions add panels and tabs to object
      pages (sandboxed frames with a message bridge) and serve their own endpoints through the
      platform.
      Done: a manifest's `ui:` adds panels (Details tab) and tabs to the pages of the objects it
      applies to, shown in sandboxed frames; a postMessage bridge gives them the object, user,
      language and theme and lets them resize, open pages, refresh, and call their own endpoints.
      `endpoints:` makes the platform serve /api/v1/ext/<id>/<path> to signed-in users, forwarded
      signed (with the user) to the extension's service. New permissions: ui:panels, endpoints:serve.
      The erp-sync sample 2.2 adds a live "ERP stock" panel with reservations.
- [x] 5.16 No-code automations: trigger (an event) → condition → actions (set state, set a value,
      notify, start a process, call a webhook), built in the admin pages, with a run history.
      Done: Administration › Automations: when (an event, for some types) → if (an optional CEL
      condition over the event, the object and the user) → then (set the state, set a value,
      notify, start a process, call a signed webhook, run an automation script), in order. They
      run after the triggering change commits, each step in its own transaction, as "automation";
      changes they make don't trigger automations. Every run is kept with each step's outcome; a
      failing step stops the run (the release gate and lifecycles still apply).

**Exit:** a part can only reach Released through an approved change notice or promotion request;
the e2e suite takes a problem report through a change request and notice to a released part, with
a signed approval.
**Met:** ReleaseGateIT shows parts and documents reach Released only when a change notice is
resolved or a promotion request approved (or an administrator overrides, recorded);
`e2e/change-flow.spec.ts` reports a problem, raises a change request, has the product's managers
approve it on the change board with their passwords (signed), makes a change notice with the part
as its result, resolves it, and finds the part Released and the request and report resolved.

## Phase 6 — CAD data and visualization (complete)

**Goal:** CAD models live in the PLM with their structure: assemblies and drawings keep their
dependencies, travel together through check-out, revise and release, build the part structure,
and are viewable in the browser (3D and 2D, with measures, sections and markups) without a CAD
seat.

- [x] 6.1 CAD documents and their dependency structure: a CAD document type (part, assembly,
      drawing; authoring application and format), with versioned dependencies (an assembly's
      components with their placement, a drawing's models); a dependency tree and where-used.
      Done: CAD part, CAD assembly and CAD drawing types (numbered CAD-, with authoring
      application and file format). An assembly's components (one line per placement, with an
      occurrence name and a 4x4 placement) and a drawing's models are kept per version, edited on
      the working copy and carried into new versions; the Model structure tab shows the tree with
      each model placed in the root, and Where used the versions that depend on a model. Cycles
      are refused and a model others depend on can't be deleted.
- [x] 6.2 Collector: act on a CAD document with what it depends on and what depends on it
      (required, optional, drawings), chosen in one dialog: check out, check in, revise, set state.
      Done: Collect… on a CAD document gathers the models it needs (or all, optional ones too, to
      any depth), their drawings and the assemblies that use it; the chosen ones are checked out,
      checked in, revised or moved to a state together, all or nothing (the error names the model
      that stopped it), or added to a change notice as its results to be released through it.
- [x] 6.3 Family tables: a generic model and its instances (a table of parameters per instance),
      instances sharing the generic's file; adding, editing and using instances in assemblies.
      Done: a CAD part or assembly becomes a generic by having columns (its parameters); each row
      is an instance, created as a CAD document of the generic's type that shares its file, with
      one value per column. The table belongs to the generic's version (edited on its working copy,
      kept in new versions); instances show the table they belong to and are used in assemblies
      like any model.
- [x] 6.4 Part ↔ CAD association and build rules: a part's owning model and contributing models;
      "build" turns the CAD assembly into the part structure (quantities, find numbers), keeping
      lines added by hand.
      Done: a part's version names its owning CAD model (one; a model owns one part) and
      contributing ones. Build from CAD reads the owning assembly's components and makes the
      part's structure match: a line per component's part, as many as it occurs; lines of parts
      that came from CAD and left the model are removed; lines of parts without a model are kept.
      Components without a part are listed, or get one made on request.
- [x] 6.5 STEP import: a STEP file (AP203/AP214/AP242) becomes CAD documents with their assembly
      structure, each with its file, optionally with parts associated and built.
      Done: Import STEP… on a product reads the file's product structure (products, assembly
      usages and their placements, in the file's length unit converted to mm) and shows it first;
      importing makes a CAD part or assembly per product with its components placed, keeps the
      file on the top model, and optionally makes a part per model, owned by it, with assembly
      parts built. Checked with the CAx-IF AS1 assembly (9 models, 27 lines).
- [x] 6.6 Conversion worker: STEP to glTF as background jobs on check-in, stored as the
      iteration's viewable; conversion status, errors and retries visible on the object.
      Done: checking in a CAD document with a STEP file queues a background job that runs the
      converter (workers/cad-converter: OpenCASCADE through occt-import-js) and keeps the .glb
      with the file, so later versions with the same file have it at once; a family instance shows
      its generic's. The CAD tab shows waiting, converting, ready (bodies, triangles, download) or
      the failure, with Convert again.
- [x] 6.7 Publishing rules, watermarks and PDF: which types publish what, when (check-in,
      release); PDFs stamped with number, version, state and viewer on download; a PDF data sheet
      of any object.
      Done: Administration › Publishing holds rules (types; on check-in or on reaching a state;
      stamp the state across the pages) that publish a version's PDF file stamped with its number,
      version and state, or a data sheet when it has none, as background jobs; the object's
      Publications list them. With the watermark setting on, every PDF handed out (files,
      publications, data sheets) carries who downloaded it and when. Any object's data sheet
      (attributes, structure, files, history) is a PDF on demand.
- [x] 6.8 Web 3D viewer: the model in the browser with its structure tree (show, hide, isolate,
      select), fit, orbit, views, transparency; from the object page and the part structure.
      Done: a 3D view tab on CAD parts and assemblies, and on parts whose geometry a model owns,
      shows the converted glTF (three.js, loaded on demand) with the model's assembly tree: show
      or hide each branch, select from the tree or by clicking the model, isolate, zoom to, fit,
      iso, front, top and right views, orbit, pan and zoom, and a see-through mode.
- [x] 6.9 Measure and section: distances, angles and coordinates between points and faces;
      section planes along the axes or any face, with a cap.
      Done: the 3D view's Measure tool gives a point's coordinates, the distance between two
      points (with its X, Y, Z components, and the gap between two parallel faces) and the angle
      between two faces, in the model's own coordinates (mm), marked on the model. Section cuts
      along the model's X, Y or Z or through a clicked face, moved with a slider, flipped, and
      capped so cut solids read as solid (stencil caps; where two solids overlap, the cap shows
      gaps).
- [x] 6.10 3D markups and saved views: annotations pinned on the model, with a camera, saved per
      version, shared and discussed.
      Done: the 3D view's Markups panel saves the camera, hidden parts and see-through with
      numbered pins and notes (model coordinates) on the model's latest version; everyone who can
      read the model sees them (earlier versions' marked as such) and opens one to get its view
      back. Each markup opens a topic in the object's discussion, with its reply count shown;
      authors delete their own, site administrators any.
- [x] 6.11 2D/PDF viewer with markups: drawings and PDFs rendered in the browser with pages and
      zoom; markups (arrows, text, clouds, rectangles) saved per version.
      Done: a 2D view tab on documents with a PDF (CAD drawings included) renders it in the
      browser (pdf.js, loaded on demand; watermarked for the viewer when the site watermarks
      downloads), page by page, zoomed in or fitted to the width, sharp at any zoom. Markups of
      arrows, rectangles, revision clouds and numbered notes, in page units, are saved per version
      and page through the same markups as the 3D view, each discussed in its own topic.
- [x] 6.12 MBD/PMI: dimensions, tolerances, datums and notes attached to the 3D model, grouped in
      views, shown in the viewer and kept with the version; imported with the model when present.
      Done: CAD parts and assemblies carry PMI per iteration (dimensions, geometric tolerances,
      datums, surface finishes and notes at points of the model, grouped in views with their own
      camera), carried into new iterations and revisions. The 3D view's PMI panel shows them as
      labels with leaders, all at once or a view at a time, and authors them on the working copy
      (place on the model, new views, keep the camera, import a file). The converter can't read
      PMI out of STEP AP242, so "with the model" means a sidecar `*.pmi.json` file attached to the
      model: it's read when that iteration is checked in. Semantic AP242 PMI is not read.

**Exit:** a STEP assembly is imported as CAD documents associated with parts and builds their
structure; it is converted on check-in, explored in the browser (structure, measure, section,
markups); and on release its drawing is published as a watermarked PDF.
**Met:** `e2e/cad-flow.spec.ts` imports the CAx-IF AS1 STEP assembly from a product page (nine
CAD documents, each owning a part, the parts' structure built), finds it converted on check-in in
the 3D view, hides a branch, measures, sections and saves a pinned markup; then a CAD drawing of
it with its PDF is released by resolving a change notice, the "released drawings" rule publishes
it, and the published PDF reads the drawing's number, Released and who downloaded it; the drawing
opens in the 2D view.

## Phase 7 — Configuration (complete)

**Goal:** one product definition answers "what do we build for this unit, on this date, with these
options": effectivity decides which revisions and lines apply, options and rules turn a 150%
structure into a buildable variant, one-off versions serve single orders without disturbing the
main line, and classification makes existing parts easy to find and reuse.

- [x] 7.1 Effectivity contexts and statements: an end item (a top part) as the context; effectivity
      by date, serial (unit) number, lot or block ranges, open-ended or closed, on part versions
      and on usage lines, relative to a context; overlap checks.
      Done: any part can be made an end item. A part's Effectivity tab gives each revision ranges
      of dates (alone or in an end item), serial numbers, lots or blocks (in an end item), closed or
      open-ended; the lines of its structure get theirs on the working copy and keep them through
      check-in, check-out and revise. Ranges of one kind in one end item can't overlap between a
      part's revisions, or on one line.
- [x] 7.2 Effectivity in configuration specs: "as effective on <date> / for serial <n> / lot <l> of
      <end item>" picks, per part, the revision effective there and keeps only effective lines; in
      structure, where-used, compare and baselines.
      Done: "As effective…" in the structure and where-used pickers (and the API's compare, with a
      value per side, and collect for baselines) takes a date, serial, lot or block of an end item.
      Each part is at the latest iteration of its revision effective there (none, if it has
      effectivity but no revision covers it; its latest Released, if it has none), and lines with
      effectivity are kept only where it covers the value.
- [x] 7.3 Pending effectivity through change: a change notice carries the effectivity of its
      resulting versions; it takes effect when the notice resolves, closing the superseded
      revision's range; shown on the notice and the part.
      Done: a change notice's "Effectivity of the results" panel sets ranges on the revisions of
      its resulting parts (by whoever may change the notice), pending, as the parts' Effectivity
      tabs show too. Resolving the notice puts them in effect and ends the range each one starts
      inside just before it (the day, unit or block before). A range that would replace another
      revision's whole range, split it, or close a lot range keeps the notice from being resolved,
      saying why.
- [x] 7.4 Effectivity in the UI: an effectivity panel on parts and lines, an "effective at" picker on
      structure views, the history of a part's ranges per end item.
      Done: the Effectivity tab draws each end item's (and the dates') ranges as a timeline, one
      colour per revision, pending ranges hatched and open ones running on; the structure tree
      tags lines that have effectivity with their ranges; "As effective…" picks the date, serial,
      lot or block in structure and where-used views; change notices show what they set.
- [x] 7.5 Options and choices: option sets in libraries (an option with its choices, e.g. Motor:
      standard, high torque), assigned to configurable products; choices can be retired.
      Done: libraries and products have an Options tab of option sets: options (code, name,
      required or optional) with their choices (code, name), retired with a click and kept for
      what already uses them. A part's Options tab makes it configurable with a set (a set in use
      can't be deleted). Codes (MOTOR, HT) are what conditions and rules refer to.
- [x] 7.6 Option rules: includes, excludes and requires between choices, and a check of any
      selection that says which rule it breaks and what would fix it.
      Done: an option set's rules say that a choice includes others, requires one of them, or
      excludes them, with a note. Checking a selection (a choice per option) reports required
      options left out, unknown or retired choices, and every broken rule, each with its fix: the
      choices to make or the ones to drop, applied with a click in the set's "Check a selection".
- [x] 7.7 150% structures: usage lines of configurable parts carry a choice condition (and, or,
      not over choices); the full structure shows every line with its condition; filtering by a
      selection leaves the lines that apply.
      Done: lines of a configurable part's working copy take conditions such as
      `MOTOR=HT and not COLOR in (RED, BLUE)` (and, or, not, parentheses, `in` lists), checked
      against its option set and kept with the line through check-out and revise; options used
      in conditions can't be removed. The part's Options tab shows the 150% structure, all levels,
      each line with its condition and whether it applies to the choices made (a line applies only
      if the lines above it do), and the structure tree tags lines with their conditions.
- [x] 7.8 Variant specifications: a named, saved selection of choices (with a configuration spec and
      effectivity) for a configurable product, checked against the rules; its filtered structure.
      Done: a configurable part's Options tab lists its variant specifications (name, choices,
      whether they still keep the rules, who saved them) and saves the choices made as a new one,
      read with the latest or latest Released versions (the API also takes a date, or effectivity
      for a unit); a selection that breaks a rule isn't saved. Opening one shows its 150%
      structure, filtered to what it builds.
- [x] 7.9 Variant generation and reuse: generate a variant part whose structure is the filtered
      result, level by level; before making new parts, find variants already generated for the
      same selection and reuse them; the variant remembers its specification.
      Done: "Generate" on a variant specification makes the variant part (named after the product
      and the specification) with the lines that apply, and, below it, a variant of every
      sub-assembly whose structure depends on the choices; others are used as they are. A
      sub-assembly variant generated earlier from the same iteration for the same choices of the
      options it depends on is reused, so "Expedition" and "Expedition, sand" share one pack
      variant. Generated parts show where they came from (base, version, choices, specification);
      base parts list their variants. Line effectivity isn't copied to variants.
- [x] 7.10 One-off versions: a version of a released part made for one order or unit (e.g. `B.2-1`),
      on a branch that the main line's configuration specs skip unless asked for, traceable to its
      origin and order.
      Done: "Make a one-off" (a part's History tab) starts a one-off of its latest version, which
      must be one that can't be changed (Released), for a stated order or unit: a working copy
      numbered on its own branch (A.2-1, A.2-2) with the base's structure, files and the rest, edited
      as usual with a banner saying so. Its check-in leaves the main line where it was: latest,
      Released and as-of specifications, effectivity and revising follow the main line only; the
      one-off is read by asking for its iteration, and lists its base and what it was for.
- [x] 7.11 Classification: a class tree (e.g. Fasteners > Screws > Hex screws) with class attributes
      inherited down the tree (types, units, value lists, required); classify parts, values kept
      per version; administered in the UI.
      Done: Administration › Classification holds the class tree (codes, names, descriptions) and
      each class's attributes (number with a unit, text, yes or no, or a value list; required or
      not), which every class below inherits; a key is used once along a branch. A part's
      Classification tab shows its class path and values; on the working copy it takes a class and
      values checked against the attributes. Check-out, revise and one-offs carry them forward, so
      each version keeps its own. A class in use, or with classes under it, isn't deleted.
- [x] 7.12 Classification search and find similar: browse by class and filter by class attribute
      ranges; from any part, find similar ones (same class, close values), ranked by similarity.
      Done: the Classes page (top bar) browses the class tree and lists the parts of a class and
      its subclasses (latest versions the reader may read) with their values as columns, filtered
      by ranges for numbers and values for the rest. A part's Classification tab lists similar
      parts: the others of its class ranked by how close each value is (numbers by relative
      difference, other values by equality, missing ones as far), with the likeness and the
      values that match shown.

**Exit:** a configurable product with options and rules is configured into a variant whose filtered
structure is generated as parts, reusing a variant generated earlier for a sub-assembly; a change
notice makes a new revision effective from a serial number, and the structure for serials below
and above it differs as planned; a one-off version for one order stays out of the main line; and a
part is found by class attributes and its similar parts.
**Met:** `e2e/config-flow.spec.ts` builds a configurable rover (an option set with a rule, a 150%
structure and a configurable power unit), picks a selection that breaks the rule and fixes it with
one click, saves and generates "Expedition" (2 parts made), then "Rally" (1 made, the power unit
variant reused); resolves a change notice whose pending effectivity makes the motor's revision B
effective from unit 100, and finds unit 50 built with A.1 and unit 150 with B.1; makes a one-off of
the motor for an order, which the main line and its structure don't see; and finds motors by a
power range on the Classes page and by likeness from one of them.

## Report builder (between Phases 7 and 8, complete)

Reports become queries built without code, over objects and the links between them.

- [x] Q1 Query engine: a `query` module compiles a query (objects of a type, links followed from
      them, conditions in all/any/none groups, columns with totals per group, sorting, which
      versions, questions asked at run time) into one SQL statement, every object's read access
      checked inside it; rows and time are capped. It knows no module's tables: versioning, bom,
      change, cad and classification contribute their objects, links (structure both ways,
      documents, changes, CAD models) and fields (built-in, attributes, class attributes).
- [x] Q2 Reports run queries: saved with answers for scheduled runs; charts by a column; mail with
      the query's columns; alerts on new rows (by the objects a query starts from).
- [x] Q3 The builder: find, where, show, with value pickers that fit each field, "ask when run",
      totals and headings, a live preview; reports show their questions, a table that opens the
      objects, a chart and an Excel export; dashboards show their rows. Simple reports remain and
      open in the builder in one click.
- [x] Q4 Demo data brings five shared reports per company; docs; end-to-end test.

**Met:** `report-builder-flow` builds a report in the browser over a structure (parts → uses →
children), asks for the assembly when it runs, keeps children heavier than 10 kg with their
quantity, and opens them from the table; `QueryApiIT` covers links, totals, all/any/none,
optional links, where used, questions, refused queries and access inside the SQL.

## Phase 8 — Desktop and native CAD (complete)
**Goal:** people work on files on their own computer and inside their authoring tools, and the
system keeps track: what they took, what they changed, what is out of date.
- [x] 8.1 Personal workspaces: a person's named workspaces (per context) holding the objects they
      work on, with each file's state: in sync, modified locally, out of date (a newer iteration
      on the server), new (not yet in the system), checked out by me or by someone else. Add
      objects with what they depend on (the collector, 6.2); upload changed files into the working
      copies; check in or undo everything in one go, all or nothing; conflicts named, never lost.
      (Done: a `workspace` module; each item remembers the iteration the person's computer holds and
      the hash their client last reported, from which its state follows; an upload into an
      out-of-date item is refused rather than overwriting someone's newer iteration; check out,
      check in and undo over many items in one transaction, the error naming the object; new files
      wait in the workspace until made into documents; a per-workspace history.)
- [x] 8.2 Workspace page: workspaces in the rail, the files with their state badges, filters,
      add from search or the clipboard, check out / update / check in / undo for a selection,
      download as a zip, and the history of what went in.
      (Done: Workspaces in the rail; state chips with counts as filters; add from search or the
      clipboard (CAD models with what they depend on); upload a changed file per row; new files
      made into documents with a type and name; the history on demand.)
- [x] 8.3 Desktop sync client: `plm-sync`, a command-line client (Node, no install beyond npm)
      that signs in with a personal access token and mirrors a workspace to a folder: `pull`
      (bring files up to date), `status` (what changed locally), `push` (upload changes, new files
      become new documents), `checkin`, `watch` (keep in sync). Personal access tokens in "My
      preferences".
      (Done: `clients/plm-sync`, no dependencies; a folder's `.plm-sync.json` records each file's
      object, iteration and hash, so changes here and in the system are told apart; a change here
      meeting a newer version there is kept, the newer one saved beside it; push goes on past a
      file that can't be sent. Personal access tokens (`PERSONAL`, read / write, admin only for
      administrators) made and revoked in My preferences; a "Sync with your computer" guide on each
      workspace. Its round trip is tested against a running server.)
- [x] 8.4 Office integration: properties of Word, Excel and PowerPoint files (title, subject,
      author, keywords and custom properties) read into attributes on upload and written back
      into the file on download, mapped per type in Admin; previews and PDF publishing of Office
      files through a converter (LibreOffice in the converter worker).
      (Done: an `office` module; core and custom properties read from and written into the file's
      Office Open XML parts (a custom part registered when missing); mappings per type, inherited
      by subtypes, with value lists matched by key or label; a download writes the object's values
      in. LibreOffice, when installed on the server, turns each Office file into a PDF for page
      previews and a thumbnail (run by the backend's job queue rather than a separate worker).)
- [x] 8.5 Connector kit: one protocol for authoring-tool connectors: a connector sends a manifest
      of the files it saves (model, kind, dependencies with their roles, parameters, the part it
      represents) and the server creates or updates the CAD documents, their dependencies, family
      tables and part associations in one transaction; the connector asks what to download for a
      model and its dependencies. A TypeScript and a Python client library with the protocol
      documented in `connectors/README.md`.
      (Done: a `connectors` module: `POST /connectors/save` creates or updates a manifest's models,
      files, values, dependencies and owned parts in one transaction, leaving unchanged models at
      their version, the error naming the model's key; `GET /connectors/models/{id}/files` lists a
      model with everything it depends on. Python and JavaScript clients without dependencies.)
- [x] 8.6 FreeCAD connector: a FreeCAD workbench (Python) on the kit: open from the system, save
      and check in a document with its linked parts and assemblies (App::Link and the like read
      from the .FCStd), parameters from spreadsheets, a thumbnail. Tested headless against real
      .FCStd files.
      (Done: `connectors/freecad`: reads links to other documents with their instance names and
      placements, spreadsheet parameters, drawings and thumbnails from the .FCStd itself; saves an
      assembly with everything it links to, sending only changed files; opens a model with its
      dependencies where its links expect them. A FreeCAD workbench (Save to PLM, Open from PLM) on
      the same code; tested on FreeCAD's own example files and against a running server.)
- [x] 8.7 Electrical CAD: KiCad projects (schematic, board, symbol and footprint libraries) as ECAD
      documents with their dependencies; the schematic's components become a part structure
      (reference designators as occurrences, 4.5); a board preview; a "compare ECAD BOM with the
      part structure" report.
      (Done: an `ecad` module reading KiCad 6+ files; ECAD project, schematic and board types; a
      project's files (sent as one folder) become the project, a schematic per sheet and the board,
      with their dependencies; the schematic's components grouped into lines become the structure of
      a new assembly part owned by the board, reference designators as occurrences, existing parts
      matched by part number (MPN) or name; a board preview drawn from the board (outline, copper on
      both sides, pads, references); a BOM check on projects and boards. Tested on KiCad's own
      pic_programmer demo.)
- [x] 8.8 Commercial CAD connectors (SolidWorks, Inventor, Creo, NX, CATIA): add-in projects on the
      connector kit, each needing the vendor's SDK and a licensed Windows machine to build and
      test. This phase delivers the kit, its documentation and a SolidWorks add-in skeleton
      (C#); finished connectors are separate projects.
      (Done: `connectors/solidworks`: a .NET protocol client and an add-in (Save to PLM walking an
      assembly's components with placements, Open from PLM), not compiled or run here, which needs
      Windows and SolidWorks; its README lists what is left. Phase 8 is complete.)

## Phase 9 — Enterprise integration and infrastructure
**Goal:** what a company needs to go live: its directory, its existing data, its suppliers, its
ERP, its legal and security rules, more than one site, and safe upgrades.
- [x] 9.1 Directory groups: SCIM 2.0 (users and groups) for identity providers that push them,
      and LDAP/AD through the identity provider's federation; a directory group becomes a shared
      team whose members the directory manages, attached to contexts with a role like any other.
      Done: `directory` module at `/api/v1/scim/v2` (Users, Groups, filters, paging, PATCH as
      Entra ID / Okta send it, SCIM errors), authenticated by an admin service account's token.
      People exist before their first sign-in and are re-keyed to their real subject when they
      sign in; deactivation takes them off every team. Each group is a read-only shared team with
      a role chosen in Administration → Directory.
- [x] 9.2 Bulk load: load objects of any type (with attributes, folders, files from a zip,
      structure and document links) from a spreadsheet, with validation, a dry run that shows
      what would happen, and the load itself as a background job with a per-row result.
      Done: Import → From a spreadsheet on a product (`/api/v1/bulk-loads`, preview and job):
      Type/Number/Name/Folder/File/Parent/Quantity/Find number/Documents/State columns, every
      other column an attribute by name or label (values converted, then validated like a form);
      an existing number updates its object; a template per type. The CSV/Excel reader moved to
      the platform (`Tables`), shared with the structure import.
- [x] 9.3 Migration from other PLM systems: an import format (a zip with a JSON manifest) that
      keeps each object's history (versions, iterations, dates, who), states, files and links,
      with type, attribute, state and user mappings, a dry run and a resumable job; numbers and
      versions are kept as they were.
      Done: `migration` module and Administration → Migration; format in
      docs/migration-format.md. History comes in through admin-only import paths in versioning
      (iterations with their labels, dates, authors, states), content (files per past iteration)
      and BOM (structure and document links per iteration). The analysis lists what to map;
      each object's outcome is kept, so running again resumes without duplicates.
- [x] 9.4 Packages and deliveries: a package is a chosen set of objects under a configuration spec
      with what to include (files, PDFs, 3D, structure, documents); a delivery sends it to a
      recipient as a download that expires, logs who got what, and a received package can be
      imported back (as copies marked with where they came from, or as updates).
      Done: `packages` module and a Packages page: roots under latest or released versions,
      with structure, describing documents, files and PDFs; a delivery is a zip in the migration
      format prepared by a job, behind an expiring link that needs no account (revocable, every
      download logged). Received packages come back through Migration with "numbers already
      here" set to copy (suffixed, marked "Received from …") or update (a new iteration).
- [x] 9.5 ERP distribution: distribution targets (an ERP system and plant) with plant-specific
      attributes; rules say which objects go to which targets when released; a release sends them
      (through the integration outbox), the ERP acknowledges, and the object shows its
      distribution status per target, with resend.
      Done: `integration` module, Administration → ERP distribution and a Distribution tab on
      objects. Messages (object, attributes, plant values, single-level BOM) are written to an
      outbox in the state change's transaction, POSTed with an HMAC signature and retries, or
      fetched by the ERP; acknowledgements carry the ERP's key or a rejection reason. Missing
      required plant values hold the message and say what to fill in.
- [x] 9.6 Standards: STEP AP242 export of a product structure (with its CAD files), and the
      OSLC core services (resources, service provider catalog, link to external resources) so
      requirements and test tools can link to parts, documents and changes. ReqIF comes with
      requirements (Phase 14), IPC-1752 with compliance (Phase 13).
      Done: `standards` module (docs/oslc.md). STEP export: Structure → STEP AP242, a zip with
      the AP242 file (products, versions, an occurrence per unit, CAD files as external document
      references) and those files; the STEP import reads it back. OSLC: catalog, a provider per
      product/library/project (Architecture and Change Management, query capability, delegated
      selection dialog), resources in RDF/XML, Turtle or JSON-LD, compact previews; links to
      requirements, tests and changes on each object, published on its resource.
- [x] 9.7 Security labels and agreements: labels (export control, proprietary…) with ordered
      values set on objects; people hold clearances; reading or downloading a labelled object needs
      a clearance or an agreement (a time-limited grant to named people for named objects or
      contexts), enforced in every access check and list filter; why someone can't see an object
      is explained.
      Done: labels with ordered values (append-only, ranks stored), object labels set by context
      managers, clearances with expiry, agreements for named people over objects or contexts
      (and below), all in `DefaultAccessService`: `permissions()` hides a blocked object and the SQL
      `filter()` adds the label predicate to every list and search. A person the context lets in
      gets `LABEL_CLEARANCE_REQUIRED` and a page saying which labels block them; others still get
      404. Site administrators pass. Changes are audited (`access.SecurityLabelsChanged.v1`).
- [ ] 9.8 Retention, legal hold, archive and purge: retention rules per type and state (keep for
      N years, then purge old iterations or archive); legal holds that freeze objects or whole
      contexts against deletion and purge; archive to the vault as a self-contained package with
      restore; all as jobs with a report.
- [ ] 9.9 Audit configuration: which kinds of events are audited, per site; audit retention.
- [ ] 9.10 File sites: file servers (vault replicas) near remote sites, each person's preferred
      site, replication of new content as jobs, downloads served from the nearest copy; resumable
      multipart uploads for large files.
- [ ] 9.11 Operations: OpenTelemetry tracing and Prometheus metrics (0.12); signed extension
      packages; `plm upgrade` (backup, migrate, health check) and an upgrade playbook with
      zero-downtime rules for migrations.
- [ ] 9.12 Tenants (SaaS): not in this phase. Built-in types, lifecycles and roles have fixed ids
      shared by every tenant; separate tenants need per-tenant built-ins first (an ADR).

## Phase 10 — Manufacturing process management
**Goal:** from the engineering structure to how each plant builds it.
- [ ] 10.1 Views: parts belong to a view (Design, Manufacturing, Service, configurable); a "new
      view version" makes a downstream part (its number from the upstream one with the view's
      suffix) linked to its upstream part by an equivalence link.
- [ ] 10.2 Plants and plant data: plants as contexts' resources; per part and plant: procurement
      (make, buy), lead time, cost, storage location, as plant-specific attributes.
- [ ] 10.3 Generate the downstream structure: build an MBOM from the EBOM (phantoms collapsed,
      purchased assemblies kept whole, lines grouped), with equivalence links throughout.
- [ ] 10.4 Reconciliation: compare upstream and downstream through equivalence links: missing,
      extra, quantity differences, upstream newer than its equivalent; fix them one by one or all.
- [ ] 10.5 Side-by-side editor: the EBOM and the MBOM next to each other, highlighted by
      equivalence, drag lines across to assign them, plant switcher.
- [ ] 10.6 Resources: work centers, tooling and skills in libraries, with capacity and cost rates.
- [ ] 10.7 Process plans and operations: a process plan for a manufacturing part in a plant: a
      sequence of operations (with work center, setup and run time, tooling, skills), the parts
      each operation consumes (MBOM lines assigned to operations), time and cost roll-ups.
- [ ] 10.8 Work instructions: steps per operation with text, images and saved 3D views (6.10),
      shown to the operator one step at a time and published as a PDF.

## Phase 11 — Projects and programs
Project plans, activities, milestones, Gantt · deliverables linked to PLM objects · resources ·
project templates · meetings and action items · share into projects and send back.

## Phase 12 — Quality and regulatory
Nonconformance · CAPA · customer complaints · audits · regulatory submissions · FMEA and control plans.

## Phase 13 — Supplier management and environmental compliance
Manufacturer and vendor parts · AML/AVL and sourcing rules · material declarations · RoHS, REACH,
conflict minerals · compliance roll-ups.

## Phase 14+ — Adjacent domains
Requirements traceability · service information, translation, service BOM and parts catalogues ·
software parts and builds · reliability analysis · mobile and AR.
