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
    String failureDetail,
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
    String audienceId,
    String uploadStrategy,
    Long partSizeBytes,
    Integer totalParts) {
  public MediaIngestion(
      UUID id, String actor, Long catalog, String upload, Phase phase, String failure, long version,
      int retries, Instant created, Instant updated, Instant next, String key, String name,
      long size, String mime, String url, Long storageId, String storageKey,
      String requestFingerprint, UUID causationId) {
    this(id, actor, catalog, upload, phase, failure, null, version, retries, created, updated, next, key,
        name, size, mime, url, storageId, storageKey, requestFingerprint, causationId, actor);
  }

  public MediaIngestion(
      UUID id, String actor, Long catalog, String upload, Phase phase, String failure, long version,
      int retries, Instant created, Instant updated, Instant next, String key, String name,
      long size, String mime, String url, Long storageId, String storageKey,
      String requestFingerprint, UUID causationId, String audienceId) {
    this(id, actor, catalog, upload, phase, failure, null, version, retries, created, updated, next,
        key, name, size, mime, url, storageId, storageKey, requestFingerprint, causationId,
        audienceId, "SIMPLE", null, null);
  }

  public MediaIngestion(UUID id, String actor, Long catalog, String upload, Phase phase, String failure,
      String detail, long version, int retries, Instant created, Instant updated, Instant next,
      String key, String name, long size, String mime, String url, Long storageId, String storageKey,
      String requestFingerprint, UUID causationId, String audienceId) {
    this(id, actor, catalog, upload, phase, failure, detail, version, retries, created, updated, next,
        key, name, size, mime, url, storageId, storageKey, requestFingerprint, causationId, audienceId,
        "SIMPLE", null, null);
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
        id, actor, catalog, upload, phase, failure, null, version, retries, created, updated, next, key,
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
        id, actor, catalog, upload, phase, failure, null, version, retries, created, updated, next, key,
         name, size, mime, url, storageId, null, null, null, actor);
  }

  public MediaIngestion(
      UUID id, String actor, Long catalog, String upload, Phase phase, String failure, long version,
      int retries, Instant created, Instant updated, Instant next, String key, String name,
      long size, String mime, String url, Long storageId, String storageKey) {
    this(id, actor, catalog, upload, phase, failure, null, version, retries, created, updated, next,
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
    VERIFYING_UPLOAD,
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
        uploadUrl,
        storageId,
        storageKey,
        requestFingerprint,
         causationId, audienceId, uploadStrategy, partSizeBytes, totalParts);
  }

  public MediaIngestion awaitUpload(String upload, String url, String objectKey) {
    return awaitUpload(upload, url, objectKey, "SIMPLE", null, null);
  }

  public MediaIngestion awaitUpload(String upload, String url, String objectKey, String strategy,
      Long partSize, Integer parts) {
    if (phase != Phase.PREPARING_UPLOAD)
      throw new IllegalStateException("upload can only be prepared from PREPARING_UPLOAD");
    return new MediaIngestion(
        ingestionId,
        actorId,
        catalogItemId,
        upload,
         Phase.AWAITING_UPLOAD,
         null,
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
         causationId, audienceId, strategy, partSize, parts);
  }

  private static boolean allowed(Phase current, Phase next) {
    return switch (current) {
      case STARTING -> next == Phase.PREPARING_CATALOG || next == Phase.CANCELLING;
      case PREPARING_CATALOG -> next == Phase.PREPARING_UPLOAD || next == Phase.CANCELLING;
      case PREPARING_UPLOAD -> next == Phase.AWAITING_UPLOAD || next == Phase.CANCELLING;
      case AWAITING_UPLOAD -> next == Phase.AWAITING_UPLOAD
          || next == Phase.VERIFYING_UPLOAD || next == Phase.FINALIZING_CATALOG || next == Phase.CANCELLING;
      case VERIFYING_UPLOAD -> next == Phase.VERIFYING_UPLOAD
          || next == Phase.FINALIZING_CATALOG || next == Phase.CANCELLING;
      case FINALIZING_CATALOG -> next == Phase.COMPLETED || next == Phase.CANCELLING;
      case CANCELLING -> next == Phase.CANCELLED;
      case RECONCILIATION_REQUIRED -> next == Phase.COMPLETED
          || next == Phase.FINALIZING_CATALOG || next == Phase.CANCELLING;
      case COMPLETED, CANCELLED, FAILED -> false;
    };
  }

  public MediaIngestion failed(String code) {
    return failed(code, null);
  }

  public MediaIngestion failed(String code, String detail) {
    return recovery(Phase.FAILED, boundedCode(code), detail, 30);
  }

  public MediaIngestion recovery(Phase next, String reason, long delaySeconds) {
    return recovery(next, reason, null, delaySeconds);
  }

  public MediaIngestion recovery(Phase next, String code, String detail, long delaySeconds) {
    return new MediaIngestion(
        ingestionId,
        actorId,
        catalogItemId,
        uploadId,
        next,
         boundedCode(code),
         detail,
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
          causationId, audienceId, uploadStrategy, partSizeBytes, totalParts);
  }

  public MediaIngestion rescheduled(Phase next, String reason, long delaySeconds) {
    return rescheduled(next, reason, null, delaySeconds);
  }

  public MediaIngestion rescheduled(Phase next, String code, String detail, long delaySeconds) {
    return new MediaIngestion(
        ingestionId,
        actorId,
        catalogItemId,
        uploadId,
        next,
         boundedCode(code),
         detail,
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
          causationId, audienceId, uploadStrategy, partSizeBytes, totalParts);
  }

  public MediaIngestion withCausationId(UUID causation) {
    return new MediaIngestion(ingestionId, actorId, catalogItemId, uploadId, phase, failureCode,
        failureDetail, version, retryCount, createdAt, updatedAt, nextAttemptAt, idempotencyKey, fileName,
         fileSize, mimeType, uploadUrl, storageId, storageKey, requestFingerprint, causation,
         audienceId, uploadStrategy, partSizeBytes, totalParts);
  }

  public MediaIngestion recordStorageIdentity(long objectId, String objectKey) {
    if (objectId <= 0 || objectKey == null || objectKey.isBlank())
      throw new IllegalArgumentException("invalid storage identity");
    if (storageId != null && !Long.valueOf(objectId).equals(storageId)
        || storageKey != null && !objectKey.equals(storageKey))
      throw new IllegalStateException("storage identity does not match ingestion");
    if (Long.valueOf(objectId).equals(storageId) && objectKey.equals(storageKey)) return this;
    return new MediaIngestion(ingestionId, actorId, catalogItemId, uploadId, phase, failureCode,
        failureDetail, version + 1, retryCount, createdAt, Instant.now(), Instant.now(),
        idempotencyKey, fileName, fileSize, mimeType, uploadUrl, objectId, objectKey,
         requestFingerprint, causationId, audienceId, uploadStrategy, partSizeBytes, totalParts);
  }

  public MediaIngestion withStorageIdentity(long objectId, String objectKey) {
    return recordStorageIdentity(objectId, objectKey);
  }

  private static String boundedCode(String code) {
    return code == null || code.isBlank() || code.length() > 120 ? "INTERNAL_ERROR" : code;
  }
}
