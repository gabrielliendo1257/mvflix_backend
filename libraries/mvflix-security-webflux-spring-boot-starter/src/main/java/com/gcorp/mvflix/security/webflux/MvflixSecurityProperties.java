package com.gcorp.mvflix.security.webflux;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mvflix.security")
public record MvflixSecurityProperties(String actuatorUsername, String actuatorPassword) {
  public MvflixSecurityProperties {
    if (actuatorUsername == null || actuatorUsername.isBlank()) actuatorUsername = "metrics";
    if (actuatorPassword == null || actuatorPassword.isBlank()) actuatorPassword = "change-me";
  }
}
