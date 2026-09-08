# mvflix

Plataforma de streaming/video por microservicios (Java 21, WebFlux). Modelo hexagonal en cada servicio, comunicacion reactiva y seguridad por JWT (OAuth2 Authorization Server).

## Modulos

| Modulo | Puerto | Rol |
|---|---|---|
| `mvflix-authorization` | 9090 | Authorization Server OAuth2/OIDC: emite JWT, expone `/oauth2/jwks` |
| `mvflix-users` | 8080 | Identidad, plan y politica de cuota de los usuarios |
| `mvflix-storage` | 6060 | Uploads, streaming, uso real del almacenamiento (MinIO) |
| `bff-mvflix-web` | 9091 | Punto de entrada web (login OAuth2, sesion httpOnly) |

## Decisiones de dominio

- `users` es la **politica**: identidad, plan y cuota derivada del plan.
- `storage` es la **fuente de verdad del uso**: reserva/libera bytes de forma atomica.
- Ver `docs/adr/0001-*`.

## Arquitectura

```
auth (9090) --JWT--> users (8080) <--quota contract--> storage (6060/8080 API dev) --> MinIO
```

- `users` valida el JWT del `authorization` (jwks) y expone `POST /api/v1/users/quota` que el storage consume con `client_credentials` (scope `users.write`).
- `storage` se integra con users via WebClient + OAuth2 (sin Feign).
- Flyway corre con usuario `db_migrator` (JDBC, solo en arranque); el negocio es 100% R2DBC con `db_rw`.

## Requisitos

- Docker (Postgres + MinIO)
- Java 21+, Maven (wrapper incluido)

## Puesta en marcha (entorno dev)

Todas las aplicaciones leen su configuracion del archivo de perfil
(`application-dev.y(a)ml` en los 4 servicios; `mvflix-storage` ademas tiene
`application-sandbox.yml`), por lo que **siempre hay que activar el perfil `dev`**
al arrancarlas con Maven.

### 1. Infraestructura (postgres + MinIO)

```bash
make up-dev
```

Si PostgreSQL ya tenía el volumen creado antes de corregir el bootstrap, aplica
los permisos retroactivos sin borrar datos:

```bash
make repair-postgres-permissions
```

El comando ejecuta `infra/postgres/03-repair-permissions.sql` dentro del
contenedor existente. La reparación es idempotente.

Levanta postgres (crea `mvflix_users_db`, `mvflix_uploads_db`, `mvflix_authorized_db`, ...)
y MinIO con el bucket raiz y el webhook hacia el storage en `:6060`.
Requiere `infra/docker/.env` (gitignored; hay una copia con valores dev). Para
clientes Java ejecutándose fuera de Docker, como Termux, configura
`KAFKA_ADVERTISED_HOST` en `infra/docker/.env` y `KAFKA_BOOTSTRAP_SERVERS` en
`envs/.env`, ambos con la IP LAN del host.

### 2. Aplicaciones (una terminal por servicio, en este orden)

```bash
# Authorization Server (IdP OAuth2/OIDC)  -> http://localhost:9090
./mvnw -pl mvflix-authorization spring-boot:run -Dspring-boot.run.profiles=dev

# users (identidad + cuota)               -> http://localhost:8080
./mvnw -pl mvflix-users spring-boot:run -Dspring-boot.run.profiles=dev

# storage (uploads + streaming)           -> http://localhost:6060
./mvnw -pl mvflix-storage  spring-boot:run -Dspring-boot.run.profiles=dev

# BFF web (login OAuth2 en el navegador)  -> http://localhost:9091
./mvnw -pl bff-mvflix-web spring-boot:run -Dspring-boot.run.profiles=dev
```

Equivalent with env var: `SPRING_PROFILES_ACTIVE=dev ./mvnw -pl mvflix-storage spring-boot:run`.

### 3. Probar el flujo login completo (BFF + OAuth2)

1. `GET http://localhost:9091/web/session` -> devuelve `{"authenticated":false}` (o 401 JSON si no hay sesion).
2. Abrir `http://localhost:9091/web/home` en el navegador: redirige al IdP (`localhost:9090/login`),
   autentica con un usuario real de `customers`, vuelve al BFF y queda la cookie de sesion.
3. El cliente OAuth2 `movie-bff` debe existir en `mvflix_authorized_db` con scopes
   `openid,profile,users.read,users.write` y PKCE obligatorio (seed automatico en el arranque,
   en una DB ya existente hay que crearlo/responder a mano).

### Perfiles disponibles

| Perfil | Se usa para | Requiere |
|---|---|---|
| `dev` | Flujo completo: todos los servicios + auth + MinIO + Users | postgres + minio (`make up-dev`), idP en 9090 |
| `sandbox` | Solo `mvflix-storage` aislado (sin authorization ni users) | postgres + minio; `make sandbox-run` |

### Tests

```bash
make sandbox-test                      # smoke test del storage (perfil sandbox)
./mvnw test                            # suite completa por modulo
./mvnw test -pl mvflix-users           # integracion con Testcontainers
```

### Variables de entorno utiles (todas con default dev)

`AUTHORIZATION_ISSUER_URL` (9090), `SERVICES_USERS_URL` (8080), `SERVICES_STORAGE_URL` (6060),
`MINIO_URL` (9000), `BFF_ADDRESS` (http://127.0.0.1:9091, redirect del cliente OAuth2).

### Ejecutar las aplicaciones desde Termux

En Termux nativo, prepara Java 21 y las herramientas del proyecto con:

```bash
chmod +x scripts/setup-termux.sh
./scripts/setup-termux.sh
```

Luego configura `envs/.env` y usa `./scripts/stack-dev.sh start|status|stop`.
El estado no depende de `ss`: Android restringe la relacion entre sockets y
procesos, por lo que el script utiliza probes HTTP/TCP y guarda los PID de los
procesos que el mismo inicia.

PostgreSQL y MinIO deben ejecutarse en un host accesible (o en un entorno Linux
con Docker); Docker Compose no esta soportado directamente por Termux nativo.

### Levantar en LAN (192.168.x.x, desde otro equipo/telefono)

Todo el stack corre en una maquina; el navegador (en otra maquina) solo ve **front (4200)**,
**BFF (9091)** y **auth (9090)**. Los servicios internos (users/storage/movies) se quedan en
`127.0.0.1` porque son llamadas servidor-a-servidor.

1. **Front**: apuntar el `environment.ts` de Angular al BFF por LAN: `http://<IP-LAN>:9091`.

2. **Variables de entorno**: poner las variables de las aplicaciones en `envs/.env`.
   `scripts/stack-dev.sh` hace `source envs/.env` (con `set -a`) antes de arrancar,
   asi que Movies y Storage las leen solas. Para Kafka, el ejemplo minimo es:

   ```env
   KAFKA_BOOTSTRAP_SERVERS=192.168.1.104:9094
   MOVIES_KAFKA_GROUP=mvflix-movies
   STORAGE_KAFKA_GROUP=mvflix-storage
   ```

   `KAFKA_BOOTSTRAP_SERVERS` debe apuntar al listener externo definido en
   `infra/docker/.env`; `MOVIES_KAFKA_GROUP` y `STORAGE_KAFKA_GROUP` deben ser
   distintos para que cada servicio mantenga sus offsets de forma independiente.
   Las variables del broker (`KAFKA_ADVERTISED_HOST`, `KAFKA_EXTERNAL_PORT`,
   `KAFKA_KRAFT_CLUSTER_ID`, retencion y volumen) pertenecen a
   `infra/docker/.env` y no a `envs/.env`. Si se arranca una app a mano con
   `mvn spring-boot:run`, hay que exportar las variables de `envs/.env` antes en
   esa misma terminal:

   ```bash
   export AUTHORIZATION_ISSUER_URL="http://<IP-LAN>:9090"   # BFF: redirige el login al auth por LAN + auth: issuer fijo de los JWT
   export BFF_ADDRESS="http://<IP-LAN>:9091"                # auth: redirect-uri registrado del cliente OAuth2
   export FRONTEND_URL="http://<IP-LAN>:4200"               # BFF: CORS + post-login redirect
   export FRONTEND_ADDRESS="http://<IP-LAN>:4200"           # auth: redirect de logout
   export BFF_CORS_ALLOWED_ORIGINS="http://<IP-LAN>:4200"   # BFF: origen CORS extra (ademas de FRONTEND_URL)
   export BFF_SESSION_COOKIE_NAME="SESSION"                 # LAN por HTTP: __Host- exige Secure
   export BFF_SESSION_COOKIE_SECURE="false"                 # Secure solo se acepta en localhost
   export SECURITY_OAUTH2_ISSUER_URI="http://<IP-LAN>:9090" # users: los JWT emitidos por LAN llevan iss LAN
   ```

   Sin los dos `BFF_SESSION_COOKIE_*`, el navegador descarta la cookie de sesion (`Secure` +
   prefijo `__Host-` solo valen en localhost) y el login queda en un loop de redirecciones.

3. **Subir los 3 servicios visibles** (auth, bff) + el resto (users, storage, movies) con el
   perfil `dev`, como en la seccion de puesta en marcha. Al primer login, el navegador avisara
   de un certificado/aviso no si se usa HTTP plano: es esperado, no hay TLS en dev.

4. Verificar: en la otra maquina `curl http://<IP-LAN>:9091/web/session` -> 401 JSON (sin
   sesion), y abrir `http://<IP-LAN>:4200` en el navegador: login completo por LAN.

Alternativa sin tocar la cookie (mas segura): servir front y BFF por **HTTPS** con cert
autofirmado de la IP LAN; entonces `__Host-SESSION` y `Secure` funcionan tal cual.

## API

### BFF web (`bff-mvflix-web`, puerto 9091)

Unico punto de entrada del navegador. Usa el patron `oauth2-client`: el navegador solo ve la
cookie de sesion (httpOnly) y **nunca ve tokens JWT**. El BFF valida la sesion y orquesta
`users` (8080) y `storage` (6060) server-to-server.

| Metodo | Ruta | Auth | Descripcion |
|---|---|---|---|
| `GET` | `/web/session` | publica | Estado de sesion: `{"authenticated":false}` o `{"authenticated":true,"subject":"<sub>"}` |
| `GET` | `/web/home` | sesion | Home: `{profile, quota, recentUploads}` (mezcla users + storage) |
| `GET` | `/web/uploads?limit=20` | sesion | Lista de uploads del usuario (proxy a storage) |
| `POST` | `/web/uploads` | sesion | Crea sesion de upload. Body: `{"filename","file_size","mime_type"}` → `uploadId`, `uploadUrl` (PUT presigned directo a MinIO), `method`, `status`, `object{expectedSize,expectedMime}` |
| `GET` | `/web/uploads/{uploadId}` | sesion | Estado del objeto: PENDING / COMPLETED / EXPIRED / DELETED |
| `POST` | `/web/uploads/{uploadId}/cancel` | sesion | Cancela y libera la reserva de cuota |
| `POST` | `/web/uploads/{uploadId}/complete` | sesion | Confirma el fin del upload (fast path); devuelve el status HTTP del storage (200 si quedo COMPLETED) |
| `POST` | `/web/uploads/streaming` | sesion | Sesion de streaming: body `{"objectId":"<id>"}` → `{uploadId, streamingUrl (GET presigned directo a MinIO), storageKey, expiresAt, method}`; la URL firmada soporta `Range` (206), clave para el seek del reproductor |
| `POST` | `/web/playback/{mediaId}/session` | sesion | **Experiencia Playback**: autoriza (movies aplica visibilidad), resuelve asset y compone la sesión → `{sessionId, media{id,title,posterPath,duration}, playback{strategy,url,mimeType,expiresAt}, resume}`. MANAGED: URL presigned directa a MinIO; LOCAL: capability para `GET /web/playback/assets/{assetId}/stream?token=` |
| `GET` | `/web/playback/assets/{assetId}/stream` | capability | Bytes LOCAL con Range (206); auth por token HMAC sin credenciales dentro (el `<video>` cross-origin no manda cookie). Los endpoints `/web/movies/{id}/stream-ticket` y `/web/movies/{id}/stream` quedan deprecated hasta que el front migre |

**Seguridad:** `/web/session`, `/login/**`, `/oauth2/**` y `/error` son publicas; `/web/**`
requiere sesion. Para el navegador (Accept: text/html) sin sesion se redirige al authorize del
IdP; para el resto (curl/Postman) se responde `401 {"error":"unauthorized"}` para que el front
arranque el login.

**Flujo de login (OAuth2 authorization code + PKCE):**
1. Front llama `GET /web/session` → `{"authenticated":false}` → redirige a `/oauth2/authorization/movie-app`.
2. El BFF (cliente `movie-bff`, PKCE obligatorio) redirige al IdP `:9090/login`; el usuario se
   autentica en `customers`.
3. Callback: `{baseUrl}/login/oauth2/movietv` → BFF pide token con PKCE, guarda cookie de
   sesion httpOnly y redirige a `/web/home`. Scopes del cliente: `users.read, users.write`.

**Flujo de subida (por que MinIO es la fuente de verdad):**
```
POST /web/uploads                → storage: reserva cuota + URL presigned PUT (SIMPLE)
PUT  directo a MinIO             → los bytes nunca pasan por BFF ni storage
POST /web/uploads/{id}/complete  → storage: verifica tamano en MinIO → COMPLETED (fast path, UX)
webhook s3:ObjectCreated:Put     → storage /internal/minio/events: reconcile (camino de verdad,
                                   idempotente; no depende de que el cliente confirme)
scheduler expireStaleSessions    → PENDING viejos → EXPIRED + libera cuota (red de seguridad)
```
`COMPLETED` habilita el streaming: `POST /web/uploads/streaming` (proxy al storage) devuelve
una URL presigned GET directa a MinIO. El evento de dominio
`UploadCompletedEvent` solo se publica en la primera transicion real (sin duplicados si
coinciden el fast path y el webhook).

**Orquestacion interna (server-to-server):**
- storage: `GET /api/v1/movie/storage/quota`, `GET /api/v1/movie/storage/uploads?limit=`, `POST /api/v1/movie/storage/upload`, `GET|POST /api/v1/movie/storage/upload/{id}` (+ `/cancel`, `/complete`), `POST /api/v1/movie/storage/streaming`.
- users: `GET /api/v1/users/me` (perfil del usuario).
- WebClient + OAuth2 (`client_credentials`) hacia users para el contrato de cuota.

### Servicios internos (specs OpenAPI)

- `docs/openapi/users.openapi.yaml` — endpoints de `mvflix-users` (:8080)
- `docs/openapi/storage.openapi.yaml` — endpoints de `mvflix-storage` (:6060)
- `docs/openapi/authorization.openapi.yaml` — Authorization Server (:9090)

### Endpoints internos (no expuestos al navegador)

| Metodo | Ruta | Auth | Descripcion |
|---|---|---|---|
| `POST` | `storage:/internal/minio/events` | `X-Minio-Token` (tiempo constante) | Webhook de bucket events: procesa `s3:ObjectCreated:*` y completa el objeto por `objectKey`. Config en compose (`make up-dev`): target webhook `upload` + `mc event add` (`put, complete-multipart-upload`) |
| `POST` | `movies:/api/v1/movies/{id}/discard-draft` | OAuth2 `SCOPE_media-ingestion` + `X-Actor-Id` | Compensación idempotente: elimina solo un draft perteneciente al actor |

## Docs

- `docs/adr/` — historial de decisiones (ADR 0001: cuota = politica en users, uso = storage)
