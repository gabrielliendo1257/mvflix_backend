package com.gcorp.service.app.mvflix_movies.catalog.application;

import com.gcorp.service.app.mvflix_movies.shared.application.security.UserProvider;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemAccessDeniedException;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetCatalogItemUseCase {

    private final CatalogItemRepository movieRepository;
    private final UserProvider userProvider;

    /**
     * Detalle solo si la pelicula es visible para el usuario autenticado
     * (la decide {@link CatalogItem#isVisibleTo(String)}: PUBLIC, propia o compartida);
     * si no, 403 sin revelar la existencia.
     */
    public Mono<CatalogItem> execute(CatalogItemId id) {
        return this.userProvider
                .getViewerContext()
                .flatMap(viewer -> this.movieRepository
                        .findById(id)
                        .switchIfEmpty(Mono.error(new CatalogItemAccessDeniedException(
                                "Movie not accessible: " + id.value())))
                         .filter(movie -> movie.isVisibleTo(viewer.subject()))
                        .switchIfEmpty(Mono.error(new CatalogItemAccessDeniedException(
                                "Movie not accessible: " + id.value()))));
    }
}
