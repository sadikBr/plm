# Local development

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | 21 (Temurin recommended) | `java -version` |
| Docker | Docker Desktop, OrbStack or Docker Engine with Compose v2 | `docker compose version` |
| make | optional, every target is a one-line command | `make --version` |

You do **not** need to install Gradle: use `./gradlew` (`gradlew.bat` on Windows).
If you don't have JDK 21, Gradle downloads one automatically (Foojay toolchain resolver).

## 1. Build and run all tests

```bash
./gradlew build
```

This compiles everything (compiler warnings fail the build), runs ktlint and runs all tests:

- **ArchitectureTest**: enforces module boundaries (ADR 0001). It includes a self-test proving
  the rule catches a deliberate violation.
- **Module integration tests** (`*IT`): run each module against a throwaway Postgres 18 started by
  Testcontainers, with every migration applied. Docker must be running. No other setup is needed.
- **VersioningApiIT**: drives the whole flow over HTTP, the way the UI will.

The first run downloads dependencies and takes a few minutes. Later runs are fast.
Test reports: `backend/<project>/build/reports/tests/test/index.html`.

## 2. Start the dev stack

```bash
make up          # postgres, s3, keycloak
make up-full     # + redpanda, opensearch, temporal (≈3 GB RAM more)
```

Without make: `docker compose -f deploy/docker-compose.yml up -d` (add `--profile full` for everything).

| Service | URL / port | Credentials |
|---|---|---|
| PostgreSQL 18 | `localhost:5432`, db `plm` | `plm` / `plm` |
| S3 (SeaweedFS) | http://localhost:8333 | access key `plm`, secret `plm-dev-secret` |
| Keycloak | http://localhost:8180 (admin console) | `admin` / `admin` |
| Keycloak realm `plm` | users `alice` (admin) and `bob` (user) | password = username |
| Redpanda (Kafka API) | `localhost:19092` | none |
| OpenSearch | http://localhost:9200 | none (security disabled in dev) |
| Temporal | gRPC `localhost:7233`, UI http://localhost:8233 | none |

These credentials are for local development only.

## 3. Run the backend

```bash
make run         # or: ./gradlew :backend:app:bootRun
```

Then check it:

```bash
curl localhost:8080/actuator/health
```

Expected output: `{"groups":["liveness","readiness"],"status":"UP"}`

Override settings with environment variables: `PLM_DB_URL`, `PLM_DB_USER`, `PLM_DB_PASSWORD`, `PLM_PORT`.

## 4. Try the versioning API

The fastest way is [`docs/api/versioning.http`](api/versioning.http). Open it in IntelliJ IDEA,
or in VS Code with the *REST Client* extension, and run the requests top to bottom.

Or use `curl` (with [`jq`](https://jqlang.org) to pick IDs out of responses). With the backend
running (`make run`), paste these into a terminal one block at a time:

```bash
API=localhost:8080/api/v1
SITE=$(curl -s $API/contexts/site | jq -r .id)
ORG=$(curl -s -X POST $API/contexts -H 'Content-Type: application/json' \
  -d "{\"parentId\":\"$SITE\",\"kind\":\"ORGANIZATION\",\"name\":\"Acme\"}" | jq -r .id)
PRODUCT=$(curl -s -X POST $API/contexts -H 'Content-Type: application/json' \
  -d "{\"parentId\":\"$ORG\",\"kind\":\"PRODUCT\",\"name\":\"Drone X1\"}" | jq -r .id)
```

Create a part. The response shows `"version":"A.1"`:

```bash
PART=$(curl -s -X POST $API/objects -H 'Content-Type: application/json' \
  -d "{\"typeId\":\"00000000-0000-7000-8000-000000000102\",\"contextId\":\"$PRODUCT\",\"name\":\"Motor mount\",\"attributes\":{\"material\":\"AL6061\"}}" \
  | jq -r .id); echo $PART
```

Check it out as alice, which creates working copy `A.2`. Then try as bob, which gets a `409 ALREADY_CHECKED_OUT`:

```bash
curl -s -X POST $API/objects/$PART/checkout -H 'X-PLM-User: alice' | jq '{version, status}'
curl -s -X POST $API/objects/$PART/checkout -H 'X-PLM-User: bob' | jq
```

Edit, check in, revise, and look at the history (`B.1`, `A.2`, `A.1`):

```bash
curl -s -X PATCH $API/objects/$PART/working-copy -H 'Content-Type: application/json' \
  -d '{"attributes":{"material":"Ti-6Al-4V"}}' | jq '{version, attributes}'
curl -s -X POST $API/objects/$PART/checkin -H 'Content-Type: application/json' \
  -d '{"comment":"Switch to titanium"}' | jq '{version, checkedOutBy}'
curl -s -X POST $API/objects/$PART/revise | jq .version
curl -s $API/objects/$PART/iterations | jq '.[] | {version, comment, material: .attributes.material}'
```

**Who am I?** Until Keycloak login lands (roadmap 0.8), the `X-PLM-User` header says who is calling.
It defaults to `alice`. This is a development stand-in with **no security**.

### Endpoints

| Method | Path | Does |
|---|---|---|
| GET | `/api/v1/contexts/site` | Root context |
| GET | `/api/v1/contexts/{id}` · `/{id}/children` | Read contexts |
| POST | `/api/v1/contexts` | Create `{parentId, kind, name}`: ORGANIZATION under SITE; PRODUCT / LIBRARY / PROJECT under ORGANIZATION |
| GET / POST | `/api/v1/types` | List types / create subtype `{parentId, name, displayName}` |
| POST | `/api/v1/objects` | Create `{typeId, contextId, name, number?, attributes?}` → A.1 |
| GET | `/api/v1/objects/{id}` | Latest checked-in iteration and who has it checked out |
| GET | `/api/v1/objects/{id}/iterations` | History, newest first |
| POST | `/api/v1/objects/{id}/checkout` | Create your working copy |
| GET / PATCH | `/api/v1/objects/{id}/working-copy` | Read / replace your working copy's `attributes` |
| POST | `/api/v1/objects/{id}/checkin` | Optional `{comment}` |
| POST | `/api/v1/objects/{id}/undo-checkout` | Discard your working copy |
| POST | `/api/v1/objects/{id}/revise` | Next revision (A.3 → B.1) |

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details with a stable `code`,
e.g. `{"status":409,"code":"ALREADY_CHECKED_OUT","details":{"checkedOutBy":"alice"}}`.

Look at the raw tables:

```bash
docker compose -f deploy/docker-compose.yml exec postgres psql -U plm -d plm \
  -c "select revision||'.'||iteration as version, status, attrs, comment from object_iteration order by seq"
```

## 5. Smoke-test the stack by hand

Get a token for alice from Keycloak (used from roadmap step 0.8 onwards):

```bash
curl -s -d grant_type=password -d client_id=plm-web -d username=alice -d password=alice \
  localhost:8180/realms/plm/protocol/openid-connect/token
```

Open a SQL shell:

```bash
docker compose -f deploy/docker-compose.yml exec postgres psql -U plm -d plm
```

## 6. Everyday commands

| Command | What it does |
|---|---|
| `make fmt` / `./gradlew ktlintFormat` | Auto-fix code style |
| `./gradlew :backend:app:test --tests '*ArchitectureTest'` | Run one test class |
| `make down` | Stop services, keep data |
| `make reset` | Stop services and **delete** all local data |

## Troubleshooting

- **`Could not find a valid Docker environment`**: Docker isn't running. Start Docker Desktop.
- **Docker Hub `429 Too Many Requests`**: pull through a mirror:
  `IMAGE_PREFIX=mirror.gcr.io/ make up`. For Testcontainers, put
  `hub.image.name.prefix=mirror.gcr.io/` in `~/.testcontainers.properties`.
- **Database in a weird state** after switching branches: `make reset && make up` gives you a fresh, empty database.
- **Port already in use**: another local Postgres or Keycloak is running. Stop it, or change the
  left side of the port mapping in `deploy/docker-compose.yml`.
- **Apple Silicon**: all images are multi-arch. No extra flags needed.
