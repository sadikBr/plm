# PLM Platform

An open Product Lifecycle Management (PLM) platform: documents, parts, multi-level BOMs,
lifecycles, workflows and change management, built to scale to millions of objects.

> Status: **Phase 7 complete**: configuration: effectivity by date, serial, lot or block on
> versions and structure lines (end items, pending effectivity set through change notices, "as
> effective" structures), option sets with rules, 150% structures with choice conditions, variant
> specifications and variant generation with reuse, one-off versions for single orders, and
> classification with inherited class attributes, class search and find similar. On top of CAD data
> and visualization (Phase 6), workflow and change management (Phase 5), product structures
> (Phase 4), documents, search, reports and collaboration (Phase 3), the extensibility platform
> (Phase 2) and the core model (Phase 1). Next: Phase 8. See the [roadmap](docs/ROADMAP.md).

![Object page: a checked-out part with its working copy](docs/images/ui-object.png) See the [roadmap](docs/ROADMAP.md).

## Run it on your computer

Every step below is a command to copy. The whole stack runs locally in Docker; nothing needs a
cloud account. A more detailed guide (environment variables, the curl walkthrough, troubleshooting)
is in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

### 1. Install the prerequisites (once)

| Tool | Version | Check it with |
|---|---|---|
| JDK | 21 (Temurin recommended) | `java -version` |
| Docker | Docker Desktop, OrbStack or Docker Engine with Compose v2; give it 8 GB of memory | `docker compose version` |
| Node.js | 22 LTS | `node --version` |
| Git | any | `git --version` |
| make | optional: each target is a one-line command shown in the [Makefile](Makefile) | `make --version` |

You don't need Gradle: the repository's `./gradlew` downloads it (`gradlew.bat` on Windows).

### 2. Get the code and install the dependencies (once)

```bash
git clone https://github.com/sadikBr/plm.git
```

```bash
cd plm
```

```bash
make ui-install            # the web UI's packages (cd frontend && npm ci)
```

```bash
make converter-install     # the CAD converter, STEP to glTF (cd workers/cad-converter && npm ci)
```

### 3. Start the services

```bash
make up-full               # Postgres, S3 storage, Keycloak, Redpanda, OpenSearch, Temporal, Mailpit
```

Wait about a minute, then check that everything is up (every line should say `running` or
`healthy`):

```bash
docker compose -f deploy/docker-compose.yml --profile full ps
```

`make up` starts a lighter set (no OpenSearch, no Temporal): search then uses Postgres and
workflows run in the backend's own process. Both work; `make up-full` is closest to production.

### 4. Start the backend (keep this terminal open)

```bash
PLM_SEARCH_ENGINE=opensearch PLM_TEMPORAL_TARGET=localhost:7233 make run
```

With `make up` instead of `make up-full`, start it with just `make run`. The first start compiles
the code and takes a few minutes; it's ready when this prints `"status":"UP"`:

```bash
curl localhost:8080/actuator/health
```

### 5. Start the web UI (in a second terminal, keep it open)

```bash
make ui                    # cd frontend && npm run dev
```

Open http://localhost:5173 and sign in. Dev users (password = user name):

| User | Role |
|---|---|
| `alice` | site administrator: sees and administers everything |
| `bob` | ordinary user: sees what he's on the team of |
| `carol` | ordinary user, on no team: sees nothing until added |

Other local addresses: mail the platform sends at http://localhost:8025 (Mailpit), workflows at
http://localhost:8233 (Temporal UI), the API explorer at http://localhost:8080/swagger-ui.html.

### 6. Try it

The database starts empty. As **alice**:

1. **Set up a product.** On the **Site** page click **New organization**; in it, open **Products**
   and click **New product**. On its **Team** tab add `bob` (he must have signed in once).
2. **Parts and documents.** In the product, click **New part** and **New document**. **Check out**,
   change attributes, upload files on a document, **Check in**, **Revise**; see **History**.
3. **Product structure.** On an assembly part's **Structure** tab add child parts with quantities;
   try **Where used**, **Compare** and **Baselines**.
4. **Change management.** Open **Changes** in the top bar (icons only on narrow screens; hover for names): report a problem, raise a change
   request, approve it from **Tasks** (inbox), make a change notice with the part as its result
   and set it to *Resolved*: the part becomes *Released*.
5. **CAD data.** On a product page click **Import STEP…** and choose
   `backend/modules/cad/src/test/resources/step/as1_pe_203.stp` (keep both boxes ticked). The
   assembly opens; after a few seconds its **3D view** tab shows the model: hide parts, **Measure**,
   **Section**, save **Markups**, show **PMI**. A document or CAD drawing with a PDF has a
   **2D view** tab with markups.
6. **Configuration.** On a product's **Options** tab create an option set (options and choices,
   rules such as "MOTOR=HT requires BATTERY=L"). On a part's **Options** tab make it configurable,
   check it out and give its structure lines conditions (`MOTOR=HT and not COLOR=RED`); pick
   choices to see what applies, save them as a variant specification and **Generate** the
   variant parts. A part's **Effectivity** tab makes it an end item and gives revisions ranges of
   dates or serial numbers (or set them on a change notice, pending until it's resolved); the
   structure's **Show** list has **As effective…**. A released part's **History** tab makes a
   one-off version for an order. **Administration › Classification** holds the class tree; parts
   are classified on their **Classification** tab, and the **Classes** page (top bar) searches
   them; each part lists similar ones.
7. **Administration** (the **Admin** link, a gear icon, in the top bar): types and attributes, lifecycles, numbering, workflows,
   publishing rules, classification, automations, extensions.

The [UI tour](docs/DEVELOPMENT.md#4-run-the-web-ui) goes further, and each phase's end-to-end test
in [frontend/e2e](frontend/e2e) is a scripted version of a whole scenario.

### 7. Run the tests

```bash
./gradlew build            # backend: compile, lint, every test (Docker must be running)
```

```bash
make ui-test               # web UI: lint, type check, unit tests
```

The browser end-to-end tests need steps 3 to 5 running, and Playwright's browser once:

```bash
cd frontend && npx playwright install chromium && cd ..
```

```bash
make e2e                   # every scenario, in a headless browser
```

### 8. Stop, or start over

```bash
make down                  # stop the services, keep the data
```

```bash
make reset                 # stop the services and delete all local data
```

Stop the backend and the UI with Ctrl-C in their terminals. If something goes wrong, see
[Troubleshooting](docs/DEVELOPMENT.md#troubleshooting).

## Documentation

| Doc | Purpose |
|---|---|
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | How to build, run and test locally |
| [docs/PLAN.md](docs/PLAN.md) | Product scope, architecture, data model, pitfalls |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Phased delivery plan with exit criteria |
| [docs/PARITY.md](docs/PARITY.md) | Capability matrix against established enterprise PLM: what's done, what's planned, where we aim to be better |
| [docs/EXTENSIBILITY.md](docs/EXTENSIBILITY.md) | How customers customize the PLM: custom actions, rules, webhooks, scripts, UI slots |
| [docs/adr/](docs/adr/) | Architecture Decision Records — the *why* behind every foundational choice |

## Core principles

1. **Modular monolith first.** Strict module boundaries, enforced in CI; extract services only with evidence.
2. **Master → Version → Iteration.** Iterations are immutable. Every versioned type shares one engine.
3. **BOM links point to child masters.** Config specs resolve which version you see.
4. **Access control lives in the query.** Never filter in application memory.
5. **Files never stream through the app.** Content-addressed object storage, presigned URLs.
6. **Everything emits events** through a transactional outbox; side effects are asynchronous.
7. **Audit everything**, append-only, from day one.

## Stack

Kotlin + Spring Boot 4 · PostgreSQL · Flyway · Temporal · Redpanda/Kafka · OpenSearch ·
S3 (SeaweedFS in dev) · Keycloak (OIDC) · React + TypeScript + AG Grid · three.js · OpenCascade
