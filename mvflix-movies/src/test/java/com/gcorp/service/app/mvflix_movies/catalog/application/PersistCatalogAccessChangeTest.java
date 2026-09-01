package com.gcorp.service.app.mvflix_movies.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.gcorp.service.app.mvflix_movies.catalog.application.port.CatalogSemanticEvent;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.CatalogSemanticOutbox;
import com.gcorp.service.app.mvflix_movies.catalog.domain.access.Visibility;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemKind;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemStatus;
import com.gcorp.service.app.mvflix_movies.catalog.domain.movie.EnrichmentStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class PersistCatalogAccessChangeTest {
  @Test
  void skipsPersistenceAndOutboxForAnEffectiveNoOp() {
    var repository = mock(CatalogItemRepository.class);
    var outbox = mock(CatalogSemanticOutbox.class);
    var collaborator = new PersistCatalogAccessChange(repository, outbox);
    var item = item(Visibility.PRIVATE, Set.of());

    assertThat(collaborator.execute(item, item, "Javier").block()).isSameAs(item);

    verifyNoInteractions(repository, outbox);
  }

  @Test
  void persistsBeforeAppendingAccessEvent() {
    var repository = mock(CatalogItemRepository.class);
    var outbox = mock(CatalogSemanticOutbox.class);
    var collaborator = new PersistCatalogAccessChange(repository, outbox);
    var previous = item(Visibility.PRIVATE, Set.of());
    var changed = item(Visibility.PUBLIC, Set.of());
    when(repository.updateAccess(changed)).thenReturn(Mono.just(changed));
    when(outbox.append(any(CatalogSemanticEvent.class))).thenReturn(Mono.empty());

    assertThat(collaborator.execute(previous, changed, "Javier").block()).isSameAs(changed);

    var order = inOrder(repository, outbox);
    order.verify(repository).updateAccess(changed);
    order.verify(outbox).append(any(CatalogSemanticEvent.class));
  }

  private static CatalogItem item(Visibility visibility, Set<String> shares) {
    return new CatalogItem(CatalogItemId.of(1L), "Javier", "Dune", CatalogItemStatus.READY,
        EnrichmentStatus.ENRICHED, null, null, visibility, shares, CatalogItemKind.MOVIE);
  }
}
