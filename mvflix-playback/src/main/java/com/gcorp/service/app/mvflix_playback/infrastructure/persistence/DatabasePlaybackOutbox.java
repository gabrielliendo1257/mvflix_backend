package com.gcorp.service.app.mvflix_playback.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcorp.service.app.mvflix_playback.application.PlaybackOutbox;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class DatabasePlaybackOutbox implements PlaybackOutbox {
  private final DatabaseClient database;
  private final ObjectMapper mapper;

  public DatabasePlaybackOutbox(DatabaseClient database, ObjectMapper mapper) {
    this.database = database;
    this.mapper = mapper;
  }

  @Override
  public Mono<Void> append(String type, UUID aggregateId, Object payload,
      PlaybackOutbox.EventMetadata metadata) {
    return append(UUID.randomUUID(), type, aggregateId, payload, metadata, false);
  }

  @Override
  public Mono<Void> appendIdempotent(String type, UUID eventId, UUID aggregateId, Object payload,
      PlaybackOutbox.EventMetadata metadata) {
    return append(eventId, type, aggregateId, payload, metadata, true);
  }

  private Mono<Void> append(UUID eventId, String type, UUID aggregateId, Object payload,
      PlaybackOutbox.EventMetadata metadata, boolean idempotent) {
    try {
      var occurredAt = Instant.now();
      var envelope = new java.util.LinkedHashMap<String, Object>();
      envelope.put("eventId", eventId);
      envelope.put("eventType", type);
      envelope.put("eventVersion", 1);
      envelope.put("occurredAt", occurredAt);
      envelope.put("actorId", metadata.actorId());
      envelope.put("audienceId", metadata.audienceId());
      envelope.put("correlationId", metadata.correlationId());
      envelope.put("causationId", metadata.causationId());
      envelope.put("producer", "mvflix-playback");
      envelope.put("aggregate", Map.of("type", "PlaybackSession", "id", aggregateId));
      envelope.put("payload", payload);
      String sql = "INSERT INTO playback_outbox(event_id,event_type,aggregate_id,occurred_at,payload) VALUES(:id,:type,:aggregate,:occurred,CAST(:payload AS jsonb))"
          + (idempotent ? " ON CONFLICT (event_id) DO NOTHING" : "");
      return database.sql(sql)
          .bind("id", eventId).bind("type", type).bind("aggregate", aggregateId)
           .bind("occurred", occurredAt).bind("payload", mapper.writeValueAsString(envelope))
          .fetch().rowsUpdated().then();
    } catch (Exception error) {
      return Mono.error(error);
    }
  }

  @Override
  public Flux<Message> claim(int limit, int maxAttempts, Duration lease) {
    return database.sql("""
        WITH candidates AS (SELECT event_id FROM playback_outbox WHERE published_at IS NULL
          AND attempts < :maxAttempts AND next_attempt_at <= now()
          AND (claimed_until IS NULL OR claimed_until < now()) ORDER BY occurred_at LIMIT :limit
          FOR UPDATE SKIP LOCKED)
        UPDATE playback_outbox e SET attempts=e.attempts+1,
          claimed_until=now()+make_interval(secs => :lease) FROM candidates c WHERE e.event_id=c.event_id
          RETURNING e.event_id,e.event_type,e.aggregate_id,e.payload::text,e.occurred_at
        """).bind("limit", limit).bind("maxAttempts", maxAttempts)
        .bind("lease", Math.max(1, lease.toSeconds()))
        .map((row, metadata) -> new Message(row.get("event_id", UUID.class),
            row.get("event_type", String.class), row.get("aggregate_id", UUID.class),
            row.get("payload", String.class), row.get("occurred_at", Instant.class))).all();
  }

  public Mono<Void> markPublished(UUID id) {
    return database.sql("UPDATE playback_outbox SET published_at=now(),claimed_until=NULL WHERE event_id=:id")
        .bind("id", id).fetch().rowsUpdated().then();
  }

  public Mono<Void> markFailed(UUID id, String error, Duration delay) {
    return database.sql("UPDATE playback_outbox SET claimed_until=NULL,next_attempt_at=now()+make_interval(secs => :delay),last_error=:error WHERE event_id=:id")
        .bind("id", id).bind("delay", Math.max(1, delay.toSeconds()))
        .bind("error", error == null ? "unknown" : error.substring(0, Math.min(2000, error.length())))
        .fetch().rowsUpdated().then();
  }
}
