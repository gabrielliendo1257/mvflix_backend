package com.gcorp.service.app.mvflix_playback.application.port;

import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import com.gcorp.service.app.mvflix_playback.domain.WatchProgress;
import reactor.core.publisher.Mono;

public interface WatchProgressRepository {
  Mono<WatchProgress> find(ViewerId viewerId, CatalogItemId catalogItemId);
  Mono<WatchProgress> save(WatchProgress progress);
}
