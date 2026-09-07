package com.gcorp.service.app.mvflix_playback.infrastructure.persistence;

import com.gcorp.service.app.mvflix_playback.application.port.PlaybackSessionRepository;
import com.gcorp.service.app.mvflix_playback.domain.AssetId;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackPosition;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSession;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionId;
import com.gcorp.service.app.mvflix_playback.domain.PlaybackSessionStatus;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import java.time.Instant;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class R2dbcPlaybackSessionRepository implements PlaybackSessionRepository {
  private final DatabaseClient database;

  public R2dbcPlaybackSessionRepository(DatabaseClient database) {
    this.database = database;
  }

  @Override
  public Mono<PlaybackSession> findById(PlaybackSessionId id) {
    return database.sql("SELECT * FROM playback_session WHERE id = :id")
        .bind("id", id.value())
        .map((row, metadata) -> restore(row))
        .one();
  }

  @Override
  public Mono<PlaybackSession> save(PlaybackSession session) {
    return database.sql("""
        INSERT INTO playback_session
          (id, viewer_id, catalog_item_id, asset_id, status, started_at, expires_at,
           last_sequence, last_position_seconds, last_duration_seconds, updated_at)
        VALUES (:id, :viewer, :catalog, :asset, :status, :started, :expires,
                :sequence, :position, :duration, NOW())
        ON CONFLICT (id) DO UPDATE SET
          status = EXCLUDED.status,
          last_sequence = EXCLUDED.last_sequence,
          last_position_seconds = EXCLUDED.last_position_seconds,
          last_duration_seconds = EXCLUDED.last_duration_seconds,
          updated_at = NOW()
        """)
        .bind("id", session.id().value())
        .bind("viewer", session.viewerId().value())
        .bind("catalog", session.catalogItemId().value())
        .bind("asset", session.assetId().value())
        .bind("status", session.status().name())
        .bind("started", session.startedAt())
        .bind("expires", session.expiresAt())
        .bind("sequence", session.lastSequence())
        .bind("position", session.lastPosition() == null ? null : session.lastPosition().seconds())
        .bind("duration", session.lastPosition() == null ? null : session.lastPosition().durationSeconds())
        .fetch().rowsUpdated()
        .thenReturn(session);
  }

  private static PlaybackSession restore(io.r2dbc.spi.Row row) {
    Long position = row.get("last_position_seconds", Long.class);
    Long duration = row.get("last_duration_seconds", Long.class);
    return PlaybackSession.restore(
        new PlaybackSessionId(row.get("id", java.util.UUID.class)),
        new ViewerId(row.get("viewer_id", String.class)),
        new CatalogItemId(row.get("catalog_item_id", Long.class)),
        new AssetId(row.get("asset_id", Long.class)),
        row.get("started_at", Instant.class), row.get("expires_at", Instant.class),
        PlaybackSessionStatus.valueOf(row.get("status", String.class)),
        row.get("last_sequence", Long.class),
        position == null ? null : new PlaybackPosition(position, duration));
  }
}
