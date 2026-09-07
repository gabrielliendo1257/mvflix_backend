package com.gcorp.service.app.mvflix_playback.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentReferenceTest {
  @Test
  void preservesTheBoundedContextOfTheIdentifier() {
    assertThat(new ManagedObjectReference(7).type()).isEqualTo("MANAGED_OBJECT");
    assertThat(new LibraryAssetReference(7).type()).isEqualTo("LIBRARY_ASSET");
    assertThat(new ManagedObjectReference(7).value())
        .isEqualTo(new LibraryAssetReference(7).value());
  }
}
