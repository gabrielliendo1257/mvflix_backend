package com.gcorp.service.app.mvflix_movies.catalog.domain.item;

import com.gcorp.service.app.mvflix_movies.catalog.domain.movie.EnrichmentStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;

import java.time.Instant;
import java.util.List;

public interface CatalogItemRepository {

    Mono<CatalogItem> save(CatalogItem movie);

    /**
     * Alta atómica del borrador identificado: INSERT de la película junto a su
     * acceso inicial (visibilidad + compartidos) como una sola unidad. La
     * decisión de negocio la tomó el agregado; acá solo se garantiza que no
     * exista un instante con película visible pero sin sus shares.
     */
    Mono<CatalogItem> saveDraftWithAccess(CatalogItem movie);

    Mono<CatalogItem> findById(CatalogItemId id);


    /**
     * Catalogo visible para el usuario (PUBLIC + propias + compartidas).
     * Traducción SQL de {@link CatalogItem#isVisibleTo(String)}: la regla de negocio
     * vive en el dominio; acá solo se filtra para no traer todo a memoria.
     */
    Flux<CatalogItem> findVisibleCatalogItems(String username, int limit);

    default Flux<CatalogItem> findPublicCatalogItems(int limit) {
        return Flux.empty();
    }

    Flux<CatalogItem> findByOwner(String ownerUsername, int limit);

    /**
     * Películas del dueño por lista de ids (para cambios en lote).
     * Las ajenas no aparecen: la política de autoría se resuelve por consulta.
     */
    Flux<CatalogItem> findByOwnerAndIds(String ownerUsername, List<CatalogItemId> ids);

    /**
     * Compare-and-set: pasa a READY solo si la película sigue en DRAFT.
     * Vacío si no hubo fila (no existe o ya no está en DRAFT).
     * La autoría la decide el use-case con {@link CatalogItem#isOwnedBy(String)}.
     * El media (object_id/object_key) lo persiste {@code MediaRepository} en el use-case.
     */
    Mono<CatalogItem> completeIfDraft(CatalogItemId id);

    /**
     * CAS READY → DELETING: inicia el borrado durable. Vacío si no estaba READY
     * (no existe, ya DELETING o en otro estado). No aplica a DRAFT (borrado
     * directo sin storage).
     */
    Mono<CatalogItem> markDeleting(CatalogItemId id);

    /** {@code true} si borró la fila. */
    Mono<Boolean> deleteById(CatalogItemId id);

    /**
     * Elimina SOLO si la película está DELETING; {@code true} si borró. Es el
     * guard del {@code finalizeDeletion}: una READY/DRAFT nunca se elimina por
     * este camino.
     */
    Mono<Boolean> deleteIfDeleting(CatalogItemId id);

    /** Elimina una película DELETING solo si el evento referencia su objeto MANAGED. */
    Mono<Boolean> deleteIfDeletingAndStorageId(CatalogItemId id, long storageId);

    /** Películas en borrado durable (para el scheduler de finalización), limitado. */
    Flux<CatalogItem> findDeleting(int limit);

    /** Selecciona borrados cuya ventana de recuperación ya expiró. */
    Flux<CatalogItem> findDeletingForRecovery(int limit, Duration retryCooldown);

    Mono<Void> markRecoveryAttempt(CatalogItemId id);

    /** Persiste la transición de proveedor ya decidida por el agregado. */
    Mono<CatalogItem> updateEnrichment(CatalogItem movie);

    /** Persiste conjuntamente metadata, clasificación y estado de enriquecimiento. */
    Mono<CatalogItem> updateDetails(CatalogItem movie);

    /** Persiste la visibilidad ya decidida por el agregado. */
    Mono<CatalogItem> updateVisibility(CatalogItem movie);

    /** Persiste la lista de compartidos ya decidida por el agregado. */
    Mono<CatalogItem> replaceShares(CatalogItem movie);

    /**
     * Persiste el ACCESO completo (visibilidad + compartidos) como UNA unidad
     * transaccional: nunca queda una película SHARED sin sus shares, ni
     * PRIVATE con residuos de compartidos anteriores.
     */
    Mono<CatalogItem> updateAccess(CatalogItem movie);

    /** Catalogo pendiente de enriquecer (para el scheduler), limitado y estable. */
    Flux<CatalogItem> findByEnrichmentStatus(EnrichmentStatus enrichmentStatus, int limit);

    /**
     * Purga películas DRAFT creadas antes del corte (metadata huérfana cuyo flujo de subida
     * nunca se completó ni se canceló). Devuelve la cantidad de filas borradas.
     */
    Mono<Long> deleteDraftsCreatedBefore(Instant cutoff);
}
