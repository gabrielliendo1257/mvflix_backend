package com.gcorp.mvflix.security.webflux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class AuthenticatedActorArgumentResolverTest {
  private final AuthenticatedActorArgumentResolver resolver =
      new AuthenticatedActorArgumentResolver();

  @Test
  void resolvesSubjectAndAuthoritiesFromJwtAuthentication() throws Exception {
    MethodParameter parameter = parameter();
    Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject("user-42")
        .claim("scope", "catalog.read")
        .build();
    var authentication = new JwtAuthenticationToken(
        jwt, List.of(() -> "SCOPE_catalog.read", () -> "ROLE_ADMIN"));
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    when(exchange.getPrincipal()).thenReturn(Mono.just(authentication));

    AuthenticatedActor actor = (AuthenticatedActor) resolver
        .resolveArgument(parameter, new BindingContext(), exchange)
        .block();

    assertThat(actor.subject()).isEqualTo("user-42");
    assertThat(actor.authorities()).containsExactlyInAnyOrder("SCOPE_catalog.read", "ROLE_ADMIN");
    assertThat(actor.isAdmin()).isTrue();
  }

  @Test
  void onlySupportsAnnotatedActorParameters() throws Exception {
    assertThat(resolver.supportsParameter(parameter())).isTrue();
    Method method = Fixture.class.getDeclaredMethod("unannotated", AuthenticatedActor.class);
    assertThat(resolver.supportsParameter(new MethodParameter(method, 0))).isFalse();
  }

  private static MethodParameter parameter() throws Exception {
    return new MethodParameter(Fixture.class.getDeclaredMethod("annotated", AuthenticatedActor.class), 0);
  }

  static class Fixture {
    void annotated(@CurrentActor AuthenticatedActor actor) {}

    void unannotated(AuthenticatedActor actor) {}
  }
}
