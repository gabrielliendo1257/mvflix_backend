package com.guille.media.bff.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import com.gcorp.mvflix.security.webflux.MvflixJwtAuthenticationConverter;

/**
 * Seguridad del BFF: patrón oauth2-client (el navegador nunca ve tokens).
 *
 * <p>Sin sesión: si el cliente espera HTML (navegador) se redirige al authorize del IdP; para el
 * resto (API/curl/Postman) se responde 401 JSON para que el front arranque el login.
 */
@Configuration
@Profile("!sandbox")
@EnableWebFluxSecurity
public class WebSecurityConfig {

  private static final String AUTHORIZATION_URI = "/oauth2/authorization/movie-app";

  @Value("${services.authorization.url}")
  private String authorizationUrl;

  @Value("${security.oauth2.jwk-set-uri}")
  private String jwkSetUriOverride;

  @Value("${frontend.url}")
  private String frontendUrl;

  @Value("${bff.cors.allowed-origins:}")
  private String extraAllowedOrigins;

  /**
   * Dev-token (Bearer) para Postman/curl en dev. En WebFlux no se pueden combinar
   * oauth2Login + oauth2ResourceServer en la misma cadena (el filtro Bearer nunca
   * llega a correr), asi que van en cadenas separadas: esta matchea cualquier
   * request con header Authorization y valida el JWT contra el auth-service.
   */
  @Bean
  @Order(1)
  @Profile("dev")
  SecurityWebFilterChain bearerDevSecurityWebFilterChain(ServerHttpSecurity http,
      MvflixJwtAuthenticationConverter converter) {
    return http.securityMatcher(this::hasAuthorizationHeader)
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .cors(Customizer.withDefaults())
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers("/web/session", "/web/shell", "/login/**",
                        "/oauth2/**", "/error",
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .pathMatchers(HttpMethod.OPTIONS, "/web/uploads")
                    .authenticated()
                    .pathMatchers(HttpMethod.GET, "/web/playback/assets/*/stream")
                    .permitAll()
                    .pathMatchers("/web/**")
                    .authenticated()
                    .anyExchange()
                    .denyAll())
        .oauth2ResourceServer(
            oauth2ResourceServer ->
                oauth2ResourceServer.jwt(
                    jwtSpec ->
                        jwtSpec
                            .jwkSetUri(this.resolveJwkSetUri())
                             .jwtAuthenticationConverter(converter)))
        .build();
  }

  @Bean
  @Order(2)
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http, ReactiveClientRegistrationRepository clientRegistrations) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .cors(Customizer.withDefaults())
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers("/web/session", "/web/shell", "/login/**",
                        "/oauth2/**", "/error",
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .pathMatchers(HttpMethod.GET, "/web/movies/*/stream")
                    .permitAll()
                    .pathMatchers(HttpMethod.GET, "/web/playback/assets/*/stream")
                    .permitAll()
                    .pathMatchers(HttpMethod.OPTIONS, "/web/uploads")
                    .authenticated()
                    .pathMatchers("/web/**")
                    .authenticated()
                    .anyExchange()
                    .denyAll())
        // Logout LOCAL: invalida la WebSession (y con ella los authorized
        // clients OAuth2 en sesión) y elimina la cookie __Host-SESSION.
        // DECISIÓN DOCUMENTADA: no se hace RP-initiated logout contra el
        // proveedor OIDC; su sesión persiste y un re-login puede ser
        // silencioso. Si el producto exige cerrar también la sesión del IdP,
        // se añadirá OidcClientInitiatedLogoutSuccessHandler.
        .logout(logout -> logout
            .logoutUrl("/web/logout")
            .logoutSuccessHandler((webFilterExchange, authentication) -> {
                var response = webFilterExchange.getExchange().getResponse();
                response.setStatusCode(org.springframework.http.HttpStatus.SEE_OTHER);
                response.getHeaders().setLocation(java.net.URI.create("/web/session"));
                return reactor.core.publisher.Mono.empty();
            }))
        // .exceptionHandling(handling -> handling.authenticationEntryPoint(delegatingEntryPoint()))
        .oauth2Login(
            oauth2 ->
                oauth2.authenticationSuccessHandler(
                    new RedirectServerAuthenticationSuccessHandler(this.frontendUrl + "/home")))
        .build();
  }

  private Mono<ServerWebExchangeMatcher.MatchResult> hasAuthorizationHeader(
      ServerWebExchange exchange) {
    boolean present = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null;
    return present
        ? ServerWebExchangeMatcher.MatchResult.match()
        : ServerWebExchangeMatcher.MatchResult.notMatch();
  }

  /** Navegador (acepta HTML) -> redirect al authorize del IdP; resto -> 401 JSON. */
  private ServerAuthenticationEntryPoint delegatingEntryPoint() {
    return (exchange, ex) -> {
      var accept = exchange.getRequest().getHeaders().getAccept();
      boolean wantsHtml =
          accept != null
              && accept.stream()
                  .anyMatch(
                      mediaType ->
                          "text".equalsIgnoreCase(mediaType.getType())
                              && "html".equalsIgnoreCase(mediaType.getSubtype()));
      if (wantsHtml) {
        return new RedirectServerAuthenticationEntryPoint(AUTHORIZATION_URI).commence(exchange, ex);
      }
      return new Json401EntryPoint().commence(exchange, ex);
    };
  }

  private String resolveJwkSetUri() {
    if (this.jwkSetUriOverride != null && !this.jwkSetUriOverride.isBlank()) {
      return this.jwkSetUriOverride;
    }
    return this.authorizationUrl + "/oauth2/jwks";
  }

  @Bean
  UrlBasedCorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(allowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Accept", "Content-Type"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  /** Origen del front + extras por config (LAN, dominio propio, ...). */
  private List<String> allowedOrigins() {
    List<String> origins = new ArrayList<>();
    origins.add(this.frontendUrl);
    if (this.extraAllowedOrigins != null && !this.extraAllowedOrigins.isBlank()) {
      Arrays.stream(this.extraAllowedOrigins.split(","))
          .map(String::trim)
          .filter(origin -> !origin.isEmpty())
          .forEach(origins::add);
    }
    return origins;
  }

  /** 401 en JSON para que el front distinga "sin sesión" y arranque el login. */
  private static final class Json401EntryPoint implements ServerAuthenticationEntryPoint {

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
      exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
      exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
      return exchange
          .getResponse()
          .writeWith(
              Mono.just(
                  exchange
                      .getResponse()
                      .bufferFactory()
                      .wrap("{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8))));
    }
  }
}
