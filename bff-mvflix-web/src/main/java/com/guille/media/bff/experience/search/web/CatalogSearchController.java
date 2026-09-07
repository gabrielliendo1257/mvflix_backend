package com.guille.media.bff.experience.search.web;

import com.guille.media.bff.experience.search.application.CatalogSearch;
import com.guille.media.bff.experience.search.application.SearchResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

/**
 * Navbar search: "¿qué contenido puedo encontrar o reproducir?". Solo
 * catálogo visible (la autorización fina la aplica Movies al abrir).
 */
@Tag(name = "Search", description = "Búsqueda global sobre catálogo visible")
@RestController
@RequestMapping(value = "/web/search", produces = MediaType.APPLICATION_JSON_VALUE)
public class CatalogSearchController {

  private final CatalogSearch catalogSearch;

  public CatalogSearchController(CatalogSearch catalogSearch) {
    this.catalogSearch = catalogSearch;
  }

  @Operation(summary = "Busca por título en el catálogo visible")
  @GetMapping
  public Flux<SearchResult> search(
      @RequestParam String q,
      @RequestParam(defaultValue = "20") int limit) {
    int capped = Math.max(1, Math.min(limit, 20));
    return this.catalogSearch.search(q).take(capped);
  }
}
