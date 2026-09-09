package com.gcorp.service.app.mvflix_movies.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.WebFilter;
import com.gcorp.mvflix.security.webflux.MvflixAccessDeniedHandler;
import com.gcorp.mvflix.security.webflux.MvflixJwtAuthenticationConverter;
import com.gcorp.mvflix.security.webflux.MvflixUnauthorizedHandler;

@Configuration
@Profile("!sandbox")
@EnableWebFluxSecurity
public class SecurityConfig {

  @Bean
  WebFilter serverWebExchangeContextFilter() {
    return (exchange, chain) ->
        chain
            .filter(exchange)
            .contextWrite(
                context -> context.put(InternalActorUserProvider.EXCHANGE_CONTEXT_KEY, exchange));
  }

  @Bean
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http,
      @Value("${security.oauth2.jwk-set-uri}") String jwkSetUri,
      MvflixJwtAuthenticationConverter jwtAuthenticationConverter,
      MvflixUnauthorizedHandler unauthorizedHandler,
      MvflixAccessDeniedHandler accessDeniedHandler) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(unauthorizedHandler)
                    .accessDeniedHandler(accessDeniedHandler))
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers("/error")
                    .permitAll()
                    .pathMatchers("/v3/api-docs/**", "/webjars/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .pathMatchers("/admin/outbox/**")
                    .hasRole("ADMIN")
                    .pathMatchers("/api/v1/movies/*/discard-draft")
                    .hasAuthority("SCOPE_media-ingestion")
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer.jwt(
                    jwt ->
                        jwt.jwkSetUri(jwkSetUri)
                            .jwtAuthenticationConverter(jwtAuthenticationConverter)))
        .build();
  }
}
