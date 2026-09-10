package com.gcorp.service.app.mvflix_media_ingestion.application;

import java.util.Map;
import reactor.core.publisher.Mono;

public interface DownstreamClients {
  Mono<Long> createCatalogDraft(Map<String, Object> draft, String actor, String key, String correlationId);

  Mono<MediaIngestionEligibility> mediaIngestionEligibility(String actor);

  Mono<Upload> prepareUpload(
      String fileName, long fileSize, String mimeType, String actor, String key);

  Mono<Void> requestUploadCompletion(String uploadId, String actor, String idempotencyKey);

  Mono<Void> completeCatalog(long catalogItemId, String objectKey, long objectId, String actor);

  Mono<Void> discardDraft(long catalogItemId, String actor, String idempotencyKey);

  Mono<Void> cancelUpload(String uploadId, String actor, String key);

  Mono<StorageStatus> storageStatus(String uploadId, String actor);

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
}
