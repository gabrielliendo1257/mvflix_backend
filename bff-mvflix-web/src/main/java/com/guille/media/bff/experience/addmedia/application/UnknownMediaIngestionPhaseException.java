package com.guille.media.bff.experience.addmedia.application;

public class UnknownMediaIngestionPhaseException extends RuntimeException {
  public UnknownMediaIngestionPhaseException(String phase) {
    super("Unknown media-ingestion phase: " + phase);
  }
}
