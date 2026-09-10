package com.guille.media.bff.infrastructure.http;

import com.guille.media.bff.app.dto.MovieDto;
import com.guille.media.bff.app.ports.PublicCatalogWebClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class PublicCatalogWebClientAdapter implements PublicCatalogWebClient {
  private static final String API = "/api/v1/catalog/public";
  private final WebClient client;

  public PublicCatalogWebClientAdapter(@Qualifier("publicCatalogWebClient") WebClient client) {
    this.client = client;
  }

  @Override
  public Flux<MovieDto> list(int limit) {
    return client.get().uri(uri -> uri.path(API).queryParam("limit", limit).build())
        .retrieve().bodyToFlux(MovieDto.class);
  }

  @Override
  public Mono<MovieDto> find(long id) {
    return client.get().uri(API + "/" + id).retrieve().bodyToMono(MovieDto.class);
  }
}
