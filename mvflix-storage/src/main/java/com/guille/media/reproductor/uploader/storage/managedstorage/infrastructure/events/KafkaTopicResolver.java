package com.guille.media.reproductor.uploader.storage.managedstorage.infrastructure.events;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class KafkaTopicResolver {
  private static final Map<String, String> TOPICS = Map.of(
      "StoredObjectDeleted", "mvflix.stored-object-deleted.v1",
      "UploadCompleted", "mvflix.upload-completed.v1",
      "UploadFailed", "mvflix.upload-failed.v1");

  public String resolve(String eventType) {
    String topic = TOPICS.get(eventType);
    if (topic == null) {
      throw new IllegalArgumentException("No Kafka topic configured for event type " + eventType);
    }
    return topic;
  }
}
