#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${PROJECT_ROOT}/envs/.env"

if [ -f "${ENV_FILE}" ]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

docker compose \
  --env-file "${PROJECT_ROOT}/infra/docker/container-versions.env" \
  --env-file "${ENV_FILE}" \
  -f "${PROJECT_ROOT}/infra/docker/docker-compose-dev.yml" \
  -p mvflix-app exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-admin}" -d postgres \
  -f /docker-entrypoint-initdb.d/03-repair-permissions.sql
