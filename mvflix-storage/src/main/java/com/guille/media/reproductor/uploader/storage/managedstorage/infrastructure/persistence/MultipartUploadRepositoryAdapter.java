package com.guille.media.reproductor.uploader.storage.managedstorage.infrastructure.persistence;

import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.MultipartUploadSession;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.MultipartUploadSession.MultipartStatus;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.MultipartUploadRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class MultipartUploadRepositoryAdapter implements MultipartUploadRepository {
  private final DatabaseClient db;

  private static final String SELECT = "SELECT upload_id, minio_upload_id, owner_username, "
      + "bucket_name, object_key, total_bytes, content_type, part_size_bytes, total_parts, "
      + "expires_at, status FROM multipart_upload_sessions ";

  @Override
  public Mono<MultipartUploadSession> save(MultipartUploadSession s, String key) {
    return db.sql("INSERT INTO multipart_upload_sessions (upload_id,minio_upload_id,owner_username,"
        + "bucket_name,object_key,total_bytes,content_type,part_size_bytes,total_parts,expires_at,status,idempotency_key) "
        + "VALUES (:id,:minio,:owner,:bucket,:object,:bytes,:type,:partSize,:parts,:expires,:status,:key) RETURNING *")
        .bind("id", s.uploadId()).bind("minio", s.minioUploadId()).bind("owner", s.ownerUsername())
        .bind("bucket", s.bucket()).bind("object", s.objectKey()).bind("bytes", s.totalBytes())
        .bind("type", s.contentType()).bind("partSize", s.partSizeBytes()).bind("parts", s.totalParts())
        .bind("expires", s.expiresAt()).bind("status", s.status().name()).bind("key", key)
         .map((row, metadata) -> map(row.get("upload_id", String.class), row.get("minio_upload_id", String.class),
            row.get("owner_username", String.class), row.get("bucket_name", String.class), row.get("object_key", String.class),
            row.get("total_bytes", Long.class), row.get("content_type", String.class), row.get("part_size_bytes", Long.class),
            row.get("total_parts", Integer.class), row.get("expires_at", Instant.class), row.get("status", String.class))).one();
  }

  @Override public Mono<MultipartUploadSession> findById(String id) { return one(SELECT + "WHERE upload_id=:id", "id", id); }
  @Override public Mono<MultipartUploadSession> findByOwnerAndIdempotencyKey(String owner, String key) {
    return db.sql(SELECT + "WHERE owner_username=:owner AND idempotency_key=:key")
        .bind("owner", owner).bind("key", key).map(this::mapRow).one();
  }

  @Override public Mono<MultipartUploadSession> transition(MultipartUploadSession s, MultipartStatus expected) {
    return db.sql("UPDATE multipart_upload_sessions SET status=:next,updated_at=NOW() WHERE upload_id=:id AND status=:expected RETURNING *")
        .bind("next", s.status().name()).bind("id", s.uploadId()).bind("expected", expected.name())
        .map(this::mapRow).one().switchIfEmpty(Mono.error(new IllegalStateException("multipart session changed concurrently")));
  }

  @Override public Flux<MultipartUploadSession> findExpired(Instant now) {
    return db.sql(SELECT + "WHERE status='PENDING' AND expires_at<=:now").bind("now", now).map(this::mapRow).all();
  }

  private Mono<MultipartUploadSession> one(String sql, String name, String value) {
    return db.sql(sql).bind(name, value).map(this::mapRow).one();
  }
  private MultipartUploadSession mapRow(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata ignored) {
     return map(row.get("upload_id", String.class), row.get("minio_upload_id", String.class),
        row.get("owner_username", String.class), row.get("bucket_name", String.class), row.get("object_key", String.class),
        row.get("total_bytes", Long.class), row.get("content_type", String.class), row.get("part_size_bytes", Long.class),
        row.get("total_parts", Integer.class), row.get("expires_at", Instant.class), row.get("status", String.class));
  }
  private MultipartUploadSession map(String id, String minio, String owner, String bucket, String object,
      Long bytes, String type, Long partSize, Integer parts, Instant expires, String status) {
    return new MultipartUploadSession(id, minio, owner, bucket, object, bytes, type, partSize, parts, expires,
        MultipartStatus.valueOf(status));
  }
}
