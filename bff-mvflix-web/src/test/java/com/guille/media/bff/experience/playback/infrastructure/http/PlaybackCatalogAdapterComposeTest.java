package com.guille.media.bff.experience.playback.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.guille.media.bff.app.dto.MediaAssetDto;
import com.guille.media.bff.app.dto.MovieDto;
import com.guille.media.bff.experience.playback.application.PlaybackContractViolationException;
import com.guille.media.bff.experience.playback.application.port.PlaybackCatalog;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * La composición del locator es la parte delicada: aquí se garantiza que
 * MANAGED y LOCAL son mutuamente excluyentes y que una movie MANAGED no exige
 * MediaAsset de catálogo (los objetos subidos no generan media_assets).
 */
class PlaybackCatalogAdapterComposeTest {

  private static final long MEDIA = 42L;

  private static MovieDto movie(Long objectId) {
    return new MovieDto(MEDIA, "READY", objectId, "PRIVATE", "MOVIE",
        "Edward Scissorhands", null, 1990, List.of(), null, null, null,
        List.of(), null, null, null, null, null, null, null);
  }

  private static final MediaAssetDto NO_ASSET =
      new MediaAssetDto(null, null, null, 0, null, null, null);

  private static MediaAssetDto libraryAsset() {
    return new MediaAssetDto(5L, 3L, "Movies/edward.mkv", 2048L,
        "video/x-matroska", "IDENTIFIED", MEDIA);
  }

  @Test
  void managedMovieNeedsNoCatalogAsset() {
    var result = PlaybackCatalogAdapter.compose(MEDIA, movie(77L), NO_ASSET);

    assertThat(result.movie().objectId()).isEqualTo(77L);
    assertThat(result.asset()).isNull();
  }

  @Test
  void managedMovieAcceptsNullAssetFromHttpContract() {
    var result = PlaybackCatalogAdapter.compose(MEDIA, movie(77L), null);

    assertThat(result.movie().objectId()).isEqualTo(77L);
    assertThat(result.asset()).isNull();
  }

  @Test
  void libraryAssetWithoutObjectIsLocal() {
    var result = PlaybackCatalogAdapter.compose(MEDIA, movie(null), libraryAsset());

    assertThat(result.asset()).isNotNull();
    assertThat(result.asset().isManaged()).isFalse();
    assertThat(result.asset().libraryId()).isEqualTo(3L);
    assertThat(result.asset().relativePath()).isEqualTo("Movies/edward.mkv");
  }

  @Test
  void readyWithoutAnyLocatorLeavesAssetNullForUseCaseToReject() {
    var result = PlaybackCatalogAdapter.compose(MEDIA, movie(null), NO_ASSET);

    assertThat(result.movie().objectId()).isNull();
    assertThat(result.asset()).isNull();
  }

  @Test
  void bothLocatorsAtOnceIsContractViolationNotSilentPreference() {
    // Estado imposible hoy (flujos de ingesta disjuntos); si apareciera, el BFF
    // falla ruidoso en vez de elegir MANAGED por convención.
    assertThatThrownBy(() ->
        PlaybackCatalogAdapter.compose(MEDIA, movie(77L), libraryAsset()))
        .isInstanceOf(PlaybackContractViolationException.class)
        .hasMessageContaining("42");
  }

  @Test
  void playbackMovieCarriesViewFields() {
    var result = PlaybackCatalogAdapter.compose(
        MEDIA, movie(null), NO_ASSET);

    assertThat(result.movie()).isEqualTo(new PlaybackCatalog.PlaybackMovie(
        MEDIA, "Edward Scissorhands", "READY", null, null, null));
  }
}
