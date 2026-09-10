package com.gcorp.service.app.mvflix_media_ingestion.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcorp.service.app.mvflix_media_ingestion.application.InboxRepository;
import com.gcorp.service.app.mvflix_media_ingestion.application.MediaIngestionService;
import com.gcorp.service.app.mvflix_media_ingestion.application.IngestionCorrelationNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Component
@ConditionalOnProperty(name="mvflix.messaging.kafka.enabled", havingValue="true")
public class UploadCompletedListener {
  private final MediaIngestionService service; private final ObjectMapper mapper; private final InboxRepository inbox;
  public UploadCompletedListener(MediaIngestionService service,ObjectMapper mapper,InboxRepository inbox){this.service=service;this.mapper=mapper;this.inbox=inbox;}

  @RetryableTopic(attempts="4", dltTopicSuffix=".DLT")
  @KafkaListener(topics="${mvflix.messaging.kafka.upload-completed-topic:mvflix.upload-completed.v1}",groupId="${mvflix.messaging.kafka.consumer-group:mvflix-media-ingestion}")
  public void onMessage(String raw) throws Exception {
    JsonNode envelope; UUID eventId;
    try { envelope=mapper.readTree(raw); eventId=requiredUuid(envelope,"eventId"); validate(envelope); }
    catch (Exception error) {
      try { eventId=requiredUuid(mapper.readTree(raw),"eventId"); }
      catch (Exception noUsableId) { throw new IllegalArgumentException("invalid UploadCompleted envelope", error); }
      UUID failedEventId=eventId;
      inbox.receive(failedEventId,"UploadCompleted").then(Mono.defer(() -> inbox.markFailed(failedEventId,error.getMessage()))).block();
      throw new IllegalArgumentException("invalid UploadCompleted envelope", error);
    }

    boolean fresh=inbox.receive(eventId,"UploadCompleted").block();
    if (!fresh && Boolean.TRUE.equals(inbox.completed(eventId).block())) return;
    try {
      JsonNode payload=envelope.path("payload");
      UUID correlation=envelope.path("correlationId").isNull()?null:UUID.fromString(envelope.path("correlationId").asText());
      long storageId=payload.path("storageId").asLong(); String key=payload.path("objectKey").asText();
       String uploadId=text(payload,"uploadId");
       String causation = eventId.toString();
       Mono<Void> work = correlation != null
           ? service.uploadCompleted(correlation, storageId, key, causation)
               .onErrorResume(error -> isUnknownCorrelation(error)
                   ? byUploadIdOrStorageId(uploadId, storageId, key, causation)
                   : Mono.error(error))
           : byUploadIdOrStorageId(uploadId, storageId, key, causation);
      UUID completedEventId=eventId;
      work.then(Mono.defer(() -> inbox.markCompleted(completedEventId))).block();
    } catch (Exception error) {
      inbox.markFailed(eventId,error.getMessage()).block();
      throw error;
    }
  }

  private void validate(JsonNode n) {
    if (!"UploadCompleted".equals(text(n,"eventType")) || n.path("eventVersion").asInt(0)!=1 || !"mvflix-storage".equals(text(n,"producer"))) throw new IllegalArgumentException("invalid UploadCompleted envelope");
    JsonNode aggregate=n.path("aggregate"); if (!"ManagedObject".equals(text(aggregate,"type")) || text(aggregate,"id")==null) throw new IllegalArgumentException("invalid aggregate");
    if (!n.has("correlationId") || (!n.path("correlationId").isNull() && !isUuid(n.path("correlationId").asText()))) throw new IllegalArgumentException("invalid correlationId");
     JsonNode p=n.path("payload");
     if (!p.isObject() || !p.hasNonNull("storageId") || p.path("storageId").asLong() <= 0
         || !p.hasNonNull("ownerUsername") || !p.hasNonNull("objectKey")
         || p.path("objectKey").asText().isBlank() || !p.hasNonNull("contentType")
         || !p.hasNonNull("contentLength")) throw new IllegalArgumentException("invalid payload");
  }
  private String text(JsonNode n,String field){String value=n.path(field).asText(null);return value==null||value.isBlank()?null:value;}
  private boolean isUuid(String value){try{UUID.fromString(value);return true;}catch(Exception e){return false;}}
  private UUID requiredUuid(JsonNode n,String field){if(n==null||!n.hasNonNull(field))throw new IllegalArgumentException("missing "+field);return UUID.fromString(n.path(field).asText());}
  private boolean isUnknownCorrelation(Throwable error) {
    return error instanceof IngestionCorrelationNotFoundException;
  }

  private Mono<Void> byUploadIdOrStorageId(String uploadId, long storageId, String key, String causation) {
    return uploadId == null
        ? service.uploadCompletedByStorageId(storageId, storageId, key, causation)
        : service.uploadCompletedByUploadId(uploadId, storageId, key, causation)
            .onErrorResume(this::isUnknownUpload,
                ignored -> service.uploadCompletedByStorageId(storageId, storageId, key, causation));
  }

  private boolean isUnknownUpload(Throwable error) {
    return error instanceof IllegalArgumentException;
  }
}
