package com.gcorp.service.app.mvflix_playback.domain;

public record PlayableCatalogItem(
    CatalogItemId id,
    String title,
    String posterPath,
    String duration,
    Long objectId,
    PlayableAsset asset) {
  public record PlayableAsset(long id, long libraryId, String relativePath, long size,
      String mimeType) {}
}
