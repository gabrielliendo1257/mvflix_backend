package com.gcorp.mvflix.security.webflux;

import java.util.Set;

/** Technical identity and authorities of the principal executing an application operation. */
public record AuthenticatedActor(String subject, Set<String> authorities) {
  public AuthenticatedActor {
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("subject must not be blank");
    }
    authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
  }

  public boolean hasAuthority(String authority) {
    return authorities.contains(authority);
  }
}
