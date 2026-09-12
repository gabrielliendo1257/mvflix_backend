package com.guille.media.bff.experience.addmedia.application;

import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Reads the canonical upload state from media-ingestion. */
@Service
public class GetAddMediaStatus {
  private final MediaIngestionClient ingestion;

  public GetAddMediaStatus(MediaIngestionClient ingestion) {
    this.ingestion = ingestion;
  }

  public Mono<AddMediaResult> handle(String ownerSubject, String addMediaId) {
    return handle(ownerSubject, addMediaId, "add-media:" + addMediaId);
  }

  public Mono<AddMediaResult> handle(String ownerSubject, String addMediaId, String correlationId) {
    return ingestion.status(ownerSubject, addMediaId, correlationId)
        .map(MediaIngestionResultMapper::map);
  }
}
