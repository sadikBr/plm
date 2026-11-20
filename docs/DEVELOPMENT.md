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
- **PlmApplicationIT**: boots the whole application against a throwaway Postgres 18 started by
  Testcontainers. Docker must be running. No other setup is needed.

The first run downloads dependencies and takes a few minutes. Later runs are fast.
Test reports: `backend/app/build/reports/tests/test/index.html`.

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

## 4. Smoke-test the stack by hand

Get a token for alice from Keycloak (used from roadmap step 0.8 onwards):

```bash
curl -s -d grant_type=password -d client_id=plm-web -d username=alice -d password=alice \
  localhost:8180/realms/plm/protocol/openid-connect/token
```

Open a SQL shell:

```bash
docker compose -f deploy/docker-compose.yml exec postgres psql -U plm -d plm
```

## 5. Everyday commands

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
- **Port already in use**: another local Postgres or Keycloak is running. Stop it, or change the
  left side of the port mapping in `deploy/docker-compose.yml`.
- **Apple Silicon**: all images are multi-arch. No extra flags needed.
