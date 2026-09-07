package com.gcorp.service.app.mvflix_playback.domain;

public record LibraryAssetReference(long assetId) implements ContentReference {
  public LibraryAssetReference {
    if (assetId <= 0) {
      throw new IllegalArgumentException("Library asset id must be positive");
    }
  }

  @Override
  public long value() {
    return assetId;
  }

  @Override
  public String type() {
    return "LIBRARY_ASSET";
  }
}
