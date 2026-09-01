package com.gcorp.service.app.mvflix_movies.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gcorp.service.app.mvflix_movies.shared.application.security.AuthenticatedUser;
import com.gcorp.service.app.mvflix_movies.shared.application.security.UserProvider;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.LibraryMovieIds;
import com.gcorp.service.app.mvflix_movies.catalog.domain.movie.EnrichmentStatus;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemKind;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.metadata.MovieMetadata;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemStatus;
import com.gcorp.service.app.mvflix_movies.catalog.domain.access.Visibility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;

@ExtendWith(MockitoExtension.class)
class BulkVisibilityUseCaseTest {

    @Mock private CatalogItemRepository movieRepository;
    @Mock private LibraryMovieIds libraryMovieIds;
    @Mock private UserProvider userProvider;
    @Mock private PersistCatalogAccessChange persistAccessChange;

    @InjectMocks private BulkVisibilityUseCase useCase;

    @org.junit.jupiter.api.BeforeEach
    void delegatePersistenceThroughAccessCollaborator() {
        org.mockito.Mockito.lenient().when(this.persistAccessChange.execute(any(), any(), any()))
                .thenAnswer(invocation -> this.movieRepository.updateAccess(invocation.getArgument(1)));
    }

    @Test
    void sharedBulkPersistsOneAccessTransitionPerMovie() {
        CatalogItem movie = movie(1L, Visibility.PRIVATE, Set.of());
        when(this.userProvider.getAuthenticatedUser())
                .thenReturn(Mono.just(new AuthenticatedUser("Javier", "j@m.com")));
        when(this.movieRepository.findByOwnerAndIds("Javier", List.of(CatalogItemId.of(1L))))
                .thenReturn(Flux.just(movie));
        when(this.movieRepository.updateAccess(any(CatalogItem.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(this.useCase.execute(
                        List.of(CatalogItemId.of(1L)), List.of(), Visibility.SHARED,
                        List.of("Maria", "Pedro", "Maria", "  ")))
                .expectNext(new BulkVisibilityResult(1, 1, 0))
                .verifyComplete();

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(this.movieRepository).updateAccess(captor.capture());
        assertThat(captor.getValue().getVisibility()).isEqualTo(Visibility.SHARED);
        assertThat(captor.getValue().getSharedWith()).isEqualTo(Set.of("Maria", "Pedro"));
    }

    @Test
    void nonSharedBulkPreservesExistingSharesInAggregate() {
        CatalogItem movie = movie(1L, Visibility.SHARED, Set.of("Maria"));
        when(this.userProvider.getAuthenticatedUser())
                .thenReturn(Mono.just(new AuthenticatedUser("Javier", "j@m.com")));
        when(this.movieRepository.findByOwnerAndIds("Javier", List.of(CatalogItemId.of(1L))))
                .thenReturn(Flux.just(movie));
        when(this.movieRepository.updateAccess(any(CatalogItem.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(this.useCase.execute(
                        List.of(CatalogItemId.of(1L)), List.of(), Visibility.PRIVATE, List.of()))
                .expectNext(new BulkVisibilityResult(1, 1, 0))
                .verifyComplete();

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(this.movieRepository).updateAccess(captor.capture());
        assertThat(captor.getValue().getVisibility()).isEqualTo(Visibility.PRIVATE);
         assertThat(captor.getValue().getSharedWith()).isEmpty();
    }

    @Test
    void sharedBulkWithoutUsersFailsBeforeLoadingCatalog() {
        StepVerifier.create(this.useCase.execute(
                        List.of(CatalogItemId.of(1L)), List.of(), Visibility.SHARED,
                        List.of("  ")))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(this.movieRepository, never()).updateAccess(any(CatalogItem.class));
        verify(this.userProvider, never()).getAuthenticatedUser();
    }

    @Test
    void resolvesLibraryMoviesThroughCatalogPort() {
        CatalogItem movie = movie(2L, Visibility.PRIVATE, Set.of());
        when(this.userProvider.getAuthenticatedUser())
                .thenReturn(Mono.just(new AuthenticatedUser("Javier", "j@m.com")));
        when(this.libraryMovieIds.findIdentifiedByLibraryIds(List.of(7L)))
                .thenReturn(Flux.just(CatalogItemId.of(2L)));
        when(this.movieRepository.findByOwnerAndIds("Javier", List.of(CatalogItemId.of(2L))))
                .thenReturn(Flux.just(movie));
        when(this.movieRepository.updateAccess(any(CatalogItem.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(this.useCase.execute(
                        List.of(), List.of(7L), Visibility.PUBLIC, List.of()))
                .expectNext(new BulkVisibilityResult(1, 1, 0))
                .verifyComplete();

        verify(this.libraryMovieIds).findIdentifiedByLibraryIds(List.of(7L));
    }

    private static CatalogItem movie(long id, Visibility visibility, Set<String> shares) {
        return new CatalogItem(
                CatalogItemId.of(id), "Javier", "Dune", CatalogItemStatus.READY,
                EnrichmentStatus.RAW, null, MovieMetadata.onlyTitle("Dune"),
                visibility, shares, CatalogItemKind.MOVIE);
    }
}
