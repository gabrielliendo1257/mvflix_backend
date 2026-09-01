package com.gcorp.service.app.mvflix_movies.catalog.application.port;

import com.gcorp.service.app.mvflix_movies.catalog.domain.access.Visibility;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CatalogItemAccessChanged(
        UUID eventId,
        Instant occurredAt,
        String actorId,
        String audienceId,
        UUID correlationId,
        long catalogItemId,
        String kind,
        String title,
        Visibility previousVisibility,
        Visibility visibility,
        int previousSharedCount,
        int sharedCount)
        implements CatalogSemanticEvent {
    @Override
    public String eventType() {
        return "CatalogItemAccessChanged";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String aggregateType() {
        return "CatalogItem";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(this.catalogItemId);
    }

    @Override
    public Object payload() {
        return Map.of(
                "catalogItemId", this.catalogItemId,
                "kind", this.kind,
                "title", this.title,
                "previousVisibility", this.previousVisibility.name(),
                "visibility", this.visibility.name(),
                "previousSharedCount", this.previousSharedCount,
                "sharedCount", this.sharedCount);
    }
}
