package com.gcorp.service.app.mvflix_playback.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.gcorp.service.app.mvflix_playback.domain.CatalogItemId;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class MoviesAuthorizedCatalogAdapterTest {
  private MockWebServer server;
  private MoviesAuthorizedCatalogAdapter adapter;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    adapter = new MoviesAuthorizedCatalogAdapter(WebClient.builder(), server.url("/").toString());
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void forwardsBearerAndViewerIdentityToMovies() throws Exception {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {"title":"Dune","posterPath":null,"duration":"PT2H","objectId":7,
             "asset":{"id":9,"libraryId":3,"relativePath":"Dune.mkv","size":10,"mimeType":"video/x-matroska"}}
            """));

    var item = adapter.getPlayableItem(new CatalogItemId(42), new ViewerId("anonymous:viewer"),
        "Bearer m2m-token").block();
    var request = server.takeRequest();

    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer m2m-token");
    assertThat(request.getHeader("X-Viewer-Id")).isEqualTo("anonymous:viewer");
    assertThat(request.getPath()).isEqualTo("/api/v1/movies/42/playback-context");
    assertThat(item.objectId()).isEqualTo(7L);
  }
}
