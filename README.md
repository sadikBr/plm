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

The whole platform runs locally: its services in Docker, the backend and the web UI as two local
processes, all started and stopped by one command, `plm`. Nothing needs a cloud account. More
detail (environment variables, the curl walkthrough, troubleshooting) is in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

### 1. Install the prerequisites (once)

You need **Git**, **Docker** (with Compose v2), **JDK 21** and **Node.js 22 or newer**. Install
them with your system's package manager, or from the official sites linked below. You don't need
Gradle: the repository brings its own (`./gradlew`).

**Windows 10/11** (in PowerShell or Command Prompt; Docker Desktop needs WSL 2):

```powershell
winget install Git.Git
```

```powershell
wsl --install
```

```powershell
winget install Docker.DockerDesktop
```

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
```

```powershell
winget install OpenJS.NodeJS.LTS
```

Then restart, start Docker Desktop once and let it finish setting up, and open a new terminal.

**macOS** (with [Homebrew](https://brew.sh)):

```bash
brew install git node
```

```bash
brew install --cask temurin@21 docker
```

Then start Docker from Applications once. ([OrbStack](https://orbstack.dev), `brew install --cask orbstack`, works
too, instead of Docker Desktop.)

**Ubuntu / Debian:**

```bash
sudo apt update && sudo apt install -y git curl openjdk-21-jdk
```

```bash
curl -fsSL https://get.docker.com | sudo sh
```

```bash
sudo usermod -aG docker "$USER"
```

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt install -y nodejs
```

Then log out and back in (so your user may use Docker). On Debian 12, `openjdk-21-jdk` comes from
bookworm-backports, or install [Temurin 21](https://adoptium.net/installation/linux/).

**Fedora:**

```bash
sudo dnf install -y git curl java-21-openjdk-devel nodejs22 dnf-plugins-core
```

```bash
sudo dnf config-manager addrepo --from-repofile=https://download.docker.com/linux/fedora/docker-ce.repo
```

```bash
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
```

```bash
sudo systemctl enable --now docker && sudo usermod -aG docker "$USER"
```

Then log out and back in.

**Arch Linux / Manjaro:**

```bash
sudo pacman -S --needed git curl jdk21-openjdk nodejs npm docker docker-compose
```

```bash
sudo systemctl enable --now docker && sudo usermod -aG docker "$USER"
```

Then log out and back in.

Official downloads, for any system: [Git](https://git-scm.com/downloads) ·
[Docker Desktop](https://docs.docker.com/get-started/get-docker/) (or
[Docker Engine on Linux](https://docs.docker.com/engine/install/)) ·
[JDK 21 (Temurin)](https://adoptium.net/temurin/releases/?version=21) ·
[Node.js](https://nodejs.org/en/download).

Check them (each should answer with a version: Java 21 or newer, Node 22 or newer):

```bash
git --version
```

```bash
docker compose version
```

```bash
java -version
```

```bash
node --version
```

Give Docker at least **8 GB of memory** (Docker Desktop: Settings › Resources); `--lite` below
needs about 5 GB.

### 2. Get the code (once)

```bash
git clone https://github.com/sadikBr/plm.git
```

```bash
cd plm
```

### 3. Start the platform

On macOS, Linux or WSL:

```bash
./plm start
```

On Windows (Command Prompt; in PowerShell, type `.\plm start`):

```powershell
plm start
```

It checks the prerequisites, installs what the web UI and the CAD converter need, builds the
backend, starts the services, the backend and the web UI, waits until each answers, and says
where to go. The first start downloads a few GB (Docker images, libraries) and takes 5 to 15
minutes; later ones take about a minute.

Then open **http://localhost:5173** and sign in. Dev users (password = user name):

| User | Role |
|---|---|
| `alice` | site administrator: sees and administers everything |
| `bob` | ordinary user: sees what he's on the team of |
| `carol` | ordinary user, on no team: sees nothing until added |

Other local addresses: the API explorer at http://localhost:8080/swagger-ui.html, the mail the
platform sends at http://localhost:8025, workflows at http://localhost:8233.

### 4. Everyday commands

| Command (Windows: `plm …`) | What it does |
|---|---|
| `./plm start` | Starts everything and waits until it's ready (does nothing to what's already running) |
| `./plm start --lite` | The same with fewer services (no OpenSearch, no Temporal): less memory |
| `./plm status` | What's running, and where |
| `./plm stop` | Stops the web UI, the backend and the services; your data is kept |
| `./plm restart` | Stop, then start (after pulling new code, say) |
| `./plm logs backend` | Follows the backend's log (`ui` and `services` too); Ctrl-C to stop following |
| `./plm setup` | Only checks the prerequisites, installs and builds |
| `./plm reset` | Stops everything and deletes all local data (asks first) |

Logs are kept in `.plm/`. If something goes wrong, `./plm status` and `./plm logs backend` say
where; see also [Troubleshooting](docs/DEVELOPMENT.md#troubleshooting).

### 5. Running the pieces yourself (for development)

`plm` runs a built backend and the web UI's dev server. While changing code, you can run each part
in its own terminal instead: `make up-full` (services), `make run` (backend, rebuilt on each start),
`make ui` (web UI, reloads as you edit). [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) explains each.

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

Backend: compile, lint and every test (Docker must be running; on Windows, `gradlew.bat build`):

```bash
./gradlew build
```

Web UI: lint, type check and unit tests:

```bash
cd frontend && npm run lint && npm run typecheck && npm test
```

The browser end-to-end tests drive the running platform (`./plm start` first). Install their
browser once, then run every scenario headless:

```bash
cd frontend && npx playwright install chromium
```

```bash
cd frontend && npm run e2e
```

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
