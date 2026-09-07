# ADR 0003: Playback es una experiencia del BFF; los bytes los mueve la infraestructura

Estado: Reemplazado por ADR 0004

## Contexto

La intención de usuario es "quiero reproducir este contenido ahora". Antes
de esta decisión el flujo vivía disperso en `WebMoviesService`
(`detail()` + `playbackFor()` + `stream()`), `WebMovieStreamController` y
`StreamTicketService`, con tres problemas:

1. El BFF ramificaba sobre `"PRIVATE".equals(visibility)`: regla de dominio
   de movies filtrando en la orquestación.
2. El ticket de stream LOCAL embebía el JWT crudo del usuario en el query
   param (exposición en logs) y expiraba a los 300 s, menos que una película.
3. No existía contrato de experiencia: el front componía playback desde
   `detail()` con un flag silencioso.

## Decisión

Nace `experience/playback/` como vertical slice del BFF:

- `POST /web/playback/{mediaId}/session` compone la sesión: autoriza,
  resuelve el asset y entrega acceso al contenido.
- La autorización REAL sigue en mvflix-movies (`Movie.isVisibleTo`, 403 sin
  revelar existencia). El BFF no duplica la política.
- MANAGED: siempre presigned vía `POST /catalog/streaming` M2M
  (scope `storage.stream`) para todas las visibilidades; storage valida
  disponibilidad del objeto y MinIO sirve los bytes con Range nativo.
- LOCAL: mientras el navegador no alcance a storage (topología LAN), el BFF
  proxya los bytes con Range, autorizado por capability HMAC ligada a
  (media, asset, biblioteca, ruta) SIN credenciales dentro; las credenciales
  hacia storage se resuelven de la sesión OAuth2 viva en cada request.
  Queda documentado como deuda: cuando haya nginx con X-Accel-Redirect o el
  deployment exponga storage, el proxy se retira sin cambiar el contrato.
- `PlaybackSession` es stateless: `sessionId` es correlación, no estado.
- Errores semánticos: `MEDIA_NOT_FOUND` 404, `PLAYBACK_FORBIDDEN` 403,
  `MEDIA_NOT_READY`/`NO_PLAYABLE_ASSET` 409, `SOURCE_UNAVAILABLE` 503,
  `STREAM_ACCESS_INVALID` 401.

## Consecuencias

- El front nunca conoce bucket, objectKey, biblioteca ni ruta de disco.
- Los endpoints `/web/movies/{id}/stream-ticket|stream` quedan deprecated
  hasta que Angular migre.
- Watch history/resume queda reservado en el contrato (`resume: null`);
  su dueño se decidirá cuando exista (no se crea infraestructura hoy).
- HLS/transcode entra mañana como variante de fuente sin romper el contrato
  (`playback.strategy` ya forma parte de la respuesta).
