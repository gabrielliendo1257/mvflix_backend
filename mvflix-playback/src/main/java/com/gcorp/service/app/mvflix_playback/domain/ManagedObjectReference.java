package com.gcorp.service.app.mvflix_playback.domain;

public record ManagedObjectReference(long objectId) implements ContentReference {
  public ManagedObjectReference {
    if (objectId <= 0) {
      throw new IllegalArgumentException("Managed object id must be positive");
    }
  }

  @Override
  public long value() {
    return objectId;
  }

  @Override
  public String type() {
    return "MANAGED_OBJECT";
  }
}
