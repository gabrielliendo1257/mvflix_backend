package com.gcorp.service.app.mvflix_movies.catalog.application;

import com.gcorp.service.app.mvflix_movies.catalog.application.port.CatalogItemAccessChanged;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.CatalogSemanticOutbox;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Coordinates the atomic catalog access update and its semantic outbox event. */
@Component
public class PersistCatalogAccessChange {
  private final CatalogItemRepository repository;
  private final CatalogSemanticOutbox outbox;

  public PersistCatalogAccessChange(CatalogItemRepository repository, CatalogSemanticOutbox outbox) {
    this.repository = repository;
    this.outbox = outbox;
  }

  @org.springframework.transaction.annotation.Transactional("connectionFactoryTransactionManager")
  public Mono<CatalogItem> execute(CatalogItem previous, CatalogItem changed, String actorId) {
    if (previous.getVisibility() == changed.getVisibility()
        && previous.getSharedWith().equals(changed.getSharedWith())) {
      return Mono.just(previous);
    }
    return this.repository.updateAccess(changed)
        .flatMap(updated -> this.outbox.append(new CatalogItemAccessChanged(
            UUID.randomUUID(), Instant.now(), actorId, actorId, UUID.randomUUID(),
            updated.getId().value(), updated.getKind().name(), updated.getTitle(),
            previous.getVisibility(), updated.getVisibility(), previous.getSharedWith().size(),
            updated.getSharedWith().size())).thenReturn(updated));
  }
}
