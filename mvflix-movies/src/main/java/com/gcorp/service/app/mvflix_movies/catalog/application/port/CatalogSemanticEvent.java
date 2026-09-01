package com.gcorp.service.app.mvflix_movies.catalog.application.port;

import java.time.Instant;
import java.util.UUID;

/** Datos comunes de un evento de catálogo ya listo para persistirse en outbox. */
public interface CatalogSemanticEvent {
    UUID eventId();
    String eventType();
    int eventVersion();
    Instant occurredAt();
    String actorId();
    default String audienceId() { return actorId(); }
    UUID correlationId();
    String aggregateType();
    String aggregateId();
    Object payload();
}
