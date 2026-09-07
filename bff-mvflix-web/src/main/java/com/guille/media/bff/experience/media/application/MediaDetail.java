package com.guille.media.bff.experience.media.application;

import java.util.List;

/**
 * Detalle de una media para la vista de administración/profundidad.
 *
 * <p>Read model puro: la derivación operacional (displayStatus, source,
 * capabilities) se calcula UNA vez en {@link #from(Source)} con las mismas
 * reglas que la proyección del catálogo, pero aquí sobre los datos del
 * detalle. NO incluye URL de reproducción: iniciar playback es la
 * experiencia POST /web/playback/{mediaId}/session.
 */
public record MediaDetail(
    Overview overview,
    Media media,
    Access access,
    Provider provider,
    Capabilities capabilities) {

  /** Entrada normalizada que el adapter arma desde las lecturas de movies. */
  public record Source(
      long mediaId,
      String title,
      String originalTitle,
      Integer year,
      String duration,
      String posterPath,
      String overviewText,
       List<String> genres,
       String director,
       List<String> cast,
       String releaseDate,
       String country,
       String language,
       List<String> awards,
       Double popularity,
       String kind,
      String visibility,
      String domainStatus,
      Long objectId,
      Long assetId,
       Boolean assetPresent,
       Long tmdbId) {
    public Source(long mediaId, String title, String originalTitle, Integer year,
        String duration, String posterPath, String overviewText, List<String> genres,
        String director, List<String> cast, String kind, String visibility,
        String domainStatus, Long objectId, Long assetId, Boolean assetPresent,
        Long tmdbId) {
      this(mediaId, title, originalTitle, year, duration, posterPath, overviewText,
          genres, director, cast, null, null, null, null, null, kind, visibility,
          domainStatus, objectId, assetId, assetPresent, tmdbId);
    }
  }

  public static MediaDetail from(Source s) {
    boolean managed = s.objectId() != null;
    boolean local = s.assetId() != null;
    boolean localReady = local && Boolean.TRUE.equals(s.assetPresent());
    boolean dual = managed && local;

    String displayStatus = dual ? "ATTENTION"
        : !managed && local && !localReady ? "MISSING"
        : "DRAFT".equals(s.domainStatus()) ? "PROCESSING"
        : (managed || localReady) ? "READY"
        : "ATTENTION";

    String source = dual ? "INVALID"
        : managed ? "MANAGED"
        : local ? "LOCAL"
        : "NONE";

    boolean playable = "READY".equals(s.domainStatus())
        && !dual
        && (managed || localReady);

    boolean invalid = "INVALID".equals(source);
    boolean missing = "MISSING".equals(displayStatus);
    boolean deleting = "DELETING".equals(s.domainStatus());
    boolean movie = "MOVIE".equals(s.kind());
    boolean linked = s.tmdbId() != null;

    return new MediaDetail(
         new Overview(s.title(), s.originalTitle(), s.year(), s.duration(),
             s.posterPath(), s.overviewText(), s.genres(), s.director(), s.cast(),
             s.releaseDate(), s.country(), s.language(), s.awards(), s.popularity()),
        new Media(s.mediaId(), s.domainStatus(), displayStatus, s.kind(), s.visibility()),
        new Access(source, s.assetId(), local ? s.assetPresent() : null),
        new Provider(linked ? "LINKED" : "NONE", s.tmdbId()),
        new Capabilities(
            playable,
            true,
            true,
            true,
            true,
            movie && !linked,
            movie && linked,
            false,
            !deleting && !invalid && !missing));
  }

  public record Overview(
      String title,
      String originalTitle,
      Integer year,
      String duration,
      String posterUrl,
      String overview,
       List<String> genres,
       String director,
       List<String> cast,
       String releaseDate,
       String country,
       String language,
       List<String> awards,
       Double popularity) {}

  public record Media(
      Long mediaId,
      String status,
      String displayStatus,
      String kind,
      String visibility) {}

  public record Access(String source, Long assetId, Boolean assetPresent) {}

  public record Provider(String status, Long providerId) {}

  public record Capabilities(
      boolean play,
      boolean viewDetail,
      boolean editMetadata,
      boolean changeVisibility,
      boolean manageSharing,
      boolean linkProvider,
      boolean unlinkProvider,
      boolean identify,
      boolean delete) {}
}
