package com.guille.media.bff.experience.activity.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivityWebClientAdapterTest {
  @Test
  void mapsDownstreamEntryToPublicActivityEntry() {
    UUID activityId = UUID.randomUUID();
    var entry = new ActivityWebClientAdapter.DownstreamEntry(activityId, activityId,
        "MEDIA_INGESTION", "FAILED", Instant.parse("2026-01-01T12:00:00Z"),
        Instant.parse("2026-01-01T12:01:00Z"), "movie.mp4", 42L, "CATALOG_FAILED", "cursor");

    var mapped = ActivityWebClientAdapter.toApplication(entry);

    assertThat(mapped.activityId()).isEqualTo(activityId);
    assertThat(mapped.status()).isEqualTo("FAILED");
    assertThat(mapped.fileName()).isEqualTo("movie.mp4");
    assertThat(mapped.catalogItemId()).isEqualTo(42L);
  }
}
