package com.gcorp.service.app.mvflix_playback.infrastructure.messaging;

import com.gcorp.service.app.mvflix_playback.application.PlaybackOutbox.Message;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class KafkaPlaybackOutboxPublisher {
  private final KafkaTemplate<String, String> kafka;

  public KafkaPlaybackOutboxPublisher(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  public Mono<Void> publish(Message message) {
    String topic = switch (message.eventType()) {
      case "PlaybackStarted" -> "mvflix.playback-started.v1";
      case "PlaybackProgressed" -> "mvflix.playback-progressed.v1";
      case "PlaybackCompleted" -> "mvflix.playback-completed.v1";
      case "QualifiedView" -> "mvflix.qualified-view.v1";
      default -> throw new IllegalArgumentException("Unknown playback event: " + message.eventType());
    };
    return Mono.fromFuture(() -> kafka.send(topic, message.aggregateId().toString(), message.payload())).then();
  }
}
