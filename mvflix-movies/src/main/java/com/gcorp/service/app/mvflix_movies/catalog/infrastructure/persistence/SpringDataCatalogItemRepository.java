package com.gcorp.service.app.mvflix_movies.catalog.infrastructure.persistence;

import com.gcorp.service.app.mvflix_movies.catalog.domain.movie.EnrichmentStatus;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.PlayableCatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.AuthorizedPlaybackCatalog;
import com.gcorp.service.app.mvflix_movies.catalog.domain.access.Visibility;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.IdentifiedDraftIdempotencyStore;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.Duration;
import java.util.List;

@Repository
public class SpringDataCatalogItemRepository implements CatalogItemRepository, IdentifiedDraftIdempotencyStore,
        AuthorizedPlaybackCatalog {

    private static final String MEDIA_OBJECT_ID =
            """
            (SELECT mm.object_id FROM media mm
             WHERE mm.catalog_item_id = m.id ORDER BY mm.id LIMIT 1) AS object_id
            """;

    private static final String SHARED_WITH =
            """
            COALESCE((SELECT array_agg(ms.shared_with ORDER BY ms.shared_with)
                      FROM movie_shares ms WHERE ms.catalog_item_id = m.id), ARRAY[]::varchar[]) AS shared_with
            """;

    /**
     * Traducción SQL de {@code CatalogItem.isVisibleTo(username)}: la política de acceso
     * se decide en el dominio; acá solo se filtra en el origen para no traer todo
     * el catálogo a memoria.
     */
    private static final String VISIBLE_WHERE =
            """
            WHERE m.visibility = 'PUBLIC'
               OR m.owner_username = :username
               OR (m.visibility = 'SHARED' AND EXISTS (
                   SELECT 1 FROM movie_shares ms
                    WHERE ms.catalog_item_id = m.id AND ms.shared_with = :username))
            """;

    private static final String SELECT_MOVIE_COLUMNS =
            """
            SELECT m.id, m.owner_username, m.title, m.status, m.enrichment_status,
                   m.metadata::text, m.visibility, m.kind,
            """ + MEDIA_OBJECT_ID + ", " + SHARED_WITH;

    private final DatabaseClient databaseClient;
    private final CatalogItemRowMapper rowMapper;
    private final JsonCodec jsonCodec;

    public SpringDataCatalogItemRepository(
            DatabaseClient databaseClient, CatalogItemRowMapper rowMapper, JsonCodec jsonCodec) {
        this.databaseClient = databaseClient;
        this.rowMapper = rowMapper;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Mono<CatalogItem> save(CatalogItem movie) {
        CatalogItemRow row = this.rowMapper.toRow(movie);
        return this.databaseClient
                        .sql(
                                """
                                 INSERT INTO catalog_items (owner_username, title, status, enrichment_status, metadata, visibility, kind)
                                VALUES (:owner_username, :title, :status, :enrichment_status, CAST(:metadata AS jsonb), :visibility, :kind)
                                RETURNING id, owner_username, title, status, enrichment_status, metadata::text, visibility, kind
                                """)
                        .bind("owner_username", row.ownerUsername())
                        .bind("title", row.title())
                        .bind("status", row.status())
                        .bind("enrichment_status", row.enrichmentStatus())
                        .bind("metadata", row.metadata())
                        .bind("visibility", row.visibility())
                        .bind("kind", row.kind())
                .map(this::toRow)
                .one()
                .map(this.rowMapper::toDomain);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional("connectionFactoryTransactionManager")
    public Mono<CatalogItem> saveDraftWithAccess(CatalogItem movie) {
        // save() asigna el id generado (INSERT RETURNING); replaceShares
        // necesita ese id para insertar en movie_shares. Se propaga el
        // agregado guardado conservando los shares previstos del original.
        return this.save(movie)
            .flatMap(saved -> this.replaceShares(
                new CatalogItem(
                    saved.getId(),
                    saved.getOwnerId(),
                    saved.getTitle(),
                    saved.getStatus(),
                    saved.getEnrichmentStatus(),
                    saved.getMetadata(),
                    saved.getVisibility(),
                    movie.getSharing(),
                    saved.getKind())));
    }

    @Override
    public Mono<IdentifiedDraftIdempotencyStore.Claim> claim(
            String actorId, String operation, String key, String requestHash) {
        return this.databaseClient.sql("""
                INSERT INTO movie_idempotency_keys
                    (actor_id, operation, idempotency_key, request_hash)
                VALUES (:actor_id, :operation, :idempotency_key, :request_hash)
                ON CONFLICT (actor_id, operation, idempotency_key) DO NOTHING
                """)
            .bind("actor_id", actorId)
            .bind("operation", operation)
            .bind("idempotency_key", key)
            .bind("request_hash", requestHash)
            .fetch().rowsUpdated()
            .then(this.databaseClient.sql("""
                SELECT actor_id, operation, idempotency_key, request_hash, movie_id
                FROM movie_idempotency_keys
                WHERE actor_id = :actor_id AND operation = :operation
                  AND idempotency_key = :idempotency_key
                FOR UPDATE
                """)
                .bind("actor_id", actorId)
                .bind("operation", operation)
                .bind("idempotency_key", key)
                .map((row, metadata) -> new IdentifiedDraftIdempotencyStore.Claim(
                    row.get("actor_id", String.class), row.get("operation", String.class),
                    row.get("idempotency_key", String.class), row.get("request_hash", String.class),
                    row.get("movie_id", Long.class) == null ? null
                        : CatalogItemId.of(row.get("movie_id", Long.class))))
                .one());
    }

    @Override
    public Mono<Void> bind(String actorId, String operation, String key,
                                         CatalogItemId movieId) {
        return this.databaseClient.sql("""
                UPDATE movie_idempotency_keys SET movie_id = :movie_id
                WHERE actor_id = :actor_id AND operation = :operation
                  AND idempotency_key = :idempotency_key
                """)
            .bind("movie_id", movieId.value())
            .bind("actor_id", actorId)
            .bind("operation", operation)
            .bind("idempotency_key", key)
            .fetch().rowsUpdated().then();
    }

    @Override
    public Mono<CatalogItem> findById(CatalogItemId id) {
        return this.databaseClient
                .sql(
                        SELECT_MOVIE_COLUMNS
                        + """
                         FROM catalog_items m
                        WHERE m.id = :id
                        """)
                .bind("id", id.value())
                .map(this::toRow)
                .one()
                .map(this.rowMapper::toDomain);
    }

    @Override
    public Mono<PlayableCatalogItem> findAuthorizedPlaybackContext(CatalogItemId id, String viewer) {
        return this.databaseClient.sql("""
                SELECT m.id, m.title, managed.object_id,
                       a.id AS asset_id, a.library_id, a.relative_path, a.size, a.mime_type
                FROM catalog_items m
                LEFT JOIN LATERAL (
                    SELECT mm.object_id
                    FROM media mm
                    WHERE mm.catalog_item_id = m.id
                    ORDER BY mm.id
                    LIMIT 1
                ) managed ON TRUE
                LEFT JOIN LATERAL (
                    SELECT ma.id, ma.library_id, ma.relative_path, ma.size, ma.mime_type
                    FROM media_assets ma
                    WHERE ma.catalog_item_id = m.id
                      AND ma.status = 'IDENTIFIED'
                      AND ma.present = TRUE
                    ORDER BY ma.id
                    LIMIT 1
                ) a ON TRUE
                WHERE m.id = :id
                  AND m.status = 'READY'
                  AND (m.visibility = 'PUBLIC' OR m.owner_username = :viewer
                       OR (m.visibility = 'SHARED' AND EXISTS (
                           SELECT 1 FROM movie_shares ms
                           WHERE ms.catalog_item_id = m.id AND ms.shared_with = :viewer)))
                  AND ((managed.object_id IS NOT NULL AND a.id IS NULL)
                       OR (managed.object_id IS NULL AND a.id IS NOT NULL))
                """)
            .bind("id", id.value())
            .bind("viewer", viewer)
            .map((row, metadata) -> new PlayableCatalogItem(
                CatalogItemId.of(row.get("id", Long.class)),
                row.get("title", String.class),
                null,
                null,
                row.get("object_id", Long.class),
                row.get("asset_id", Long.class) == null ? null
                    : new PlayableCatalogItem.PlayableAsset(
                        row.get("asset_id", Long.class), row.get("library_id", Long.class),
                        row.get("relative_path", String.class), row.get("size", Long.class),
                        row.get("mime_type", String.class))))
            .one();
    }

    @Override
    public Flux<CatalogItem> findVisibleCatalogItems(String username, int limit) {
        return this.databaseClient
                .sql(
                        SELECT_MOVIE_COLUMNS
                        + """
                         FROM catalog_items m
                        """
                        + VISIBLE_WHERE
                        + """
                        ORDER BY m.id DESC
                        LIMIT :limit
                        """)
                .bind("username", username)
                .bind("limit", limit)
                .map(this::toRow)
                .all()
                .map(this.rowMapper::toDomain);
    }

    @Override
    public Flux<CatalogItem> findPublicCatalogItems(int limit) {
        return this.databaseClient
                .sql(SELECT_MOVIE_COLUMNS + """
                         FROM catalog_items m
                         WHERE m.visibility = 'PUBLIC'
                           AND m.status = 'READY'
                         ORDER BY m.id DESC
                         LIMIT :limit
                         """)
                .bind("limit", limit)
                .map(this::toRow)
                .all()
                .map(this.rowMapper::toDomain);
    }

    @Override
    public Flux<CatalogItem> findByOwner(String ownerUsername, int limit) {
        return this.databaseClient
                .sql(
                        SELECT_MOVIE_COLUMNS
                        + """
                         FROM catalog_items m
                        WHERE m.owner_username = :owner_username
                        ORDER BY m.id DESC
                        LIMIT :limit
                        """)
                .bind("owner_username", ownerUsername)
                .bind("limit", limit)
                .map(this::toRow)
                .all()
                .map(this.rowMapper::toDomain);
    }

    @Override
    public Mono<CatalogItem> completeIfDraft(CatalogItemId id) {
        return this.databaseClient
                .sql(
                        """
                         UPDATE catalog_items
                        SET status = 'READY', updated_at = NOW()
                        WHERE id = :id AND status = 'DRAFT'
                        RETURNING id, owner_username, title, status, enrichment_status,
                                  metadata::text, visibility, kind,
                                  (SELECT array_agg(ms.shared_with ORDER BY ms.shared_with)
                                    FROM movie_shares ms WHERE ms.catalog_item_id = catalog_items.id) AS shared_with
                        """)
                .bind("id", id.value())
                .map(this::toRow)
                .one()
                .map(this.rowMapper::toDomain);
    }

    @Override
    public Mono<Boolean> deleteById(CatalogItemId id) {
        return this.databaseClient
                .sql(
                        """
                         DELETE FROM catalog_items
                        WHERE id = :id
                        """)
                .bind("id", id.value())
                .fetch()
                .rowsUpdated()
                .map(rows -> rows > 0);
    }

    @Override
    public Mono<CatalogItem> markDeleting(CatalogItemId id) {
        return this.databaseClient
                .sql(
                        """
                         UPDATE catalog_items
                        SET status = 'DELETING', updated_at = NOW()
                        WHERE id = :id AND status = 'READY'
                        RETURNING id, owner_username, title, status, enrichment_status,
                                  metadata::text, visibility, kind,
                                  (SELECT mm.object_id FROM media mm
                                   WHERE mm.catalog_item_id = catalog_items.id ORDER BY mm.id LIMIT 1) AS object_id,
                                  (SELECT array_agg(ms.shared_with ORDER BY ms.shared_with)
                                    FROM movie_shares ms WHERE ms.catalog_item_id = catalog_items.id) AS shared_with
                        """)
                .bind("id", id.value())
                .map(this::toRow)
                .one()
                .map(this.rowMapper::toDomain);
    }

    @Override
    public Mono<Boolean> deleteIfDeleting(CatalogItemId id) {
        return this.databaseClient
                .sql(
                        """
                         DELETE FROM catalog_items
                        WHERE id = :id AND status = 'DELETING'
                        """)
                .bind("id", id.value())
                .fetch()
                .rowsUpdated()
                .map(rows -> rows > 0);
    }

    @Override
    public Mono<Boolean> deleteIfDeletingAndStorageId(CatalogItemId id, long storageId) {
        return this.databaseClient
                .sql("""
                         DELETE FROM catalog_items m
                        WHERE m.id = :id
                          AND m.status = 'DELETING'
                          AND EXISTS (
                              SELECT 1 FROM media
                               WHERE media.catalog_item_id = m.id AND media.object_id = :storageId)
                        """)
                .bind("id", id.value())
                .bind("storageId", storageId)
                .fetch()
                .rowsUpdated()
                .map(rows -> rows > 0);
    }

    @Override
    public Flux<CatalogItem> findDeleting(int limit) {
        return this.databaseClient
                .sql(
                        SELECT_MOVIE_COLUMNS
                        + """
                         FROM catalog_items m
                        WHERE m.status = 'DELETING'
                        ORDER BY m.id
                        LIMIT :limit
                        """)
                .bind("limit", limit)
                .map(this::toRow)
                .all()
                .map(this.rowMapper::toDomain);
    }

    @Override
    public Flux<CatalogItem> findDeletingForRecovery(int limit, Duration retryCooldown) {
        return this.databaseClient
                .sql(SELECT_MOVIE_COLUMNS
                        + """
                         FROM catalog_items m
                        WHERE m.status = 'DELETING'
                          AND (m.last_recovery_attempt_at IS NULL
                               OR m.last_recovery_attempt_at <= NOW() - make_interval(secs => :cooldown_seconds))
                        ORDER BY m.last_recovery_attempt_at NULLS FIRST, m.id
                        LIMIT :limit
                        """)
                .bind("cooldown_seconds", Math.max(1L, retryCooldown.toSeconds()))
                .bind("limit", limit)
                .map(this::toRow)
                .all()
                .map(this.rowMapper::toDomain);
    }

    @Override
    public Mono<Void> markRecoveryAttempt(CatalogItemId id) {
        return this.databaseClient
                 .sql("UPDATE catalog_items SET last_recovery_attempt_at = NOW() "
                        + "WHERE id = :id AND status = 'DELETING'")
                .bind("id", id.value())
                .fetch()
                .rowsUpdated()
                .then();
    }

    @Override
    public Mono<Long> deleteDraftsCreatedBefore(Instant cutoff) {
        return this.databaseClient
                .sql(
                        """
                         DELETE FROM catalog_items
                        WHERE status = 'DRAFT' AND created_at < :cutoff
                        """)
                .bind("cutoff", cutoff)
                .fetch()
                .rowsUpdated()
                .map(Long::valueOf);
    }

    @Override
    public Mono<CatalogItem> updateEnrichment(CatalogItem movie) {
        return this.updateDetailsState(movie, false);
    }

    @Override
    public Mono<CatalogItem> updateDetails(CatalogItem movie) {
        return this.updateDetailsState(movie, true);
    }

    private Mono<CatalogItem> updateDetailsState(CatalogItem movie, boolean updateKind) {
        String kindAssignment = updateKind ? ", kind = :kind" : "";
        DatabaseClient.GenericExecuteSpec statement = this.databaseClient
                .sql(
                        """
                         UPDATE catalog_items
                        SET title = :title, metadata = CAST(:metadata AS jsonb),
                            enrichment_status = :enrichment_status
                        """
                        + kindAssignment
                        + """
                            , updated_at = NOW()
                        WHERE id = :id
                        RETURNING id, owner_username, title, status, enrichment_status,
                                  metadata::text, visibility, kind,
                                  (SELECT mm.object_id FROM media mm
                                   WHERE mm.catalog_item_id = catalog_items.id ORDER BY mm.id LIMIT 1) AS object_id,
                                  (SELECT array_agg(ms.shared_with ORDER BY ms.shared_with)
                                    FROM movie_shares ms WHERE ms.catalog_item_id = catalog_items.id) AS shared_with
                        """)
                .bind("metadata", this.jsonCodec.encode(movie.getMetadata()))
                .bind("title", movie.getMetadata().title())
                .bind("enrichment_status", movie.getEnrichmentStatus().name())
                .bind("id", movie.getId().value());
        if (updateKind) {
            statement = statement.bind("kind", movie.getKind().name());
        }
        return statement
                .map(this::toRow)
                .one()
                .map(this.rowMapper::toDomain);
    }

    @Override
    public Flux<CatalogItem> findByOwnerAndIds(String ownerUsername, List<CatalogItemId> ids) {
        if (ids == null || ids.isEmpty()) {
            return Flux.empty();
        }
        List<Long> values = ids.stream().map(CatalogItemId::value).toList();
        String in = java.util.stream.IntStream.range(0, values.size())
                .mapToObj(i -> ":id" + i)
                .collect(java.util.stream.Collectors.joining(", "));
        DatabaseClient.GenericExecuteSpec spec = this.databaseClient
                .sql(
                        SELECT_MOVIE_COLUMNS
                        + """
                         FROM catalog_items m
                        WHERE m.owner_username = :owner_username
                          AND m.id IN ("""
                        + in
                        + """
                        )
                        ORDER BY m.id
                        """)
                .bind("owner_username", ownerUsername);
        for (int i = 0; i < values.size(); i++) {
            spec = spec.bind("id" + i, values.get(i));
        }
        return spec
                .map(this::toRow)
                .all()
                .map(this.rowMapper::toDomain);
    }

    @Override
    public Mono<CatalogItem> updateVisibility(CatalogItem movie) {
        return this.databaseClient
                .sql(
                        """
                         UPDATE catalog_items
                        SET visibility = :visibility, updated_at = NOW()
                        WHERE id = :id
                        RETURNING id, owner_username, title, status, enrichment_status,
                                  metadata::text, visibility, kind,
                                  (SELECT mm.object_id FROM media mm
                                   WHERE mm.catalog_item_id = catalog_items.id ORDER BY mm.id LIMIT 1) AS object_id,
                                  (SELECT array_agg(ms.shared_with ORDER BY ms.shared_with)
                                    FROM movie_shares ms WHERE ms.catalog_item_id = catalog_items.id) AS shared_with
                        """)
                .bind("visibility", movie.getVisibility().name())
                .bind("id", movie.getId().value())
                .map(this::toRow)
                .one()
                .map(this.rowMapper::toDomain);
    }

    /**
     * Acceso completo en UNA transacción: visibilidad y compartidos se
     * escriben juntos o no se escriben. Espejo de {@code saveDraftWithAccess}
     * para el camino de actualización.
     */
    @Override
    @org.springframework.transaction.annotation.Transactional("connectionFactoryTransactionManager")
    public Mono<CatalogItem> updateAccess(CatalogItem movie) {
        // Do not map the intermediate SHARED row: its shares are inserted next.
        return this.databaseClient
                .sql(
                        """
                         UPDATE catalog_items
                        SET visibility = :visibility, updated_at = NOW()
                        WHERE id = :id
                        """)
                .bind("visibility", movie.getVisibility().name())
                .bind("id", movie.getId().value())
                .fetch()
                .rowsUpdated()
                .then(this.replaceShares(movie));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional("connectionFactoryTransactionManager")
    public Mono<CatalogItem> replaceShares(CatalogItem movie) {
        return this.databaseClient
                .sql(
                        """
                        DELETE FROM movie_shares
                         WHERE catalog_item_id = :id
                        """)
                .bind("id", movie.getId().value())
                .fetch()
                .rowsUpdated()
                .flatMapMany(deleted ->
                        Flux.fromIterable(movie.getSharedWith())
                            .distinct()
                            .flatMap(username -> this.databaseClient
                                    .sql(
                                            """
                                             INSERT INTO movie_shares (catalog_item_id, shared_with)
                                            SELECT :id, :username
                                            WHERE EXISTS (
                                                 SELECT 1 FROM catalog_items m
                                                WHERE m.id = :id)
                                            """)
                                    .bind("id", movie.getId().value())
                                    .bind("username", username)
                                    .fetch()
                                    .rowsUpdated()))
                .then(this.databaseClient
                        .sql(
                                """
                                 UPDATE catalog_items
                                SET updated_at = NOW()
                                WHERE id = :id
                                RETURNING id, owner_username, title, status, enrichment_status,
                                          metadata::text, visibility, kind,
                                          (SELECT mm.object_id FROM media mm
                                            WHERE mm.catalog_item_id = catalog_items.id ORDER BY mm.id LIMIT 1) AS object_id,
                                          (SELECT array_agg(ms.shared_with ORDER BY ms.shared_with)
                                            FROM movie_shares ms WHERE ms.catalog_item_id = catalog_items.id) AS shared_with
                                """)
                        .bind("id", movie.getId().value())
                        .map(this::toRow)
                        .one()
                        .map(this.rowMapper::toDomain));
    }

    @Override
    public Flux<CatalogItem> findByEnrichmentStatus(EnrichmentStatus enrichmentStatus, int limit) {
        return this.databaseClient
                .sql(
                        SELECT_MOVIE_COLUMNS
                        + """
                         FROM catalog_items m
                        WHERE m.enrichment_status = :enrichment_status
                        ORDER BY m.id
                        LIMIT :limit
                        """)
                .bind("enrichment_status", enrichmentStatus.name())
                .bind("limit", limit)
                .map(this::toRow)
                .all()
                .map(this.rowMapper::toDomain);
    }

    private CatalogItemRow toRow(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
        return new CatalogItemRow(
            row.get("id", Long.class),
            row.get("owner_username", String.class),
            row.get("title", String.class),
            row.get("status", String.class),
            row.get("enrichment_status", String.class),
            row.get("metadata", String.class),
            row.get("visibility", String.class),
            metadata.contains("shared_with") ? row.get("shared_with", String[].class) : null,
            row.get("kind", String.class));
    }
}
