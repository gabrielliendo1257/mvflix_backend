package com.gcorp.service.app.mvflix_movies.catalog.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcorp.service.app.mvflix_movies.catalog.domain.item.CatalogItemKind;
import java.util.List;

public record PublicCatalogItemResponse(
    long id,
    String title,
    @JsonProperty("poster_path") String posterPath,
    Integer year,
    String duration,
    List<String> genres,
    CatalogItemKind kind,
    boolean playable) {}
