package com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web;

import com.gcorp.service.app.mvflix_movies.catalog.application.PublicCatalogQuery;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItem;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemId;
import com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto.PublicCatalogItemResponse;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/v1/catalog/public", produces = MediaType.APPLICATION_JSON_VALUE)
public class PublicCatalogController {
  private final PublicCatalogQuery query;

  public PublicCatalogController(PublicCatalogQuery query) {
    this.query = query;
  }

  @GetMapping
  public Flux<PublicCatalogItemResponse> list(@RequestParam(defaultValue = "20") int limit) {
    return query.list(limit).map(PublicCatalogController::toResponse);
  }

  @GetMapping("/{id}")
  public Mono<PublicCatalogItemResponse> find(@PathVariable Long id) {
    return query.find(CatalogItemId.of(id)).map(PublicCatalogController::toResponse);
  }

  private static PublicCatalogItemResponse toResponse(CatalogItem item) {
    var metadata = item.getMovieMetadataOrNull();
    return new PublicCatalogItemResponse(
        item.getId().value(), item.getTitle(), metadata == null ? null : metadata.posterPath(),
        metadata == null ? null : metadata.year(), metadata == null ? null : metadata.duration(),
        metadata == null ? List.of() : metadata.genres(), item.getKind(), true);
  }
}
