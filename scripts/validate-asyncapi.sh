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

yq -o=json '.components.schemas' "$SPEC" > "$TMP_DIR/schemas.json"
jq '{"$schema":"http://json-schema.org/draft-07/schema#", "$ref":"#/components/schemas/StoredObjectDeletedEnvelope", "components":{"schemas":.}}' \
  "$TMP_DIR/schemas.json" > "$TMP_DIR/schema.json"
yq -o=json '.components.messages.StoredObjectDeleted.examples[0].payload' "$SPEC" \
  > "$TMP_DIR/stored-object-deleted.json"
yq -o=json '.components.messages.UploadCompleted.examples[0].payload' "$SPEC" \
  > "$TMP_DIR/upload-completed.json"

for message in MediaIngestionStarted MediaIngestionCompleted MediaIngestionFailed MediaIngestionCancelled; do
  filename="$(printf '%s' "$message" | tr '[:upper:]' '[:lower:]' | tr -d '-')"
  yq -o=json ".components.messages.${message}.examples[0].payload" "$SPEC" \
    > "$TMP_DIR/${filename}.json"
done

npx --yes ajv-cli@5.0.0 validate \
  --spec=draft7 \
  --strict=false \
  -s "$TMP_DIR/schema.json" \
  -d "$TMP_DIR/stored-object-deleted.json"

jq '."$ref" = "#/components/schemas/UploadCompletedEnvelope"' "$TMP_DIR/schema.json" \
  > "$TMP_DIR/upload-schema.json"
npx --yes ajv-cli@5.0.0 validate \
  --spec=draft7 \
  --strict=false \
  -s "$TMP_DIR/upload-schema.json" \
  -d "$TMP_DIR/upload-completed.json"

for message in MediaIngestionStarted MediaIngestionCompleted MediaIngestionFailed MediaIngestionCancelled; do
  filename="$(printf '%s' "$message" | tr '[:upper:]' '[:lower:]' | tr -d '-')"
  jq ' ."$ref" = "#/components/schemas/MediaIngestionEnvelope"' "$TMP_DIR/schema.json" \
    > "$TMP_DIR/media-schema.json"
  npx --yes ajv-cli@5.0.0 validate \
    --spec=draft7 \
    --strict=false \
    -s "$TMP_DIR/media-schema.json" \
    -d "$TMP_DIR/${filename}.json"
done

printf '%s\n' "AsyncAPI contract and integration event examples are valid."
