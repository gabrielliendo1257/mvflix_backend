package com.guille.media.bff.experience.playback.application.port;

import com.guille.media.bff.experience.playback.application.PlayableAsset;

import reactor.core.publisher.Mono;

/**
 * Contrato hacia el catálogo (mvflix-movies), dueño de la política de
 * visibilidad. La carga se ejecuta bajo la identidad del usuario: si movies no
 * la sirve, la experiencia no empieza.
 */
public interface PlaybackCatalog {

  /**
   * Media visible para el sujeto junto con su asset asociado (asset {@code null}
   * si aún no tiene contenido vinculado). El acceso MANAGED/LOCAL se expresa en
   * {@link PlayableAsset#objectId()}.
   */
  Mono<PlaybackMedia> loadVisibleMedia(long mediaId);

  default Mono<PlaybackMedia> loadVisibleMedia(long mediaId, String viewerId) {
    return loadVisibleMedia(mediaId);
  }

  /** Vista mínima del catálogo para la experiencia; nada de modelo interno de movies. */
  record PlaybackMedia(PlaybackMovie movie, PlayableAsset asset) {}

  record PlaybackMovie(
      long id,
      String title,
      String status,
      String posterPath,
      String duration,
      Long objectId) {}
}
