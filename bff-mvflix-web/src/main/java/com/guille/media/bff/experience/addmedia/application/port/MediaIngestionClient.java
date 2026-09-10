package com.guille.media.bff.experience.addmedia.application.port;

import com.guille.media.bff.experience.addmedia.application.StartAddMediaCommand;
import reactor.core.publisher.Mono;

/** HTTP boundary for the new ingestion owner. It never accepts an actor from the request. */
public interface MediaIngestionClient {
  Mono<MediaIngestionView> create(String ownerSubject, StartAddMediaCommand command, String correlationId);
  Mono<MediaIngestionView> status(String ownerSubject, String ingestionId, String correlationId);
  Mono<MediaIngestionView> complete(String ownerSubject, String ingestionId, Long sizeBytes, String correlationId);
  Mono<MediaIngestionView> cancel(String ownerSubject, String ingestionId, String correlationId);

  record MediaIngestionView(String ingestionId, String actorId, Long catalogItemId, String uploadId,
      String phase, String failureCode, String uploadUrl, String storageKey, long fileSize,
      String mimeType, String strategy, Long partSizeBytes, Integer totalParts) {
    public MediaIngestionView(String ingestionId, String actorId, Long catalogItemId, String uploadId,
        String phase, String failureCode, String uploadUrl, String storageKey, long fileSize,
        String mimeType) {
      this(ingestionId, actorId, catalogItemId, uploadId, phase, failureCode, uploadUrl, storageKey,
          fileSize, mimeType, "SIMPLE", null, null);
    }
  }
}
