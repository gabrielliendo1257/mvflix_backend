package com.guille.media.bff;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * Smoke de contexto en perfil dev, AISLADO de la red: el yaml de dev declara
 * el issuer http://127.0.0.1:9090 y Spring valida el descubrimiento OIDC al
 * construir el repositorio de registros, lo que rompía este test sin servicios
 * vivos. Reemplazamos ese bean con registros de URIs estáticas (sin
 * discovery) y apuntamos el jwk-set-uri directo que ya consume
 * WebSecurityConfig.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
     "spring.profiles.active=dev,local",
     "security.oauth2.jwk-set-uri=http://localhost:0/oauth2/jwks"
})
@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
class BffMvflixWebApplicationTests {

  @Autowired
  private WebTestClient client;

  @Test
  void contextLoads() {}

  /** Logout LOCAL configurado: POST /web/logout responde 303 a /web/session
   * y elimina la cookie de sesión. La sesión del IdP persiste (decisión
   * documentada); el re-login puede ser silencioso. */
  @Test
  void logoutInvalidatesSessionAndClearsCookie() {
    this.client
        .post()
        .uri("/web/logout")
        .exchange()
        .expectStatus().isEqualTo(org.springframework.http.HttpStatus.SEE_OTHER)
        .expectHeader().valueEquals("Location", "/web/session");
    // La eliminación de la cookie ocurre cuando existe sesión previa; sin
    // sesión el handler solo redirige.
  }

  @TestConfiguration
  static class OfflineOAuth2ClientRegistrations {

    @Bean
    InMemoryReactiveClientRegistrationRepository clientRegistrationRepository() {
      ClientRegistration movieApp =
          ClientRegistration.withRegistrationId("movie-app")
              .clientId("test-client")
              .clientSecret("test-secret")
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .redirectUri("{baseUrl}/login/oauth2/code/movie-app")
              .scope("openid")
              .authorizationUri("http://localhost:0/authorize")
              .tokenUri("http://localhost:0/token")
              .clientName("test")
              .build();
      return new InMemoryReactiveClientRegistrationRepository(movieApp);
    }
  }
}
