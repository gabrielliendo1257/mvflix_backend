package com.gcorp.mvflix.security.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class MvflixJwtAuthenticationConverterTest {
  private final MvflixJwtAuthenticationConverter converter = new MvflixJwtAuthenticationConverter();

  @Test
  void combinesScopesAndRolesWithoutChangingTheirSemantics() {
    var jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject("viewer-1")
        .issuedAt(Instant.EPOCH)
        .expiresAt(Instant.MAX)
        .claim("scope", "movies.read storage.write")
        .claim("roles", List.of("ADMIN"))
        .build();

    var authorities = converter.convert(jwt).block().getAuthorities();

    assertThat(authorities).extracting("authority")
        .containsExactlyInAnyOrder("SCOPE_movies.read", "SCOPE_storage.write", "ADMIN");
  }
}
