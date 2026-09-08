package com.gcorp.mvflix.security.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;

class MvflixSecurityAutoConfigurationTest {
  private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
           ReactiveSecurityAutoConfiguration.class,
           ReactiveUserDetailsServiceAutoConfiguration.class,
           MvflixSecurityAutoConfiguration.class))
      .withUserConfiguration(TestWebSecurity.class);

  private final ApplicationContextRunner nonReactiveRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(MvflixSecurityAutoConfiguration.class));

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

  @Test
  void isNotActivatedForNonReactiveApplications() {
    nonReactiveRunner.run(context -> assertThat(context)
        .doesNotHaveBean(MvflixSecurityProperties.class));
  }

  @Test
  void rejectsTheDefaultPasswordInProduction() {
    runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("mvflix.security.actuator-password must be configured in production");
    });
  }

  @Test
  void bindsActuatorCredentialsFromSharedProperties() {
    runner.withPropertyValues(
        "mvflix.security.actuator-username=prometheus",
        "mvflix.security.actuator-password=real-secret").run(context -> {
          var properties = context.getBean(MvflixSecurityProperties.class);
          assertThat(properties.actuatorUsername()).isEqualTo("prometheus");
          assertThat(properties.actuatorPassword()).isEqualTo("real-secret");
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
