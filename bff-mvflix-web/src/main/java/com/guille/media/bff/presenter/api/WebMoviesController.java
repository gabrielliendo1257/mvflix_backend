package com.guille.media.bff.presenter.api;

import com.guille.media.bff.app.dto.BulkVisibilityRequest;
import com.guille.media.bff.app.dto.CreateMovieRequest;
import com.guille.media.bff.app.dto.MovieDetailDto;
import com.guille.media.bff.app.dto.MovieDto;
import com.guille.media.bff.app.dto.MovieEnrichmentPreviewDto;
import com.guille.media.bff.app.dto.MovieEnrichmentRequest;
import com.guille.media.bff.app.dto.MovieEnrichmentSearchDto;
import com.guille.media.bff.app.dto.MovieListItemDto;
import com.guille.media.bff.app.dto.MovieSharesRequest;
import com.guille.media.bff.app.dto.MovieUpdateRequest;
import com.guille.media.bff.app.dto.MovieVisibilityRequest;
import com.guille.media.bff.app.service.Job;
import com.guille.media.bff.app.service.WebMoviesService;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Tag(name = "Web · Movies", description = "Catálogo desde la experiencia web (endpoints legacy de alta marcados @Deprecated)")
@RestController
@RequestMapping("/web/movies")
public class WebMoviesController {

  private final WebMoviesService webMoviesService;

  public WebMoviesController(WebMoviesService webMoviesService) {
    this.webMoviesService = webMoviesService;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public Flux<MovieListItemDto> list(@RequestParam(defaultValue = "50") int limit) {
    return this.webMoviesService.publicList(limit);
  }

  @GetMapping(value = "/{movieId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<MovieDetailDto>> findById(@PathVariable Long movieId) {
    return this.webMoviesService.detailForViewer(movieId)
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @GetMapping(value = "/enrichment/search", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<List<MovieEnrichmentSearchDto>> search(
      @RequestParam String query, @RequestParam(required = false) Integer year) {
    return this.webMoviesService.search(query, year);
  }

  @GetMapping(value = "/enrichment/preview", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<MovieEnrichmentPreviewDto>> preview(@RequestParam("tmdb_id") Long tmdbId) {
    return this.webMoviesService.preview(tmdbId).map(ResponseEntity::ok);
  }

  /**
   * @deprecated usar {@code POST /web/media/{id}/provider} (experiencia media).
   *     Se conserva hasta que migre el front.
   */
  @Deprecated
  @PostMapping(
      value = "/{movieId}/enrichment",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<MovieDto>> enrich(
      @PathVariable Long movieId, @RequestBody MovieEnrichmentRequest request) {
    return this.webMoviesService.enrich(movieId, request.tmdbId()).map(ResponseEntity::ok);
  }

  /**
   * @deprecated usar {@code DELETE /web/media/{id}/provider} (experiencia media).
   *     Se conserva hasta que migre el front.
   */
  @Deprecated
  @DeleteMapping(
      value = "/{movieId}/enrichment",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<MovieDto>> unlinkEnrichment(@PathVariable Long movieId) {
    return this.webMoviesService.unlinkEnrichment(movieId).map(ResponseEntity::ok);
  }

  /**
   * @deprecated usar {@code PUT /web/media/{id}/access} (experiencia media,
   *     atómico en movies). Se conserva hasta que migre el front.
   */
  @Deprecated
  @PostMapping(
      value = "/{movieId}/visibility",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<MovieDto>> visibility(
      @PathVariable Long movieId, @RequestBody MovieVisibilityRequest request) {
    return this.webMoviesService.visibility(movieId, request.visibility(), request.usernames()).map(ResponseEntity::ok);
  }

  @PostMapping(
      value = "/{movieId}/shares",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<MovieDto>> shares(
      @PathVariable Long movieId, @RequestBody MovieSharesRequest request) {
    return this.webMoviesService.shares(movieId, request.usernames()).map(ResponseEntity::ok);
  }

  /**
   * @deprecated usar {@code POST /web/catalog/actions/change-visibility}
   *     (experiencia catalog). Se conserva hasta que migre el front.
   */
  @Deprecated
  @PostMapping(
      value = "/visibility",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<Job>> bulkVisibility(
      @RequestBody BulkVisibilityRequest request) {
    return this.webMoviesService
        .bulkVisibility(request)
        .map(job -> ResponseEntity.accepted().body(job));
  }

  /**
   * @deprecated Parte de la coreografía técnica del alta. Usar
   *             {@code POST /web/add-media}, que orquesta draft + upload con
   *             idempotencia y compensaciones.
   */
  @Deprecated
  @PostMapping(
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<MovieDto>> create(@RequestBody CreateMovieRequest request) {
    return this.webMoviesService.create(request).map(ResponseEntity::ok);
  }

  /**
   * Edición manual de la metadata (merge: null conserva el valor actual); solo el dueño.
   *
   * @deprecated usar {@code PUT /web/media/{id}/metadata} (experiencia media).
   *     Se conserva hasta que migre el front.
   */
  @Deprecated
  @PutMapping(
      value = "/{movieId}",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<MovieDto>> update(
      @PathVariable Long movieId, @RequestBody MovieUpdateRequest request) {
    return this.webMoviesService.updateMovie(movieId, request).map(ResponseEntity::ok);
  }
}
