package com.gcorp.service.app.mvflix_playback.application.port;

import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.PlayableCatalogItem;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import reactor.core.publisher.Mono;

public interface AuthorizedCatalog {
  Mono<PlayableCatalogItem> getPlayableItem(CatalogItemId id, ViewerId viewerId, String bearerToken);
}
