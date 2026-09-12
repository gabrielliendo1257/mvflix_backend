package com.guille.media.bff.experience.addmedia.application;

import com.guille.media.bff.app.dto.MultipartUploadDtos.CompletedPart;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** BFF adapter for the canonical media-ingestion completion workflow. */
@Service
public class CompleteProcessAddMedia {
  private final MediaIngestionClient ingestion;

  public CompleteProcessAddMedia(MediaIngestionClient ingestion) {
    this.ingestion = ingestion;
  }

  public Mono<AddMediaResult> handle(String ownerSubject, String addMediaId,
      Long reportedSizeBytes, String correlationId) {
    return ingestion.complete(ownerSubject, addMediaId, reportedSizeBytes, correlationId)
        .map(MediaIngestionResultMapper::map);
  }

  public Mono<AddMediaResult> handleMultipart(String ownerSubject, String addMediaId,
      Long reportedSizeBytes, List<CompletedPart> parts, String correlationId) {
    return ingestion.status(ownerSubject, addMediaId, correlationId)
        .flatMap(view -> {
          if (!"PRESIGNED_MULTIPART".equals(view.strategy()) || view.uploadId() == null) {
            return Mono.error(new IllegalArgumentException("add-media upload is not multipart"));
          }
          if ("COMPLETED".equals(view.phase())) {
            return Mono.just(MediaIngestionResultMapper.map(view));
          }
          return ingestion.complete(ownerSubject, addMediaId, reportedSizeBytes, correlationId, parts)
              .map(MediaIngestionResultMapper::map);
        });
  }
}
