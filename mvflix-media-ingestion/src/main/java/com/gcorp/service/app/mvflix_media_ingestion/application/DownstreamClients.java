package com.gcorp.service.app.mvflix_media_ingestion.application;

import java.util.Map;
import java.util.List;
import reactor.core.publisher.Mono;

public interface DownstreamClients {
  Mono<Long> createCatalogDraft(Map<String, Object> draft, String actor, String key, String correlationId);

  Mono<MediaIngestionEligibility> mediaIngestionEligibility(String actor);

  Mono<Void> reportViolation(String actor, String reason);

  Mono<Upload> prepareUpload(
      String fileName, long fileSize, String mimeType, String actor, String key);

  Mono<Void> requestUploadCompletion(String uploadId, String actor, String idempotencyKey);

  default Mono<Void> requestUploadCompletion(String uploadId, String actor, String idempotencyKey,
      String strategy) {
    return requestUploadCompletion(uploadId, actor, idempotencyKey);
  }

  default Mono<Void> requestUploadCompletion(String uploadId, String actor, String idempotencyKey,
      String strategy, List<CompletedPart> parts) {
    return requestUploadCompletion(uploadId, actor, idempotencyKey, strategy);
  }

  Mono<Void> completeCatalog(long catalogItemId, String objectKey, long objectId, String actor);

  Mono<Void> discardDraft(long catalogItemId, String actor, String idempotencyKey);

  Mono<Void> cancelUpload(String uploadId, String actor, String key);

  default Mono<Void> cancelUpload(String uploadId, String actor, String key, String strategy) {
    return cancelUpload(uploadId, actor, key);
  }

  Mono<StorageStatus> storageStatus(String uploadId, String actor);

  default Mono<StorageStatus> storageStatus(String uploadId, String actor, String strategy) {
    return storageStatus(uploadId, actor);
  }

  Mono<CatalogStatus> catalogStatus(long catalogItemId, String actor);

  record Upload(String uploadId, String storageKey, String uploadUrl, String strategy,
      Long partSizeBytes, Integer totalParts) {
    public Upload(String uploadId, String storageKey, String uploadUrl) {
      this(uploadId, storageKey, uploadUrl, "SIMPLE", null, null);
    }
  }

  record StorageStatus(String status, Long objectId, String objectKey) {}

  record CatalogStatus(String status) {}

  record MediaIngestionEligibility(boolean allowed) {}

  record CompletedPart(int partNumber, String etag) {}
}
