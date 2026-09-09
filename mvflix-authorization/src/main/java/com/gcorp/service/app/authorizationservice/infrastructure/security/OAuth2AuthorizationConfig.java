package com.gcorp.service.app.authorizationservice.infrastructure.security;

import com.gcorp.service.app.authorizationservice.infrastructure.security.props.OAuth2PropertiesConfig;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class OAuth2AuthorizationConfig {

  private final OAuth2PropertiesConfig oauth2PropertiesConfig;

  @Bean
  @Order(value = Ordered.HIGHEST_PRECEDENCE)
  SecurityFilterChain oauth2SecurityFilterChain(HttpSecurity http) throws Exception {
    OAuth2AuthorizationServerConfigurer oAuthorizationServerConfigurer =
        new OAuth2AuthorizationServerConfigurer();

    http.securityMatcher(oAuthorizationServerConfigurer.getEndpointsMatcher())
        .exceptionHandling(
            exceptionConfig ->
                exceptionConfig.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
        .with(
            oAuthorizationServerConfigurer,
            authorizationServer -> authorizationServer.oidc(Customizer.withDefaults()))
        .authorizeHttpRequests(authorizeConfig -> authorizeConfig.anyRequest().authenticated());

    return http.build();
  }

  @Bean
  RegisteredClientRepository registeredClientRepository(
      JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
    var repository = new JdbcRegisteredClientRepository(jdbcTemplate);

    if (repository.findByClientId(this.oauth2PropertiesConfig.getOauth2ClientId()) == null) {
      var movieFrontRegisteredClient =
          RegisteredClient.withId(this.oauth2PropertiesConfig.getRegistrationId())
              .clientId(this.oauth2PropertiesConfig.getOauth2ClientId())
              .clientSecret(
                  passwordEncoder.encode(this.oauth2PropertiesConfig.getFrontClientSecret()))
              .scope(OidcScopes.PROFILE)
              .scope(OidcScopes.OPENID)
              .scope("users.read")
              .scope("users.write")
              .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
              .redirectUri(this.oauth2PropertiesConfig.getOauth2Redirect())
              .postLogoutRedirectUri(this.oauth2PropertiesConfig.getOauth2LogOutRedirect())
              .tokenSettings(
                  TokenSettings.builder()
                      .reuseRefreshTokens(false)
                      .accessTokenTimeToLive(Duration.ofMinutes(7))
                      .refreshTokenTimeToLive(Duration.ofDays(5))
                      .build())
              .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
              .build();
      log.info("Front registered client id: {}", this.oauth2PropertiesConfig);
      repository.save(movieFrontRegisteredClient);
    }

    if (repository.findByClientId("storage-service") == null) {
      var storageServiceRegisteredClient =
          RegisteredClient.withId("storage-app")
              .clientId("storage-service")
              .clientSecret(
                  passwordEncoder.encode(this.oauth2PropertiesConfig.getFrontClientSecret()))
              .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
              .scope("users.read")
              .scope("users.write")
              .build();
      log.info("Front registered client id: {}", storageServiceRegisteredClient);
      repository.save(storageServiceRegisteredClient);
    }

    // Machine-client de playback: Movies/BFF lo usa para pedir bytes a
    // Storage (POST /catalog/streaming) tras validar visibilidad, con el
    // scope dedicado storage.stream. Client_credentials puro: sin usuario.
    if (repository.findByClientId("movies-playback") == null) {
      var playbackServiceRegisteredClient =
          RegisteredClient.withId("movies-playback-app")
              .clientId("movies-playback")
              .clientSecret(
                  passwordEncoder.encode(this.oauth2PropertiesConfig.getFrontClientSecret()))
              .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
              .scope("storage.stream")
              .build();
      log.info("Playback machine client registered: {}", playbackServiceRegisteredClient);
      repository.save(playbackServiceRegisteredClient);
    }

    if (repository.findByClientId("bff-playback") == null) {
      repository.save(
          RegisteredClient.withId("bff-playback-app")
              .clientId("bff-playback")
              .clientSecret(passwordEncoder.encode(this.oauth2PropertiesConfig.getFrontClientSecret()))
              .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
              .scope("playback.sessions.write")
              .build());
    }

    if (repository.findByClientId("bff-catalog-public") == null) {
      repository.save(
          RegisteredClient.withId("bff-catalog-public-app")
              .clientId("bff-catalog-public")
              .clientSecret(passwordEncoder.encode(
                  this.oauth2PropertiesConfig.getCatalogPublicSecret()))
              .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
              .scope("catalog.public.read")
              .build());
    }

    // Machine-client de limpieza MANAGED: Movies lo usa para borrar el
    // objeto (y su cuota) en Storage al eliminar una media. Scope dedicado
    // storage.objects.delete: NO reutiliza storage.stream (eliminar ≠
    // reproducir). Secret propio vía variable de entorno.
    if (repository.findByClientId("movies-catalog") == null) {
      repository.save(
          moviesCatalogClient(
              passwordEncoder, this.oauth2PropertiesConfig.getMoviesCatalogSecret()));
      log.info("Movies catalog machine client registered: movies-catalog");
    }

    if (repository.findByClientId("media-ingestion") == null) {
      repository.save(
          mediaIngestionClient(
              passwordEncoder, this.oauth2PropertiesConfig.getMediaIngestionSecret()));
      log.info("Media ingestion machine client registered: media-ingestion");
    }

    return repository;
  }

  static RegisteredClient moviesCatalogClient(PasswordEncoder encoder, String secret) {
    return RegisteredClient.withId("movies-catalog-app")
        .clientId("movies-catalog")
        .clientSecret(encoder.encode(secret))
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .scope("storage.objects.delete")
        .build();
  }

  static RegisteredClient mediaIngestionClient(PasswordEncoder encoder, String secret) {
    return RegisteredClient.withId("media-ingestion-app")
        .clientId("media-ingestion")
        .clientSecret(encoder.encode(secret))
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .scope("media-ingestion")
        .build();
  }

  @Bean
  OAuth2AuthorizationService authorizationService(
      JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
    return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
  }

  @Bean
  OAuth2AuthorizationConsentService authorizationConsentService(
      JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
    return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
  }

  @Bean
  WebSecurityCustomizer webSecurityCustomizer() {
    return web -> web.ignoring().requestMatchers("/h2-console/**");
  }

  @Bean
  AuthorizationServerSettings authorizationServerSettings(
      @Value("${spring.security.oauth2.authorizationserver.issuer:http://127.0.0.1:9090}")
          String issuer) {
    return AuthorizationServerSettings.builder().issuer(issuer).build();
  }
}
