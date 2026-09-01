package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.RetryableTopic;

class KafkaCatalogItemAccessChangedListenerTest {
  @Test
  void configuresRetriesAndDltForCatalogAccessEvents() throws Exception {
    Method listener = KafkaCatalogItemAccessChangedListener.class
        .getMethod("onMessage", String.class);
    RetryableTopic retry = listener.getAnnotation(RetryableTopic.class);

    assertThat(retry).isNotNull();
    assertThat(retry.attempts()).isEqualTo("4");
    assertThat(KafkaCatalogItemAccessChangedListener.class.getMethod("onDlt",
        org.apache.kafka.clients.consumer.ConsumerRecord.class, Exception.class)
        .isAnnotationPresent(DltHandler.class)).isTrue();
  }

  @Test
  void incrementsCatalogAccessDltMetric() {
    var meters = new SimpleMeterRegistry();
    var listener = new KafkaCatalogItemAccessChangedListener(null, null, meters);

    listener.onDlt(null, new RuntimeException("failed"));

    assertThat(meters.get("mvflix_activity_catalog_access_kafka_dlt_total").counter().count())
        .isEqualTo(1.0);
  }
}
