#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAKE=(make -C "${PROJECT_ROOT}")
COMPOSE=(docker compose --env-file "${PROJECT_ROOT}/infra/docker/container-versions.env"
  -f "${PROJECT_ROOT}/e2e/docker-compose-e2e.yml" -p "${E2E_COMPOSE_PROJECT:-mvflix-e2e}")
LOG_DIR="${E2E_LOG_DIR:-${PROJECT_ROOT}/e2e/logs}"
mkdir -p "${LOG_DIR}"

cleanup() {
  status=$?
  "${COMPOSE[@]}" logs --no-color > "${LOG_DIR}/compose.log" 2>&1 || true
  "${MAKE[@]}" down-e2e-v
  exit "${status}"
}
trap cleanup EXIT

"${MAKE[@]}" up-e2e-d
export KAFKA_E2E_BOOTSTRAP="${KAFKA_E2E_BOOTSTRAP:-localhost:${KAFKA_E2E_PORT:-19092}}"
if [[ -n "${E2E_TEST_GROUP:-}" ]]; then
  "${PROJECT_ROOT}/mvnw" -f "${PROJECT_ROOT}/e2e/runner/pom.xml" \
    -Dgroups="${E2E_TEST_GROUP}" test
else
  "${PROJECT_ROOT}/mvnw" -f "${PROJECT_ROOT}/e2e/runner/pom.xml" test
fi
