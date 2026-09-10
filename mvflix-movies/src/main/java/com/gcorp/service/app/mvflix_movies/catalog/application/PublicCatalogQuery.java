package com.gcorp.service.app.mvflix_movies.catalog.application;

import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemStatus;
import com.gcorp.service.app.mvflix_movies.catalog.domain.access.Visibility;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Read model for anonymous visitors. It never exposes owner or storage data. */
@Service
public class PublicCatalogQuery {
  private static final int MAX_LIMIT = 50;
  private final CatalogItemRepository repository;

  public PublicCatalogQuery(CatalogItemRepository repository) {
    this.repository = repository;
  }

  public Flux<CatalogItem> list(int limit) {
    return repository.findPublicCatalogItems(Math.min(Math.max(limit, 1), MAX_LIMIT));
  }

  public Mono<CatalogItem> find(CatalogItemId id) {
    return repository.findById(id)
        .filter(item -> item.getVisibility() == Visibility.PUBLIC
            && item.getStatus() == CatalogItemStatus.READY);
  }
}
