#!/usr/bin/env bash
# Levanta/derriba todo el ecosistema mvflix en dev (con seguridad real y dev-token).
# Orden: auth primero (el BFF hace issuer discovery al arrancar), luego el resto en
# paralelo, y el BFF al final.
#
#   ./scripts/stack-dev.sh start | stop | status
#
# Requiere: PostgreSQL y MinIO accesibles (locales o configurados en envs/.env)
# y, opcionalmente, TMDB_API_TOKEN para que el enriquecimiento funcione.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${PROJECT_ROOT}/envs/.env"

# Credenciales privadas de dev (TMDB, etc.) desde envs/.env (gitignored).
# Quedan en el entorno para que las apps las lean via ${VAR:default}.
if [ -f "${ENV_FILE}" ]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
else
  echo "[WARN] ${ENV_FILE} no existe; se usaran los defaults de dev"
fi

cd "${PROJECT_ROOT}"

if [ -x "${PROJECT_ROOT}/mvnw" ]; then
  MVN_CMD="${PROJECT_ROOT}/mvnw"
elif command -v mvn >/dev/null 2>&1; then
  MVN_CMD="$(command -v mvn)"
else
  echo "No se encontro Maven ni ${PROJECT_ROOT}/mvnw" >&2
  exit 1
fi

AUTH_PORT=9090
USERS_PORT=8080
STORAGE_PORT=6060
MOVIES_PORT=4040
MEDIA_INGESTION_PORT=7080
PLAYBACK_PORT=7071
BFF_PORT=9091
DB_TARGET_HOST="${DB_HOST:-127.0.0.1}"
DB_TARGET_PORT="${DB_PORT:-5432}"
MINIO_TARGET_URL="${MINIO_URL:-http://127.0.0.1:9000}"
KAFKA_TARGET_HOST="${KAFKA_HOST:-127.0.0.1}"
KAFKA_TARGET_PORT="${KAFKA_EXTERNAL_PORT:-9094}"
KAFKA_REQUIRED="${MVFLIX_MESSAGING_KAFKA_ENABLED:-false}"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/mvflix-dev"
LOG_DIR="${STATE_DIR}/logs"
PID_DIR="${STATE_DIR}/pids"
mkdir -p "${LOG_DIR}" "${PID_DIR}"

declare -A SERVICES=(
  [mvflix-authorization]=$AUTH_PORT
  [mvflix-users]=$USERS_PORT
  [mvflix-storage]=$STORAGE_PORT
  [mvflix-movies]=$MOVIES_PORT
  [mvflix-media-ingestion]=$MEDIA_INGESTION_PORT
  [mvflix-playback]=$PLAYBACK_PORT
  [bff-mvflix-web]=$BFF_PORT
)

wait_port() {
  local port=$1
  local name=$2
  for _ in $(seq 1 90); do
    if http_responding "${port}"; then
      return 0
    fi
    sleep 1
  done
  echo "  [WARN] ${name} no respondio en 90s (revisa ${LOG_DIR}/stack-${name}.log)"
}

http_responding() {
  local port=$1
  url_responding "http://127.0.0.1:${port}/"
}

url_responding() {
  local url=$1
  curl --silent --output /dev/null --connect-timeout 1 --max-time 2 \
    "${url}" 2>/dev/null
}

tcp_open() {
  local host=$1
  local port=$2
  if command -v timeout >/dev/null 2>&1; then
    timeout 2 bash -c 'exec 3<>"/dev/tcp/${1}/${2}"' _ "${host}" "${port}" 2>/dev/null
  else
    (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null
  fi
}

kafka_responding() {
  if ! tcp_open "${KAFKA_TARGET_HOST}" "${KAFKA_TARGET_PORT}"; then
    return 1
  fi
  if command -v docker >/dev/null 2>&1 && docker inspect kafka >/dev/null 2>&1; then
    docker exec kafka /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server 127.0.0.1:9092 --list >/dev/null 2>&1
  else
    return 0
  fi
}

wait_kafka() {
  for _ in $(seq 1 60); do
    if kafka_responding; then
      return 0
    fi
    sleep 1
  done
  echo "  [WARN] kafka no respondio en ${KAFKA_TARGET_HOST}:${KAFKA_TARGET_PORT}"
}

port_free() {
  ! tcp_open 127.0.0.1 "$1"
}

pid_file() {
  printf '%s/%s.pid\n' "${PID_DIR}" "$1"
}

managed_pid() {
  local name=$1
  local file
  local pid
  file="$(pid_file "${name}")"
  [ -f "${file}" ] || return 1
  read -r pid < "${file}" || return 1
  [[ "${pid}" =~ ^[0-9]+$ ]] || return 1
  kill -0 "${pid}" 2>/dev/null || return 1
  if command -v ps >/dev/null 2>&1; then
    local command_line
    command_line="$(ps -p "${pid}" -o args= 2>/dev/null || true)"
    [[ "${command_line}" == *"${name}"* ]] || return 1
  fi
  printf '%s\n' "${pid}"
}

start_one() {
  local name=$1
  local port=${SERVICES[$name]}
  local pid
  if pid="$(managed_pid "${name}")"; then
    echo "  [SKIP] ${name} ya fue iniciado por este script (pid ${pid})"
    return
  fi
  if ! port_free "$port"; then
    echo "  [SKIP] ${name} ya escucha en :${port} (instancia tuya? no la toco)"
    return
  fi
  echo "  [START] ${name} (dev) en :${port} ..."
  local extra_args=()
  if [ "${name}" = "mvflix-media-ingestion" ]; then
    extra_args+=("-Dspring-boot.run.jvmArguments=-DMEDIA_INGESTION_PORT_INTERNAL=${MEDIA_INGESTION_PORT} -DMVFLIX_INTERNAL_TOKEN_URI=http://127.0.0.1:${AUTH_PORT}/oauth2/token -DSECURITY_OAUTH2_JWK_SET_URI=http://127.0.0.1:${AUTH_PORT}/oauth2/jwks")
  elif [ "${name}" = "mvflix-playback" ]; then
    extra_args+=("-Dspring-boot.run.jvmArguments=-DPLAYBACK_PORT_INTERNAL=${PLAYBACK_PORT} -DSERVICES_MOVIES_URL=http://127.0.0.1:${MOVIES_PORT} -DSERVICES_STORAGE_URL=http://127.0.0.1:${STORAGE_PORT} -DSERVICES_AUTHORIZATION_URL=http://127.0.0.1:${AUTH_PORT} -DSECURITY_OAUTH2_JWK_SET_URI=http://127.0.0.1:${AUTH_PORT}/oauth2/jwks -DKAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:9094}")
  elif [ "${name}" = "bff-mvflix-web" ]; then
    extra_args+=("-Dspring-boot.run.jvmArguments=-DMEDIA_INGESTION_URL=http://127.0.0.1:${MEDIA_INGESTION_PORT} -DMEDIA_INGESTION_ENABLED=true -DPLAYBACK_URL=http://127.0.0.1:${PLAYBACK_PORT}")
  fi
  TMDB_API_TOKEN="${TMDB_API_TOKEN:-}" nohup "${MVN_CMD}" -q -pl "${name}" spring-boot:run \
    "${extra_args[@]}" \
    -Dspring-boot.run.profiles=dev > "${LOG_DIR}/stack-${name}.log" 2>&1 &
  pid=$!
  printf '%s\n' "${pid}" > "$(pid_file "${name}")"
}

terminate_tree() {
  local pid=$1
  local child
  if command -v pgrep >/dev/null 2>&1; then
    while read -r child; do
      [ -n "${child}" ] && terminate_tree "${child}"
    done < <(pgrep -P "${pid}" 2>/dev/null || true)
  fi
  kill "${pid}" 2>/dev/null || true
}

stop_one() {
  local name=$1
  local port=${SERVICES[$name]}
  local pid
  local file
  file="$(pid_file "${name}")"
  if pid="$(managed_pid "${name}")"; then
    terminate_tree "${pid}"
    rm -f "${file}"
    echo "  [STOP] ${name} (pid raiz ${pid})"
    return
  fi
  rm -f "${file}"
  if port_free "${port}"; then
    echo "  [STOP] ${name}: :${port} ya estaba libre"
  else
    echo "  [SKIP] ${name}: :${port} pertenece a una instancia no gestionada"
  fi
}

start() {
  if ! tcp_open "${DB_TARGET_HOST}" "${DB_TARGET_PORT}"; then
    echo "postgres no responde en ${DB_TARGET_HOST}:${DB_TARGET_PORT} -> revisa envs/.env"
  fi
  if ! url_responding "${MINIO_TARGET_URL}"; then
    echo "minio no responde en ${MINIO_TARGET_URL} -> revisa envs/.env"
  fi
  if kafka_responding; then
    echo "kafka responde en ${KAFKA_TARGET_HOST}:${KAFKA_TARGET_PORT}"
  elif [ "${KAFKA_REQUIRED}" = "true" ]; then
    echo "kafka es obligatorio; esperando disponibilidad en ${KAFKA_TARGET_HOST}:${KAFKA_TARGET_PORT}"
    wait_kafka
  else
    echo "kafka no responde en ${KAFKA_TARGET_HOST}:${KAFKA_TARGET_PORT} (mensajeria deshabilitada)"
  fi

  echo "== Compilando e instalando modulos compartidos =="
  "${MVN_CMD}" -q -pl mvflix-devseed -am install -DskipTests

  echo "== Arrancando auth (obligatorio antes del BFF) =="
  start_one mvflix-authorization
  wait_port "$AUTH_PORT" mvflix-authorization

  echo "== Arrancando users, storage, movies, media-ingestion y playback en paralelo =="
  start_one mvflix-users
  start_one mvflix-storage
  start_one mvflix-movies
  start_one mvflix-media-ingestion
  start_one mvflix-playback
  wait_port "$USERS_PORT" mvflix-users
  wait_port "$STORAGE_PORT" mvflix-storage
  wait_port "$MOVIES_PORT" mvflix-movies
  wait_port "$MEDIA_INGESTION_PORT" mvflix-media-ingestion
  wait_port "$PLAYBACK_PORT" mvflix-playback

  echo "== Arrancando BFF (requiere auth arriba) =="
  start_one bff-mvflix-web
  wait_port "$BFF_PORT" bff-mvflix-web

  echo ""
  echo "Stack dev arriba:"
  echo "  auth    http://127.0.0.1:${AUTH_PORT}   (POST /oauth2/dev-token)"
  echo "  users   http://127.0.0.1:${USERS_PORT}"
  echo "  storage http://127.0.0.1:${STORAGE_PORT}"
  echo "  movies  http://127.0.0.1:${MOVIES_PORT}"
  echo "  media-ingestion http://127.0.0.1:${MEDIA_INGESTION_PORT}"
  echo "  playback http://127.0.0.1:${PLAYBACK_PORT}"
  echo "  bff     http://127.0.0.1:${BFF_PORT}"
  echo "Dev token: curl -s -X POST http://127.0.0.1:${AUTH_PORT}/oauth2/dev-token"
  echo "           -H 'Content-Type: application/json' -d '{\"username\":\"Javier\",\"password\":\"JavierPassword\"}'"
}

stop() {
  echo "== Deteniendo stack dev =="
  stop_one bff-mvflix-web
  stop_one mvflix-media-ingestion
  stop_one mvflix-playback
  stop_one mvflix-users
  stop_one mvflix-storage
  stop_one mvflix-movies
  stop_one mvflix-authorization
}

status() {
  echo "== Estado del stack dev =="
  if tcp_open "${DB_TARGET_HOST}" "${DB_TARGET_PORT}"; then
    echo "  postgres: UP (${DB_TARGET_HOST}:${DB_TARGET_PORT})"
  else
    echo "  postgres: DOWN (${DB_TARGET_HOST}:${DB_TARGET_PORT})"
  fi
  if url_responding "${MINIO_TARGET_URL}"; then
    echo "  minio: UP (${MINIO_TARGET_URL})"
  else
    echo "  minio: DOWN (${MINIO_TARGET_URL})"
  fi
  if kafka_responding; then
    echo "  kafka: UP (${KAFKA_TARGET_HOST}:${KAFKA_TARGET_PORT})"
  else
    echo "  kafka: DOWN (${KAFKA_TARGET_HOST}:${KAFKA_TARGET_PORT})"
  fi
  for name in mvflix-authorization mvflix-users mvflix-storage mvflix-movies mvflix-media-ingestion mvflix-playback bff-mvflix-web; do
    local port=${SERVICES[$name]}
    local pid
    if http_responding "${port}"; then
      if pid="$(managed_pid "${name}")"; then
        echo "  ${name}: UP (:${port}, pid ${pid})"
      else
        echo "  ${name}: UP (:${port}, proceso externo)"
      fi
    elif pid="$(managed_pid "${name}")"; then
      echo "  ${name}: STARTING/UNHEALTHY (:${port}, pid ${pid})"
    else
      echo "  ${name}: DOWN (:${port})"
    fi
  done
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  *) echo "uso: $0 [start|stop|status]" && exit 1 ;;
esac
