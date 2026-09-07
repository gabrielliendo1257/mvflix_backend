package com.gcorp.service.app.mvflix_playback.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gcorp.service.app.mvflix_playback.application.port.AuthorizedCatalog;
import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.PlayableCatalogItem;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class MoviesAuthorizedCatalogAdapter implements AuthorizedCatalog {
  private final WebClient client;

  public MoviesAuthorizedCatalogAdapter(WebClient.Builder builder,
      @Value("${services.movies.url:http://localhost:4040}") String moviesUrl) {
    this.client = builder.baseUrl(moviesUrl).build();
  }

  @Override
  public Mono<PlayableCatalogItem> getPlayableItem(CatalogItemId id, String bearerToken) {
    return client.get()
        .uri("/api/v1/movies/{id}/playback-context", id.value())
        .header(HttpHeaders.AUTHORIZATION, bearerToken)
        .retrieve()
        .bodyToMono(DownstreamItem.class)
        .map(item -> new PlayableCatalogItem(id, item.title(), item.posterPath(), item.duration(),
            item.objectId(), item.optionalAsset().map(asset -> new PlayableCatalogItem.PlayableAsset(
                asset.id(), asset.libraryId(), asset.relativePath(), asset.size(), asset.mimeType()))
                .orElse(null)));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record DownstreamItem(long id, String title, String posterPath, String duration, Long objectId,
      DownstreamAsset asset) {
    Optional<DownstreamAsset> optionalAsset() {
      return Optional.ofNullable(asset);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record DownstreamAsset(long id, long libraryId, String relativePath, long size, String mimeType) {}
}
