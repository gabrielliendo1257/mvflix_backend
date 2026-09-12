package com.guille.media.bff.experience.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.guille.media.bff.app.ports.MoviesWebClient;
import com.guille.media.bff.app.ports.StorageWebClient;
import com.guille.media.bff.app.ports.UsersWebPort;
import com.guille.media.bff.app.service.JobStore;
import com.guille.media.bff.app.service.StreamTicketService;
import com.guille.media.bff.app.service.WebMoviesService;
import com.guille.media.bff.app.service.WebSessionService;
import com.guille.media.bff.infrastructure.http.StoragePlaybackTokenProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Caracteriza el flujo de eliminación ACTUAL del BFF, antes de mover el botón
 * Delete a experience/media. La vía de borrado hoy es una única llamada a
 * movies (catalog-only); storage NO participa.
 *
 * <p>Hallazgos que fija esta caracterización (deuda para la futura
 * {@code DeleteMedia}):
 *
 * <ol>
 *   <li>MANAGED: el objeto en MinIO y la reserva de cuota quedan HUÉRFANOS:
 *       ni el BFF ni movies limpian storage. Falta compensación durable.</li>
 *   <li>LOCAL: el asset queda desvinculado (UNIDENTIFIED) del lado movies;
 *       el archivo del operador no se toca.</li>
 *   <li>DRAFT: borrado limpio, sin media ni asset.</li>
 *   <li>Retry / ya eliminada: movies responde 404 y el BFF lo propaga; NO
 *       hay idempotencia (ningún 2xx ni no-op silencioso).</li>
 *   <li>Storage caído: hoy es invisible (no se llama a storage), lo que
 *       además significa que no existe disparador de compensación.</li>
 * </ol>
 */
class MediaDeletionWorkflowCharacterizationTest {

  private final MoviesWebClient movies = mock(MoviesWebClient.class);
  private final StorageWebClient storage = mock(StorageWebClient.class);

  private WebMoviesService service;

  @BeforeEach
  void setUp() {
    this.service = new WebMoviesService(
        this.movies,
        this.storage,
        mock(UsersWebPort.class),
        mock(StreamTicketService.class),
        mock(JobStore.class),
        mock(WebSessionService.class),
         mock(StoragePlaybackTokenProvider.class),
        mock(WebClient.class));
  }

  @Test
  void managedMovieDeletionLeavesTheStorageObjectOrphaned() {
    // El BFF solo conoce el movieId; discardDraft nunca consulta el objeto
    // ni su storageId. Borrar la entrada de catálogo NO limpia MinIO/cuota.
    when(this.movies.deleteMovie(42L)).thenReturn(Mono.empty());

    StepVerifier.create(this.service.discardDraft(42L)).verifyComplete();

    verify(this.movies).deleteMovie(42L);
    // GAP: el objeto MANAGED queda huérfano; no hay llamada de compensación.
    verify(this.storage, never()).deleteObject(org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void localMovieDeletionIsCatalogOnly() {
    when(this.movies.deleteMovie(42L)).thenReturn(Mono.empty());

    StepVerifier.create(this.service.discardDraft(42L)).verifyComplete();

    // La desvinculación del asset es asunto interno de movies; el BFF no la ve.
    verify(this.movies).deleteMovie(42L);
    verify(this.storage, never()).deleteObject(org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void draftWithoutContentDeletesCleanly() {
    when(this.movies.deleteMovie(42L)).thenReturn(Mono.empty());

    StepVerifier.create(this.service.discardDraft(42L)).verifyComplete();

    verify(this.movies).deleteMovie(42L);
  }

  @Test
  void retryAfterDeleteSurfacesDownstream404NotIdempotentNoOp() {
    when(this.movies.deleteMovie(42L))
        .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "MOVIE_NOT_FOUND")));

    StepVerifier.create(this.service.discardDraft(42L))
        .expectErrorSatisfies(error -> {
          assertThat(error).isInstanceOf(ResponseStatusException.class);
          assertThat(((ResponseStatusException) error).getStatusCode())
              .isEqualTo(HttpStatus.NOT_FOUND);
        })
        .verify();

    verify(this.movies).deleteMovie(42L);
  }

  @Test
  void alreadyDeletedMovieSurfacesDownstream404() {
    when(this.movies.deleteMovie(42L))
        .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "MOVIE_NOT_FOUND")));

    StepVerifier.create(this.service.discardDraft(42L))
        .expectErrorMatches(error -> error instanceof ResponseStatusException se
            && se.getStatusCode() == HttpStatus.NOT_FOUND)
        .verify();
  }

  @Test
  void storageDownIsInvisibleBecauseStorageIsNeverCalled() {
    // Hoy el borrado no depende de storage: si estuviera caído, el borrado
    // "triunfaría" igual y el huérfano persistiría sin disparador de limpieza.
    when(this.movies.deleteMovie(42L)).thenReturn(Mono.empty());

    StepVerifier.create(this.service.discardDraft(42L)).verifyComplete();

    verify(this.movies).deleteMovie(42L);
    verify(this.storage, never()).deleteObject(org.mockito.ArgumentMatchers.anyLong());
  }
}
