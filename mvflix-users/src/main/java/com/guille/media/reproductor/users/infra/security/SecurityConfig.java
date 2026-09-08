package com.guille.media.reproductor.users.infra.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity.CsrfSpec;
import org.springframework.security.config.web.server.ServerHttpSecurity.HttpBasicSpec;
import org.springframework.security.web.server.SecurityWebFilterChain;
import com.gcorp.mvflix.security.webflux.MvflixAccessDeniedHandler;
import com.gcorp.mvflix.security.webflux.MvflixJwtAuthenticationConverter;
import com.gcorp.mvflix.security.webflux.MvflixUnauthorizedHandler;

@Configuration
@Profile("!sandbox")
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http,
            MvflixJwtAuthenticationConverter converter,
            MvflixUnauthorizedHandler unauthorized,
            MvflixAccessDeniedHandler denied) {
        http.csrf(CsrfSpec::disable)
                .httpBasic(HttpBasicSpec::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(denied))
                .oauth2ResourceServer(spec -> spec.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(converter)))
                .authorizeExchange(
                        authorizeSpec ->
                                authorizeSpec
                                        .pathMatchers(HttpMethod.POST, "/api/v1/users")
                                        .permitAll()
                                        .pathMatchers("/api/v1/users/me")
                                        .authenticated()
                                        .pathMatchers(HttpMethod.POST, "/api/v1/users/me/violations")
                                        .authenticated()
                                        .pathMatchers("/api/v1/users/*/plan")
                                         .hasAuthority("SCOPE_users.write")
                                         .pathMatchers("/api/v1/users/*/policy")
                                         .hasAuthority("SCOPE_media-ingestion")
                                        .anyExchange()
                                        .denyAll());

        return http.build();
    }
}
