package com.gcorp.service.app.mvflix_playback.domain;

public record ViewerId(String value) {
  public ViewerId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Viewer id is required");
    }
  }
}
