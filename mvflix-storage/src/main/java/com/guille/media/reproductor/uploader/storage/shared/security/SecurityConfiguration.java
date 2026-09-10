package com.guille.media.reproductor.uploader.storage.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.WebFilter;
import com.gcorp.mvflix.security.webflux.MvflixAccessDeniedHandler;
import com.gcorp.mvflix.security.webflux.MvflixJwtAuthenticationConverter;
import com.gcorp.mvflix.security.webflux.MvflixUnauthorizedHandler;

/**
 * Resource server configuration.
 *
 * <p>Valida los JWT emitidos por el authorization-service (endpoint {@code /oauth2/jwks} cuya URL
 * se resuelve desde {@code services.authorization.url}, red interna de docker-compose) y traduce el
 * claim {@code roles} a autoridades. El claim ya contiene el prefijo {@code ROLE_} (por ejemplo
 * {@code ROLE_ADMIN}), por lo que el converter no añade prefijo.
 */
@Configuration
@Profile("!sandbox")
@EnableWebFluxSecurity
public class SecurityConfiguration {

  @Value("${api.path.base}")
  private String apiPathBase;

  @Bean
  WebFilter serverWebExchangeContextFilter() {
    return (exchange, chain) ->
        chain.filter(exchange)
            .contextWrite(context -> context.put(InternalActorUserProvider.EXCHANGE_CONTEXT_KEY, exchange));
  }

  /**
   * Cadena para el webhook de MinIO: no firma JWT (envía el token como {@code Authorization: Bearer
   * <webhook-token>}, validado en el controller) y el filtro de resource server lo rechazaría con
   * 401. El matcher es el path EXACTO del webhook, no {@code /internal/**}: cualquier endpoint
   * interno futuro cae en la cadena JWT principal y su denyAll, nunca queda abierto por accidente.
   */
  @Bean
  @Order(0)
  SecurityWebFilterChain internalSecurityWebFilterChain(ServerHttpSecurity http) {
    http.securityMatcher(
            ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/internal/minio/events"))
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .authorizeExchange(authorize -> authorize.anyExchange().permitAll());
    return http.build();
  }

  @Bean
  SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
      @Value("${security.oauth2.jwk-set-uri:${services.authorization.url}/oauth2/jwks}") String jwkSetUri,
      MvflixJwtAuthenticationConverter jwtAuthenticationConverter,
      MvflixUnauthorizedHandler unauthorizedHandler,
      MvflixAccessDeniedHandler accessDeniedHandler) {
    http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint(unauthorizedHandler)
            .accessDeniedHandler(accessDeniedHandler))
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers("/ws")
                    .permitAll()
                    .pathMatchers(HttpMethod.POST, "/internal/minio/events")
                     .permitAll()
                    .pathMatchers(HttpMethod.POST, "/api/v1/uploads")
                    .authenticated()
                    .pathMatchers(HttpMethod.GET, "/api/v1/uploads/*/parts")
                    .authenticated()
                    .pathMatchers(HttpMethod.POST, "/api/v1/uploads/*/complete")
                    .authenticated()
                    .pathMatchers(HttpMethod.DELETE, "/api/v1/uploads/*")
                    .authenticated()
                    .pathMatchers(HttpMethod.POST, this.apiPathBase + "/storage/upload")
                    .authenticated()
                    .pathMatchers(HttpMethod.GET, this.apiPathBase + "/storage/users/*/quota")
                    .hasAuthority("SCOPE_storage.read")
                    .pathMatchers(HttpMethod.POST, this.apiPathBase + "/storage/upload/*/complete")
                    .authenticated()
                    .pathMatchers(
                        HttpMethod.POST, this.apiPathBase + "/storage/upload/*/instructions")
                    .authenticated()
                    .pathMatchers(HttpMethod.POST, this.apiPathBase + "/storage/streaming")
                    .authenticated()
                    // M2M playback del catálogo: movies/BFF validó visibilidad
                    // y pide bytes con scope dedicado (ver ADR 0002).
                    .pathMatchers(HttpMethod.POST, this.apiPathBase + "/storage/catalog/streaming")
                    .hasAuthority("SCOPE_storage.stream")
                    // M2M borrado de objetos MANAGED: movies valida la asociación
                    // (owner + objectKey) y storage la verifica antes de borrar.
                    .pathMatchers(HttpMethod.POST, this.apiPathBase + "/storage/objects/*/deletion")
                    .hasAuthority("SCOPE_storage.objects.delete")
                    .pathMatchers(HttpMethod.GET, this.apiPathBase + "/storage/playback/libraries/*/files/**")
                    .hasAuthority("SCOPE_storage.stream")
                    .pathMatchers(
                        HttpMethod.GET,
                        this.apiPathBase + "/storage/uploads",
                         this.apiPathBase + "/storage/upload/*",
                         this.apiPathBase + "/storage/uploads/by-idempotency/*",
                         this.apiPathBase + "/storage/quota")
                    .authenticated()
                    .pathMatchers(
                        HttpMethod.GET,
                        this.apiPathBase + "/storage/libraries",
                        this.apiPathBase + "/storage/libraries/*/files",
                        this.apiPathBase + "/storage/libraries/*/files/**")
                    .authenticated()
                    .pathMatchers(HttpMethod.POST, this.apiPathBase + "/storage/libraries")
                    .authenticated()
                    .pathMatchers(
                        HttpMethod.DELETE,
                        this.apiPathBase + "/storage/libraries/*",
                        this.apiPathBase + "/storage/libraries/*/scan")
                    .authenticated()
                    .pathMatchers(
                        HttpMethod.POST,
                        this.apiPathBase + "/storage/upload",
                        this.apiPathBase + "/storage/upload/*/cancel")
                    .authenticated()
                    // M2M: aprovisiona user_storage para un usuario (cuota la decide users,
                    // ver ADR 0001). Tras las reglas de libraries para no sombrearlas.
                    .pathMatchers(HttpMethod.POST, this.apiPathBase + "/storage/users/*/provision")
                    .hasAuthority("SCOPE_storage.write")
                    .pathMatchers(HttpMethod.DELETE, this.apiPathBase + "/storage/*")
                    .authenticated()
                    .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .pathMatchers("/admin/outbox/**")
                    .hasRole("ADMIN")
                    .anyExchange()
                    .denyAll())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer.jwt(
                    jwt ->
                        jwt.jwkSetUri(jwkSetUri)
                            .jwtAuthenticationConverter(jwtAuthenticationConverter)));

    return http.build();
  }

}
