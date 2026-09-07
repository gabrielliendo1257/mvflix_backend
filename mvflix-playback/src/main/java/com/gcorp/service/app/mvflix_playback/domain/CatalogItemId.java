package com.gcorp.service.app.mvflix_playback.domain;

public record CatalogItemId(long value) {
  public CatalogItemId {
    if (value <= 0) {
      throw new IllegalArgumentException("Catalog item id must be positive");
    }
  }
}
