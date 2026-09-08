package com.gcorp.mvflix.security.webflux;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mvflix.security")
public record MvflixSecurityProperties(
    @DefaultValue("metrics") String actuatorUsername,
    @DefaultValue("change-me") String actuatorPassword) {}
