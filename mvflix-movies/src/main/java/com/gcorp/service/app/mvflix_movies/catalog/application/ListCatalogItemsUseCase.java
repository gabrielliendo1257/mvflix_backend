package com.gcorp.service.app.mvflix_movies.catalog.application;

import com.gcorp.service.app.mvflix_movies.shared.application.security.UserProvider;
import com.gcorp.service.app.mvflix_movies.shared.application.security.ViewerContext;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Listado del catálogo con dos lecturas de acceso:
 *
 * <ul>
 *   <li>{@code visible}: PUBLIC + propias + compartidas (Home/Search global).
 *       La política la decide {@code CatalogItem.isVisibleTo} y su traducción SQL.
 *   <li>{@code owned}: solo contenido propio. Es la lectura de
 *       ADMINISTRACIÓN: nunca mezcla contenido ajeno con acciones de
 *       edición/borrado, aunque sea visible para el usuario.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListCatalogItemsUseCase {

    static final int MAX_LIMIT = 50;

    private final CatalogItemRepository movieRepository;
    private final UserProvider userProvider;

    public Flux<CatalogItem> execute(String scope, int limit) {
        int capped = Math.min(limit, MAX_LIMIT);
        Mono<ViewerContext> viewerContext = this.userProvider.getViewerContext();
        return (viewerContext == null
                ? this.userProvider.getAuthenticatedUser().map(ViewerContext::authenticated)
                : viewerContext)
                .flatMapMany(viewer -> "owned".equalsIgnoreCase(scope)
                        ? viewer.authenticated()
                            ? this.movieRepository.findByOwner(viewer.subject(), capped)
                            : Flux.error(new com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemAccessDeniedException("Authentication required"))
                        : this.movieRepository.findVisibleCatalogItems(viewer.subject(), capped));
    }
}
