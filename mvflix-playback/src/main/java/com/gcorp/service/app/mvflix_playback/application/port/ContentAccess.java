package com.gcorp.service.app.mvflix_playback.application.port;

import com.gcorp.service.app.mvflix_playback.domain.PlayableCatalogItem;
import java.time.Instant;
import reactor.core.publisher.Mono;

public interface ContentAccess {
  Mono<PlaybackSource> open(PlayableCatalogItem item);

  record PlaybackSource(String strategy, String url, Instant expiresAt, String mimeType) {}
}
