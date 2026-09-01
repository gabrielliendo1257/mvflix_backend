package com.gcorp.mvflix.e2e;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;

class ManagedDeletionE2ETest {
  private static final String MOVIES = env("MOVIES_URL", "http://localhost:14040");
  private static final String STORAGE = env("STORAGE_URL", "http://localhost:16060");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String USER = "e2e-" + UUID.randomUUID();

  @Test
  void deletesManagedObjectAcrossKafkaAndServices() throws Exception {
    String token = token(USER, "storage.write");
    post(STORAGE + "/api/v1/movie/storage/users/" + USER + "/provision", token,
        "{\"quota_bytes\":1048576}", 200);

    JsonNode upload = JSON.readTree(post(STORAGE + "/api/v1/movie/storage/upload", token,
        "{\"filename\":\"e2e.mp4\",\"file_size\":4,\"mime_type\":\"video/mp4\"}", 200));
    long uploadId = upload.get("uploadId").asLong();
    String storageKey = upload.get("storageKey").asText();
    put(storageKey, new byte[] {1, 2, 3, 4});
    post(STORAGE + "/api/v1/movie/storage/upload/" + uploadId + "/complete", token, null, 200, 202);
     awaitUploadCompleted(uploadId);

    long movieId = JSON.readTree(post(MOVIES + "/api/v1/movies", token,
        "{\"title\":\"E2E managed deletion\",\"kind\":\"MOVIE\"}", 200)).get("id").asLong();
    post(MOVIES + "/api/v1/movies/" + movieId + "/complete", token,
        "{\"object_id\":" + uploadId + ",\"object_key\":\"" + storageKey + "\"}", 200);

    assertTrue(delete(MOVIES + "/api/v1/movies/" + movieId, token) == 202);
    assertTrue(delete(MOVIES + "/api/v1/movies/" + movieId, token) == 202);
    await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).until(() -> {
      HttpResponse<String> response = get(MOVIES + "/api/v1/movies/" + movieId, token);
      return response.statusCode() == 403
          && deletionCompleted(movieId, uploadId, USER, storageKey);
    });
  }

  private static String token(String subject, String scope) throws Exception {
    String jwkJson = JSON.readTree(Files.readString(Path.of("../oidc-stub/jwks/jwks.json"))).get("keys").get(0).toString();
    RsaJsonWebKey key = (RsaJsonWebKey) JsonWebKey.Factory.newJwk(jwkJson);
    key.setPrivateKey(privateKey(Files.readString(Path.of("../oidc-stub/test-private-key.pem"))));
    JwtClaims claims = new JwtClaims();
    claims.setSubject(subject); claims.setClaim("scope", scope); claims.setIssuer("http://jwks-stub:8080");
    claims.setExpirationTimeMinutesInTheFuture(5); claims.setGeneratedJwtId();
    JsonWebSignature jws = new JsonWebSignature();
    jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256); jws.setKeyIdHeaderValue("mvflix-e2e-2026-01");
    jws.setKey(key.getPrivateKey()); jws.setPayload(claims.toJson());
    jws.setAlgorithmConstraints(new AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, AlgorithmIdentifiers.RSA_USING_SHA256));
    return jws.getCompactSerialization();
  }

  private static PrivateKey privateKey(String pem) throws Exception {
    String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(encoded)));
  }

  private static String post(String url, String token, String body, int... expected) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
    HttpResponse<String> r = HTTP.send(b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(), HttpResponse.BodyHandlers.ofString());
    expect(r, expected); return r.body();
  }
  private static void put(String objectKey, byte[] body) throws Exception {
    MinioClient minio = MinioClient.builder().endpoint("http://localhost:19000")
        .credentials("admin", "admin123").build();
    minio.putObject(PutObjectArgs.builder().bucket("uploads").object(objectKey)
        .contentType("video/mp4")
        .stream(new java.io.ByteArrayInputStream(body), body.length, -1).build());
  }
  private static int delete(String url, String token) throws Exception { HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).DELETE().build(), HttpResponse.BodyHandlers.ofString()); expect(r, 204, 202); return r.statusCode(); }
  private static HttpResponse<String> get(String url, String token) throws Exception { return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()); }
  private static void expect(HttpResponse<?> r, int... expected) { for (int code : expected) if (r.statusCode() == code) return; throw new AssertionError("Expected " + java.util.Arrays.toString(expected) + ", got " + r.statusCode() + ": " + r.body()); }
  private static boolean deletionCompleted(long movieId, long uploadId, String owner, String storageKey) throws Exception {
    UUID eventId;
    try (Connection movies = db("mvflix_movies_db"); var statement = movies.prepareStatement(
        "select (payload->>'eventId')::uuid from outbox_events where event_type = 'ManagedMediaDeletionRequested' and payload->'payload'->>'storageId' = ? and payload->'payload'->>'movieId' = ?")) {
      statement.setString(1, String.valueOf(uploadId));
      statement.setString(2, String.valueOf(movieId));
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) return false;
        eventId = rows.getObject(1, UUID.class);
      }
    }

    String bucket;
    String objectStatus;
    String inboxStatus;
    long usage;
    try (Connection storage = db("mvflix_uploads_db"); var statement = storage.prepareStatement(
        "select so.status, us.storage_usage, us.bucket_name, inbox.status "
            + "from store_objects so join user_storage us on us.owner_username = ? "
            + "left join managed_media_deletion_inbox inbox on inbox.event_id = ? "
            + "where so.storage_id = ? and so.owner_username = ? and so.object_key = ?")) {
      statement.setString(1, owner);
      statement.setObject(2, eventId);
      statement.setLong(3, uploadId);
      statement.setString(4, owner);
      statement.setString(5, storageKey);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) return false;
        objectStatus = rows.getString(1);
        usage = rows.getLong(2);
        bucket = rows.getString(3);
        inboxStatus = rows.getString(4);
      }
    }
    if (!"DELETED".equals(objectStatus) || !"COMPLETED".equals(inboxStatus) || usage != 0) return false;
    return !minioObjectExists(bucket, storageKey);
  }

   private static void awaitUploadCompleted(long uploadId) throws Exception {
     Properties properties = new Properties();
     properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, env("KAFKA_E2E_BOOTSTRAP", "localhost:19092"));
     properties.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-upload-completed-" + UUID.randomUUID());
     properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
     properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
     properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

     try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
       consumer.subscribe(java.util.List.of("mvflix.upload-completed.v1"));
       long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
       while (System.nanoTime() < deadline) {
         for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
           UploadCompletedEnvelope event = JSON.readValue(record.value(), UploadCompletedEnvelope.class);
           if ("UploadCompleted".equals(event.eventType())
               && event.eventVersion() == 1
               && "mvflix-storage".equals(event.producer())
               && String.valueOf(uploadId).equals(event.aggregate().id())
               && "ManagedObject".equals(event.aggregate().type())
               && uploadId == event.payload().storageId()) {
             return;
           }
         }
       }
     }
     throw new AssertionError("UploadCompleted was not consumed from Kafka for storageId=" + uploadId);
   }

    private record UploadCompletedEnvelope(
        String eventId,
        String eventType,
         int eventVersion,
         String occurredAt,
         String producer,
         String actorId,
         String audienceId,
         String correlationId,
        AggregateReference aggregate,
        UploadCompletedPayload payload) {}

   private record AggregateReference(String type, String id) {}

   private record UploadCompletedPayload(
       long storageId,
       String ownerUsername,
       String objectKey,
       String contentType,
       long contentLength) {}

  private static Connection db(String database) throws Exception {
    return DriverManager.getConnection("jdbc:postgresql://localhost:15432/" + database, "db_ro", "12345678");
  }

  private static boolean minioObjectExists(String bucket, String objectKey) throws Exception {
    MinioClient minio = MinioClient.builder().endpoint("http://localhost:19000")
        .credentials("admin", "admin123").build();
    try {
      minio.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
      return true;
    } catch (ErrorResponseException error) {
      if ("NoSuchKey".equals(error.errorResponse().code())
          || "NoSuchObject".equals(error.errorResponse().code())) return false;
      throw error;
    }
  }
  private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
