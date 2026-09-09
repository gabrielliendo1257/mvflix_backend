package com.gcorp.service.app.mvflix_movies.catalog.application;

import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemAccessDeniedException;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.PlayableCatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.AuthorizedPlaybackCatalog;
import com.gcorp.service.app.mvflix_movies.shared.application.security.UserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class GetAuthorizedPlaybackContext {
  private final AuthorizedPlaybackCatalog catalogRepository;
  private final UserProvider userProvider;

  public Mono<PlayableCatalogItem> execute(CatalogItemId catalogItemId) {
    return userProvider.getViewerContext()
        .flatMap(user -> catalogRepository.findAuthorizedPlaybackContext(catalogItemId, user.subject()))
        .switchIfEmpty(Mono.error(new CatalogItemAccessDeniedException(
            "Movie not accessible: " + catalogItemId.value())));
  }
}
