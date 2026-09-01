package com.gcorp.service.app.mvflix_movies.catalog.application;

import com.gcorp.service.app.mvflix_movies.shared.application.security.UserProvider;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.LibraryMovieIds;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;
import com.gcorp.service.app.mvflix_movies.catalog.domain.access.Visibility;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * Cambia la visibilidad de varias peliculas del catalogo en un solo lote.
 * Los ids pueden venir directos (movieIds) o derivarse de librerias
 * (libraryIds resuelve los assets identificados de cada libreria).
 * Solo se procesan las peliculas del dueño autenticado: las ajenas ni se
 * consultan. SHARED exige al menos un username; los que fallan por cualquier
 * motivo se cuentan como failed y no abortan el lote.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkVisibilityUseCase {

    private final CatalogItemRepository movieRepository;
    private final LibraryMovieIds libraryMovieIds;
    private final UserProvider userProvider;
    private final PersistCatalogAccessChange persistAccessChange;

    public Mono<BulkVisibilityResult> execute(
            List<CatalogItemId> movieIds, List<Long> libraryIds,
            Visibility visibility, List<String> usernames) {
        if (visibility == Visibility.SHARED
                && (usernames == null
                        || usernames.stream().noneMatch(u -> u != null && !u.isBlank()))) {
            return Mono.error(new IllegalArgumentException(
                    "SHARED requiere al menos un username en usernames"));
        }
        List<String> clean = usernames == null
                ? List.of()
                : usernames.stream()
                        .filter(u -> u != null && !u.isBlank())
                        .distinct()
                        .toList();
        return this.userProvider
                .getAuthenticatedUser()
                .flatMapMany(user -> Flux.concat(
                                Flux.fromIterable(movieIds),
                                libraryIds.isEmpty()
                                        ? Flux.empty()
                                        : this.libraryMovieIds
                                                .findIdentifiedByLibraryIds(libraryIds))
                        .distinct()
                        .collectList()
                        .flatMapMany(ids -> ids.isEmpty()
                                ? Flux.empty()
                                : this.movieRepository
                                        .findByOwnerAndIds(user.subject(), ids)))
                .flatMap(movie -> this.applyVisibility(movie, visibility, clean)
                        .map(updated -> true)
                        .onErrorResume(error -> {
                            log.warn("Bulk: movie {} no se pudo actualizar: {}",
                                    movie.getId().value(), error.getMessage());
                            return Mono.just(false);
                        }), 16)
                .collectList()
                .map(results -> {
                    int updated = (int) results.stream().filter(Boolean.TRUE::equals).count();
                    return new BulkVisibilityResult(
                            results.size(), updated, results.size() - updated);
                })
                .doOnNext(result -> log.info(
                        "Bulk visibilidad {} -> {}, procesadas: {}, actualizadas: {}, fallidas: {}",
                        visibility, clean, result.total(), result.updated(), result.failed()));
    }

    private Mono<CatalogItem> applyVisibility(
            CatalogItem movie, Visibility visibility, List<String> usernames) {
        CatalogItem access = movie.withAccess(visibility, Set.copyOf(usernames));
        return this.persistAccessChange.execute(movie, access, movie.getOwnerUsername());
    }
}
