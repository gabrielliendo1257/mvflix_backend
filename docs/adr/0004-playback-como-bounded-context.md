# ADR 0004: Playback como bounded context propio

Estado: Aceptado

## Contexto

Playback dejó de ser solamente la composición de una URL en el BFF. La
reproducción necesita sesiones durables, reanudación, progreso idempotente,
expiración, finalización, renovación de fuentes y eventos de ciclo de vida.

El BFF no debe convertirse en propietario de estas reglas ni duplicar la
política de visibilidad de Movies o la infraestructura de bytes de Storage.

## Decisión

Se crea `mvflix-playback` como bounded context y microservicio propio.

- Movies sigue siendo dueño de ownership, visibilidad, estado del catálogo,
  asociación de assets y selección del asset reproducible. Expone una consulta
  autorizada atómica que devuelve un `PlayableCatalogItem`.
- Storage sigue siendo dueño de MinIO/S3, archivos locales, disponibilidad,
  validación del locator, Range requests y URLs presignadas.
- Playback es dueño de `PlaybackSession`, `WatchProgress`, sus transiciones,
  resume, expiración, renovación, concurrencia futura y eventos.
- Playback persiste sesiones y progreso en PostgreSQL y publica eventos mediante
  outbox. Activity solo proyecta esos eventos y nunca es fuente de verdad para
  resume.
- El BFF conserva el contrato orientado a Angular, la sesión OAuth2 y,
  temporalmente, el proxy de contenido LOCAL. No recibe ni expone bucket,
  objectKey o rutas físicas.

## Modelo inicial

`PlaybackSession` representa una reproducción concreta y usa los estados
`ACTIVE`, `COMPLETED`, `EXPIRED` y `FAILED`. `WatchProgress` se comparte entre
sesiones y dispositivos con clave `(viewerId, catalogItemId)`.

El progreso acepta únicamente secuencias mayores que la última persistida.
`CompletePlayback` es idempotente. No se añade `PAUSED`: es estado del
reproductor, no necesariamente del servidor.

## Consecuencias

- El workflow deja de estar concentrado en el BFF.
- Movies no se replica en Playback: Playback consume una decisión autorizada.
- Playback no lee buckets, rutas físicas ni mueve bytes.
- MANAGED continúa usando una URL temporal de Storage; LOCAL conserva por ahora
  la capability del BFF y su proxy.
- Los primeros eventos son `PlaybackStarted.v1`, `PlaybackProgressed.v1`,
  `PlaybackCompleted.v1` y `PlaybackFailed.v1`.
- `PlaybackProgressed` usa `viewerId`, `catalogItemId` y `assetId`; si el
  contrato anterior ya estuviera publicado externamente, se versionaría como
  `PlaybackProgressed.v2`.
