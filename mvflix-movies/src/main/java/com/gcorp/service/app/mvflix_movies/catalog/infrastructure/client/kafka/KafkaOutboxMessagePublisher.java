package com.gcorp.service.app.mvflix_movies.catalog.infrastructure.client.kafka;

import com.gcorp.service.app.mvflix_movies.catalog.application.port.OutboxMessage;
import com.gcorp.service.app.mvflix_movies.catalog.application.port.OutboxMessagePublisher;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.Map;

/** Adapter Kafka que conserva el payload versionado de la outbox sin reserializarlo. */
@Component
public class KafkaOutboxMessagePublisher implements OutboxMessagePublisher {

    private static final Map<String, String> TOPICS = Map.of(
            "ManagedMediaDeletionRequested", "mvflix.managed-media-deletion-requested.v1",
            "CatalogItemAdded", "mvflix.catalog-item-added.v1",
            "CatalogItemAccessChanged", "mvflix.catalog-item-access-changed.v1",
            "CatalogItemDeleted", "mvflix.catalog-item-deleted.v1");

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaOutboxMessagePublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public Mono<Void> publish(OutboxMessage message) {
        String topic = TOPICS.get(message.eventType());
        if (topic == null) {
            return Mono.error(new IllegalArgumentException(
                    "No Kafka topic configured for event type " + message.eventType()));
        }
        return Mono.fromFuture(() -> this.kafkaTemplate
                        .send(topic, message.aggregateId(), message.payload()))
                .then();
    }
}
