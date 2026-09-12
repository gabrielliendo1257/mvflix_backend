package com.guille.media.bff.experience.addmedia.application.port;

import com.guille.media.bff.experience.addmedia.application.StartAddMediaCommand;
import reactor.core.publisher.Mono;
import java.util.List;
import com.guille.media.bff.app.dto.MultipartUploadDtos.CompletedPart;

/** HTTP boundary for the new ingestion owner. It never accepts an actor from the request. */
public interface MediaIngestionClient {
  Mono<MediaIngestionView> create(String ownerSubject, StartAddMediaCommand command, String correlationId);
  Mono<MediaIngestionView> status(String ownerSubject, String ingestionId, String correlationId);
  Mono<MediaIngestionView> complete(String ownerSubject, String ingestionId, Long sizeBytes, String correlationId);
  default Mono<MediaIngestionView> complete(String ownerSubject, String ingestionId, Long sizeBytes,
      String correlationId, List<CompletedPart> parts) {
    return complete(ownerSubject, ingestionId, sizeBytes, correlationId);
  }
  Mono<MediaIngestionView> cancel(String ownerSubject, String ingestionId, String correlationId);

  record MediaIngestionView(String ingestionId, String actorId, Long catalogItemId, String uploadId,
      String phase, String failureCode, String uploadUrl, String storageKey, long fileSize,
      String mimeType, String strategy, Long partSizeBytes, Integer totalParts) {
    public Phase phaseType() {
      return Phase.from(phase);
    }

    public Strategy strategyType() {
      return Strategy.from(strategy);
    }

    public enum Phase {
      COMPLETED,
      UNKNOWN;

      static Phase from(String value) {
        return "COMPLETED".equals(value) ? COMPLETED : UNKNOWN;
      }
    }

    public enum Strategy {
      PRESIGNED_MULTIPART,
      OTHER;

      static Strategy from(String value) {
        return "PRESIGNED_MULTIPART".equals(value) ? PRESIGNED_MULTIPART : OTHER;
      }
    }

    public MediaIngestionView(String ingestionId, String actorId, Long catalogItemId, String uploadId,
        String phase, String failureCode, String uploadUrl, String storageKey, long fileSize,
        String mimeType) {
      this(ingestionId, actorId, catalogItemId, uploadId, phase, failureCode, uploadUrl, storageKey,
          fileSize, mimeType, "SIMPLE", null, null);
    }
  }
}
