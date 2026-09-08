package com.gcorp.service.app.mvflix_movies.catalog.application;

import java.util.Set;

public record CatalogActor(String subject, Set<String> authorities) {
  public static final String MODERATE_CATALOG = "ROLE_ADMIN";

  public CatalogActor {
    authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
  }

  public boolean canModerateCatalog() {
    return authorities.contains(MODERATE_CATALOG);
  }
}
