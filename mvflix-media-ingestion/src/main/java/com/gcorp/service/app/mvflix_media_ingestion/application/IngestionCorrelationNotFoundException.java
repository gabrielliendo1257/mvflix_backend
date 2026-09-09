package com.gcorp.service.app.mvflix_media_ingestion.application;

import java.util.UUID;

public class IngestionCorrelationNotFoundException extends RuntimeException {
  public IngestionCorrelationNotFoundException(UUID correlationId) {
    super("unknown correlationId: " + correlationId);
  }
}
