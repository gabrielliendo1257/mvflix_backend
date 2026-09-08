#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPEC="$ROOT_DIR/docs/asyncapi/mvflix-events.asyncapi.yaml"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

command -v yq >/dev/null || { printf '%s\n' "yq is required" >&2; exit 1; }
command -v jq >/dev/null || { printf '%s\n' "jq is required" >&2; exit 1; }
command -v npx >/dev/null || { printf '%s\n' "npx is required" >&2; exit 1; }

npx --yes @asyncapi/cli@6.0.2 validate "$SPEC"

 yq -o=json '.components' "$SPEC" > "$TMP_DIR/components.json"

mapfile -t messages < <(jq -r '.messages | keys[]' "$TMP_DIR/components.json")
for message in "${messages[@]}"; do
  example_count="$(jq --arg message "$message" '.messages[$message].examples // [] | length' \
    "$TMP_DIR/components.json")"
  if [[ "$example_count" -eq 0 ]]; then
    printf 'Message %s has no examples\n' "$message" >&2
    exit 1
  fi

  schema_ref="$(jq -r --arg message "$message" \
    '.messages[$message].payload["$ref"]' "$TMP_DIR/components.json")"
  schema_name="${schema_ref##*/}"
  filename="$(printf '%s' "$message" | tr '[:upper:]' '[:lower:]' | tr -d '-')"
  jq --arg schema "$schema_name" \
    '{"$schema":"http://json-schema.org/draft-07/schema#", "$ref":("#/components/schemas/" + $schema), components:{schemas:.schemas}}' \
    "$TMP_DIR/components.json" > "$TMP_DIR/schema.json"
  jq --arg message "$message" '.messages[$message].examples[0].payload' \
    "$TMP_DIR/components.json" > "$TMP_DIR/${filename}.json"

  npx --yes ajv-cli@5.0.0 validate \
    --spec=draft7 \
    --strict=false \
    -s "$TMP_DIR/schema.json" \
    -d "$TMP_DIR/${filename}.json"
done

printf '%s\n' "AsyncAPI contract and integration event examples are valid."
