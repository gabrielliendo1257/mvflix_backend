package com.guille.media.bff.app.ports;

import com.guille.media.bff.app.dto.MovieDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PublicCatalogWebClient {
  Flux<MovieDto> list(int limit);
  Mono<MovieDto> find(long id);
}
