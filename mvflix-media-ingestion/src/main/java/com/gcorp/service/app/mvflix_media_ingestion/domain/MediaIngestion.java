package com.gcorp.service.app.mvflix_media_ingestion.domain;

import java.time.Instant;
import java.util.UUID;

public record MediaIngestion(
    UUID ingestionId,
    String actorId,
    Long catalogItemId,
    String uploadId,
    Phase phase,
    String failureCode,
    long version,
    int retryCount,
    Instant createdAt,
    Instant updatedAt,
    Instant nextAttemptAt,
    String idempotencyKey,
    String fileName,
    long fileSize,
    String mimeType,
    String uploadUrl,
    Long storageId,
    String storageKey,
    String requestFingerprint,
    UUID causationId,
    String audienceId) {
  public MediaIngestion(
      UUID id, String actor, Long catalog, String upload, Phase phase, String failure, long version,
      int retries, Instant created, Instant updated, Instant next, String key, String name,
      long size, String mime, String url, Long storageId, String storageKey,
      String requestFingerprint, UUID causationId) {
    this(id, actor, catalog, upload, phase, failure, version, retries, created, updated, next, key,
        name, size, mime, url, storageId, storageKey, requestFingerprint, causationId, actor);
  }

  public MediaIngestion(
      UUID id,
      String actor,
      Long catalog,
      String upload,
      Phase phase,
      String failure,
      long version,
      int retries,
      Instant created,
      Instant updated,
      Instant next,
      String key,
      String name,
      long size,
      String mime,
      String url) {
    this(
        id, actor, catalog, upload, phase, failure, version, retries, created, updated, next, key,
         name, size, mime, url, null, null, null, null, actor);
  }

  public MediaIngestion(
      UUID id,
      String actor,
      Long catalog,
      String upload,
      Phase phase,
      String failure,
      long version,
      int retries,
      Instant created,
      Instant updated,
      Instant next,
      String key,
      String name,
      long size,
      String mime,
      String url,
      Long storageId) {
    this(
        id, actor, catalog, upload, phase, failure, version, retries, created, updated, next, key,
         name, size, mime, url, storageId, null, null, null, actor);
  }

  public MediaIngestion(
      UUID id, String actor, Long catalog, String upload, Phase phase, String failure, long version,
      int retries, Instant created, Instant updated, Instant next, String key, String name,
      long size, String mime, String url, Long storageId, String storageKey) {
    this(id, actor, catalog, upload, phase, failure, version, retries, created, updated, next,
        key, name, size, mime, url, storageId, storageKey, null, null, actor);
  }

  public MediaIngestion(
      UUID id, String actor, Long catalog, String upload, Phase phase, String failure, long version,
      int retries, Instant created, Instant updated, Instant next, String key, String name,
      long size, String mime, String url, Long storageId, String storageKey,
      String requestFingerprint) {
    this(id, actor, catalog, upload, phase, failure, version, retries, created, updated, next,
        key, name, size, mime, url, storageId, storageKey, requestFingerprint, null, actor);
  }

  public enum Phase {
    STARTING,
    PREPARING_CATALOG,
    PREPARING_UPLOAD,
    AWAITING_UPLOAD,
    FINALIZING_CATALOG,
    COMPLETED,
    CANCELLING,
    CANCELLED,
    FAILED,
    RECONCILIATION_REQUIRED
  }

  public MediaIngestion transition(Phase next, Long catalog, String upload, String failure) {
    if (phase == Phase.COMPLETED || phase == Phase.CANCELLED)
      throw new IllegalStateException("terminal ingestion");
    if (!allowed(phase, next))
      throw new IllegalStateException("invalid ingestion transition " + phase + " -> " + next);
    return new MediaIngestion(
        ingestionId,
        actorId,
        catalog == null ? catalogItemId : catalog,
        upload == null ? uploadId : upload,
        next,
        failure,
        version + 1,
        retryCount,
        createdAt,
        Instant.now(),
        nextAttemptAt,
        idempotencyKey,
        fileName,
        fileSize,
        mimeType,
        uploadUrl,
        storageId,
        storageKey,
        requestFingerprint,
         causationId, audienceId);
  }

  public MediaIngestion awaitUpload(String upload, String url, String objectKey) {
    if (phase != Phase.PREPARING_UPLOAD)
      throw new IllegalStateException("upload can only be prepared from PREPARING_UPLOAD");
    return new MediaIngestion(
        ingestionId,
        actorId,
        catalogItemId,
        upload,
        Phase.AWAITING_UPLOAD,
        null,
        version + 1,
        retryCount,
        createdAt,
        Instant.now(),
        nextAttemptAt,
        idempotencyKey,
        fileName,
        fileSize,
        mimeType,
        url,
        storageId,
        objectKey,
        requestFingerprint,
         causationId, audienceId);
  }

  private static boolean allowed(Phase current, Phase next) {
    return switch (current) {
      case STARTING -> next == Phase.PREPARING_CATALOG || next == Phase.CANCELLING;
      case PREPARING_CATALOG -> next == Phase.PREPARING_UPLOAD || next == Phase.CANCELLING;
      case PREPARING_UPLOAD -> next == Phase.AWAITING_UPLOAD || next == Phase.CANCELLING;
      case AWAITING_UPLOAD -> next == Phase.AWAITING_UPLOAD
          || next == Phase.FINALIZING_CATALOG || next == Phase.CANCELLING;
      case FINALIZING_CATALOG -> next == Phase.COMPLETED || next == Phase.CANCELLING;
      case CANCELLING -> next == Phase.CANCELLED;
      case RECONCILIATION_REQUIRED -> next == Phase.COMPLETED
          || next == Phase.FINALIZING_CATALOG || next == Phase.CANCELLING;
      case COMPLETED, CANCELLED, FAILED -> false;
    };
  }

  public MediaIngestion failed(String code) {
    return recovery(Phase.FAILED, code, 30);
  }

  public MediaIngestion recovery(Phase next, String reason, long delaySeconds) {
    return new MediaIngestion(
        ingestionId,
        actorId,
        catalogItemId,
        uploadId,
        next,
        reason,
        version + 1,
        retryCount + 1,
        createdAt,
        Instant.now(),
        Instant.now().plusSeconds(delaySeconds),
        idempotencyKey,
        fileName,
        fileSize,
        mimeType,
        uploadUrl,
        storageId,
        storageKey,
        requestFingerprint,
         causationId, audienceId);
  }

  public MediaIngestion rescheduled(Phase next, String reason, long delaySeconds) {
    return new MediaIngestion(
        ingestionId,
        actorId,
        catalogItemId,
        uploadId,
        next,
        reason,
        version + 1,
        retryCount,
        createdAt,
        Instant.now(),
        Instant.now().plusSeconds(delaySeconds),
        idempotencyKey,
        fileName,
        fileSize,
        mimeType,
        uploadUrl,
        storageId,
        storageKey,
        requestFingerprint,
         causationId, audienceId);
  }

  public MediaIngestion withCausationId(UUID causation) {
    return new MediaIngestion(ingestionId, actorId, catalogItemId, uploadId, phase, failureCode,
        version, retryCount, createdAt, updatedAt, nextAttemptAt, idempotencyKey, fileName,
         fileSize, mimeType, uploadUrl, storageId, storageKey, requestFingerprint, causation,
         audienceId);
  }
}
