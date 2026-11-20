COMPOSE := docker compose -f deploy/docker-compose.yml

.PHONY: up up-full down reset build test run fmt events

up:        ## Start core services (postgres, s3, keycloak, redpanda)
	$(COMPOSE) up -d
up-full:   ## Start every service (adds opensearch, temporal)
	$(COMPOSE) --profile full up -d
down:      ## Stop services, keep data
	$(COMPOSE) --profile full down
reset:     ## Stop services and delete all local data
	$(COMPOSE) --profile full down -v
build:     ## Compile, lint and run all tests
	./gradlew build
test:      ## Run tests only
	./gradlew test
run:       ## Run the backend on :8080 (needs `make up`)
	./gradlew :backend:app:bootRun
fmt:       ## Auto-fix code style
	./gradlew ktlintFormat
events:    ## Tail domain events on the bus (Ctrl-C to stop)
	$(COMPOSE) exec redpanda rpk topic consume plm.domain-events --offset start
