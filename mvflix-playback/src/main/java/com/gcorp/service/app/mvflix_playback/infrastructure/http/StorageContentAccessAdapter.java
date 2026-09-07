package com.gcorp.service.app.mvflix_playback.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcorp.service.app.mvflix_playback.application.port.ContentAccess;
import com.gcorp.service.app.mvflix_playback.domain.PlayableCatalogItem;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class StorageContentAccessAdapter implements ContentAccess {
  private final WebClient storage;
  private final WebClient authorization;
  private final String clientId;
  private final String clientSecret;

  public StorageContentAccessAdapter(WebClient.Builder builder,
      @Value("${services.storage.url:http://localhost:6060}") String storageUrl,
      @Value("${services.authorization.url:http://localhost:8081}") String authorizationUrl,
      @Value("${STORAGE_PLAYBACK_CLIENT_ID:movies-playback}") String clientId,
      @Value("${STORAGE_PLAYBACK_SECRET:super-secret}") String clientSecret) {
    this.storage = builder.baseUrl(storageUrl).build();
    this.authorization = builder.baseUrl(authorizationUrl).build();
    this.clientId = clientId;
    this.clientSecret = clientSecret;
  }

  @Override
  public Mono<PlaybackSource> open(PlayableCatalogItem item) {
    if (item.objectId() == null) {
      return Mono.just(new PlaybackSource("LOCAL", null, null, item.asset().mimeType()));
    }
    return token().flatMap(accessToken -> storage.post()
        .uri("/api/v1/movie/storage/catalog/streaming")
        .contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> headers.setBearerAuth(accessToken))
        .bodyValue(new StreamingRequest(String.valueOf(item.objectId())))
        .retrieve()
        .bodyToMono(StreamingResponse.class)
        .map(response -> new PlaybackSource("MANAGED", response.streamingUrl(),
            Instant.parse(response.expiresAt()), item.asset() == null ? null : item.asset().mimeType())));
  }

  private Mono<String> token() {
    return authorization.post().uri("/oauth2/token")
        .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(BodyInserters.fromFormData("grant_type", "client_credentials")
            .with("scope", "storage.stream"))
        .retrieve()
        .bodyToMono(TokenResponse.class)
        .map(TokenResponse::accessToken);
  }

  record StreamingRequest(String objectId) {}
  record StreamingResponse(String streamingUrl, String expiresAt) {}
  record TokenResponse(@JsonProperty("access_token") String accessToken) {}
}
