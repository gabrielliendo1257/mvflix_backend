package com.gcorp.service.app.mvflix_playback.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.server.SecurityWebFilterChain;
import com.gcorp.mvflix.security.webflux.MvflixAccessDeniedHandler;
import com.gcorp.mvflix.security.webflux.MvflixJwtAuthenticationConverter;
import com.gcorp.mvflix.security.webflux.MvflixUnauthorizedHandler;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
  @Bean
  SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
      MvflixJwtAuthenticationConverter converter,
      MvflixUnauthorizedHandler unauthorized,
      MvflixAccessDeniedHandler denied) {
    return http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(denied))
        .authorizeExchange(exchange -> exchange
            .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
            .pathMatchers(HttpMethod.POST, "/api/v1/playback/**")
            .hasAuthority("SCOPE_playback.sessions.write")
            .anyExchange().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
        .build();
  }
}
