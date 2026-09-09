#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OBSERVABILITY_DIR="$SCRIPT_DIR/observability"

: "${ACTUATOR_METRICS_USER:=metrics}"
: "${ACTUATOR_METRICS_PASSWORD:=change-me}"
export ACTUATOR_METRICS_USER ACTUATOR_METRICS_PASSWORD

mkdir -p "$OBSERVABILITY_DIR/generated"
envsubst '${ACTUATOR_METRICS_USER} ${ACTUATOR_METRICS_PASSWORD}' \
  < "$OBSERVABILITY_DIR/prometheus.yml.template" \
  > "$OBSERVABILITY_DIR/generated/prometheus.yml"
