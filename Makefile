COMPOSE_VERSIONS = --env-file ./infra/docker/container-versions.env
COMPOSE_DEV = docker compose $(COMPOSE_VERSIONS) --env-file ./envs/.env -f ./infra/docker/docker-compose-dev.yml -p mvflix-app
COMPOSE_E2E = docker compose $(COMPOSE_VERSIONS) -f ./e2e/docker-compose-e2e.yml -p mvflix-e2e

.PHONY: up-dev up-dev-d down-dev down-dev-v repair-postgres-permissions validate-contracts up-observability up-observability-d up-e2e up-e2e-d down-e2e down-e2e-v ps sandbox-run sandbox-test

up-dev:
	$(COMPOSE_DEV) up --remove-orphans

up-dev-d:
	$(COMPOSE_DEV) up --remove-orphans -d

down-dev:
	$(COMPOSE_DEV) down --remove-orphans

down-dev-v:
	$(COMPOSE_DEV) down --remove-orphans -v

repair-postgres-permissions:
	bash ./scripts/repair-postgres-permissions.sh

validate-contracts:
	bash ./scripts/validate-contracts.sh

up-observability:
	$(COMPOSE_DEV) up prometheus otel-collector tempo grafana

up-observability-d:
	$(COMPOSE_DEV) up prometheus otel-collector tempo grafana -d

up-e2e:
	$(COMPOSE_E2E) up --build --remove-orphans

up-e2e-d:
	$(COMPOSE_E2E) up --build --remove-orphans --wait -d

down-e2e:
	$(COMPOSE_E2E) down --remove-orphans

down-e2e-v:
	$(COMPOSE_E2E) down --remove-orphans -v

ps:
	docker compose ls

# Perfil sandbox: storage-service SIN authorization-service (postgres + minio solos)
sandbox-run:
	SPRING_PROFILES_ACTIVE=sandbox mvn -pl mvflix-storage spring-boot:run

sandbox-test:
	mvn -pl mvflix-storage test -Dtest='StorageFlowSmokeTest'
