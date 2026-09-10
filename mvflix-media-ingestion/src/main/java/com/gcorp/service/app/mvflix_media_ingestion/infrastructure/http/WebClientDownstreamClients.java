package com.gcorp.service.app.mvflix_media_ingestion.infrastructure.http;

import com.gcorp.service.app.mvflix_media_ingestion.application.DownstreamClients;
import java.util.Map;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import reactor.core.publisher.Mono;

@Component
public class WebClientDownstreamClients implements DownstreamClients {
  private final WebClient movies;
  private final WebClient storage;
  private final WebClient users;

  @Autowired
  public WebClientDownstreamClients(
      @Value("${mvflix.downstream.movies-url:http://localhost:4040}") String moviesUrl,
      @Value("${mvflix.downstream.storage-url:http://localhost:6060}") String storageUrl,
      @Value("${mvflix.downstream.users-url:http://localhost:8080}") String usersUrl,
      WebClient.Builder builder,
      ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
      @Value("${mvflix.downstream.response-timeout-seconds:10}") long responseTimeoutSeconds,
      @Value("${mvflix.downstream.connect-timeout-ms:2000}") int connectTimeoutMillis) {
    this.movies = client(builder, moviesUrl, authorizedClientManager, "movies", responseTimeoutSeconds, connectTimeoutMillis);
    this.storage = client(builder, storageUrl, authorizedClientManager, "storage", responseTimeoutSeconds, connectTimeoutMillis);
    this.users = client(builder, usersUrl, authorizedClientManager, "users", responseTimeoutSeconds, connectTimeoutMillis);
  }

  public WebClientDownstreamClients(
      String moviesUrl,
      String storageUrl,
      WebClient.Builder builder,
      ReactiveOAuth2AuthorizedClientManager authorizedClientManager) {
    this(moviesUrl, storageUrl, "http://localhost:8080", builder, authorizedClientManager, 10, 2000);
  }

  private WebClient client(
      WebClient.Builder builder,
      String baseUrl,
      ReactiveOAuth2AuthorizedClientManager manager,
      String registrationId,
      long responseTimeoutSeconds,
      int connectTimeoutMillis) {
    var oauth2 = new ServerOAuth2AuthorizedClientExchangeFilterFunction(manager);
    oauth2.setDefaultClientRegistrationId(registrationId);
    var httpClient = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
        .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds));
    return builder.clone()
        .baseUrl(baseUrl)
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .filter(oauth2)
        .build();
  }

  @Override
  public Mono<Long> createCatalogDraft(
      Map<String, Object> draft, String actor, String key, String correlationId) {
    Map<String, Object> payload = draft.containsKey("draft")
        ? draft
        : Map.of("draft", draft);
    return movies
        .post()
        .uri("/api/v1/movies/identified-drafts")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Actor-Id", actor)
        .header("Idempotency-Key", key)
        .header("X-Correlation-Id", correlationId)
        .bodyValue(payload)
        .retrieve()
        .bodyToMono(Map.class)
        .map(response -> ((Number) response.get("id")).longValue());
  }

  @Override
  public Mono<MediaIngestionEligibility> mediaIngestionEligibility(String actor) {
    return users
        .get()
        .uri("/api/v1/users/{username}/policy", actor)
        .retrieve()
        .bodyToMono(Map.class)
        .map(response -> new MediaIngestionEligibility(Boolean.TRUE.equals(response.get("allowed"))));
  }

  @Override
  public Mono<Upload> prepareUpload(String name, long size, String mime, String actor, String key) {
    return storage
        .post()
        .uri("/api/v1/movie/storage/upload")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Actor-Id", actor)
        .bodyValue(
            Map.of(
                "filename", name,
                "file_size", size,
                "mime_type", mime,
                "idempotency_key", key))
        .retrieve()
        .bodyToMono(Map.class)
        .map(
            response ->
                new Upload(
                    String.valueOf(response.get("uploadId")),
                    String.valueOf(response.get("storageKey")),
                    response.get("uploadUrl") == null ? null : String.valueOf(response.get("uploadUrl")),
                    response.get("strategy") == null ? "SIMPLE" : String.valueOf(response.get("strategy")),
                    number(response.get("partSizeBytes")), integer(response.get("totalParts"))));
  }

  @Override
  public Mono<Void> requestUploadCompletion(String uploadId, String actor, String idempotencyKey) {
    return storage
        .post()
        .uri("/api/v1/movie/storage/upload/{uploadId}/complete", uploadId)
        .header("X-Actor-Id", actor)
        .header("Idempotency-Key", idempotencyKey)
        .retrieve()
        .bodyToMono(Void.class);
  }

  @Override
  public Mono<Void> completeCatalog(long id, String objectKey, long objectId, String actor) {
    return movies
        .post()
        .uri("/api/v1/movies/{id}/complete", id)
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Actor-Id", actor)
        .bodyValue(Map.of("object_id", objectId, "object_key", objectKey))
        .retrieve()
        .bodyToMono(Void.class);
  }

  @Override
  public Mono<Void> discardDraft(long id, String actor, String idempotencyKey) {
    return movies
        .post()
        .uri("/api/v1/movies/{id}/discard-draft", id)
        .header("X-Actor-Id", actor)
        .header("Idempotency-Key", idempotencyKey)
        .retrieve()
        .bodyToMono(Void.class);
  }

  @Override
  public Mono<Void> cancelUpload(String id, String actor, String key) {
    return storage
        .post()
        .uri("/api/v1/movie/storage/upload/{id}/cancel", id)
        .header("X-Actor-Id", actor)
        .header("Idempotency-Key", key)
        .retrieve()
        .bodyToMono(Void.class);
  }

  @Override
  public Mono<StorageStatus> storageStatus(String uploadId, String actor) {
    return storage
        .get()
        .uri("/api/v1/movie/storage/upload/{uploadId}", uploadId)
        .header("X-Actor-Id", actor)
        .retrieve()
        .bodyToMono(Map.class)
        .map(
            r ->
                new StorageStatus(
                    String.valueOf(r.get("status")),
                    number(r.get("storageId")),
                    r.get("storageKey") == null ? null : String.valueOf(r.get("storageKey"))));
  }

  @Override
  public Mono<CatalogStatus> catalogStatus(long id, String actor) {
    return movies
        .get()
        .uri("/api/v1/movies/{id}", id)
        .header("X-Actor-Id", actor)
        .retrieve()
        .bodyToMono(Map.class)
        .onErrorResume(
            org.springframework.web.reactive.function.client.WebClientResponseException.NotFound
                .class,
            error -> Mono.just(Map.of("status", "MISSING")))
        .map(r -> new CatalogStatus(String.valueOf(r.get("status"))));
  }

  private static Long number(Object value) {
    return value instanceof Number n ? n.longValue() : null;
  }

  private static Integer integer(Object value) {
    return value instanceof Number n ? n.intValue() : null;
  }
}
