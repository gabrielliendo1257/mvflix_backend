package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AsyncApiParserContractTest {
  private final YAMLMapper mapper = new YAMLMapper();

  @Test
  void parsesActivityExamplesPublishedByOtherServices() throws Exception {
    var resource = Files.newInputStream(Path.of("..", "docs", "asyncapi", "mvflix-events.asyncapi.yaml"));
    assertThat(resource).as("AsyncAPI specification").isNotNull();
    var document = mapper.readValue(resource, new TypeReference<Map<String, Object>>() {});
    var components = map(document.get("components"));
    var messages = map(components.get("messages"));

    assertThat(parse(messages, "MediaIngestionCompleted")).isNotNull();
    assertThat(parse(messages, "CatalogItemAccessChanged")).isNotNull();
    assertThat(parse(messages, "UploadFailed")).isNotNull();
    assertThat(parse(messages, "PlaybackProgressed")).isNotNull();
  }

  private Object parse(Map<String, Object> messages, String message) throws Exception {
    var definition = map(messages.get(message));
    var examples = (List<?>) definition.get("examples");
    var payload = examples.get(0) instanceof Map<?, ?> example
        ? map(example).get("payload")
        : null;
    var json = mapper.writeValueAsString(payload);
    return switch (message) {
      case "MediaIngestionCompleted" -> new MediaIngestionActivityParser(mapper).parse(json);
      case "CatalogItemAccessChanged" -> new CatalogItemAccessChangedParser(mapper).parse(json);
      case "UploadFailed" -> new UploadFailedParser(mapper).parse(json);
      case "PlaybackProgressed" -> new PlaybackProgressedParser(mapper).parse(json);
      default -> throw new IllegalArgumentException("Unsupported test message: " + message);
    };
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
