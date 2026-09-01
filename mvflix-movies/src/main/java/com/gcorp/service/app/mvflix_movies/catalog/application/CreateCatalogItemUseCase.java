package com.gcorp.service.app.mvflix_movies.catalog.application;

import com.gcorp.service.app.mvflix_movies.shared.application.security.UserProvider;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.CatalogItemAdded;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.CatalogSemanticOutbox;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import java.util.UUID;

@Slf4j
@Service
public class CreateCatalogItemUseCase {

  private final CatalogItemRepository movieRepository;
  private final UserProvider userProvider;
  private final CatalogSemanticOutbox outbox;

  @org.springframework.beans.factory.annotation.Autowired
  public CreateCatalogItemUseCase(
      CatalogItemRepository repository, UserProvider userProvider, CatalogSemanticOutbox outbox) {
    this.movieRepository = repository;
    this.userProvider = userProvider;
    this.outbox = outbox == null ? event -> Mono.empty() : outbox;
  }

  @Deprecated
  public CreateCatalogItemUseCase(CatalogItemRepository repository, UserProvider userProvider) {
    this(repository, userProvider, event -> Mono.empty());
  }

  @org.springframework.transaction.annotation.Transactional("connectionFactoryTransactionManager")
  public Mono<CatalogItem> execute(CreateCatalogItemCommand command) {
    UUID correlationId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    return this.userProvider
        .getAuthenticatedUser()
        .doOnNext(
            user ->
                log.info(
                    "Creando pelicula en DRAFT: owner={} title={}",
                    user.subject(),
                    command.metadata().title()))
        .flatMap(
            user ->
                this.movieRepository
                    .save(
                        CatalogItem.createDraft(user.subject(), command.metadata(), command.kind()))
                    .flatMap(
                        saved ->
                            this.outbox
                                .append(
                                    new CatalogItemAdded(
                                        eventId,
                                        Instant.now(),
                                        user.subject(),
                                        correlationId,
                                        saved.getId().value(),
                                        saved.getOwnerUsername(),
                                        saved.getKind().name(),
                                        saved.getStatus().name()))
                                .thenReturn(saved)))
        .doOnNext(
            movie ->
                log.info(
                    "Pelicula creada: id={} owner={}", movie.getId(), movie.getOwnerUsername()));
  }
}
