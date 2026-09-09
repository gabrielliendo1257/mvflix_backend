package com.guille.media.bff.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Client credentials dedicated to the BFF-to-Playback API. */
@Component
public class PlaybackServiceTokenProvider {
  private final WebClient authWebClient;
  private final String clientId;
  private final String clientSecret;
  private volatile String cachedToken;
  private volatile Instant expiresAt = Instant.EPOCH;

  public PlaybackServiceTokenProvider(
      @Value("${services.authorization.url}") String authorizationUrl,
      @Value("${PLAYBACK_CLIENT_ID:bff-playback}") String clientId,
      @Value("${PLAYBACK_CLIENT_SECRET:super-secret}") String clientSecret,
      WebClient.Builder builder) {
    this.authWebClient = builder.baseUrl(authorizationUrl).build();
    this.clientId = clientId;
    this.clientSecret = clientSecret;
  }

  public synchronized Mono<String> token() {
    if (cachedToken != null && Instant.now().isBefore(expiresAt)) return Mono.just(cachedToken);
    return authWebClient.post().uri("/oauth2/token")
        .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(BodyInserters.fromFormData("grant_type", "client_credentials")
            .with("scope", "playback.sessions.write"))
        .retrieve().bodyToMono(TokenResponse.class)
        .doOnNext(response -> {
          cachedToken = response.accessToken();
          expiresAt = Instant.now().plusSeconds(Math.max(60, response.expiresIn() - 60));
        })
        .map(TokenResponse::accessToken);
  }

  record TokenResponse(@JsonProperty("access_token") String accessToken,
                       @JsonProperty("expires_in") long expiresIn) {}
}
