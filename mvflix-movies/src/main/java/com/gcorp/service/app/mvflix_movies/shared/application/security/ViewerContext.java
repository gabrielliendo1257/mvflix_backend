package com.gcorp.service.app.mvflix_movies.shared.application.security;

import java.util.Set;

/** Contexto de consulta: autenticado o visitante anónimo. */
public record ViewerContext(String subject, Set<String> roles, boolean authenticated) {

  public ViewerContext {
    subject = subject == null ? "" : subject;
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  public static ViewerContext anonymous() {
    return new ViewerContext("", Set.of(), false);
  }

  public static ViewerContext authenticated(AuthenticatedUser user) {
    return new ViewerContext(user.subject(), user.roles(), true);
  }

  public boolean isAdmin() {
    return this.roles.contains(AuthenticatedUser.ADMIN_ROLE);
  }
}
