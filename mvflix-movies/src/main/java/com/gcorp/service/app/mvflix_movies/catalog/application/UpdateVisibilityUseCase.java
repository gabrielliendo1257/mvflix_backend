package com.gcorp.service.app.mvflix_movies.catalog.application;

import com.gcorp.service.app.mvflix_movies.shared.application.security.UserProvider;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemAccessDeniedException;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;
import com.gcorp.service.app.mvflix_movies.catalog.domain.access.Visibility;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.CatalogItemAccessChanged;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.CatalogSemanticOutbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Cambia la visibilidad (PUBLIC/PRIVATE/SHARED) de una pelicula del catalogo.
 * Solo el dueño (lo decide {@link CatalogItem#isOwnedBy(String)}); el resto ve 403.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateVisibilityUseCase {

    private final CatalogItemRepository movieRepository;
    private final UserProvider userProvider;
    private final CatalogSemanticOutbox outbox;

    @org.springframework.transaction.annotation.Transactional("connectionFactoryTransactionManager")
    public Mono<CatalogItem> execute(CatalogItemId id, Visibility visibility) {
        return this.userProvider
                .getAuthenticatedUser()
                .flatMap(user -> this.movieRepository
                        .findById(id)
                        .switchIfEmpty(Mono.error(new CatalogItemAccessDeniedException(
                                "Movie not accessible: " + id.value())))
                        .filter(movie -> movie.isOwnedBy(user.subject()))
                        .switchIfEmpty(Mono.error(new CatalogItemAccessDeniedException(
                                "CatalogItem not owned: " + id.value())))
                        .flatMap(movie -> {
                            Visibility previous = movie.getVisibility();
                            CatalogItem changed = movie.withAccess(visibility, movie.getSharedWith());
                            return this.movieRepository.updateAccess(changed)
                                    .flatMap(updated -> this.outbox.append(new CatalogItemAccessChanged(
                                            UUID.randomUUID(), Instant.now(), user.subject(), user.subject(), UUID.randomUUID(),
                                            updated.getId().value(), updated.getKind().name(), updated.getTitle(),
                                            previous, updated.getVisibility(), movie.getSharedWith().size(),
                                            updated.getSharedWith().size()))
                                    .thenReturn(updated)
                                    .doOnNext(saved -> log.info(
                                            "CatalogItem {} visibilidad {} -> {}",
                                            id.value(), previous, saved.getVisibility())));
                        })
                        );
    }

}
