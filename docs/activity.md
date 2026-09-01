# Activity

`mvflix-activity` contiene dos proyecciones de lectura dentro del mismo
microservicio:

- `watchhistory`: estado de visionado derivado de `PlaybackProgressed.v1`.
- `feed`: actividad durable de operaciones dirigidas a una audiencia.

Ninguna proyección llama a Movies, Storage o Media Ingestion, coordina sagas ni
expone endpoints de escritura. Cada proyección tiene su propia tabla y puede
reconstruirse desde sus eventos de integración.

## Watch History

Playback es el productor de `PlaybackProgressed.v1`. Esta proyección conserva la
última posición por usuario, película y media, y expone las consultas autenticadas
actuales en `/api/v1/activity/history`, `/api/v1/activity/continue-watching` y
`/api/v1/activity/movies/{movieId}`.

Watch history responde a la pregunta "que ha visto o esta viendo este usuario".
No debe reutilizarse como feed de operaciones: una posición de reproducción es un
estado mutable, no una entrada de actividad histórica.

## Durable Feed

El feed responde a «¿qué ocurrió en una operación que afecta a esta audiencia?».
Cada evento elegible se persiste de forma idempotente en el inbox y proyecta una
entrada durable. La entrega al feed es *at least once*; la deduplicación se hace
por `eventId` y la relectura no debe crear entradas duplicadas.

### Identity and Ownership

- `audienceId` identifica al usuario que puede ver la actividad. Es el único
  campo usado para autorizar lecturas del feed.
- `actorId` identifica quién o qué ejecutó la acción. Puede ser un usuario o
  `system`; sirve para auditoría y nunca concede acceso a la entrada.
- `correlationId` identifica la operación completa y se conserva en todos sus
  eventos. Si no existe una audiencia explícita, el evento no se proyecta en el
  feed personal.
- `eventId` identifica una ocurrencia concreta y es inmutable.

El consumidor nunca deriva `audienceId` desde `actorId`, el JWT del consumidor ni
un campo de presentación. Los eventos internos con `actorId: system` solo entran
en el feed cuando transportan explícitamente una audiencia válida.

### Grouping and Ordering

Los eventos con el mismo `audienceId` y `correlationId` pertenecen a una misma
actividad lógica. La entrada agrupada conserva el estado más avanzado conocido,
el primer `occurredAt`, el último `occurredAt` y los tipos de eventos observados.
Un evento tardío puede completar o enriquecer la entrada, pero no debe hacerla
retroceder.

La consulta ordena por un cursor estable compuesto por `(occurredAt, eventId)`.
El cursor es opaco para el cliente; no se basa en el orden de consumo de Kafka ni
en un offset de partición.

### Internal Envelope

Los productores publican un envelope de integración con esta información mínima:

```json
{
  "eventId": "uuid",
  "eventType": "MediaIngestionStarted",
  "eventVersion": 1,
  "occurredAt": "2026-01-01T12:00:00Z",
  "producer": "mvflix-media-ingestion",
  "actorId": "user-or-system",
  "audienceId": "user-or-null",
  "correlationId": "operation-uuid",
  "aggregate": {"type": "MediaIngestion", "id": "operation-uuid"},
  "payload": {}
}
```

`eventId`, `eventType`, `eventVersion`, `occurredAt`, `producer`, `actorId`,
`audienceId`, `correlationId` y `aggregate` forman el contrato de integración.
El payload puede evolucionar mediante una nueva versión del evento, no mediante
la reinterpretación silenciosa de una versión existente.

La primera familia proyectada será `MediaIngestionStarted`,
`MediaIngestionCompleted`, `MediaIngestionFailed` y `MediaIngestionCancelled`.
Catálogo, visibilidad, Storage y Playback se incorporarán solo cuando sus
productores publiquen eventos durables con identidad suficiente.

### BFF Contract

El BFF consultará la proyección con la identidad del usuario autenticado y no
aceptará `audienceId` desde query parameters o request body. El contrato público
de feed expondrá solo datos de presentación, por ejemplo `activityId`,
`correlationId`, `type`, `status`, `occurredAt` y los datos de operación
permitidos; no propagará el envelope completo ni información privada del actor.

La experiencia del feed del BFF usa `/web/activity`. El BFF reenvía la sesión
autenticada al endpoint interno `/api/v1/activity/feed`; `audienceId` no es un
parámetro controlable por el cliente. Los jobs transitorios se exponen de forma
independiente en `/web/jobs`.
