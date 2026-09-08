package com.gcorp.mvflix.security.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;

class MvflixSecurityAutoConfigurationTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
           ReactiveSecurityAutoConfiguration.class,
           ReactiveUserDetailsServiceAutoConfiguration.class,
           MvflixSecurityAutoConfiguration.class))
      .withUserConfiguration(TestWebSecurity.class);

  @Test
  void createsOnePropertiesBeanAndBothSecurityChains() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(MvflixSecurityProperties.class);
      assertThat(context.getBeansOfType(MvflixSecurityProperties.class)).hasSize(1);
      assertThat(context).hasBean("mvflixActuatorSecurityWebFilterChain");
      assertThat(context).hasSingleBean(MvflixJwtAuthenticationConverter.class);
    });
  }

  @Test
  void consumerBeansOverrideStarterDefaults() {
    runner.withUserConfiguration(ConsumerOverrides.class).run(context -> {
      assertThat(context).getBean(MvflixJwtAuthenticationConverter.class)
          .isSameAs(ConsumerOverrides.CONVERTER);
      assertThat(context).getBean(MvflixUnauthorizedHandler.class)
          .isSameAs(ConsumerOverrides.UNAUTHORIZED);
      assertThat(context).getBean(MvflixAccessDeniedHandler.class)
          .isSameAs(ConsumerOverrides.DENIED);
    });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebFluxSecurity
  static class TestWebSecurity {
  }

  @Configuration(proxyBeanMethods = false)
  static class ConsumerOverrides {
    static final MvflixJwtAuthenticationConverter CONVERTER = new MvflixJwtAuthenticationConverter();
    static final MvflixUnauthorizedHandler UNAUTHORIZED = new MvflixUnauthorizedHandler();
    static final MvflixAccessDeniedHandler DENIED = new MvflixAccessDeniedHandler();

    @Bean
    MvflixJwtAuthenticationConverter converter() {
      return CONVERTER;
    }

    @Bean
    MvflixUnauthorizedHandler unauthorized() {
      return UNAUTHORIZED;
    }

    @Bean
    MvflixAccessDeniedHandler denied() {
      return DENIED;
    }
  }
}
