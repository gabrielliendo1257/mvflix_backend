package com.gcorp.service.app.mvflix_media_ingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.gcorp.service.app.mvflix_media_ingestion.domain.MediaIngestion;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DatabaseOutboxTest {
  @Test
  void lifecyclePayloadContainsFeedIdentityAndContext() {
    UUID ingestionId = UUID.randomUUID();
    var ingestion = new MediaIngestion(
        ingestionId, "actor", 42L, "upload", MediaIngestion.Phase.FAILED, "CATALOG_UNAVAILABLE",
        1, 0, Instant.now(), Instant.now(), Instant.now(), "key", "movie.mp4", 4, "video/mp4", null);

    assertThat(DatabaseOutbox.eventPayload(ingestion))
        .containsEntry("ingestionId", ingestionId)
        .containsEntry("fileName", "movie.mp4")
        .containsEntry("catalogItemId", 42L)
        .containsEntry("failureCode", "CATALOG_UNAVAILABLE")
        .containsEntry("phase", "FAILED");
  }

  @Test
  void lifecyclePayloadKeepsNullableFieldsForSuccessfulEvents() {
    var ingestion = new MediaIngestion(
        UUID.randomUUID(), "actor", null, null, MediaIngestion.Phase.STARTING, null,
        1, 0, Instant.now(), Instant.now(), Instant.now(), "key", "movie.mp4", 4, "video/mp4", null);

    assertThat(DatabaseOutbox.eventPayload(ingestion))
        .containsKeys("fileName", "catalogItemId", "failureCode")
        .containsEntry("catalogItemId", null)
        .containsEntry("failureCode", null);
  }
}
