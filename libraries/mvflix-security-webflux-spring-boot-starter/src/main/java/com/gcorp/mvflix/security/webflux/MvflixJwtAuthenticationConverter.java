package com.gcorp.mvflix.security.webflux;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import reactor.core.publisher.Mono;

public final class MvflixJwtAuthenticationConverter
    implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {
  private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
  private final JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();

  public MvflixJwtAuthenticationConverter() {
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix("");
  }

  @Override
  public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
    return Mono.just(new JwtAuthenticationToken(jwt, authorities(jwt)));
  }

  private List<org.springframework.security.core.GrantedAuthority> authorities(Jwt jwt) {
    var result = new ArrayList<org.springframework.security.core.GrantedAuthority>();
    add(result, scopes.convert(jwt));
    add(result, roles.convert(jwt));
    return result;
  }

  private static void add(
      List<org.springframework.security.core.GrantedAuthority> target,
      Collection<org.springframework.security.core.GrantedAuthority> values) {
    if (values != null) target.addAll(values);
  }
}
