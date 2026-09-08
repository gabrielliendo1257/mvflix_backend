package com.gcorp.mvflix.security.webflux;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public final class MvflixAccessDeniedHandler implements ServerAccessDeniedHandler {
  @Override
  public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException exception) {
    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory()
        .wrap("{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8))));
  }
}
