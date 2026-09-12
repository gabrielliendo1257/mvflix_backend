package com.guille.media.bff.experience.addmedia.application;

import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Cancels uploads through the canonical media-ingestion owner. */
@Service
public class CancelAddMedia {
  private final MediaIngestionClient ingestion;

  public CancelAddMedia(MediaIngestionClient ingestion) {
    this.ingestion = ingestion;
  }

  public Mono<AddMediaResult> handle(String ownerSubject, String addMediaId) {
    return handle(ownerSubject, addMediaId, "add-media:" + addMediaId);
  }

  public Mono<AddMediaResult> handle(String ownerSubject, String addMediaId, String correlationId) {
    return ingestion.cancel(ownerSubject, addMediaId, correlationId)
        .map(MediaIngestionResultMapper::map);
  }
}
