package com.gcorp.service.app.mvflix_movies.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gcorp.service.app.mvflix_movies.shared.application.security.AuthenticatedUser;
import com.gcorp.service.app.mvflix_movies.shared.application.security.UserProvider;
import com.gcorp.service.app.mvflix_movies.catalog.domain.movie.EnrichmentStatus;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemKind;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemAccessDeniedException;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.metadata.MovieMetadata;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemStatus;
import com.gcorp.service.app.mvflix_movies.catalog.domain.access.Visibility;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class UpdateCatalogItemAccessUseCaseTest {

    @Mock private CatalogItemRepository movieRepository;
    @Mock private UserProvider userProvider;
    @Mock private PersistCatalogAccessChange persistAccessChange;

    @InjectMocks private UpdateCatalogItemAccessUseCase useCase;

    private CatalogItem movie;

    @BeforeEach
    void setUp() {
        this.movie = new CatalogItem(
                CatalogItemId.of(1L), "Javier", "Dune", CatalogItemStatus.READY,
                EnrichmentStatus.ENRICHED, null, null,
                Visibility.PRIVATE, java.util.Set.of(), CatalogItemKind.MOVIE);
        // Lenientes: el test de autorización corta el flujo antes de
        // consumir la cadena completa de stubs.
        org.mockito.Mockito.lenient()
                .when(this.userProvider.getAuthenticatedUser())
                .thenReturn(Mono.just(new AuthenticatedUser("Javier", "j@m.com")));
        org.mockito.Mockito.lenient()
                .when(this.movieRepository.findById(CatalogItemId.of(1L)))
                .thenReturn(Mono.just(this.movie));
        org.mockito.Mockito.lenient()
                .when(this.movieRepository.updateAccess(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        org.mockito.Mockito.lenient()
                .when(this.persistAccessChange.execute(any(), any(), any()))
                .thenAnswer(invocation -> this.movieRepository.updateAccess(invocation.getArgument(1)));
    }

    @Test
    void appliesVisibilityAndSharesAsOneDecision() {
        StepVerifier.create(this.useCase.execute(
                        CatalogItemId.of(1L), Visibility.SHARED, List.of("Maria", "", "Pedro")))
                .assertNext(updated -> {
                    assertThat(updated.getVisibility()).isEqualTo(Visibility.SHARED);
                    assertThat(updated.getSharedWith()).containsExactlyInAnyOrder("Maria", "Pedro");
                })
                .verifyComplete();

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(this.movieRepository).updateAccess(captor.capture());
        assertThat(captor.getValue().getSharedWith()).containsExactlyInAnyOrder("Maria", "Pedro");
        verify(this.persistAccessChange).execute(any(), any(), org.mockito.ArgumentMatchers.eq("Javier"));
    }

    @Test
    void blankAndDuplicateUsernamesAreNormalized() {
        StepVerifier.create(this.useCase.execute(
                        CatalogItemId.of(1L), Visibility.SHARED, List.of("Maria", "Maria", "  ")))
                .assertNext(updated ->
                        assertThat(updated.getSharedWith()).containsExactly("Maria"))
                .verifyComplete();
    }

    @Test
    void nonOwnerIsDeniedWithoutPersisting() {
        org.mockito.Mockito.lenient()
                .when(this.userProvider.getAuthenticatedUser())
                .thenReturn(Mono.just(new AuthenticatedUser("Maria", "m@m.com")));
        // findById/updateAccess quedan sin stub estricto: el flujo se corta
        // en la autorización, así que no se consumen.
        org.mockito.Mockito.lenient()
                .when(this.movieRepository.findById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(this.useCase.execute(
                        CatalogItemId.of(1L), Visibility.PUBLIC, List.of()))
                .expectError(CatalogItemAccessDeniedException.class)
                .verify();

        verify(this.movieRepository, org.mockito.Mockito.never()).updateAccess(any());
    }

    @Test
    void sharedWithoutUsersIsRejectedByDomain() {
        StepVerifier.create(this.useCase.execute(
                        CatalogItemId.of(1L), Visibility.SHARED, List.of()))
                .expectError(com.gcorp.service.app.mvflix_movies.catalog.domain.item.InvalidCatalogItemAccessException.class)
                .verify();

        verify(this.movieRepository, org.mockito.Mockito.never()).updateAccess(any());
    }

    @Test
    void privateCleansSharesAtTheDomain() {
        StepVerifier.create(this.useCase.execute(
                        CatalogItemId.of(1L), Visibility.PRIVATE, List.of("Maria")))
                .assertNext(updated -> {
                    assertThat(updated.getVisibility()).isEqualTo(Visibility.PRIVATE);
                    assertThat(updated.getSharedWith()).isEmpty();
                })
                .verifyComplete();

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(this.movieRepository).updateAccess(captor.capture());
        assertThat(captor.getValue().getSharedWith()).isEmpty();
    }

    @Test
    void publicCleansSharesAtTheDomain() {
        StepVerifier.create(this.useCase.execute(
                        CatalogItemId.of(1L), Visibility.PUBLIC, List.of("Maria")))
                .assertNext(updated -> {
                    assertThat(updated.getVisibility()).isEqualTo(Visibility.PUBLIC);
                    assertThat(updated.getSharedWith()).isEmpty();
                })
                .verifyComplete();
    }
}
