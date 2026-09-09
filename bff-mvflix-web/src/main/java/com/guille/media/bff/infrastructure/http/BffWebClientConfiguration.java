package com.guille.media.bff.infrastructure.http;

import io.netty.channel.ChannelOption;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import com.guille.media.bff.app.ports.StorageWebClient;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Instant;

@Configuration
public class BffWebClientConfiguration {

  /**
   * Filtro que inyecta el access token de la sesión OAuth2 del navegador (guardado por
   * {@link ServerOAuth2AuthorizedClientRepository} en la sesión server-side del BFF).
   * Solo con el OAuth2-client activo (perfil no sandbox).
   */
  @Bean
  @Profile("!sandbox")
  ServerOAuth2AuthorizedClientExchangeFilterFunction oauth2AuthorizedClientFilter(
      ReactiveClientRegistrationRepository clientRegistrationRepository,
      ServerOAuth2AuthorizedClientRepository authorizedClientRepository) {
    ServerOAuth2AuthorizedClientExchangeFilterFunction filter =
        new ServerOAuth2AuthorizedClientExchangeFilterFunction(
            clientRegistrationRepository, authorizedClientRepository);
    filter.setDefaultClientRegistrationId("movie-app");
    return filter;
  }

  /** Provider de refresh token para renovar el access token de la sesión OAuth2. */
  @Bean
  @Profile("!sandbox")
  ReactiveOAuth2AuthorizedClientProvider oauth2RefreshTokenProvider() {
    return ReactiveOAuth2AuthorizedClientProviderBuilder.builder().refreshToken().build();
  }

  /**
   * Renueva el access token de la sesión antes de salir: el auth-server emite access
   * tokens de 7 min con refresh de 5 días, y sin renovación toda llamada del BFF con
   * sesión cookie muere a los 7 min (el <video> corta con 401 en el siguiente Range).
   * Corre antes del filtro de autorización: si renueva, guarda el nuevo authorized
   * client en la sesión y el siguiente filtro manda el Bearer ya fresco.
   */
  @Bean
  @Profile("!sandbox")
  ExchangeFilterFunction oauth2AccessTokenRefreshFilter(
      ServerOAuth2AuthorizedClientRepository authorizedClientRepository,
      ReactiveOAuth2AuthorizedClientProvider oauth2RefreshTokenProvider,
      @Value("${bff.oauth2.client-registration-id:movie-app}") String clientRegistrationId) {
    return (request, next) -> {
      ServerWebExchange exchange = request
          .attribute(ServerWebExchange.class.getName())
          .filter(ServerWebExchange.class::isInstance)
          .map(ServerWebExchange.class::cast)
          .orElse(null);
      if (exchange == null) {
        return next.exchange(request);
      }
      return ReactiveSecurityContextHolder.getContext()
          .flatMap(context -> {
            var auth = context.getAuthentication();
            if (auth == null) {
              return next.exchange(request);
            }
            return this.refreshIfNeeded(
                    clientRegistrationId, auth, exchange, oauth2RefreshTokenProvider,
                    authorizedClientRepository, request, next)
                .switchIfEmpty(Mono.defer(() -> next.exchange(request)));
          });
    };
  }

  private static Mono<ClientResponse> refreshIfNeeded(
      String clientRegistrationId,
      Authentication auth,
      ServerWebExchange exchange,
      ReactiveOAuth2AuthorizedClientProvider refreshTokenProvider,
      ServerOAuth2AuthorizedClientRepository authorizedClientRepository,
      ClientRequest request,
      ExchangeFunction next) {
    return authorizedClientRepository
        .loadAuthorizedClient(clientRegistrationId, auth, exchange)
        .flatMap(client -> {
          if (client.getAccessToken().getExpiresAt() == null
              || client.getAccessToken().getExpiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return next.exchange(request);
          }
          var context = org.springframework.security.oauth2.client.OAuth2AuthorizationContext
              .withAuthorizedClient(client)
              .principal(auth)
              .build();
          return refreshTokenProvider
              .authorize(context)
              .flatMap(refreshed -> authorizedClientRepository
                  .saveAuthorizedClient(refreshed, auth, exchange)
                  .then(Mono.defer(() -> next.exchange(request))));
        });
  }

  /** En sandbox no hay tokens: filtro no-op para que los WebClient sigan construyendose. */
  @Bean
  @Profile("sandbox")
  ServerOAuth2AuthorizedClientExchangeFilterFunction sandboxOauth2AuthorizedClientFilter() {
    return new ServerOAuth2AuthorizedClientExchangeFilterFunction(
        (clientRegistrationId) -> Mono.empty());
  }

  /**
   * Dev-token: filtro de salida que ramifica segun como se autentico la request entrante.
   * Con Bearer (JwtAuthenticationToken) reenvia ese mismo JWT a los servicios backend; con
   * sesion OAuth2 (navegador) delega en el filtro oauth2-client (token de sesion). Fuera de
   * dev no existe este bean: siempre manda el filtro de sesion.
   */
@Bean
  @Profile("dev")
  ExchangeFilterFunction devOutboundAuthFilter(
      ServerOAuth2AuthorizedClientExchangeFilterFunction oauth2AuthorizedClientFilter) {
    return (request, next) ->
        ReactiveSecurityContextHolder.getContext()
            .flatMap(
                context -> {
                  var auth = context.getAuthentication();
                  if (auth instanceof JwtAuthenticationToken jwtAuth) {
                    return next.exchange(
                        ClientRequest.from(request)
                            .headers(
                                headers ->
                                    headers.setBearerAuth(
                                        jwtAuth.getToken().getTokenValue()))
                            .build());
                  }
                  return oauth2AuthorizedClientFilter.filter(request, next);
                })
            .switchIfEmpty(
                Mono.defer(
                    () -> {
                      // Trabajo asíncrono (p. ej. bulk de visibilidad): el subscribe
                      // corre sin contexto de seguridad; el token ya viaja en el header
                      // del request (lo puso el adapter).
                      return next.exchange(request);
                    }));
  }

  @Bean
  WebClient usersWebClient(
      ServerOAuth2AuthorizedClientExchangeFilterFunction oauth2AuthorizedClientFilter,
      ObjectProvider<ExchangeFilterFunction> devOutboundAuthFilter,
      ObjectProvider<ExchangeFilterFunction> oauth2AccessTokenRefreshFilter,
      @Value("${services.users.url}") String usersUrl,
      @Value("${bff.webclient.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${bff.webclient.response-timeout-ms:10000}") long responseTimeoutMs) {
    return this.build(
        usersUrl,
        this.outboundAuthFilter(oauth2AuthorizedClientFilter, devOutboundAuthFilter, oauth2AccessTokenRefreshFilter),
        connectTimeoutMs,
        responseTimeoutMs);
  }

  @Bean
  WebClient storageWebClient(
      ServerOAuth2AuthorizedClientExchangeFilterFunction oauth2AuthorizedClientFilter,
      ObjectProvider<ExchangeFilterFunction> devOutboundAuthFilter,
      ObjectProvider<ExchangeFilterFunction> oauth2AccessTokenRefreshFilter,
      @Value("${services.storage.url}") String storageUrl,
      @Value("${bff.webclient.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${bff.webclient.response-timeout-ms:10000}") long responseTimeoutMs) {
    return this.build(
        storageUrl,
        this.outboundAuthFilter(oauth2AuthorizedClientFilter, devOutboundAuthFilter, oauth2AccessTokenRefreshFilter),
        connectTimeoutMs,
        responseTimeoutMs);
  }

  /** WebClient M2M con Bearer del machine-client movies-playback. */
  @Bean
  WebClient playbackWebClient(
      StoragePlaybackTokenProvider tokenProvider,
      @Value("${services.storage.url}") String storageUrl,
      @Value("${bff.webclient.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${bff.webclient.response-timeout-ms:10000}") long responseTimeoutMs) {
    var bearerFilter = org.springframework.web.reactive.function.client.ExchangeFilterFunction
        .ofRequestProcessor(request -> tokenProvider.token().map(token ->
            org.springframework.web.reactive.function.client.ClientRequest
                .from(request)
                .headers(h -> h.setBearerAuth(token))
                .build()));
    return this.build(storageUrl, bearerFilter, connectTimeoutMs, responseTimeoutMs);
  }

  @Bean
  WebClient playbackServiceWebClient(
      PlaybackServiceTokenProvider tokenProvider,
      @Value("${services.playback.url}") String playbackUrl,
      @Value("${bff.webclient.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${bff.webclient.response-timeout-ms:10000}") long responseTimeoutMs) {
    var bearerFilter = ExchangeFilterFunction.ofRequestProcessor(request ->
        tokenProvider.token().map(token -> ClientRequest.from(request)
            .headers(headers -> headers.setBearerAuth(token)).build()));
    return this.build(playbackUrl, bearerFilter, connectTimeoutMs, responseTimeoutMs);
  }

  @Bean
  WebClient moviesWebClient(
      ServerOAuth2AuthorizedClientExchangeFilterFunction oauth2AuthorizedClientFilter,
      ObjectProvider<ExchangeFilterFunction> devOutboundAuthFilter,
      ObjectProvider<ExchangeFilterFunction> oauth2AccessTokenRefreshFilter,
      @Value("${services.movies.url}") String moviesUrl,
      @Value("${bff.webclient.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${bff.webclient.response-timeout-ms:10000}") long responseTimeoutMs) {
    return this.build(
        moviesUrl,
        this.outboundAuthFilter(oauth2AuthorizedClientFilter, devOutboundAuthFilter, oauth2AccessTokenRefreshFilter),
        connectTimeoutMs,
        responseTimeoutMs);
  }

  @Bean
  WebClient playbackCatalogWebClient(
      PlaybackServiceTokenProvider tokenProvider,
      @Value("${services.movies.url}") String moviesUrl,
      @Value("${bff.webclient.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${bff.webclient.response-timeout-ms:10000}") long responseTimeoutMs) {
    var bearerFilter = ExchangeFilterFunction.ofRequestProcessor(request ->
        tokenProvider.token().map(token -> ClientRequest.from(request)
            .headers(headers -> headers.setBearerAuth(token)).build()));
    return this.build(moviesUrl, bearerFilter, connectTimeoutMs, responseTimeoutMs);
  }

  @Bean
  WebClient mediaIngestionWebClient(
      ServerOAuth2AuthorizedClientExchangeFilterFunction oauth2AuthorizedClientFilter,
      ObjectProvider<ExchangeFilterFunction> devOutboundAuthFilter,
      ObjectProvider<ExchangeFilterFunction> oauth2AccessTokenRefreshFilter,
      @Value("${services.media-ingestion.url:${MEDIA_INGESTION_URL:http://localhost:8080}}") String mediaIngestionUrl,
      @Value("${bff.webclient.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${bff.webclient.response-timeout-ms:10000}") long responseTimeoutMs) {
    return this.build(mediaIngestionUrl,
        this.outboundAuthFilter(oauth2AuthorizedClientFilter, devOutboundAuthFilter, oauth2AccessTokenRefreshFilter),
        connectTimeoutMs, responseTimeoutMs);
  }

  @Bean
  WebClient activityWebClient(
      ServerOAuth2AuthorizedClientExchangeFilterFunction oauth2AuthorizedClientFilter,
      ObjectProvider<ExchangeFilterFunction> devOutboundAuthFilter,
      ObjectProvider<ExchangeFilterFunction> oauth2AccessTokenRefreshFilter,
      @Value("${services.activity.url:http://localhost:7070}") String activityUrl,
      @Value("${bff.webclient.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${bff.webclient.response-timeout-ms:10000}") long responseTimeoutMs) {
    return this.build(activityUrl,
        this.outboundAuthFilter(oauth2AuthorizedClientFilter, devOutboundAuthFilter, oauth2AccessTokenRefreshFilter),
        connectTimeoutMs, responseTimeoutMs);
  }

  /**
   * En dev el filtro de salida es el compuesto (Bearer o sesion); fuera de dev no hay bean
   * ExchangeFilterFunction propio y manda siempre el filtro oauth2 de sesion. En ambos
   * casos el filtro de refresh corre primero (renueva el access token de sesion si expira).
   */
  private ExchangeFilterFunction outboundAuthFilter(
      ServerOAuth2AuthorizedClientExchangeFilterFunction oauth2AuthorizedClientFilter,
      ObjectProvider<ExchangeFilterFunction> devOutboundAuthFilter,
      ObjectProvider<ExchangeFilterFunction> oauth2AccessTokenRefreshFilter) {
    ExchangeFilterFunction dev = devOutboundAuthFilter.getIfAvailable();
    ExchangeFilterFunction auth = dev != null ? dev : oauth2AuthorizedClientFilter;
    ExchangeFilterFunction refresh = oauth2AccessTokenRefreshFilter.getIfAvailable();
    return refresh != null ? refresh.andThen(auth) : auth;
  }

  private WebClient build(
      String baseUrl,
      ExchangeFilterFunction outboundAuthFilter,
      int connectTimeoutMs,
      long responseTimeoutMs) {
    HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
            .responseTimeout(Duration.ofMillis(responseTimeoutMs));
    return WebClient.builder()
        .baseUrl(baseUrl)
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .filter(outboundAuthFilter)
        .build();
  }
}
