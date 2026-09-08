package com.gcorp.service.app.mvflix_activity.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import com.gcorp.mvflix.security.webflux.MvflixAccessDeniedHandler;
import com.gcorp.mvflix.security.webflux.MvflixJwtAuthenticationConverter;
import com.gcorp.mvflix.security.webflux.MvflixUnauthorizedHandler;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
  @Bean SecurityWebFilterChain security(ServerHttpSecurity http,
      @Value("${security.oauth2.jwk-set-uri}") String jwk,
      MvflixJwtAuthenticationConverter converter,
      MvflixUnauthorizedHandler unauthorized,
      MvflixAccessDeniedHandler denied) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(denied))
        .authorizeExchange(e -> e.pathMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**")
            .permitAll().anyExchange().authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwkSetUri(jwk).jwtAuthenticationConverter(converter)))
        .build();
  }
}
