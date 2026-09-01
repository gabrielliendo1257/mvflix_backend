package com.guille.media.bff.app.service;

import com.guille.media.bff.app.dto.BulkVisibilityRequest;
import com.guille.media.bff.app.dto.CompleteMovieRequest;
import com.guille.media.bff.app.dto.CreateMovieRequest;
import com.guille.media.bff.app.dto.MediaAssetDto;
import com.guille.media.bff.app.dto.MovieDetailDto;
import com.guille.media.bff.app.dto.MovieDetailDto.PlaybackDto;
import com.guille.media.bff.app.dto.MovieDto;
import com.guille.media.bff.app.dto.MovieEnrichmentPreviewDto;
import com.guille.media.bff.app.dto.MovieEnrichmentSearchDto;
import com.guille.media.bff.app.dto.MovieListItemDto;
import com.guille.media.bff.app.dto.MovieUpdateRequest;
import com.guille.media.bff.app.dto.StreamTicketDto;
import com.guille.media.bff.app.ports.MoviesWebClient;
import com.guille.media.bff.app.ports.StorageWebClient;
import com.guille.media.bff.app.ports.UsersWebPort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.beans.factory.annotation.Qualifier;
import com.guille.media.bff.infrastructure.http.StoragePlaybackTokenProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orquestador del flujo de alta de película. El front solo llama al complete; el BFF
 * verifica el estado real del storage (el objeto llega por webhook), clasifica el veredicto,
 * persiste la película y, si algo falla, hace rollback (película + objeto + cuota) y
 * registra penalidades cuando el caso lo amerita.
 */
@Slf4j
@Service
public class WebMoviesService {

  public WebMoviesService(
      MoviesWebClient moviesWebClient,
      StorageWebClient storageWebClient,
      UsersWebPort usersWebPort,
      StreamTicketService streamTicketService,
      JobStore jobStore,
      WebSessionService webSessionService,
      com.guille.media.bff.experience.addmedia.application.CompleteAddMedia addMediaCompletion,
      StoragePlaybackTokenProvider playbackTokenProvider,
      @org.springframework.beans.factory.annotation.Qualifier("playbackWebClient")
      WebClient playbackWebClient) {
    this.moviesWebClient = moviesWebClient;
    this.storageWebClient = storageWebClient;
    this.usersWebPort = usersWebPort;
    this.streamTicketService = streamTicketService;
    this.jobStore = jobStore;
    this.webSessionService = webSessionService;
    this.addMediaCompletion = addMediaCompletion;
    this.playbackTokenProvider = playbackTokenProvider;
    this.playbackWebClient = playbackWebClient;
  }


  private static final int PENDING_RETRIES = 3;

  /** Tamaño de lote interno del BFF hacia mvflix-movies (progreso por SSE). */
  private static final int BULK_CHUNK_SIZE = 25;

  /** Chunks de visibilidad en vuelo hacia mvflix-movies (velocidad sin saturar la DB). */
  private static final int BULK_PARALLELISM = 4;

  private final MoviesWebClient moviesWebClient;
  private final StorageWebClient storageWebClient;
  private final UsersWebPort usersWebPort;
  private final StreamTicketService streamTicketService;
  private final JobStore jobStore;
  private final WebSessionService webSessionService;
  private final com.guille.media.bff.experience.addmedia.application.CompleteAddMedia addMediaCompletion;
  private final StoragePlaybackTokenProvider playbackTokenProvider;
  private final @org.springframework.beans.factory.annotation.Qualifier("playbackWebClient")
      WebClient playbackWebClient;

  public Flux<MovieListItemDto> list(int limit) {
    return this.moviesWebClient
        .listMovies(limit)
        .map(movie -> new MovieListItemDto(
            movie.id(), movie.status(), movie.visibility(), movie.kind(), movie.title(),
            movie.year(), movie.posterPath()));
  }

  /**
   * Detalle orientado a la vista: metadata de movies + disponibilidad de reproducción
   * desde storage (URL firmada). Si storage no responde, la pantalla degrada a
   * {@code playback.available=false} en lugar de romper.
   */
  public Mono<MovieDetailDto> detail(Long movieId) {
    return this.moviesWebClient
        .movieById(movieId)
        .flatMap(movie -> this.playbackFor(movie)
            .map(playback -> new MovieDetailDto(movie, playback)));
  }

  private Mono<PlaybackDto> playbackFor(MovieDto movie) {
    if (movie.objectId() == null) {
      return this.localPlaybackFor(movie);
    }
    // PRIVATE: preview del dueño (user token; storage hace ensureOwnedBy).
    // PUBLIC/SHARED: playback M2M de catálogo (scope storage.stream); la
    // autorización fina la aplica Movies al servir el detalle.
    // PRIVATE: preview del dueño (user token; storage hace ensureOwnedBy).
    // PUBLIC/SHARED: M2M presigned (scope storage.stream) sin identidad de usuario.
    if ("PRIVATE".equals(movie.visibility())) {
      return this.storageWebClient
          .stream(String.valueOf(movie.objectId()))
          .map(session -> new PlaybackDto(true, session.streamingUrl()))
          .onErrorResume(error -> Mono.just(new PlaybackDto(false, null)));
    }
    return this.m2mPlayback(movie.objectId())
        .map(url -> new PlaybackDto(true, url))
        .doOnError(error -> log.warn(
            "detail: movie={} m2m playback no disponible: {}",
            movie.id(), error.getMessage()))
        .onErrorResume(error -> Mono.just(new PlaybackDto(false, null)));
  }

  /**
   * Presigned GET directo contra Storage usando el machine-client. Sin
   * sesión de usuario: el BFF ya validó visibilidad al servir el detalle.
   */
  private Mono<String> m2mPlayback(Long objectId) {
    return this.playbackTokenProvider.token().flatMap(token ->
        this.playbackWebClient.post()
            .uri("/api/v1/movie/storage/catalog/streaming")
            .headers(h -> h.setBearerAuth(token))
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(java.util.Map.of("objectId", String.valueOf(objectId)))
            .retrieve()
            .bodyToMono(com.guille.media.bff.app.dto.StreamingSessionDto.class)
            .map(session -> session.streamingUrl()));
  }

  /**
   * Playback LOCAL (movie de biblioteca del operador): el archivo vive en el
   * filesystem del storage y se sirve con Range via el proxy del BFF.
   * Sin asset asociado (p.ej. DRAFT sin media) degrada a no disponible.
   */
  private Mono<PlaybackDto> localPlaybackFor(MovieDto movie) {
    return this.moviesWebClient
        .assetByMovie(movie.id())
        .map(asset -> new PlaybackDto(true, "/web/movies/" + movie.id() + "/stream"))
        .doOnError(error -> log.warn(
            "detail: movie={} playback LOCAL no disponible: {}",
            movie.id(), error.getMessage()))
        .onErrorResume(error -> Mono.just(new PlaybackDto(false, null)));
  }

  /**
   * Proxy de stream LOCAL: resuelve el asset de la movie y delega en el
   * storage, pasando Range y devolviendo status/headers/cuerpo tal cual.
   *
   * @deprecated usar la entrega LOCAL de experience/playback.
   */
  @Deprecated
  public Mono<ResponseEntity<Flux<DataBuffer>>> stream(
      Long movieId, String rangeHeader, String ticket) {
    if (ticket == null || ticket.isBlank()) {
      return this.streamAs(movieId, rangeHeader, null);
    }
    return Mono.defer(() -> Mono.justOrEmpty(this.streamTicketService.resolve(ticket)))
        .flatMap(resolved -> {
          if (!resolved.movieId().equals(movieId)) {
            log.warn("stream: ticket de movie={} usado en movie={}", resolved.movieId(), movieId);
            return Mono.error(new StreamTicketException("Ticket de otra movie"));
          }
          return this.streamAs(movieId, rangeHeader, resolved.userJwt());
        });
  }

  /**
   * Stream bajo la identidad del JWT dado (ticket) o del contexto de seguridad
   * (Bearer o sesión). Con {@code userJwt} se reenvía ese JWT a los backends
   * metiéndolo en el contexto reactivo, donde el filtro outbound lo toma.
   */
  private Mono<ResponseEntity<Flux<DataBuffer>>> streamAs(
      Long movieId, String rangeHeader, String userJwt) {
    Mono<ResponseEntity<Flux<DataBuffer>>> stream =
        this.moviesWebClient
            .assetByMovie(movieId)
            .flatMap(asset -> this.storageWebClient.streamLibraryFile(
                asset.libraryId(), asset.relativePath(), rangeHeader))
            .switchIfEmpty(Mono.defer(() -> {
              log.warn("stream: movie={} sin asset de biblioteca", movieId);
              return Mono.just(ResponseEntity.notFound().build());
            }))
            .onErrorResume(error -> {
              if (error instanceof WebClientResponseException responseException) {
                if (responseException.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
                  log.warn("stream: movie={} acceso denegado (403)", movieId);
                  return Mono.error(responseException);
                }
                log.warn("stream: movie={} no disponible: {}", movieId, responseException.getMessage());
                return Mono.just(ResponseEntity.notFound().build());
              }
              log.warn("stream: movie={} no disponible: {}", movieId, error.getMessage());
              return Mono.just(ResponseEntity.notFound().build());
            });
    if (userJwt == null) {
      return stream;
    }
    return stream.contextWrite(ReactiveSecurityContextHolder.withAuthentication(
        new JwtAuthenticationToken(jwtFrom(userJwt))));
  }

  private static org.springframework.security.oauth2.jwt.Jwt jwtFrom(String userJwt) {
    return org.springframework.security.oauth2.jwt.Jwt.withTokenValue(userJwt)
        .header("alg", "RS256")
        .claim("sub", "ticket")
        .build();
  }

  /**
   * Emite un ticket de stream (para el {@code <video>} del front, que no puede
   * mandar el JWT en header). {@code userJwt} llega ya resuelto por el controller
   * (Bearer o access token de sesion); el ticket expira a los pocos minutos.
   *
   * @deprecated usar la experiencia playback: {@code POST /web/playback/{mediaId}/session}.
   */
  @Deprecated
  public Mono<StreamTicketDto> issueStreamTicket(Long movieId, String userJwt) {
    if (userJwt == null || userJwt.isBlank()) {
      return Mono.error(new StreamTicketException("Autenticación requerida"));
    }
    return this.moviesWebClient
        .movieById(movieId)
        .map(movie -> new StreamTicketDto(
            "/web/movies/" + movie.id() + "/stream?ticket="
                + this.streamTicketService.issue(movie.id(), userJwt)));
  }

  public Mono<List<MovieEnrichmentSearchDto>> search(String query, Integer year) {
    return this.moviesWebClient.searchCandidates(query, year).collectList();
  }

  /** Preview de un candidato sin persistir: el usuario confirma antes del enrich. */
  public Mono<MovieEnrichmentPreviewDto> preview(Long tmdbId) {
    return this.moviesWebClient.previewCandidate(tmdbId);
  }

  /** Autocompletado con el candidato elegido por el usuario. */
  public Mono<MovieDto> enrich(Long movieId, Long tmdbId) {
    return this.moviesWebClient.enrichMovie(movieId, tmdbId);
  }

  /** Desvincula la película del proveedor externo (vuelve a RAW). */
  public Mono<MovieDto> unlinkEnrichment(Long movieId) {
    return this.moviesWebClient.unlinkEnrichment(movieId);
  }

  /**
   * Cambia la visibilidad del catalogo (PUBLIC/PRIVATE/SHARED). El BFF orquesta: en
   * SHARED tambien reemplaza la lista de usuarios compartidos (una sola llamada
   * desde el front); en PUBLIC/PRIVATE usernames se ignora. Solo el dueno.
   */
  public Mono<MovieDto> visibility(Long movieId, String visibility, List<String> usernames) {
    if (visibility == null || visibility.isBlank()) {
      return Mono.error(new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "VISIBILITY_REQUIRED"));
    }
    // Atómico en movies: visibilidad + compartidos se escriben juntos.
    // La política de si SHARED exige usuarios vive en movies, no aquí.
    return this.moviesWebClient.updateMovieAccess(movieId, visibility, usernames);
  }

  /** Reemplaza la lista de usuarios compartidos; solo el dueño. */
  public Mono<MovieDto> shares(Long movieId, List<String> usernames) {
    return this.moviesWebClient.updateShares(movieId, usernames);
  }

  /**
   * Cambio de visibilidad en lote (selección del front y/o librerías enteras).
   * El trabajo corre en background por lotes de {@link #BULK_CHUNK_SIZE} y se
   * registra como un {@link Job} en {@link JobStore}: el POST responde YA con el
   * estado inicial y el progreso llega por SSE en /web/jobs/{id}/events.
   */
  public Mono<Job> bulkVisibility(BulkVisibilityRequest request) {
    String visibility = request.visibility();
    if (visibility == null || visibility.isBlank()) {
      return Mono.error(new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "VISIBILITY_REQUIRED"));
    }
    boolean shared = "SHARED".equals(visibility);
    if (shared && (request.usernames() == null || request.usernames().isEmpty())) {
      return Mono.error(new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "SHARED_REQUIRES_USERNAMES"));
    }
    String jobId = UUID.randomUUID().toString();
    return ReactiveSecurityContextHolder.getContext()
        .map(context -> {
          var auth = context.getAuthentication();
          return auth instanceof JwtAuthenticationToken jwtAuth
              ? jwtAuth.getToken().getTokenValue()
              : "";
        })
        .switchIfEmpty(Mono.just(""))
        .flatMap(token -> this.webSessionService
            .currentSubject()
            .defaultIfEmpty("anonymous")
            .map(owner -> {
              Job initial = this.jobStore.start(jobId, owner, JobType.BULK_VISIBILITY);
              this.resolveMovieIds(request)
                  .flatMap(ids -> this.runBulkJob(initial, ids, request, token))
                  .subscribe(
                      finalState -> log.info("bulkVisibility {} -> {} (total={}, ok={}, fail={})",
                          jobId, visibility, finalState.total(), finalState.done(),
                          finalState.failed()),
                      error -> {
                        log.error("bulkVisibility {} fallo: {}", jobId,
                            error.getMessage(), error);
                        this.jobStore.complete(jobId, initial.failed(0, 0, 0));
                      });
              return initial;
            }));
  }

  /**
   * Resuelve los ids pedidos: los directos + los assets identificados de las
   * librerías (para que el total y el progreso sean exactos desde el inicio).
   */
  private Mono<List<Long>> resolveMovieIds(BulkVisibilityRequest request) {
    Flux<Long> direct = request.movieIds() == null ? Flux.empty() : Flux.fromIterable(request.movieIds());
    Flux<Long> fromLibraries = request.libraryIds() == null
        ? Flux.empty()
        : Flux.fromIterable(request.libraryIds())
            .flatMap(libraryId -> this.moviesWebClient.listAssets(libraryId, null))
            .filter(asset -> asset.movieId() != null)
            .map(MediaAssetDto::movieId);
    return Flux.concat(direct, fromLibraries).distinct().collectList();
  }

  private Mono<Job> runBulkJob(
      Job initial, List<Long> movieIds, BulkVisibilityRequest request, String accessToken) {
    String jobId = initial.id();
    int total = movieIds.size();
    if (total == 0) {
      return Mono.defer(() -> {
        Job done = initial.completed(0, 0, 0);
        this.jobStore.complete(jobId, done);
        return Mono.just(done);
      });
    }
    AtomicInteger done = new AtomicInteger(0);
    AtomicInteger failed = new AtomicInteger(0);
    return Flux.fromIterable(chunk(movieIds, BULK_CHUNK_SIZE))
        .flatMap(chunk -> this.moviesWebClient
            .bulkUpdateVisibility(chunk, List.of(), request.visibility(), request.usernames(),
                accessToken)
            .doOnNext(result -> {
              done.addAndGet(result.updated());
              failed.addAndGet(result.failed());
              this.jobStore.update(jobId, initial.progress(total, done.get(), failed.get()));
            }), BULK_PARALLELISM)
        .then(Mono.defer(() -> {
          Job finalState = initial.completed(total, done.get(), failed.get());
          this.jobStore.complete(jobId, finalState);
          return Mono.just(finalState);
        }));
  }

  private static List<List<Long>> chunk(List<Long> ids, int size) {
    List<List<Long>> chunks = new ArrayList<>();
    for (int i = 0; i < ids.size(); i += size) {
      chunks.add(ids.subList(i, Math.min(ids.size(), i + size)));
    }
    return chunks;
  }

  /** Edición manual de la metadata (merge: null conserva el valor actual); solo el dueño. */
  public Mono<MovieDto> updateMovie(Long movieId, MovieUpdateRequest request) {
    return this.moviesWebClient.updateMovie(movieId, request);
  }

  /** Descarte de un draft creado por el flujo Add Media (compensación). */
  public Mono<Void> discardDraft(Long movieId) {
    return this.moviesWebClient.deleteMovie(movieId);
  }

  public Mono<MovieDto> create(CreateMovieRequest request) {
    return this.usersWebPort
        .me()
        .flatMap(profile -> {
          if (profile.blocked()) {
            log.warn("create bloqueado: usuario={} violaciones={}",
                profile.username(), profile.violations());
            return Mono.error(new com.guille.media.bff.experience.addmedia.application.UserBlockedException(
                profile.username() == null ? "?" : profile.username(),
                profile.violations()));
          }
          log.info("create: usuario={} title={}", profile.username(), request.title());
          return this.moviesWebClient.createMovie(request);
        });
  }

  /**
   * Complete orquestado del alta: delegado en {@link com.guille.media.bff.experience.addmedia.application.CompleteAddMedia}.
   */
  public Mono<com.guille.media.bff.experience.addmedia.application.UploadCompletionOutcome>
      complete(Long movieId, CompleteMovieRequest request) {
    return this.addMediaCompletion.complete(movieId, request);
  }


}
