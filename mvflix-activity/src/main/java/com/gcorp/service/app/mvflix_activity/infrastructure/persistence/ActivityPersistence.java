package com.gcorp.service.app.mvflix_activity.infrastructure.persistence;

import com.gcorp.service.app.mvflix_activity.application.ActivityQueryService.ActivityRecord;
import com.gcorp.service.app.mvflix_activity.application.port.WatchActivityRepository;
import com.gcorp.service.app.mvflix_activity.domain.PlaybackProgressed;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedInbox;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedRepository;
import com.gcorp.service.app.mvflix_activity.feed.domain.ActivityMutation;
import com.gcorp.service.app.mvflix_activity.feed.domain.ActivityEntry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class ActivityPersistence implements WatchActivityRepository, ActivityFeedRepository {
  private final DatabaseClient db;
  private final ObjectMapper mapper;

  public ActivityPersistence(DatabaseClient db, ObjectMapper mapper) {
    this.db = db;
    this.mapper = mapper;
  }

  public Mono<Void> upsert(PlaybackProgressed e) {
    var q = """
        INSERT INTO watch_activity(owner_username,movie_id,media_id,position_seconds,duration_seconds,completed,sequence,last_watched_at,created_at,updated_at)
        VALUES(:owner,:movie,:media,:position,:duration,:completed,:sequence,NOW(),NOW(),NOW())
        ON CONFLICT(owner_username,movie_id,media_id_key) DO UPDATE SET position_seconds=EXCLUDED.position_seconds,duration_seconds=EXCLUDED.duration_seconds,completed=EXCLUDED.completed,sequence=EXCLUDED.sequence,last_watched_at=EXCLUDED.last_watched_at,updated_at=NOW()
        WHERE watch_activity.sequence < EXCLUDED.sequence""";
    var s = db.sql(q).bind("owner", e.ownerUsername()).bind("movie", e.movieId())
        .bind("position", e.positionSeconds()).bind("completed", e.completed())
        .bind("sequence", e.sequence());
    s = e.mediaId() == null ? s.bindNull("media", Long.class) : s.bind("media", e.mediaId());
    s = e.durationSeconds() == null ? s.bindNull("duration", Long.class)
        : s.bind("duration", e.durationSeconds());
    return s.fetch().rowsUpdated().then();
  }

  public Mono<Void> project(ActivityMutation e) {
    var q = """
        INSERT INTO activity_feed(activity_id,audience_id,actor_id,correlation_id,activity_type,status,started_at,last_occurred_at,last_event_id,last_event_type,file_name,catalog_item_id,failure_code,activity_key,category,severity,resource_type,resource_id,resource_title,details,received_at)
        VALUES(:activity,:audience,:actor,:correlation,:type,:status,:started,:occurred,:event,:eventType,:fileName,:catalog,:failure,:activityKey,:category,:severity,:resourceType,:resourceId,:resourceTitle,CAST(:context AS jsonb),NOW())
        ON CONFLICT(audience_id,activity_key) DO UPDATE SET
          actor_id=EXCLUDED.actor_id,
          started_at=LEAST(activity_feed.started_at,EXCLUDED.started_at),
          last_occurred_at=GREATEST(activity_feed.last_occurred_at,EXCLUDED.last_occurred_at),
          last_event_id=CASE WHEN (EXCLUDED.last_occurred_at,EXCLUDED.last_event_id) > (activity_feed.last_occurred_at,activity_feed.last_event_id) THEN EXCLUDED.last_event_id ELSE activity_feed.last_event_id END,
          last_event_type=CASE WHEN (EXCLUDED.last_occurred_at,EXCLUDED.last_event_id) > (activity_feed.last_occurred_at,activity_feed.last_event_id) THEN EXCLUDED.last_event_type ELSE activity_feed.last_event_type END,
          status=CASE WHEN CASE EXCLUDED.status WHEN 'COMPLETED' THEN 3 WHEN 'FAILED' THEN 2 WHEN 'CANCELLED' THEN 2 WHEN 'STARTED' THEN 1 ELSE 0 END > CASE activity_feed.status WHEN 'COMPLETED' THEN 3 WHEN 'FAILED' THEN 2 WHEN 'CANCELLED' THEN 2 WHEN 'STARTED' THEN 1 ELSE 0 END THEN EXCLUDED.status ELSE activity_feed.status END,
          file_name=COALESCE(EXCLUDED.file_name,activity_feed.file_name),
          catalog_item_id=COALESCE(EXCLUDED.catalog_item_id,activity_feed.catalog_item_id),
          failure_code=COALESCE(EXCLUDED.failure_code,activity_feed.failure_code),
          details=COALESCE(EXCLUDED.details,activity_feed.details)
        """;
    var s = db.sql(q).bind("activity", e.activityId()).bind("audience", e.audienceId())
        .bind("actor", e.actorId()).bind("correlation", e.correlationId())
        .bind("type", e.type()).bind("status", e.status()).bind("started", e.startedAt())
        .bind("occurred", e.occurredAt())
         .bind("event", e.eventId()).bind("eventType", e.eventType())
         .bind("activityKey", e.activityKey()).bind("category", e.category())
         .bind("severity", e.severity()).bind("resourceType", e.resourceType())
         .bind("resourceId", e.resourceId());
    s = bind(s, "resourceTitle", e.resourceTitle(), String.class)
        .bind("context", context(e.context()));
    s = bind(s, "fileName", e.fileName(), String.class);
    s = bind(s, "catalog", e.catalogItemId(), Long.class);
    s = bind(s, "failure", e.failureCode(), String.class);
    return s.fetch().rowsUpdated().then();
  }

  public Flux<ActivityEntry> feed(String audience, String cursor, int limit) {
    int safeLimit = limit <= 0 ? 20 : Math.min(limit, 101);
    var sql = new StringBuilder("SELECT * FROM activity_feed WHERE audience_id=:audience");
    Cursor before = Cursor.parse(cursor);
    if (before != null) sql.append(" AND (last_occurred_at,last_event_id) < (:beforeAt,:beforeId)");
    sql.append(" ORDER BY last_occurred_at DESC,last_event_id DESC LIMIT :limit");
    var s = db.sql(sql.toString()).bind("audience", audience).bind("limit", safeLimit);
    if (before != null) s = s.bind("beforeAt", before.occurredAt()).bind("beforeId", before.eventId());
    return s.map((r, m) -> {
      Instant occurred = r.get("last_occurred_at", Instant.class);
      UUID eventId = r.get("last_event_id", UUID.class);
      return new ActivityEntry(r.get("activity_id", UUID.class), r.get("correlation_id", UUID.class),
          r.get("activity_type", String.class), r.get("status", String.class),
          r.get("started_at", Instant.class), occurred, r.get("file_name", String.class),
          r.get("catalog_item_id", Long.class), r.get("failure_code", String.class),
          Cursor.of(occurred, eventId), r.get("category", String.class),
          r.get("severity", String.class), r.get("resource_type", String.class),
           r.get("resource_id", String.class), r.get("resource_title", String.class),
           json(r.get("details", String.class)));
    }).all();
  }

  private String context(java.util.Map<String, Object> value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception error) {
      throw new IllegalArgumentException("Could not serialize activity context", error);
    }
  }

  private Map<String, Object> json(String value) {
    try {
      return value == null ? null : mapper.readValue(value, Map.class);
    } catch (Exception error) {
      throw new IllegalStateException("Invalid activity context", error);
    }
  }

  private static <T> DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec,
      String name, T value, Class<T> type) {
    return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
  }

  private record Cursor(Instant occurredAt, UUID eventId) {
    static String of(Instant occurredAt, UUID eventId) {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          (occurredAt + "|" + eventId).getBytes(StandardCharsets.UTF_8));
    }

    static Cursor parse(String value) {
      if (value == null || value.isBlank()) return null;
      try {
        String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
            .split("\\|", 2);
        return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
      } catch (RuntimeException error) {
        throw new IllegalArgumentException("Invalid activity feed cursor", error);
      }
    }
  }

  private Flux<ActivityRecord> query(String sql, String owner, Long movie, Integer limit) {
    DatabaseClient.GenericExecuteSpec s = db.sql(sql).bind("owner", owner);
    if (movie != null) s = s.bind("movie", movie);
    if (limit != null) s = s.bind("limit", limit);
    return s.map((r, m) -> new ActivityRecord(r.get("movie_id", Long.class),
        r.get("media_id", Long.class), r.get("position_seconds", Long.class),
        r.get("duration_seconds", Long.class), r.get("completed", Boolean.class),
        r.get("sequence", Long.class), r.get("last_watched_at", Instant.class))).all();
  }

  public Flux<ActivityRecord> history(String owner, int limit) {
    return query("SELECT * FROM watch_activity WHERE owner_username=:owner ORDER BY last_watched_at DESC LIMIT :limit", owner, null, limit);
  }

  public Flux<ActivityRecord> continueWatching(String owner, int limit) {
    return query("SELECT * FROM watch_activity WHERE owner_username=:owner AND completed=false ORDER BY last_watched_at DESC LIMIT :limit", owner, null, limit);
  }

  public Mono<ActivityRecord> movie(String owner, long movie) {
    return query("SELECT * FROM watch_activity WHERE owner_username=:owner AND movie_id=:movie ORDER BY last_watched_at DESC", owner, movie, null).next();
  }
}
