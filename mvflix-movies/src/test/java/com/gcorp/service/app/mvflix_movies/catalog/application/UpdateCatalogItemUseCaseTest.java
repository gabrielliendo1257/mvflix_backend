package com.gcorp.service.app.mvflix_movies.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gcorp.service.app.mvflix_movies.catalog.domain.movie.EnrichmentStatus;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemKind;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemAccessDeniedException;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.metadata.MovieMetadata;
import com.gcorp.service.app.mvflix_movies.catalog.domain.metadata.VideoMetadata;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemRepository;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemStatus;
import com.gcorp.service.app.mvflix_movies.catalog.domain.access.Visibility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.time.Instant;

@ExtendWith(MockitoExtension.class)
class UpdateCatalogItemUseCaseTest {

    @Mock private CatalogItemRepository movieRepository;
    @InjectMocks private UpdateCatalogItemUseCase useCase;

    private static final MovieMetadata METADATA = new MovieMetadata(
            "Dune", "Dune", 2021, List.of("Sci-Fi"), 7.9, "2h 35m", "Denis Villeneuve",
            List.of("Timothée Chalamet"), "Overview", "/poster.jpg", "2021-10-22",
            "USA", "English", List.of("Oscar"), 438631L);

    private static CatalogItem movie(long id, String owner, MovieMetadata metadata) {
        return new CatalogItem(
                CatalogItemId.of(id), owner, "Dune", CatalogItemStatus.READY, EnrichmentStatus.ENRICHED,
                null, metadata, Visibility.PRIVATE, java.util.Set.of(), CatalogItemKind.MOVIE);
    }

    private static CatalogItem video(long id, String owner, VideoMetadata metadata) {
        return new CatalogItem(
                CatalogItemId.of(id), owner, metadata.title(), CatalogItemStatus.READY,
                EnrichmentStatus.RAW, null, metadata, Visibility.PRIVATE, java.util.Set.of(),
                CatalogItemKind.VIDEO);
    }

    @Test
    void ownerUpdatesProvidedFieldsAndKeepsOthers() {
        CatalogItem movie = movie(1L, "Javier", METADATA);
        CatalogItem updated = movie(1L, "Javier", new MovieMetadata(
                "Dune: Part Two", "Dune: Part Two", 2024, List.of("Sci-Fi", "Adventure"),
                7.9, "2h 35m", "Denis Villeneuve", List.of("Timothée Chalamet"),
                "Overview", "/poster.jpg", "2024-03-01", "USA", "English",
                List.of("Oscar"), 438631L));

        when(this.movieRepository.findById(CatalogItemId.of(1L))).thenReturn(Mono.just(movie));
        when(this.movieRepository.updateDetails(any(CatalogItem.class)))
                .thenReturn(Mono.just(updated));

        var command = new UpdateCatalogItemCommand(
                "Dune: Part Two", "Dune: Part Two", 2024, List.of("Sci-Fi", "Adventure"),
                null, null, null, null, null, "2024-03-01", null, null, null, null, null);

        StepVerifier.create(this.useCase.execute(new CatalogActor("Javier", java.util.Set.of()), CatalogItemId.of(1L), command))
                .expectNext(updated)
                .verifyComplete();

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(this.movieRepository).updateDetails(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(CatalogItemId.of(1L));
         assertThat(captor.getValue().getMovieMetadata().title()).isEqualTo("Dune: Part Two");
         assertThat(captor.getValue().getMovieMetadata().year()).isEqualTo(2024);
         assertThat(captor.getValue().getMovieMetadata().genres())
                .containsExactly("Sci-Fi", "Adventure");
         assertThat(captor.getValue().getMovieMetadata().releaseDate()).isEqualTo("2024-03-01");
         assertThat(captor.getValue().getMovieMetadata().duration()).isEqualTo("2h 35m");
         assertThat(captor.getValue().getMovieMetadata().posterPath()).isEqualTo("/poster.jpg");
         assertThat(captor.getValue().getMovieMetadata().tmdbId()).isEqualTo(438631L);
    }

    @Test
    void mergeKeepsNullFieldsAndNonEditableIdentity() {
        MovieMetadata merged = UpdateCatalogItemUseCase.merge(METADATA, new UpdateCatalogItemCommand(
                "Dune: Part Two", null, null, List.of(), null, null, null, "Nueva sinopsis",
                null, null, null, null, null, null, null));

        assertThat(merged.title()).isEqualTo("Dune: Part Two");
        assertThat(merged.genres()).isEmpty();
        assertThat(merged.overview()).isEqualTo("Nueva sinopsis");
        assertThat(merged.popularity()).isEqualTo(7.9);
        assertThat(merged.posterPath()).isEqualTo("/poster.jpg");
        assertThat(merged.tmdbId()).isEqualTo(438631L);
        assertThat(merged.year()).isEqualTo(2021);
    }

    @Test
    void mergeUpdatesPosterAndPopularity() {
        MovieMetadata merged = UpdateCatalogItemUseCase.merge(METADATA, new UpdateCatalogItemCommand(
                null, null, null, null, null, null, null, null,
                "/nuevo-poster.jpg", null, null, null, null, 9.1, null));

        assertThat(merged.posterPath()).isEqualTo("/nuevo-poster.jpg");
        assertThat(merged.popularity()).isEqualTo(9.1);
        assertThat(merged.tmdbId()).isEqualTo(438631L);
        assertThat(merged.title()).isEqualTo("Dune");
    }

    @Test
    void explicitMovieKindUsesMovieMerge() {
        MovieMetadata merged = UpdateCatalogItemUseCase.merge(METADATA, new UpdateCatalogItemCommand(
                "Edited", null, null, null, null, null, null, null,
                null, null, null, null, null, null, CatalogItemKind.MOVIE));

        assertThat(merged.title()).isEqualTo("Edited");
        assertThat(merged.tmdbId()).isEqualTo(438631L);
    }

    @Test
    void videoMergeWithoutKindKeepsDescriptionAndRecordedAt() {
        Instant recordedAt = Instant.parse("2024-01-01T00:00:00Z");
        VideoMetadata merged = UpdateCatalogItemUseCase.merge(
                new VideoMetadata("Clip", "Description", recordedAt),
                new UpdateCatalogItemCommand(null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null));

        assertThat(merged.title()).isEqualTo("Clip");
        assertThat(merged.description()).isEqualTo("Description");
        assertThat(merged.recordedAt()).isEqualTo(recordedAt);
    }

    @Test
    void videoItemCanBePartiallyUpdatedWithoutExplicitKind() {
        Instant recordedAt = Instant.parse("2024-01-01T00:00:00Z");
        CatalogItem video = video(1L, "Javier", new VideoMetadata("Clip", "Description", recordedAt));
        when(this.movieRepository.findById(CatalogItemId.of(1L))).thenReturn(Mono.just(video));
        when(this.movieRepository.updateDetails(any(CatalogItem.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(this.useCase.execute(new CatalogActor("Javier", java.util.Set.of()), CatalogItemId.of(1L), new UpdateCatalogItemCommand(
                        "Edited clip", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null)))
                .assertNext(updated -> {
                    assertThat(updated.getKind()).isEqualTo(CatalogItemKind.VIDEO);
                    assertThat(updated.getVideoMetadata().description()).isEqualTo("Description");
                    assertThat(updated.getVideoMetadata().recordedAt()).isEqualTo(recordedAt);
                })
                .verifyComplete();
    }

    @Test
    void explicitVideoKindUsesVideoMerge() {
        VideoMetadata merged = UpdateCatalogItemUseCase.merge(
                new VideoMetadata("Clip", "Description", null),
                new UpdateCatalogItemCommand(null, null, null, null, null, null, null,
                        "Edited description", null, null, null, null, null, null,
                        CatalogItemKind.VIDEO));

        assertThat(merged.title()).isEqualTo("Clip");
        assertThat(merged.description()).isEqualTo("Edited description");
    }

    @Test
    void switchingToOtherClearsProviderMetadata() {
        CatalogItem movie = movie(1L, "Javier", METADATA);

        when(this.movieRepository.findById(CatalogItemId.of(1L))).thenReturn(Mono.just(movie));
        when(this.movieRepository.updateDetails(any(CatalogItem.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(this.useCase.execute(new CatalogActor("Javier", java.util.Set.of()), CatalogItemId.of(1L), new UpdateCatalogItemCommand(
                        "Mi grabacion", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, CatalogItemKind.VIDEO)))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(this.movieRepository).updateDetails(captor.capture());
        CatalogItem reclassified = captor.getValue();
        assertThat(reclassified.getKind()).isEqualTo(CatalogItemKind.VIDEO);
        assertThat(reclassified.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.RAW);
         assertThat(reclassified.getMetadata().title()).isEqualTo("Mi grabacion");
         assertThat(reclassified.getMetadata()).isInstanceOf(VideoMetadata.class);
    }

    @Test
    void switchingFromOtherCreatesRawUnlinkedMovie() {
        CatalogItem other = new CatalogItem(
                CatalogItemId.of(1L), "Javier", "Imported clip", CatalogItemStatus.READY,
                 EnrichmentStatus.RAW, null, new VideoMetadata("Imported clip", null, null),
                Visibility.PRIVATE, java.util.Set.of(), CatalogItemKind.VIDEO);

        when(this.movieRepository.findById(CatalogItemId.of(1L))).thenReturn(Mono.just(other));
        when(this.movieRepository.updateDetails(any(CatalogItem.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(this.useCase.execute(new CatalogActor("Javier", java.util.Set.of()), CatalogItemId.of(1L), new UpdateCatalogItemCommand(
                        "Identifiable movie", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, CatalogItemKind.MOVIE)))
                .assertNext(updated -> {
                    assertThat(updated.getKind()).isEqualTo(CatalogItemKind.MOVIE);
                    assertThat(updated.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.RAW);
                    assertThat(updated.getMetadata().title()).isEqualTo("Identifiable movie");
                     assertThat(updated.getMovieMetadata().tmdbId()).isNull();
                })
                .verifyComplete();

        verify(this.movieRepository).updateDetails(any(CatalogItem.class));
    }

    @Test
    void nonOwnerIsDenied() {
        CatalogItem movie = movie(1L, "Javier", METADATA);

        when(this.movieRepository.findById(CatalogItemId.of(1L))).thenReturn(Mono.just(movie));

        StepVerifier.create(this.useCase.execute(new CatalogActor("Maria", java.util.Set.of()), CatalogItemId.of(1L), new UpdateCatalogItemCommand(
                        "Otro", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null)))
                .expectError(CatalogItemAccessDeniedException.class)
                .verify();

        verify(this.movieRepository, never()).updateDetails(any(CatalogItem.class));
    }

    @Test
    void adminCanModerateMoviesOfOthers() {
        CatalogItem movie = movie(1L, "Javier", METADATA);

        when(this.movieRepository.findById(CatalogItemId.of(1L))).thenReturn(Mono.just(movie));
        when(this.movieRepository.updateDetails(any(CatalogItem.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(this.useCase.execute(new CatalogActor("Admin", java.util.Set.of("ROLE_ADMIN")), CatalogItemId.of(1L), new UpdateCatalogItemCommand(
                        "Moderado", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null)))
                .expectNextCount(1)
                .verifyComplete();

        verify(this.movieRepository).updateDetails(any(CatalogItem.class));
    }

    @Test
    void adminWithoutRoleAuthorityIsJustAnotherNonOwner() {
        CatalogItem movie = movie(1L, "Javier", METADATA);

        // Username "Admin" sin el rol: la política la decide el token, no el nombre.
        when(this.movieRepository.findById(CatalogItemId.of(1L))).thenReturn(Mono.just(movie));

        StepVerifier.create(this.useCase.execute(new CatalogActor("Admin", java.util.Set.of()), CatalogItemId.of(1L), new UpdateCatalogItemCommand(
                        "X", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null)))
                .expectError(CatalogItemAccessDeniedException.class)
                .verify();
    }

    @Test
    void missingMovieIsDeniedWithoutRevealingExistence() {
        when(this.movieRepository.findById(CatalogItemId.of(99L))).thenReturn(Mono.empty());

        StepVerifier.create(this.useCase.execute(new CatalogActor("Javier", java.util.Set.of()), CatalogItemId.of(99L), new UpdateCatalogItemCommand(
                        "X", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null)))
                .expectError(CatalogItemAccessDeniedException.class)
                .verify();

        verify(this.movieRepository, never()).updateDetails(any(CatalogItem.class));
    }
}
