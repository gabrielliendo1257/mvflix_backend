package com.gcorp.service.app.mvflix_movies.catalog.application.port;

import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.PlayableCatalogItem;
import reactor.core.publisher.Mono;

public interface AuthorizedPlaybackCatalog {
  Mono<PlayableCatalogItem> findAuthorizedPlaybackContext(CatalogItemId id, String viewer);
}
