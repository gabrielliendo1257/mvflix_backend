package com.gcorp.mvflix.e2e;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PlaybackLifecycleE2ETest {
  private static final String BFF = System.getenv().getOrDefault("BFF_URL", "http://localhost:19091");
  private static final String ACTIVITY = System.getenv().getOrDefault("ACTIVITY_URL", "http://localhost:17070");
  private static final String USER = AddMediaE2ETest.USER;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Test
  void recordsPlaybackLifecycleIdempotentlyAndProjectsOneActivity() throws Exception {
    String setupToken = AddMediaE2ETest.token(USER, "media-ingestion");
    AddMediaE2ETest.provisionStorage(AddMediaE2ETest.token(USER, "storage.write"));
    JsonNode created = AddMediaE2ETest.start(setupToken, "e2e-playback-" + System.nanoTime(),
        AddMediaE2ETest.request("playback.mp4", 4, "playback movie"), 201);
    AddMediaE2ETest.upload(created.get("upload"));
    AddMediaE2ETest.complete(setupToken, created.get("addMediaId").asText(), 202, 200);
    JsonNode ready = AddMediaE2ETest.awaitStatus(setupToken,
        created.get("addMediaId").asText(), "READY");
    long movieId = ready.path("movieId").asLong();
    assertNotNull(ready.get("movieId"));

    String token = AddMediaE2ETest.token(USER, "playback");
    JsonNode firstSession = postJson(BFF + "/web/playback/" + movieId + "/session", token, null, 201);
    String sessionId = firstSession.path("sessionId").asText();
    assertNotNull(firstSession.get("playback"));

    String progress = "{\"sequence\":1,\"positionSeconds\":120,\"durationSeconds\":7200,\"completed\":false}";
    postJson(BFF + "/web/playback/sessions/" + sessionId + "/progress", token, progress, 200);
    JsonNode duplicate = postJson(BFF + "/web/playback/sessions/" + sessionId + "/progress",
        token, progress, 200);
    assertEquals(1, duplicate.path("sequence").asInt());

    JsonNode resumed = postJson(BFF + "/web/playback/" + movieId + "/session", token, null, 201);
    assertEquals(120, resumed.path("resume").path("positionSeconds").asInt());
    String resumedSessionId = resumed.path("sessionId").asText();
    String completion = "{\"sequence\":2,\"positionSeconds\":7200,\"durationSeconds\":7200,\"completed\":true}";
    postJson(BFF + "/web/playback/sessions/" + resumedSessionId + "/progress", token, completion, 200);
    postJson(BFF + "/web/playback/sessions/" + resumedSessionId + "/progress", token, completion, 200);

    JsonNode history = awaitActivity(token, movieId);
    assertEquals(movieId, history.path("movieId").asLong());
    assertEquals(7200, history.path("positionSeconds").asInt());
    assertEquals(true, history.path("completed").asBoolean());
  }

  private static JsonNode awaitActivity(String token, long movieId) {
    return await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).until(
        () -> {
          HttpResponse<String> response = request("GET", ACTIVITY + "/api/v1/activity/history?limit=100",
              token, null);
          if (response.statusCode() != 200) return null;
          int movieActivities = 0;
          JsonNode finalActivity = null;
          for (JsonNode item : JSON.readTree(response.body())) {
            if (item.path("movieId").asLong() == movieId) {
              movieActivities++;
              if (item.path("completed").asBoolean()
                  && item.path("positionSeconds").asLong() == 7200) finalActivity = item;
            }
          }
          if (finalActivity != null && movieActivities != 1) {
            throw new AssertionError("Expected one activity for movie " + movieId
                + " but found " + movieActivities);
          }
          return finalActivity;
        }, value -> value != null);
  }

  private static JsonNode postJson(String url, String token, String body, int expected) {
    HttpResponse<String> response = request("POST", url, token, body);
    assertEquals(expected, response.statusCode(), response.body());
    return response.body().isBlank() ? JSON.createObjectNode() : read(response.body());
  }

  private static JsonNode read(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception error) {
      throw new AssertionError("Invalid JSON response: " + body, error);
    }
  }

  private static HttpResponse<String> request(String method, String url, String token, String body) {
    try {
      var builder = HttpRequest.newBuilder(URI.create(url))
          .header("Authorization", "Bearer " + token)
          .header("Content-Type", "application/json");
      var payload = body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(body);
      return HTTP.send(builder.method(method, payload).build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception error) {
      throw new RuntimeException(error);
    }
  }
}
