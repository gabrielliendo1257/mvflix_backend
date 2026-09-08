#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

command -v npx >/dev/null || { printf '%s\n' "npx is required" >&2; exit 1; }

for spec in "$ROOT_DIR"/docs/openapi/*.yaml; do
  npx --yes @apidevtools/swagger-cli@4.0.4 validate "$spec"
done

printf '%s\n' "OpenAPI contracts are valid."
