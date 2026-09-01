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

import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.util.UUID;

/**
 * Acceso completo de una película en UNA decisión transaccional:
 * visibilidad + compartidos se aplican juntos o no se aplican. Sustituye la
 * coreografía de dos casos de uso que podía dejar estado parcialmente
 * modificado si el segundo paso fallaba. Solo el dueño; el resto ve 403.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateCatalogItemAccessUseCase {

    private final CatalogItemRepository movieRepository;
    private final UserProvider userProvider;
    private final CatalogSemanticOutbox outbox;

    @org.springframework.transaction.annotation.Transactional("connectionFactoryTransactionManager")
    public Mono<CatalogItem> execute(CatalogItemId id, Visibility visibility, List<String> sharedWith) {
        List<String> clean = sharedWith == null
                ? List.of()
                : sharedWith.stream()
                        .filter(name -> name != null && !name.isBlank())
                        .distinct()
                        .toList();
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
                            CatalogItem updated = movie.withAccess(visibility, Set.copyOf(clean));
                            return this.movieRepository.updateAccess(updated)
                                    .flatMap(saved -> this.outbox.append(new CatalogItemAccessChanged(
                                            UUID.randomUUID(), Instant.now(), user.subject(), user.subject(),
                                            UUID.randomUUID(), saved.getId().value(), saved.getKind().name(),
                                            saved.getTitle(), movie.getVisibility(), saved.getVisibility(),
                                            movie.getSharedWith().size(), saved.getSharedWith().size()))
                                    .thenReturn(saved));
                        })
                        .doOnNext(updated -> log.info(
                                "CatalogItem {} acceso -> {} compartidos={}",
                                id.value(), visibility, clean)));
    }

}
