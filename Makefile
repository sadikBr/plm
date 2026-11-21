COMPOSE := docker compose -f deploy/docker-compose.yml

.PHONY: up up-full down reset build test run fmt events ui ui-install ui-test e2e api-contract converter-install start stop status

start:     ## Start the whole platform and wait until it's ready (same as ./plm start)
	./plm start
stop:      ## Stop the whole platform, keep the data (same as ./plm stop)
	./plm stop
status:    ## What's running (same as ./plm status)
	./plm status
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
ui-install: ## Install frontend dependencies
	cd frontend && npm ci
converter-install: ## Install the CAD converter (STEP to glTF, for the 3D viewer)
	cd workers/cad-converter && npm ci
ui:        ## Run the web UI on :5173 (needs `make up` and `make run`)
	cd frontend && npm run dev
ui-test:   ## Frontend lint, typecheck and unit tests
	cd frontend && npm run lint && npm run typecheck && npm test
e2e:       ## Browser end-to-end test (needs `make up`, `make run` and `make ui`)
	cd frontend && npm run e2e
api-contract: ## Regenerate api/openapi.json after changing the API
	./gradlew :backend:app:test --tests '*OpenApiContractIT' -Dplm.openapi.update=true
