package com.gcorp.service.app.mvflix_playback.domain;

public sealed interface ContentReference
    permits ManagedObjectReference, LibraryAssetReference {
  long value();

  String type();
}
