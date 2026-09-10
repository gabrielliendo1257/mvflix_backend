package com.guille.media.reproductor.uploader.storage.shared.security;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import com.gcorp.mvflix.security.webflux.MvflixSecurityAutoConfiguration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Matriz de seguridad de la cadena JWT (perfil !sandbox). Sin controladores en
 * el slice: un request que pasa la capa de seguridad responde 404 (no hay
 * handler); uno denegado responde 401/403. Protege contra reglas ausentes que
 * caen silenciosamente en el denyAll final.
 */
@WebFluxTest(controllers = StorageSecurityMatrixTest.NoControllers.class)
@Import(SecurityConfiguration.class)
@ImportAutoConfiguration(MvflixSecurityAutoConfiguration.class)
@TestPropertySource(
    properties = {
      "services.authorization.url=http://authorization.invalid",
      "api.path.base=/api/v1/movie"
    })
class StorageSecurityMatrixTest {

  /** Clase no-controlador: limita el scan del slice a cero controladores. */
  abstract static class NoControllers {}

  private static final String BASE = "/api/v1/movie/storage";
  private static final String MULTIPART = "/api/v1/uploads";

  @Autowired private WebTestClient client;

  @Test
  void uploadEndpointsRequireAuthentication() {
    this.client.post().uri(BASE + "/upload").exchange().expectStatus().isUnauthorized();
    this.client.get().uri(BASE + "/quota").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void multipartEndpointsRequireAuthentication() {
    this.client.post().uri(MULTIPART).exchange().expectStatus().isUnauthorized();
    this.client.get().uri(MULTIPART + "/upload-id/parts").exchange().expectStatus().isUnauthorized();
    this.client.post().uri(MULTIPART + "/upload-id/complete").exchange().expectStatus().isUnauthorized();
    this.client.delete().uri(MULTIPART + "/upload-id").exchange().expectStatus().isUnauthorized();

    this.client.mutateWith(mockJwt()).post().uri(MULTIPART).exchange().expectStatus().isNotFound();
    this.client.mutateWith(mockJwt()).get().uri(MULTIPART + "/upload-id/parts").exchange().expectStatus().isNotFound();
    this.client.mutateWith(mockJwt()).post().uri(MULTIPART + "/upload-id/complete").exchange().expectStatus().isNotFound();
    this.client.mutateWith(mockJwt()).delete().uri(MULTIPART + "/upload-id").exchange().expectStatus().isNotFound();
  }

  @Test
  void provisionRequiresStorageWriteScope() {
    this.client
        .post()
        .uri(BASE + "/users/ana/provision")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    this.client
        .mutateWith(
            mockJwt().authorities(new SimpleGrantedAuthority("SCOPE_storage.read")))
        .post()
        .uri(BASE + "/users/ana/provision")
        .exchange()
        .expectStatus()
        .isForbidden();

    this.client
        .mutateWith(
            mockJwt().authorities(new SimpleGrantedAuthority("SCOPE_storage.write")))
        .post()
        .uri(BASE + "/users/ana/provision")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void objectDeleteIsReachableForAuthenticatedUsers() {
    this.client.delete().uri(BASE + "/42").exchange().expectStatus().isUnauthorized();

    this.client
        .mutateWith(mockJwt())
        .delete()
        .uri(BASE + "/42")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void m2mQuotaEndpointRequiresStorageReadScope() {
    this.client
        .mutateWith(mockJwt())
        .get()
        .uri(BASE + "/users/pepe/quota")
        .exchange()
        .expectStatus()
        .isForbidden();

    this.client
        .mutateWith(
            mockJwt().authorities(new SimpleGrantedAuthority("SCOPE_storage.read")))
        .get()
        .uri(BASE + "/users/pepe/quota")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void internalChainIsLimitedToTheExactWebhookPath() {
    // El path exacto del webhook pasa la capa de seguridad (404 sin handler).
    this.client
        .post()
        .uri("/internal/minio/events")
        .exchange()
        .expectStatus()
        .isNotFound();

    // Cualquier otro endpoint interno cae en la cadena JWT principal:
    // 401 sin token, nunca abierto por heredar el permitAll.
    this.client
        .get()
        .uri("/internal/minio/events")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    this.client
        .post()
        .uri("/internal/future-endpoint")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void uploadInstructionRenewalIsAuthenticatedOnly() {
    // Sin token: 401. La ruta existe en la cadena JWT (no cae en denyAll).
    this.client
        .post()
        .uri(BASE + "/upload/9/instructions")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    // Con token pasa seguridad; 404 porque el slice no carga controladores.
    this.client
        .mutateWith(mockJwt())
        .post()
        .uri(BASE + "/upload/9/instructions")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void catalogStreamingRequiresStorageStreamScope() {
    this.client
        .post()
        .uri(BASE + "/catalog/streaming")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    this.client
        .mutateWith(mockJwt())
        .post()
        .uri(BASE + "/catalog/streaming")
        .exchange()
        .expectStatus()
        .isForbidden();

    this.client
        .mutateWith(
            mockJwt().authorities(new SimpleGrantedAuthority("SCOPE_storage.stream")))
        .post()
        .uri(BASE + "/catalog/streaming")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void managedObjectDeletionRequiresStorageObjectsDeleteScope() {
    this.client
        .post()
        .uri(BASE + "/objects/7/deletion")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    // Token de usuario sin el scope M2M: 403.
    this.client
        .mutateWith(mockJwt())
        .post()
        .uri(BASE + "/objects/7/deletion")
        .exchange()
        .expectStatus()
        .isForbidden();

    // Scope correcto pasa la seguridad (404: el slice no carga controladores).
    this.client
        .mutateWith(
            mockJwt().authorities(new SimpleGrantedAuthority("SCOPE_storage.objects.delete")))
        .post()
        .uri(BASE + "/objects/7/deletion")
        .exchange()
        .expectStatus()
        .isNotFound();
  }
}
