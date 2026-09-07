package com.guille.media.bff.experience.search.application;

/** Proyección mínima para sugerencias del catálogo visible; no expone MovieDto completo. */
public record SearchResult(
    Long id,
    String title,
    String originalTitle,
    Integer year,
    String posterUrl,
    String kind,
    String status,
    boolean playable) {}
