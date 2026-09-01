package com.guille.media.bff.experience.activity.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.guille.media.bff.experience.activity.application.GetActivityFeed.ActivityPage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.test.StepVerifier;

class ActivityWebClientAdapterTest {

  private HttpServer server;
  private URI requestUri;
  private String authorization;

  @BeforeEach
  void setUp() throws IOException {
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
  }

  @AfterEach
  void tearDown() {
    this.server.stop(0);
  }

  @Test
  void requestsOneExtraDownstreamEntryAndPropagatesBearerToken() throws IOException {
    this.server.createContext("/api/v1/activity/feed", exchange -> {
      captureRequest(exchange);
      respond(exchange, 200, entries(21));
    });
    this.server.start();

    ActivityPage page = this.adapter().feed(null, 20).block(Duration.ofSeconds(2));

    assertThat(page.items()).hasSize(20);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextCursor()).isEqualTo("cursor-20");
    assertThat(this.requestUri.getQuery()).isEqualTo("limit=21");
    assertThat(this.authorization).isEqualTo("Bearer test-token");
  }

  @Test
  void finalPageHasNoNextCursor() throws IOException {
    this.server.createContext("/api/v1/activity/feed", exchange ->
        respond(exchange, 200, entries(3)));
    this.server.start();

    ActivityPage page = this.adapter().feed(null, 20).block(Duration.ofSeconds(2));

    assertThat(page.items()).hasSize(3);
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void propagatesDownstreamClientError() throws IOException {
    this.server.createContext("/api/v1/activity/feed", exchange ->
        respond(exchange, 404, "{\"error\":\"not found\"}"));
    this.server.start();

    StepVerifier.create(this.adapter().feed(null, 20))
        .expectErrorSatisfies(error -> assertThat(error)
            .isInstanceOf(WebClientResponseException.NotFound.class))
        .verify();
  }

  @Test
  void propagatesDownstreamServerError() throws IOException {
    this.server.createContext("/api/v1/activity/feed", exchange ->
        respond(exchange, 503, "{\"error\":\"unavailable\"}"));
    this.server.start();

    StepVerifier.create(this.adapter().feed(null, 20))
        .expectErrorSatisfies(error -> assertThat(error)
            .isInstanceOf(WebClientResponseException.ServiceUnavailable.class))
        .verify();
  }

  @Test
  void mapsDownstreamEntryToPublicActivityEntry() {
    UUID activityId = UUID.randomUUID();
    var entry = new ActivityWebClientAdapter.DownstreamEntry(activityId, activityId,
        "MEDIA_INGESTION", "FAILED", Instant.parse("2026-01-01T12:00:00Z"),
        Instant.parse("2026-01-01T12:01:00Z"), "movie.mp4", 42L, "CATALOG_FAILED", "cursor");

    var mapped = ActivityWebClientAdapter.toApplication(entry);

    assertThat(mapped.activityId()).isEqualTo(activityId);
    assertThat(mapped.status()).isEqualTo("FAILED");
    assertThat(mapped.fileName()).isEqualTo("movie.mp4");
    assertThat(mapped.catalogItemId()).isEqualTo(42L);
  }

  private ActivityWebClientAdapter adapter() {
    return new ActivityWebClientAdapter(WebClient.builder()
        .baseUrl("http://127.0.0.1:" + this.server.getAddress().getPort())
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token")
        .build());
  }

  private void captureRequest(HttpExchange exchange) {
    this.requestUri = exchange.getRequestURI();
    this.authorization = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static String entries(int count) {
    StringBuilder body = new StringBuilder("[");
    for (int index = 1; index <= count; index++) {
      if (index > 1) {
        body.append(',');
      }
      body.append("{\"activityId\":\"").append(UUID.randomUUID())
          .append("\",\"correlationId\":\"").append(UUID.randomUUID())
          .append("\",\"type\":\"MEDIA_INGESTION\",\"status\":\"COMPLETED\"")
          .append(",\"startedAt\":\"2026-01-01T12:00:00Z\"")
          .append(",\"lastOccurredAt\":\"2026-01-01T12:01:00Z\"")
          .append(",\"cursor\":\"cursor-").append(index).append("\"}");
    }
    return body.append(']').toString();
  }
}
