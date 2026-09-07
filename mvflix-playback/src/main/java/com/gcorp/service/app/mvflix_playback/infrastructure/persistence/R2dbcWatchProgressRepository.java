package com.gcorp.service.app.mvflix_playback.infrastructure.persistence;

import com.gcorp.service.app.mvflix_playback.application.port.WatchProgressRepository;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackPosition;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import com.gcorp.service.app.mvflix_playback.domain.WatchProgress;
import java.time.Instant;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class R2dbcWatchProgressRepository implements WatchProgressRepository {
  private final DatabaseClient database;

  public R2dbcWatchProgressRepository(DatabaseClient database) {
    this.database = database;
  }

  @Override
  public Mono<WatchProgress> find(ViewerId viewerId, CatalogItemId catalogItemId) {
    return database.sql("SELECT * FROM watch_progress WHERE viewer_id = :viewer AND catalog_item_id = :catalog")
        .bind("viewer", viewerId.value())
        .bind("catalog", catalogItemId.value())
        .map((row, metadata) -> WatchProgress.restore(viewerId, catalogItemId,
            row.get("position_seconds", Long.class) == null ? null
                : new PlaybackPosition(row.get("position_seconds", Long.class),
                    row.get("duration_seconds", Long.class)),
            row.get("completed", Boolean.class),
            row.get("last_session_id", java.util.UUID.class) == null ? null
                : new PlaybackSessionId(row.get("last_session_id", java.util.UUID.class)),
            row.get("updated_at", Instant.class), row.get("version", Long.class)))
        .one();
  }

  @Override
  public Mono<WatchProgress> save(WatchProgress progress) {
    var position = progress.position();
    var statement = database.sql("""
        INSERT INTO watch_progress
          (viewer_id, catalog_item_id, position_seconds, duration_seconds, completed,
           last_session_id, updated_at, version)
        VALUES (:viewer, :catalog, :position, :duration, :completed, :session, NOW(), :version)
        ON CONFLICT (viewer_id, catalog_item_id) DO UPDATE SET
          position_seconds = EXCLUDED.position_seconds,
          duration_seconds = EXCLUDED.duration_seconds,
           completed = EXCLUDED.completed,
           last_session_id = EXCLUDED.last_session_id,
           updated_at = EXCLUDED.updated_at,
           version = EXCLUDED.version
         WHERE watch_progress.version < EXCLUDED.version
         """)
        .bind("viewer", progress.viewerId().value())
        .bind("catalog", progress.catalogItemId().value())
        .bind("completed", progress.completed())
        .bind("version", progress.version());
    if (progress.lastSessionId() == null) {
      statement = statement.bindNull("session", java.util.UUID.class);
    } else {
      statement = statement.bind("session", progress.lastSessionId().value());
    }
    if (position == null) {
      statement = statement.bindNull("position", Long.class).bindNull("duration", Long.class);
    } else {
      statement = statement.bind("position", position.seconds())
          .bind("duration", position.durationSeconds());
    }
    return statement.fetch().rowsUpdated()
        .flatMap(rows -> rows == 1L
            ? Mono.just(progress)
            : Mono.error(new OptimisticLockingFailureException(
                "Watch progress was modified concurrently for viewer "
                    + progress.viewerId().value() + " and catalog item "
                    + progress.catalogItemId().value())));
  }
}
