# Service and Endpoint Catalog

Puertos usados por `scripts/stack-dev.sh start`. Los puertos de management
exponen `GET /actuator/health/readiness` y se usan para decidir si un servicio
esta listo, no solo si su puerto HTTP acepta conexiones.

| Servicio | Puerto dev | Management | Endpoints principales |
| --- | ---: | ---: | --- |
| Authorization | 9090 | 10090 | `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/login`, `/logout` |
| Users | 8080 | 10080 | `/api/v1/users`, `/api/v1/users/me`, `/api/v1/users/{username}/plan`, `/api/v1/users/quota` |
| Storage | 6060 | 10060 | `/api/v1/movie/storage/upload`, `/api/v1/movie/storage/streaming`, `/api/v1/movie/storage/quota`, `/api/v1/movie/storage/{storageId}` |
| Movies | 4040 | 10040 | `/api/v1/movies`, `/api/v1/movies/{id}`, `/api/v1/movies/{id}/playback-context`, `/api/v1/movies/{id}/visibility`, `/api/v1/media-assets/{id}/identify` |
| Media Ingestion | 7080 | 10081 | `/api/v1/ingestions`, `/api/v1/ingestions/{id}`, `/api/v1/ingestions/{id}/complete`, `/api/v1/ingestions/{id}/cancel` |
| Playback | 7071 | 10071 | `/api/v1/playback/sessions/{catalogItemId}`, `/api/v1/playback/sessions/{sessionId}/progress` |
| Activity | 7070 | 10070 | `/api/v1/activity/history`, `/api/v1/activity/continue-watching`, `/api/v1/activity/movies/{movieId}`, `/api/v1/activity/feed` |
| BFF | 9091 | 10091 | `/web/session`, `/web/movies`, `/web/movies/{movieId}`, `/web/movies/{movieId}/stream`, `/web/playback/{mediaId}/session`, `/web/add-media`, `/web/activity`, `/web/jobs`, `/web/logout` |

## Local Checks

```bash
./scripts/stack-dev.sh status
curl -fsS http://127.0.0.1:10070/actuator/health/readiness
curl -fsS http://127.0.0.1:10091/actuator/health/readiness
```

Los contratos detallados de request/response estan en `docs/openapi/`. Los
endpoints internos de Actuator no forman parte del contrato de negocio.
