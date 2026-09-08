package com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web;

import com.gcorp.service.app.mvflix_movies.catalog.application.BulkVisibilityUseCase;
import com.gcorp.mvflix.security.webflux.AuthenticatedActor;
import com.gcorp.mvflix.security.webflux.CurrentActor;
import com.gcorp.service.app.mvflix_movies.catalog.application.CatalogActor;
import com.gcorp.service.app.mvflix_movies.catalog.application.CatalogQueryUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.application.CompleteCatalogItemUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.application.CreateCatalogItemCommand;
import com.gcorp.service.app.mvflix_movies.catalog.application.CreateCatalogItemUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.application.CreateIdentifiedDraftCommand;
import com.gcorp.service.app.mvflix_movies.catalog.application.CreateIdentifiedDraftUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.application.DeleteCatalogItemUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.application.DeletionOutcome;
import com.gcorp.service.app.mvflix_movies.catalog.application.DiscardDraftUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.application.EnrichCatalogItemUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.application.GetCatalogItemUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.application.GetAuthorizedPlaybackContext;
import com.gcorp.service.app.mvflix_movies.catalog.application.ListCatalogItemsUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.application.ManagedObjectIdLookup;
import com.gcorp.service.app.mvflix_movies.catalog.application.UpdateCatalogItemAccessUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.application.UpdateCatalogItemUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.application.UpdateSharesUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.application.UpdateVisibilityUseCase;
import com.gcorp.service.app.mvflix_movies.catalog.domain.access.Visibility;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemKind;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemVisibility;
import com.gcorp.service.app.mvflix_movies.catalog.domain.metadata.MovieMetadata;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.BulkVisibilityRequest;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.BulkVisibilityResponse;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.CatalogPageResponse;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.CompleteMovieRequest;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.CreateIdentifiedDraftRequest;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.CreateMovieRequest;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.EnrichMovieRequest;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.EnrichMovieSearchResponse;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.EnrichmentPreviewResponse;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.MovieResponse;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.UpdateMovieAccessRequest;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.UpdateMovieRequest;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.UpdateSharesRequest;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.UpdateVisibilityRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Tag(
    name = "Movies",
    description =
        "Catálogo: drafts (incl. identificados), READY, visibilidad, shares, enriquecimiento TMDB")
@RestController
@Validated
@RequestMapping(path = "/api/v1/movies", produces = MediaType.APPLICATION_JSON_VALUE)
public class MovieController {

  private final CreateCatalogItemUseCase createMovieUseCase;
  private final CreateIdentifiedDraftUseCase createIdentifiedDraftUseCase;
  private final GetCatalogItemUseCase getMovieUseCase;
  private final ListCatalogItemsUseCase listMoviesUseCase;
  private final CatalogQueryUseCase catalogQueryUseCase;
  private final UpdateVisibilityUseCase updateVisibilityUseCase;
  private final UpdateSharesUseCase updateSharesUseCase;
  private final UpdateCatalogItemAccessUseCase updateMovieAccessUseCase;
  private final BulkVisibilityUseCase bulkVisibilityUseCase;
  private final UpdateCatalogItemUseCase updateMovieUseCase;
  private final CompleteCatalogItemUseCase completeMovieUseCase;
  private final DeleteCatalogItemUseCase deleteMovieUseCase;
  private final EnrichCatalogItemUseCase enrichMovieUseCase;
  private final DiscardDraftUseCase discardDraftUseCase;
  private final GetAuthorizedPlaybackContext authorizedPlaybackContext;
  private final MovieApiMapper mapper;
  private final ManagedObjectIdLookup objectIdLookup;

  @org.springframework.beans.factory.annotation.Autowired
  public MovieController(
      CreateCatalogItemUseCase createMovieUseCase,
      CreateIdentifiedDraftUseCase createIdentifiedDraftUseCase,
      GetCatalogItemUseCase getMovieUseCase,
      ListCatalogItemsUseCase listMoviesUseCase,
      CatalogQueryUseCase catalogQueryUseCase,
      UpdateVisibilityUseCase updateVisibilityUseCase,
      UpdateCatalogItemAccessUseCase updateMovieAccessUseCase,
      UpdateSharesUseCase updateSharesUseCase,
      BulkVisibilityUseCase bulkVisibilityUseCase,
      UpdateCatalogItemUseCase updateMovieUseCase,
      CompleteCatalogItemUseCase completeMovieUseCase,
      DeleteCatalogItemUseCase deleteMovieUseCase,
      EnrichCatalogItemUseCase enrichMovieUseCase,
      MovieApiMapper mapper,
      ManagedObjectIdLookup objectIdLookup,
       DiscardDraftUseCase discardDraftUseCase,
       GetAuthorizedPlaybackContext authorizedPlaybackContext) {
    this.createMovieUseCase = createMovieUseCase;
    this.createIdentifiedDraftUseCase = createIdentifiedDraftUseCase;
    this.getMovieUseCase = getMovieUseCase;
    this.listMoviesUseCase = listMoviesUseCase;
    this.catalogQueryUseCase = catalogQueryUseCase;
    this.updateVisibilityUseCase = updateVisibilityUseCase;
    this.updateMovieAccessUseCase = updateMovieAccessUseCase;
    this.updateSharesUseCase = updateSharesUseCase;
    this.bulkVisibilityUseCase = bulkVisibilityUseCase;
    this.updateMovieUseCase = updateMovieUseCase;
    this.completeMovieUseCase = completeMovieUseCase;
    this.deleteMovieUseCase = deleteMovieUseCase;
    this.enrichMovieUseCase = enrichMovieUseCase;
    this.mapper = mapper;
    this.objectIdLookup = objectIdLookup;
    this.discardDraftUseCase = discardDraftUseCase;
    this.authorizedPlaybackContext = authorizedPlaybackContext;
  }

  public MovieController(
      CreateCatalogItemUseCase createMovieUseCase,
      CreateIdentifiedDraftUseCase createIdentifiedDraftUseCase,
      GetCatalogItemUseCase getMovieUseCase,
      ListCatalogItemsUseCase listMoviesUseCase,
      CatalogQueryUseCase catalogQueryUseCase,
      UpdateVisibilityUseCase updateVisibilityUseCase,
      UpdateCatalogItemAccessUseCase updateMovieAccessUseCase,
      UpdateSharesUseCase updateSharesUseCase,
      BulkVisibilityUseCase bulkVisibilityUseCase,
      UpdateCatalogItemUseCase updateMovieUseCase,
      CompleteCatalogItemUseCase completeMovieUseCase,
      DeleteCatalogItemUseCase deleteMovieUseCase,
      EnrichCatalogItemUseCase enrichMovieUseCase,
      MovieApiMapper mapper) {
    this(
        createMovieUseCase,
        createIdentifiedDraftUseCase,
        getMovieUseCase,
        listMoviesUseCase,
        catalogQueryUseCase,
        updateVisibilityUseCase,
        updateMovieAccessUseCase,
        updateSharesUseCase,
        bulkVisibilityUseCase,
        updateMovieUseCase,
        completeMovieUseCase,
        deleteMovieUseCase,
        enrichMovieUseCase,
        mapper,
        null,
        null,
        null);
  }

  /** Adds the legacy object_id only at the HTTP projection boundary. */
  private Mono<MovieResponse> response(
      com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem movie) {
    return this.objectIdLookup == null
        ? Mono.just(this.mapper.toResponse(movie))
        : this.objectIdLookup
            .findObjectId(movie.getId())
            .map(objectId -> this.mapper.toResponse(movie, objectId))
            .switchIfEmpty(Mono.fromSupplier(() -> this.mapper.toResponse(movie, null)));
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<MovieResponse> create(@Valid @RequestBody CreateMovieRequest request) {
    CatalogItemKind kind = request.kind() == null ? CatalogItemKind.MOVIE : request.kind();
    return this.createMovieUseCase
        .execute(new CreateCatalogItemCommand(this.mapper.toMetadata(request), kind))
        .flatMap(this::response);
  }

  /** Alta guiada (Add Media): draft identificado + acceso inicial, atómicos. */
  @PostMapping(value = "/identified-drafts", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Draft identificado con acceso inicial, aplicado atómicamente")
  public Mono<MovieResponse> createIdentifiedDraft(
      @Valid @RequestBody CreateIdentifiedDraftRequest request,
      @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey,
      @RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
    if (idempotencyKey != null && idempotencyKey.length() > 128) {
      return Mono.error(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
          "Idempotency-Key must be at most 128 characters"));
    }
    return this.createIdentifiedDraftUseCase
        .execute(this.toCommand(request, idempotencyKey, correlationId))
        .flatMap(this::response);
  }

  private CreateIdentifiedDraftCommand toCommand(CreateIdentifiedDraftRequest request,
      String idempotencyKey, UUID correlationId) {
    MovieMetadata metadata = this.mapper.toMetadata(request.draft());
    if (request.tmdbId() != null && request.draft().kind() != CatalogItemKind.VIDEO) {
      metadata = metadata.withTmdbId(request.tmdbId());
    }
    return new CreateIdentifiedDraftCommand(
        metadata,
        request.draft().kind(),
        request.visibility() == null ? null : Visibility.valueOf(request.visibility()),
        request.sharedWith() == null
            ? java.util.List.of()
            : java.util.List.copyOf(request.sharedWith()),
        idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey,
        correlationId);
  }

  @GetMapping("/{id}")
  public Mono<MovieResponse> findById(@PathVariable Long id) {
    return this.getMovieUseCase.execute(CatalogItemId.of(id)).flatMap(this::response);
  }

  @GetMapping("/{id}/playback-context")
  public Mono<com.gcorp.service.app.mvflix_movies.catalog.domain.item.PlayableCatalogItem>
      authorizedPlaybackContext(@PathVariable Long id) {
    return this.authorizedPlaybackContext.execute(CatalogItemId.of(id));
  }

  @PostMapping("/{id}/visibility")
  public Mono<MovieResponse> updateVisibility(
      @PathVariable Long id, @Valid @RequestBody UpdateVisibilityRequest request) {
    return this.updateVisibilityUseCase
        .execute(CatalogItemId.of(id), toVisibility(request.visibility()))
        .flatMap(this::response);
  }

  @PostMapping("/{id}/shares")
  public Mono<MovieResponse> updateShares(
      @PathVariable Long id, @Valid @RequestBody UpdateSharesRequest request) {
    return this.updateSharesUseCase
        .execute(CatalogItemId.of(id), request.usernames())
        .flatMap(this::response);
  }

  @PostMapping(value = "/visibility/bulk", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<BulkVisibilityResponse> bulkVisibility(
      @Valid @RequestBody BulkVisibilityRequest request) {
    List<CatalogItemId> movieIds =
        request.movieIds() == null
            ? List.of()
            : request.movieIds().stream().map(CatalogItemId::of).toList();
    List<Long> libraryIds = request.libraryIds() == null ? List.of() : request.libraryIds();
    return this.bulkVisibilityUseCase
        .execute(movieIds, libraryIds, toVisibility(request.visibility()), request.usernames())
        .map(
            result ->
                new BulkVisibilityResponse(result.total(), result.updated(), result.failed()));
  }

  /** Acceso completo (visibilidad + compartidos) en una transacción. */
  @org.springframework.web.bind.annotation.PutMapping(
      value = "/{id}/access",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
      consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  public Mono<MovieResponse> updateAccess(
      @PathVariable Long id, @Valid @RequestBody UpdateMovieAccessRequest request) {
    return this.updateMovieAccessUseCase
        .execute(CatalogItemId.of(id), toVisibility(request.visibility()), request.sharedWith())
        .flatMap(this::response);
  }

  @GetMapping
  public Flux<MovieResponse> list(
      @RequestParam(defaultValue = "visible") String scope,
      @RequestParam(defaultValue = "20") int limit) {
    return this.listMoviesUseCase.execute(scope, limit).flatMap(this::response);
  }

  /** Proyección owned paginada para la grilla de administración del BFF. */
  @org.springframework.web.bind.annotation.GetMapping("/catalog")
  public Mono<CatalogPageResponse> catalogPage(
      @RequestParam(name = "page", defaultValue = "0") Integer page,
      @RequestParam(name = "size", defaultValue = "25") Integer size,
      @RequestParam(name = "q", required = false) String search,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "sort", required = false) String sort,
      @RequestParam(name = "dir", required = false) String direction) {
    return this.catalogQueryUseCase
        .execute(page, size, search, status, sort, direction)
        .map(CatalogPageResponse::from);
  }

  @PostMapping(value = "/{id}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<MovieResponse> complete(
      @PathVariable Long id, @Valid @RequestBody CompleteMovieRequest request) {
    return this.completeMovieUseCase
        .execute(CatalogItemId.of(id), request.objectId(), request.objectKey())
        .flatMap(this::response);
  }

  @PostMapping("/{id}/discard-draft")
  public Mono<ResponseEntity<Void>> discardDraft(
      @PathVariable Long id,
      @RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
    UUID effectiveCorrelation =
        correlationId == null
            ? idempotencyKey == null
                ? UUID.randomUUID()
                : UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8))
            : correlationId;
    return discardDraftUseCase
        .execute(CatalogItemId.of(id), effectiveCorrelation)
        .thenReturn(ResponseEntity.noContent().build());
  }

  /** Edición manual de la metadata del dueño (merge: null conserva el valor actual). */
  @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<MovieResponse> update(
      @CurrentActor AuthenticatedActor principal,
      @PathVariable Long id, @Valid @RequestBody UpdateMovieRequest request) {
    return this.updateMovieUseCase
        .execute(new CatalogActor(principal.subject(), principal.authorities()),
            CatalogItemId.of(id), this.mapper.toCommand(request))
        .flatMap(this::response);
  }

  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
    return this.deleteMovieUseCase
        .execute(CatalogItemId.of(id))
        .map(
            outcome ->
                outcome instanceof DeletionOutcome.Pending
                    ? ResponseEntity.accepted().build()
                    : ResponseEntity.noContent().build());
  }

  @PostMapping(value = "/{id}/enrich", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<MovieResponse> enrich(
      @PathVariable Long id, @Valid @RequestBody(required = false) EnrichMovieRequest request) {
    return this.enrichMovieUseCase
        .enrichCurrentUser(CatalogItemId.of(id), request == null ? null : request.tmdbId())
        .flatMap(this::response);
  }

  @DeleteMapping("/{id}/enrich")
  public Mono<MovieResponse> unlinkEnrichment(@PathVariable Long id) {
    return this.enrichMovieUseCase.unlinkCurrentUser(CatalogItemId.of(id)).flatMap(this::response);
  }

  private static Visibility toVisibility(CatalogItemVisibility visibility) {
    return visibility == null ? null : visibility.toVisibility();
  }

  @GetMapping("/enrich/search")
  public Mono<List<EnrichMovieSearchResponse>> search(
      @RequestParam String query, @RequestParam(required = false) Integer year) {
    return this.enrichMovieUseCase
        .search(query, year)
        .map(results -> results.stream().map(this.mapper::toSearchResponse).toList());
  }

  @GetMapping("/enrich/preview")
  public Mono<EnrichmentPreviewResponse> preview(@RequestParam("tmdb_id") Long tmdbId) {
    return this.enrichMovieUseCase.preview(tmdbId).map(this.mapper::toPreviewResponse);
  }
}
