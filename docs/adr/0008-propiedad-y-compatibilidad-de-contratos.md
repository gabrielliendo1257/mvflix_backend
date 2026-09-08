# ADR 0008: Propiedad y compatibilidad de contratos

- **Estado:** Aceptado
- **Fecha:** 2026-09-08

## Contexto

MVFlix ya tiene contratos de eventos en
`docs/asyncapi/mvflix-events.asyncapi.yaml` y contratos OpenAPI para algunos
servicios. Los servicios intercambian eventos versionados, pero la propiedad y
las reglas de evolucion no estaban documentadas de forma explicita.

Crear ahora JARs `*-api`, un directorio `contracts/` o varias plataformas de
contract testing anadiria complejidad sin resolver el riesgo principal: la
deriva entre productor y consumidor.

## Decision

Los contratos son propiedad del servicio productor. La propiedad es logica y no
obliga a mover fisicamente los archivos actuales de `docs/`.

| Contrato | Productor |
| --- | --- |
| `CatalogItemAdded` | `mvflix-movies` |
| `CatalogItemDeleted` | `mvflix-movies` |
| `CatalogItemAccessChanged` | `mvflix-movies` |
| `ManagedMediaDeletionRequested` | `mvflix-movies` |
| `StoredObjectDeleted` | `mvflix-storage` |
| `UploadCompleted` | `mvflix-storage` |
| `UploadFailed` | `mvflix-storage` |
| `MediaIngestionStarted` | `mvflix-media-ingestion` |
| `MediaIngestionCompleted` | `mvflix-media-ingestion` |
| `MediaIngestionFailed` | `mvflix-media-ingestion` |
| `MediaIngestionCancelled` | `mvflix-media-ingestion` |
| `PlaybackProgressed` | `mvflix-playback` |

Los consumidores pueden validar y adaptar sus propios parsers, pero no
modifican el contrato del productor.

## Compatibilidad

Un cambio es compatible si conserva el nombre versionado del canal, mantiene
los campos requeridos y permite que los consumidores existentes sigan
deserializando el mensaje.

Cambios compatibles:

- anadir campos opcionales con valores por defecto;
- ampliar enumeraciones cuando los consumidores toleren valores desconocidos;
- anadir nuevos canales o mensajes.

Cambios incompatibles requieren una nueva version del canal y del mensaje:

- eliminar o renombrar campos;
- cambiar el tipo o significado de un campo;
- convertir un campo opcional en requerido;
- cambiar envelope, particionamiento, semantica de entrega o clave de
  idempotencia de forma incompatible.

Todo evento mantiene su envelope versionado, `eventId`, correlacion y
causalidad. La entrega Kafka continua siendo at-least-once y los consumidores
deben conservar efectos idempotentes.

## Consecuencias

- `docs/asyncapi/` y `docs/openapi/` siguen siendo la fuente contractual del
  monorepo.
- No se comparte dominio ni payload Java mediante dependencias entre servicios.
- La validacion de ejemplos debe cubrir todos los mensajes declarados, no solo
  una seleccion historica.
- Contract testing se incorporara de forma incremental, empezando por
  validacion de esquemas y parsers reales.
- Spring Cloud Contract, Pact, Schema Registry y generacion de clientes quedan
  para una decision posterior basada en una necesidad concreta.
