package com.gcorp.mvflix.e2e;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ActivityProjectionsE2ETest {
  private static final String BFF = env("BFF_URL", "http://localhost:19091");
  private static final String USER = "e2e-activity-" + UUID.randomUUID();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Test
  @Tag("smoke")
  void projectsCatalogAccessChangedIntoActivityFeed() throws Exception {
    UUID eventId = UUID.randomUUID();
    publish("mvflix.catalog-item-access-changed.v1", eventId.toString(), """
        {"eventId":"%s","eventType":"CatalogItemAccessChanged","eventVersion":1,
        "occurredAt":"%s","producer":"mvflix-movies","actorId":"%s","audienceId":"%s",
        "correlationId":"%s","aggregate":{"type":"CatalogItem","id":"101"},
        "payload":{"catalogItemId":101,"kind":"MOVIE","title":"E2E access movie",
        "previousVisibility":"PRIVATE","visibility":"PUBLIC","previousSharedCount":0,"sharedCount":0}}
        """.formatted(eventId, Instant.now(), USER, USER, eventId));

    JsonNode activity = awaitActivity(eventId);
    assertEquals("CATALOG_ACCESS", activity.path("type").asText());
    assertEquals("ACCESS_CHANGED", activity.path("status").asText());
    assertEquals("101", activity.path("resourceId").asText());
    assertEquals("E2E access movie", activity.path("resourceTitle").asText());
  }

  @Test
  void projectsUploadFailedIntoActivityFeed() throws Exception {
    UUID eventId = UUID.randomUUID();
    publish("mvflix.upload-failed.v1", eventId.toString(), """
        {"eventId":"%s","eventType":"UploadFailed","eventVersion":1,
        "occurredAt":"%s","producer":"mvflix-storage","actorId":"%s","audienceId":"%s",
        "correlationId":"%s","aggregate":{"type":"ManagedObject","id":"202"},
        "payload":{"storageId":202,"ownerUsername":"%s","objectKey":"e2e/failure.mp4",
        "reason":"MINIO_UNAVAILABLE"}}
        """.formatted(eventId, Instant.now(), USER, USER, eventId, USER));

    JsonNode activity = awaitActivity(eventId);
    assertEquals("UPLOAD_FAILED", activity.path("type").asText());
    assertEquals("FAILED", activity.path("status").asText());
    assertEquals("202", activity.path("resourceId").asText());
    assertEquals("e2e/failure.mp4", activity.path("resourceTitle").asText());
  }

  private static void publish(String topic, String key, String value) {
    Properties properties = new Properties();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
        env("KAFKA_E2E_BOOTSTRAP", "localhost:19092"));
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
      producer.send(new ProducerRecord<>(topic, key, value));
      producer.flush();
    }
  }

  private static JsonNode awaitActivity(UUID eventId) {
    String token = token(USER, "activity.read");
    return await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(
        () -> {
          try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                URI.create(BFF + "/web/activity?limit=100"))
                .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            for (JsonNode item : JSON.readTree(response.body()).path("items")) {
              if (eventId.toString().equals(item.path("activityId").asText())) return item;
            }
          } catch (Exception transientFailure) {
            return null;
          }
          return null;
        }, value -> value != null);
  }

  private static String token(String subject, String scope) {
    try {
      String jwkJson = JSON.readTree(Files.readString(Path.of("../oidc-stub/jwks/jwks.json")))
          .get("keys").get(0).toString();
      RsaJsonWebKey key = (RsaJsonWebKey) JsonWebKey.Factory.newJwk(jwkJson);
      key.setPrivateKey(privateKey(Files.readString(Path.of("../oidc-stub/test-private-key.pem"))));
      JwtClaims claims = new JwtClaims();
      claims.setSubject(subject);
      claims.setClaim("scope", scope);
      claims.setIssuer("http://jwks-stub:8080");
      claims.setExpirationTimeMinutesInTheFuture(5);
      claims.setGeneratedJwtId();
      JsonWebSignature jws = new JsonWebSignature();
      jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
      jws.setKeyIdHeaderValue("mvflix-e2e-2026-01");
      jws.setKey(key.getPrivateKey());
      jws.setPayload(claims.toJson());
      jws.setAlgorithmConstraints(new AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT,
          AlgorithmIdentifiers.RSA_USING_SHA256));
      return jws.getCompactSerialization();
    } catch (Exception error) {
      throw new IllegalStateException("Could not create E2E token", error);
    }
  }

  private static PrivateKey privateKey(String pem) throws Exception {
    String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
    return KeyFactory.getInstance("RSA").generatePrivate(
        new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
  }

  private static String env(String name, String fallback) {
    return System.getenv().getOrDefault(name, fallback);
  }
}
