package com.gcorp.service.app.mvflix_movies.catalog.application;

import com.gcorp.mvflix.security.webflux.AuthenticatedActor;
import com.gcorp.service.app.mvflix_movies.shared.application.security.UserProvider;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemKind;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemAccessDeniedException;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.metadata.MovieMetadata;
import com.gcorp.service.app.mvflix_movies.catalog.domain.metadata.CatalogMetadata;
import com.gcorp.service.app.mvflix_movies.catalog.domain.metadata.VideoMetadata;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Edición manual de la metadata de una película (título, año, sinopsis, ...) sin
 * depender de la fuente externa. Solo el dueño (lo decide {@link CatalogItem#isOwnedBy(String)});
 * el resto ve 403/404 sin revelar existencia, igual que el resto del catálogo.
 * Semántica de merge: campos {@code null} del command conservan el valor actual.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateCatalogItemUseCase {

    private final CatalogItemRepository movieRepository;
    private final UserProvider userProvider;

    @Transactional(transactionManager = "connectionFactoryTransactionManager")
    public Mono<CatalogItem> execute(CatalogItemId id, UpdateCatalogItemCommand command) {
        return this.userProvider
                .getAuthenticatedUser()
                .flatMap(user -> this.execute(id, command,
                        new AuthenticatedActor(user.subject(), user.roles())));
    }

    public Mono<CatalogItem> execute(
            CatalogItemId id, UpdateCatalogItemCommand command, AuthenticatedActor actor) {
        return this.movieRepository
                        .findById(id)
                        .switchIfEmpty(Mono.error(new CatalogItemAccessDeniedException(
                                "Movie not accessible: " + id.value())))
                        .filter(movie -> movie.isOwnedBy(actor.subject()) || actor.isAdmin())
                        .switchIfEmpty(Mono.error(new CatalogItemAccessDeniedException(
                                "CatalogItem not owned: " + id.value())))
                        .flatMap(movie -> this.update(movie, command))
                        .doOnNext(updated -> log.info(
                                "CatalogItem {} metadata actualizada manualmente{}",
                                id.value(),
                                updated.isOwnedBy(actor.subject()) ? "" : " (moderacion)"));
    }

    private Mono<CatalogItem> update(CatalogItem movie, UpdateCatalogItemCommand command) {
        CatalogItemKind targetKind = command.kind() == null ? movie.getKind() : command.kind();
        boolean reclassified = targetKind != movie.getKind();
        CatalogMetadata merged = targetKind == CatalogItemKind.MOVIE
                ? mergeMovie(movie, command)
                : mergeVideo(movie, command);

        CatalogItem edited = reclassified
                ? targetKind == CatalogItemKind.MOVIE
                        ? movie.reclassifyAsMovie(merged)
                        : movie.reclassifyAsVideo(merged)
                : movie.withMetadata(merged);
        return this.movieRepository.updateDetails(edited);
    }

    private static MovieMetadata mergeMovie(CatalogItem item, UpdateCatalogItemCommand command) {
        MovieMetadata current;
        if (item.getKind() == CatalogItemKind.MOVIE) {
            current = item.getMovieMetadata();
        } else if (item.getKind() == CatalogItemKind.VIDEO
                && item.getMetadata() instanceof VideoMetadata video) {
            current = new MovieMetadata(video.title(), null, null, List.of(), null, null, null,
                    List.of(), video.description(), null, null, null, null, List.of(), null);
        } else {
            throw new IllegalArgumentException("metadata does not match catalog kind");
        }
        return merge(current, command);
    }

    private static VideoMetadata mergeVideo(CatalogItem item, UpdateCatalogItemCommand command) {
        VideoMetadata current;
        if (item.getKind() == CatalogItemKind.VIDEO) {
            current = item.getVideoMetadata();
        } else if (item.getKind() == CatalogItemKind.MOVIE
                && item.getMetadata() instanceof MovieMetadata movie) {
            current = new VideoMetadata(movie.title(), movie.overview(), null);
        } else {
            throw new IllegalArgumentException("metadata does not match catalog kind");
        }
        return merge(current, command);
    }

    /** Merge de metadata de video; solo title y overview(description) son editables. */
    static VideoMetadata merge(VideoMetadata current, UpdateCatalogItemCommand command) {
        return new VideoMetadata(
                command.title() != null ? command.title() : current.title(),
                command.overview() != null ? command.overview() : current.description(),
                current.recordedAt());
    }

    /** Merge de metadata de película; null conserva el valor actual. */
    static MovieMetadata merge(MovieMetadata current, UpdateCatalogItemCommand command) {
        return new MovieMetadata(
                command.title() != null ? command.title() : current.title(),
                command.originalTitle() != null ? command.originalTitle() : current.originalTitle(),
                command.year() != null ? command.year() : current.year(),
                command.genres() != null ? List.copyOf(command.genres()) : current.genres(),
                command.popularity() != null ? command.popularity() : current.popularity(),
                command.duration() != null ? command.duration() : current.duration(),
                command.director() != null ? command.director() : current.director(),
                command.cast() != null ? List.copyOf(command.cast()) : current.cast(),
                command.overview() != null ? command.overview() : current.overview(),
                command.posterPath() != null ? command.posterPath() : current.posterPath(),
                command.releaseDate() != null ? command.releaseDate() : current.releaseDate(),
                command.country() != null ? command.country() : current.country(),
                command.language() != null ? command.language() : current.language(),
                command.awards() != null ? List.copyOf(command.awards()) : current.awards(),
                current.tmdbId());
    }
}
