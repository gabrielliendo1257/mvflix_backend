package com.gcorp.service.app.mvflix_media_ingestion.infrastructure.persistence;

import com.gcorp.service.app.mvflix_media_ingestion.application.MediaIngestionRepository;
import com.gcorp.service.app.mvflix_media_ingestion.domain.MediaIngestion;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class R2dbcMediaIngestionRepository implements MediaIngestionRepository {
  private final DatabaseClient db;
  private final long recoveryStaleAfterSeconds;

  public R2dbcMediaIngestionRepository(
      DatabaseClient db,
      @Value("${mvflix.recovery.stale-after-seconds:30}") long recoveryStaleAfterSeconds) {
    this.db = db;
    this.recoveryStaleAfterSeconds = recoveryStaleAfterSeconds;
  }

  private MediaIngestion map(io.r2dbc.spi.Row r) {
    return new MediaIngestion(
        r.get("ingestion_id", UUID.class),
        r.get("actor_id", String.class),
        r.get("catalog_item_id", Long.class),
        r.get("upload_id", String.class),
        MediaIngestion.Phase.valueOf(r.get("phase", String.class)),
        r.get("failure_code", String.class),
        r.get("version", Long.class),
        r.get("retry_count", Integer.class),
        r.get("created_at", Instant.class),
        r.get("updated_at", Instant.class),
        r.get("next_attempt_at", Instant.class),
        r.get("idempotency_key", String.class),
        r.get("file_name", String.class),
        r.get("file_size", Long.class),
        r.get("mime_type", String.class),
        r.get("upload_url", String.class),
        r.get("storage_id", Long.class),
        r.get("storage_key", String.class),
        r.get("request_fingerprint", String.class),
         r.get("causation_id", UUID.class),
         r.get("audience_id", String.class));
  }

  private org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec bind(
      org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec s,
      String n,
      Object v,
      Class<?> type) {
    return v == null ? s.bindNull(n, type) : s.bind(n, v);
  }

  private String select() {
    return "SELECT * FROM media_ingestions WHERE ingestion_id=:id";
  }

  public Mono<MediaIngestion> find(UUID id) {
    return db.sql(select()).bind("id", id).map((r, m) -> map(r)).one();
  }

  public Mono<MediaIngestion> findByKey(String actor, String key) {
    return db.sql("SELECT * FROM media_ingestions WHERE actor_id=:a AND idempotency_key=:k")
        .bind("a", actor)
        .bind("k", key)
        .map((r, m) -> map(r))
        .one();
  }

  public Mono<MediaIngestion> findByUploadId(String uploadId) {
    return db.sql("SELECT * FROM media_ingestions WHERE upload_id=:u")
        .bind("u", uploadId)
        .map((r, m) -> map(r))
        .one();
  }

  public Mono<MediaIngestion> findByStorageId(long storageId) {
    return db.sql("SELECT * FROM media_ingestions WHERE storage_id=:s")
        .bind("s", storageId)
        .map((r, m) -> map(r))
        .one();
  }

  public Mono<MediaIngestion> insert(MediaIngestion i) {
    var s =
        db.sql(
            "INSERT INTO"
                + " media_ingestions(ingestion_id,actor_id,audience_id,catalog_item_id,upload_id,upload_url,phase,version,retry_count,created_at,updated_at,next_attempt_at,idempotency_key,file_name,file_size,mime_type,correlation_id,storage_id,storage_key,request_fingerprint,causation_id)"
                + " VALUES(:id,:a,:audience,:c,:u,:url,:p,:v,0,:created,:updated,:n,:k,:f,:s,:m,:correlation,:sid,:skey,:fp,:cause) RETURNING *");
    s = bind(s, "id", i.ingestionId(), UUID.class);
    s = bind(s, "a", i.actorId(), String.class);
    s = bind(s, "audience", i.audienceId(), String.class);
    s = bind(s, "c", i.catalogItemId(), Long.class);
    s = bind(s, "u", i.uploadId(), String.class);
    s = bind(s, "url", i.uploadUrl(), String.class);
    s = bind(s, "p", i.phase().name(), String.class);
    s = bind(s, "v", i.version(), Long.class);
    s = bind(s, "created", i.createdAt(), Instant.class);
    s = bind(s, "updated", i.updatedAt(), Instant.class);
    s = bind(s, "n", i.nextAttemptAt(), Instant.class);
    s = bind(s, "k", i.idempotencyKey(), String.class);
    s = bind(s, "f", i.fileName(), String.class);
    s = bind(s, "s", i.fileSize(), Long.class);
    s = bind(s, "m", i.mimeType(), String.class);
    s = bind(s, "correlation", i.ingestionId(), UUID.class);
    s = bind(s, "sid", i.storageId(), Long.class);
    s = bind(s, "skey", i.storageKey(), String.class);
    s = bind(s, "fp", i.requestFingerprint(), String.class);
    s = bind(s, "cause", i.causationId(), UUID.class);
    return s.map((r, m) -> map(r)).one();
  }

  public Mono<Boolean> compareAndSet(MediaIngestion e, MediaIngestion n) {
    var s =
        db.sql(
            "UPDATE media_ingestions SET"
                + " catalog_item_id=:c,upload_id=:u,storage_id=:sid,storage_key=:skey,upload_url=:url,request_fingerprint=:fp,causation_id=:cause,phase=:p,failure_code=:f,version=:nv,updated_at=:now,retry_count=:r,next_attempt_at=:next,recovery_claimed_until=NULL"
                + " WHERE ingestion_id=:id AND version=:ov");
    s = bind(s, "c", n.catalogItemId(), Long.class);
    s = bind(s, "u", n.uploadId(), String.class);
    s = bind(s, "sid", n.storageId(), Long.class);
    s = bind(s, "skey", n.storageKey(), String.class);
    s = bind(s, "url", n.uploadUrl(), String.class);
    s = bind(s, "fp", n.requestFingerprint(), String.class);
    s = bind(s, "cause", n.causationId(), UUID.class);
    s = bind(s, "p", n.phase().name(), String.class);
    s = bind(s, "f", n.failureCode(), String.class);
    s = bind(s, "nv", n.version(), Long.class);
    s = bind(s, "now", n.updatedAt(), Instant.class);
    s = bind(s, "r", n.retryCount(), Integer.class);
    s = bind(s, "next", n.nextAttemptAt(), Instant.class);
    return bind(s, "id", e.ingestionId(), UUID.class)
        .bind("ov", e.version())
        .fetch()
        .rowsUpdated()
        .map(x -> x == 1);
  }

  public Flux<MediaIngestion> claimDueRecoverable(int limit, Duration lease) {
    return db.sql(
            "UPDATE media_ingestions i SET recovery_claimed_until=now() +"
                + " (:lease * interval '1 second'), retry_count=i.retry_count+1,"
                + " version=i.version+1, updated_at=now() WHERE i.ingestion_id IN ("
                + "SELECT ingestion_id FROM media_ingestions WHERE phase IN"
                + " ('STARTING','PREPARING_CATALOG','PREPARING_UPLOAD','FINALIZING_CATALOG','RECONCILIATION_REQUIRED')"
                + " AND ((phase IN ('STARTING','PREPARING_CATALOG','PREPARING_UPLOAD')"
                + " AND updated_at<=now() - (:stale * interval '1 second'))"
                + " OR (phase IN ('FINALIZING_CATALOG','RECONCILIATION_REQUIRED')"
                + " AND next_attempt_at<=now()))"
                + " AND (recovery_claimed_until IS NULL OR"
                + " recovery_claimed_until<now()) ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED"
                + " LIMIT :limit) RETURNING i.*")
        .bind("limit", limit)
        .bind("stale", recoveryStaleAfterSeconds)
        .bind("lease", lease.toSeconds())
        .map((r, m) -> map(r))
        .all();
  }
}
