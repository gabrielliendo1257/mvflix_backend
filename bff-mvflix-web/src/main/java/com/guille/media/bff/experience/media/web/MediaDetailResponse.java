package com.guille.media.bff.experience.media.web;

import com.guille.media.bff.experience.media.application.MediaDetail;

import java.util.List;

/** Contrato HTTP del detalle; grupos estables para la vista profunda. */
public record MediaDetailResponse(
    OverviewResponse overview,
    MediaResponse media,
    AccessResponse access,
    ProviderResponse provider,
    CapabilitiesResponse capabilities) {

  public static MediaDetailResponse from(MediaDetail detail) {
    return new MediaDetailResponse(
        new OverviewResponse(
            detail.overview().title(), detail.overview().originalTitle(),
            detail.overview().year(), detail.overview().duration(),
             detail.overview().posterUrl(), detail.overview().overview(),
             detail.overview().genres(), detail.overview().director(),
             detail.overview().cast(), detail.overview().releaseDate(),
             detail.overview().country(), detail.overview().language(),
             detail.overview().awards(), detail.overview().popularity()),
        new MediaResponse(
            detail.media().mediaId(), detail.media().status(),
            detail.media().displayStatus(), detail.media().kind(),
            detail.media().visibility()),
        new AccessResponse(
            detail.access().source(), detail.access().assetId(),
            detail.access().assetPresent()),
        new ProviderResponse(
            detail.provider().status(), detail.provider().providerId()),
        CapabilitiesResponse.from(detail.capabilities()));
  }

  public record OverviewResponse(
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

  public record MediaResponse(
      Long mediaId,
      String status,
      String displayStatus,
      String kind,
      String visibility) {}

  public record AccessResponse(String source, Long assetId, Boolean assetPresent) {}

  public record ProviderResponse(String status, Long providerId) {}

  public record CapabilitiesResponse(
      boolean play,
      boolean viewDetail,
      boolean editMetadata,
      boolean changeVisibility,
      boolean manageSharing,
      boolean linkProvider,
      boolean unlinkProvider,
      boolean identify,
      boolean delete) {

    static CapabilitiesResponse from(MediaDetail.Capabilities caps) {
      return new CapabilitiesResponse(
          caps.play(), caps.viewDetail(), caps.editMetadata(),
          caps.changeVisibility(), caps.manageSharing(),
          caps.linkProvider(), caps.unlinkProvider(),
          caps.identify(), caps.delete());
    }
  }
}
