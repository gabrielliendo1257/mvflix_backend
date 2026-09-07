package com.gcorp.mvflix.e2e;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;

class AddMediaE2ETest {
  static final String BFF = env("BFF_URL", "http://localhost:19091");
  static final String USER = "e2e-add-media";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Test
  void completesAddMediaThroughBffAndKeepsReplayIdempotent() throws Exception {
    String key = "e2e-add-media-" + UUID.randomUUID();
    String body = request("e2e-flow.mp4", 4, "e2e movie");
    String token = token(USER, "media-ingestion");
    provisionStorage(token(USER, "storage.write"));

    JsonNode first = start(token, key, body, 201);
    assertNotNull(first.get("addMediaId"));
    assertNotNull(first.get("upload").get("storageKey"), first.toString());
    upload(first.get("upload"));
    complete(token, first.get("addMediaId").asText(), 202, 200);
    JsonNode completed = awaitStatus(token, first.get("addMediaId").asText(), "READY");

    JsonNode replay = start(token, key, body, 201);
    assertEquals(first.get("addMediaId"), replay.get("addMediaId"));
    assertEquals(first.get("movieId"), replay.get("movieId"));
    assertEquals(first.get("uploadId"), replay.get("uploadId"));
    assertEquals(completed.get("phase"), replay.get("phase"));
    assertEquals("READY", replay.path("phase").asText());
  }

  @Test
  void rejectsSameKeyWithDifferentPayload() throws Exception {
    String key = "e2e-add-media-conflict-" + UUID.randomUUID();
    String token = token(USER, "media-ingestion");
    provisionStorage(token(USER, "storage.write"));
    start(token, key, request("conflict.mp4", 4, "conflict movie"), 201);

    HttpResponse<String> response = request(
        "POST", BFF + "/web/add-media", token, key,
        request("conflict-renamed.mp4", 4, "conflict movie"));
    assertEquals(409, response.statusCode(), response.body());
  }

  @Test
  void publishesCompletedIngestionActivityThroughBff() throws Exception {
    String key = "e2e-add-media-activity-" + UUID.randomUUID();
    String token = token(USER, "media-ingestion");
    provisionStorage(token(USER, "storage.write"));

    JsonNode started = start(token, key, request("activity.mp4", 4, "activity movie"), 201);
    String id = started.get("addMediaId").asText();
    upload(started.get("upload"));
    complete(token, id, 202, 200);

    JsonNode activity = awaitActivity(token, id);
    assertEquals(id, activity.path("correlationId").asText());
    assertEquals("MEDIA_INGESTION", activity.path("type").asText());
    assertEquals("COMPLETED", activity.path("status").asText());
    assertEquals("activity.mp4", activity.path("fileName").asText());
  }

  @Test
  void recoversWhenIngestionRestartsAfterUploadCompletion() throws Exception {
    String key = "e2e-add-media-restart-" + UUID.randomUUID();
    String token = token(USER, "media-ingestion");
    provisionStorage(token(USER, "storage.write"));
    JsonNode started = start(token, key, request("restart.mp4", 4, "restart movie"), 201);
    String id = started.get("addMediaId").asText();
    upload(started.get("upload"));
    boolean moviesRunning = false;
    try {
      compose("stop", "movies");
      complete(token, id, 202, 200);
      awaitIngestionPhase(token, id, Set.of("RECONCILIATION_REQUIRED"));

      restartIngestion();
      awaitIngestionPhase(token, id, Set.of("RECONCILIATION_REQUIRED"));

      compose("start", "movies");
      moviesRunning = true;
      awaitIngestionPhase(token, id, Set.of("COMPLETED"));
    } finally {
      if (!moviesRunning) compose("start", "movies");
    }
  }

  static JsonNode start(String token, String key, String body, int expected) throws Exception {
    return JSON.readTree(request("POST", BFF + "/web/add-media", token, key, body, expected).body());
  }

  static void complete(String token, String id, int... expected) throws Exception {
    request("POST", BFF + "/web/add-media/" + id + "/complete", token, null,
        "{\"sizeBytes\":4}", expected);
  }

  static JsonNode awaitStatus(String token, String id, String phase) {
    return await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).until(
        () -> {
          HttpResponse<String> response = request("GET", BFF + "/web/add-media/" + id, token, null, null);
          failImmediatelyOnClientError(response);
          if (response.statusCode() != 200) return null;
          JsonNode value = JSON.readTree(response.body());
          return phase.equals(value.path("phase").asText()) ? value : null;
        }, value -> value != null);
  }

  private static JsonNode awaitIngestionPhase(String token, String id, Set<String> phases) {
    return await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).until(
        () -> {
          try {
            HttpResponse<String> response = request("GET",
                env("MEDIA_INGESTION_URL", "http://localhost:17080") + "/api/v1/ingestions/" + id,
                token, null, null);
            failImmediatelyOnClientError(response);
            if (response.statusCode() != 200) return null;
            JsonNode value = JSON.readTree(response.body());
            return phases.contains(value.path("phase").asText()) ? value : null;
          } catch (RuntimeException transientFailure) {
            return null;
          }
        }, value -> value != null);
  }

  private static JsonNode awaitActivity(String token, String correlationId) {
    return await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).until(
        () -> {
          try {
            HttpResponse<String> response = request("GET", BFF + "/web/activity?limit=100",
                token, null, null);
            failImmediatelyOnClientError(response);
            if (response.statusCode() != 200) return null;
            for (JsonNode entry : JSON.readTree(response.body()).path("items")) {
              if (correlationId.equals(entry.path("correlationId").asText())
                  && "COMPLETED".equals(entry.path("status").asText())) return entry;
            }
            return null;
          } catch (RuntimeException transientFailure) {
            return null;
          }
        }, value -> value != null);
  }

  private static void failImmediatelyOnClientError(HttpResponse<String> response) {
    if (response.statusCode() >= 400 && response.statusCode() < 500) {
      throw new AssertionError("Unexpected client error " + response.statusCode()
          + ": " + response.body());
    }
  }

  static void upload(JsonNode upload) throws Exception {
    byte[] bytes = {1, 2, 3, 4};
    Path payload = Files.createTempFile("mvflix-e2e-upload-", ".mp4");
    Files.write(payload, bytes);
    payload.toFile().setReadable(true, false);
    try {
      Process process = new ProcessBuilder(
          "docker", "run", "--rm", "--network", env("E2E_COMPOSE_PROJECT", "mvflix-e2e") + "_default",
          "-v", payload + ":/payload:ro", "curlimages/curl:8.11.1", "--fail", "--silent",
          "--show-error", "--output", "/dev/null", "--write-out", "%{http_code}",
          "-H", "Content-Type: video/mp4", "-X", "PUT", "--data-binary", "@/payload",
          upload.get("url").asText()).redirectErrorStream(true).start();
      String status = new String(process.getInputStream().readAllBytes()).trim();
      assertEquals(0, process.waitFor(), status);
      assertEquals("200", status);
    } finally {
      Files.deleteIfExists(payload);
    }
    assertStored(upload.get("storageKey").asText());
  }

  private static void assertStored(String storageKey) throws Exception {
    var object = MinioClient.builder().endpoint(env("MINIO_URL", "http://localhost:19000"))
        .credentials("admin", "admin123").build()
        .statObject(StatObjectArgs.builder().bucket("uploads").object(storageKey).build());
    assertEquals(4, object.size());
  }

  static void provisionStorage(String token) {
    request("POST", env("STORAGE_URL", "http://localhost:16060")
        + "/api/v1/movie/storage/users/" + USER + "/provision", token, null,
        "{\"quota_bytes\":1048576}", 200);
  }

  private static void restartIngestion() throws Exception {
    Process process = new ProcessBuilder("docker", "restart",
        env("E2E_COMPOSE_PROJECT", "mvflix-e2e") + "-media-ingestion-1")
        .redirectErrorStream(true).start();
    boolean finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
    String output = new String(process.getInputStream().readAllBytes());
    if (!finished || process.exitValue() != 0) {
      throw new AssertionError("media-ingestion restart failed with exit "
          + (finished ? process.exitValue() : "timeout") + ": " + output);
    }
  }

  private static void compose(String... arguments) throws Exception {
    Path root = Path.of("../..").toAbsolutePath().normalize();
    var command = new java.util.ArrayList<String>();
    command.add("docker");
    command.add("compose");
    command.add("--env-file");
    command.add(root.resolve("infra/docker/container-versions.env").toString());
    command.add("-f");
    command.add(root.resolve("e2e/docker-compose-e2e.yml").toString());
    command.add("-p");
    command.add(env("E2E_COMPOSE_PROJECT", "mvflix-e2e"));
    command.addAll(java.util.List.of(arguments));
    Process process = new ProcessBuilder(command)
        .directory(root.toFile())
        .redirectErrorStream(true).start();
    boolean finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
    String output = new String(process.getInputStream().readAllBytes());
    if (!finished || process.exitValue() != 0) {
      throw new AssertionError("media-ingestion restart failed with exit "
          + (finished ? process.exitValue() : "timeout") + ": " + output);
    }
  }

  private static HttpResponse<String> request(String method, String url, String token,
      String key, String body, int... expected) {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
          .header("Authorization", "Bearer " + token)
          .header("Content-Type", "application/json");
      if (key != null) builder.header("Idempotency-Key", key);
      HttpRequest.BodyPublisher payload = body == null
          ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
      HttpResponse<String> response = HTTP.send(builder.method(method, payload).build(),
          HttpResponse.BodyHandlers.ofString());
      if (expected.length > 0) {
        for (int code : expected) if (code == response.statusCode()) return response;
        throw new AssertionError("Expected " + java.util.Arrays.toString(expected) + ", got "
            + response.statusCode() + ": " + response.body());
      }
      return response;
    } catch (Exception error) {
      throw new RuntimeException(error);
    }
  }

  static String request(String file, long size, String title) {
    long providerId = Math.abs((long) title.hashCode()) + 1;
    return "{\"file\":{\"filename\":\"" + file + "\",\"sizeBytes\":" + size
        + ",\"mimeType\":\"video/mp4\"},\"movie\":{\"providerId\":" + providerId + ",\"draft\":{"
        + "\"title\":\"" + title + "\",\"kind\":\"MOVIE\"}},\"access\":{"
        + "\"visibility\":\"PRIVATE\",\"sharedWith\":[]},\"idempotencyKey\":\"e2e-"
        + title + "\"}";
  }

  static String token(String subject, String scope) throws Exception {
    JsonNode jwk = JSON.readTree(Files.readString(Path.of("../oidc-stub/jwks/jwks.json")))
        .get("keys").get(0);
    RsaJsonWebKey key = (RsaJsonWebKey) JsonWebKey.Factory.newJwk(jwk.toString());
    key.setPrivateKey(privateKey(Files.readString(Path.of("../oidc-stub/test-private-key.pem"))));
    JwtClaims claims = new JwtClaims();
    claims.setSubject(subject);
    claims.setClaim("scope", scope);
    claims.setIssuer("http://jwks-stub:8080");
    claims.setExpirationTimeMinutesInTheFuture(5);
    claims.setGeneratedJwtId();
    JsonWebSignature signature = new JsonWebSignature();
    signature.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
    signature.setKeyIdHeaderValue("mvflix-e2e-2026-01");
    signature.setKey(key.getPrivateKey());
    signature.setPayload(claims.toJson());
    signature.setAlgorithmConstraints(new AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT,
        AlgorithmIdentifiers.RSA_USING_SHA256));
    return signature.getCompactSerialization();
  }

  private static PrivateKey privateKey(String pem) throws Exception {
    String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
        Base64.getDecoder().decode(encoded)));
  }

  private static String env(String name, String fallback) {
    return System.getenv().getOrDefault(name, fallback);
  }
}
