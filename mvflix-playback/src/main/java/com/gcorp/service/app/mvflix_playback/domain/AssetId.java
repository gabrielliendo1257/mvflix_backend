package com.gcorp.service.app.mvflix_playback.domain;

public record AssetId(long value) {
  public AssetId {
    if (value <= 0) {
      throw new IllegalArgumentException("Asset id must be positive");
    }
  }
}
