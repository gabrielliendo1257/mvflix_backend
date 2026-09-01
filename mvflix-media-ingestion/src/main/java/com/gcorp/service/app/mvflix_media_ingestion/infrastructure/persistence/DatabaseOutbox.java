package com.gcorp.service.app.mvflix_media_ingestion.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcorp.service.app.mvflix_media_ingestion.application.Outbox;
import com.gcorp.service.app.mvflix_media_ingestion.domain.MediaIngestion;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;
import java.time.*;
import java.util.UUID;

@Repository
public class DatabaseOutbox implements Outbox {
  private final DatabaseClient db;
  private final ObjectMapper mapper;

  public DatabaseOutbox(DatabaseClient db, ObjectMapper mapper) {
    this.db = db;
    this.mapper = mapper;
  }

  private Mono<Void> add(MediaIngestion i, String type) {
    try {
      var eventId = UUID.randomUUID();
      var occurred = i.updatedAt();
      var envelope = new java.util.LinkedHashMap<String, Object>();
      envelope.put("eventId", eventId);
      envelope.put("eventType", type);
      envelope.put("eventVersion", 1);
      envelope.put("occurredAt", occurred);
      envelope.put("actorId", i.actorId());
      envelope.put("audienceId", i.actorId());
      envelope.put("correlationId", i.ingestionId());
      if (i.causationId() != null) envelope.put("causationId", i.causationId());
      envelope.put("producer", "mvflix-media-ingestion");
      envelope.put("aggregate", Map.of("type", "MediaIngestion", "id", i.ingestionId()));
      envelope.put("payload", eventPayload(i));
      var serializedPayload = mapper.writeValueAsString(envelope);
      return db.sql(
              "INSERT INTO media_ingestion_outbox(event_id,event_type,event_version,aggregate_id,occurred_at,payload) VALUES(:e,:t,1,:a,:o,CAST(:p AS jsonb)) ON CONFLICT(event_id) DO NOTHING")
          .bind("e", eventId)
          .bind("t", type)
          .bind("a", i.ingestionId())
          .bind("o", occurred)
          .bind("p", serializedPayload)
          .fetch()
          .rowsUpdated()
          .then();
    } catch (Exception e) {
      return Mono.error(e);
    }
  }

  static Map<String, Object> eventPayload(MediaIngestion i) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("phase", i.phase().name());
    payload.put("ingestionId", i.ingestionId());
    payload.put("fileName", i.fileName());
    payload.put("catalogItemId", i.catalogItemId());
    payload.put("failureCode", i.failureCode());
    return payload;
  }

  public Mono<Void> started(MediaIngestion i) {
    return add(i, "MediaIngestionStarted");
  }

  public Mono<Void> completed(MediaIngestion i) {
    return add(i, "MediaIngestionCompleted");
  }

  public Mono<Void> cancelled(MediaIngestion i) {
    return add(i, "MediaIngestionCancelled");
  }

  public Mono<Void> failed(MediaIngestion i) {
    return add(i, "MediaIngestionFailed");
  }

  @Transactional(transactionManager = "connectionFactoryTransactionManager")
  public Flux<Message> claim(int limit, int maxAttempts, Duration lease) {
    return db.sql(
            """
    WITH candidates AS (SELECT event_id FROM media_ingestion_outbox WHERE published_at IS NULL
      AND attempts < :maxAttempts AND next_attempt_at <= now() AND (claimed_until IS NULL OR claimed_until < now())
      ORDER BY occurred_at,event_id LIMIT :limit FOR UPDATE SKIP LOCKED)
    UPDATE media_ingestion_outbox e SET attempts=e.attempts+1,
      claimed_until=now()+make_interval(secs => :leaseSeconds)
      FROM candidates c WHERE e.event_id=c.event_id
      RETURNING e.event_id,e.event_type,e.aggregate_id,e.payload::text,e.occurred_at""")
        .bind("limit", limit)
        .bind("maxAttempts", maxAttempts)
        .bind("leaseSeconds", Math.max(1, lease.toSeconds()))
        .map(
            (r, m) ->
                new Message(
                    r.get("event_id", UUID.class),
                    r.get("event_type", String.class),
                    r.get("aggregate_id", UUID.class),
                    r.get("payload", String.class),
                    r.get("occurred_at", Instant.class)))
        .all();
  }

  public Mono<Void> markPublished(UUID id) {
    return db.sql(
            "UPDATE media_ingestion_outbox SET published_at=now(),claimed_until=NULL WHERE event_id=:id AND published_at IS NULL")
        .bind("id", id)
        .fetch()
        .rowsUpdated()
        .then();
  }

  public Mono<Void> markFailed(UUID id, String error, Duration delay) {
    return db.sql(
            "UPDATE media_ingestion_outbox SET claimed_until=NULL,next_attempt_at=now()+make_interval(secs => :seconds),last_error=:e WHERE event_id=:id AND published_at IS NULL")
        .bind("id", id)
        .bind("seconds", Math.max(1, delay.toSeconds()))
        .bind("e", error == null ? "unknown" : error.substring(0, Math.min(2000, error.length())))
        .fetch()
        .rowsUpdated()
        .then();
  }

  public Mono<Long> pendingCount(int max) {
    return db.sql(
            "SELECT count(*) n FROM media_ingestion_outbox WHERE published_at IS NULL AND attempts < :m")
        .bind("m", max)
        .map((r, m) -> r.get("n", Long.class))
        .one();
  }

  public Mono<Long> exhaustedCount(int max) {
    return db.sql(
            "SELECT count(*) n FROM media_ingestion_outbox WHERE published_at IS NULL AND attempts >= :m")
        .bind("m", max)
        .map((r, m) -> r.get("n", Long.class))
        .one();
  }

  public Mono<Long> oldestPendingAgeSeconds() {
    return db.sql(
            "SELECT coalesce(extract(epoch from(now()-min(occurred_at))),0)::bigint n FROM media_ingestion_outbox WHERE published_at IS NULL")
        .map((r, m) -> r.get("n", Long.class))
        .one();
  }
}
