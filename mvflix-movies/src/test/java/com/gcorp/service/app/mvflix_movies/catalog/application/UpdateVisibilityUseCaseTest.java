package com.gcorp.service.app.mvflix_movies.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gcorp.service.app.mvflix_movies.shared.application.security.AuthenticatedUser;
import com.gcorp.service.app.mvflix_movies.shared.application.security.UserProvider;
import com.gcorp.service.app.mvflix_movies.catalog.domain.movie.EnrichmentStatus;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemKind;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemAccessDeniedException;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemStatus;
import com.gcorp.service.app.mvflix_movies.catalog.domain.access.Visibility;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.CatalogSemanticOutbox;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.CatalogSemanticEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

@ExtendWith(MockitoExtension.class)
class UpdateVisibilityUseCaseTest {

    @Mock private CatalogItemRepository movieRepository;
    @Mock private UserProvider userProvider;
    @Mock private CatalogSemanticOutbox outbox;

    @InjectMocks private UpdateVisibilityUseCase useCase;

    private static CatalogItem movie(long id, String owner, Visibility visibility) {
        return new CatalogItem(
                CatalogItemId.of(id), owner, "Dune", CatalogItemStatus.READY, EnrichmentStatus.ENRICHED,
                null, null, visibility, java.util.Set.of(), CatalogItemKind.MOVIE);
    }

    @Test
    void ownerChangesVisibility() {
        CatalogItem movie = movie(1L, "Javier", Visibility.PRIVATE);
        CatalogItem published = movie(1L, "Javier", Visibility.PUBLIC);

        when(this.userProvider.getAuthenticatedUser())
                .thenReturn(Mono.just(new AuthenticatedUser("Javier", "j@m.com")));
        when(this.movieRepository.findById(CatalogItemId.of(1L)))
                .thenReturn(Mono.just(movie));
        when(this.movieRepository.updateAccess(any(CatalogItem.class)))
                .thenReturn(Mono.just(published));
        when(this.outbox.append(any(CatalogSemanticEvent.class))).thenReturn(Mono.empty());

        StepVerifier.create(this.useCase.execute(CatalogItemId.of(1L), Visibility.PUBLIC))
                .expectNextMatches(m -> m.getVisibility() == Visibility.PUBLIC)
                .verifyComplete();

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(this.movieRepository).updateAccess(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(CatalogItemId.of(1L));
        assertThat(captor.getValue().getVisibility()).isEqualTo(Visibility.PUBLIC);
        ArgumentCaptor<CatalogSemanticEvent> eventCaptor = ArgumentCaptor.forClass(CatalogSemanticEvent.class);
        verify(this.outbox).append(eventCaptor.capture());
        assertThat(eventCaptor.getValue().payload()).isEqualTo(Map.of(
                "catalogItemId", 1L, "kind", "MOVIE", "title", "Dune",
                "previousVisibility", "PRIVATE", "visibility", "PUBLIC",
                "previousSharedCount", 0, "sharedCount", 0));
    }

    @Test
    void nonOwnerIsDenied() {
        CatalogItem movie = movie(1L, "Javier", Visibility.PRIVATE);

        when(this.userProvider.getAuthenticatedUser())
                .thenReturn(Mono.just(new AuthenticatedUser("Maria", "m@m.com")));
        when(this.movieRepository.findById(CatalogItemId.of(1L)))
                .thenReturn(Mono.just(movie));

        StepVerifier.create(this.useCase.execute(CatalogItemId.of(1L), Visibility.PUBLIC))
                .expectError(CatalogItemAccessDeniedException.class)
                .verify();

        verify(this.movieRepository, never()).updateAccess(any(CatalogItem.class));
    }

    @Test
    void invisibleMovieIsDenied() {
        when(this.userProvider.getAuthenticatedUser())
                .thenReturn(Mono.just(new AuthenticatedUser("Maria", "m@m.com")));
        when(this.movieRepository.findById(CatalogItemId.of(1L)))
                .thenReturn(Mono.empty());

        StepVerifier.create(this.useCase.execute(CatalogItemId.of(1L), Visibility.PUBLIC))
                .expectError(CatalogItemAccessDeniedException.class)
                .verify();

        verify(this.movieRepository, never()).updateAccess(any(CatalogItem.class));
    }
}
