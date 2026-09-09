package com.gcorp.service.app.mvflix_movies.infrastructure.security;

import com.gcorp.service.app.mvflix_movies.shared.application.security.AuthenticatedUser;
import com.gcorp.service.app.mvflix_movies.shared.application.security.UserProvider;
import com.gcorp.service.app.mvflix_movies.shared.application.security.ViewerContext;

import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Extrae el usuario autenticado del contexto reactivo de seguridad (JWT del resource server). */
@Component
@Profile("!sandbox")
public class JwtUserProvider implements UserProvider {

  @Override
  public Mono<AuthenticatedUser> getAuthenticatedUser() {
    return ReactiveSecurityContextHolder.getContext()
        .map(SecurityContext::getAuthentication)
        .flatMap(this::toAuthenticatedUser);
  }

  @Override
  public Mono<ViewerContext> getViewerContext() {
    return this.getAuthenticatedUser()
        .map(ViewerContext::authenticated)
        .onErrorResume(AuthenticationCredentialsNotFoundException.class,
            ignored -> Mono.just(ViewerContext.anonymous()));
  }

  private Mono<AuthenticatedUser> toAuthenticatedUser(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return Mono.error(new AuthenticationCredentialsNotFoundException("No authenticated user"));
    }
    Object principal = authentication.getPrincipal();
    if (principal instanceof Jwt jwt) {
      var roles = jwt.getClaimAsStringList("roles");
      return Mono.just(new AuthenticatedUser(
          jwt.getSubject(), jwt.getClaimAsString("email"),
          roles == null ? java.util.Set.of() : java.util.Set.copyOf(roles)));
    }
    return Mono.error(
        new AuthenticationCredentialsNotFoundException(
            "Unsupported principal type: "
                + (principal == null ? "null" : principal.getClass().getName())));
  }
}
