package com.gcorp.service.app.mvflix_media_ingestion.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import com.gcorp.mvflix.security.webflux.MvflixAccessDeniedHandler;
import com.gcorp.mvflix.security.webflux.MvflixJwtAuthenticationConverter;
import com.gcorp.mvflix.security.webflux.MvflixUnauthorizedHandler;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
  @Bean
  ReactiveJwtDecoder jwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
    return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
  }

  @Bean
  SecurityWebFilterChain security(ServerHttpSecurity h,
      MvflixJwtAuthenticationConverter converter,
      MvflixUnauthorizedHandler unauthorized,
      MvflixAccessDeniedHandler denied) {
    return h.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(denied))
        .authorizeExchange(
            e -> e.pathMatchers("/actuator/**").permitAll().anyExchange().authenticated())
        .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
        .build();
  }
}
