package com.gcorp.service.app.mvflix_activity.infrastructure.messaging;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PlaybackProgressedParserTest {
  private final PlaybackProgressedParser parser = new PlaybackProgressedParser(new ObjectMapper());
  @Test void parsesOptionalMediaAndDurationAsNull() {
    var event = parser.parse("{\"eventId\":\"7d9f3c1e-6c2d-4d79-9c3a-7fbcd5c6a2a1\",\"eventType\":\"PlaybackProgressed\",\"eventVersion\":1,\"producer\":\"mvflix-playback\",\"aggregate\":{\"type\":\"PlaybackSession\",\"id\":\"s-1\"},\"payload\":{\"ownerUsername\":\"Javier\",\"movieId\":101,\"positionSeconds\":12,\"completed\":false,\"sequence\":2}}");
    assertThat(event.mediaId()).isNull(); assertThat(event.durationSeconds()).isNull(); assertThat(event.sequence()).isEqualTo(2);
  }
  @Test void rejectsWrongAggregateOrSequence() {
    assertThatThrownBy(() -> parser.parse("{\"eventId\":\"7d9f3c1e-6c2d-4d79-9c3a-7fbcd5c6a2a1\",\"eventType\":\"PlaybackProgressed\",\"eventVersion\":1,\"producer\":\"mvflix-playback\",\"aggregate\":{\"type\":\"Movie\",\"id\":\"s-1\"},\"payload\":{\"ownerUsername\":\"u\",\"movieId\":1,\"positionSeconds\":1,\"completed\":false,\"sequence\":0}}"))
         .isInstanceOf(IllegalArgumentException.class);
  }
  @Test void rejectsNonPositiveIdentifiers() {
    assertThatThrownBy(() -> parser.parse("{\"eventId\":\"7d9f3c1e-6c2d-4d79-9c3a-7fbcd5c6a2a1\",\"eventType\":\"PlaybackProgressed\",\"eventVersion\":1,\"producer\":\"mvflix-playback\",\"aggregate\":{\"type\":\"PlaybackSession\",\"id\":\"s-1\"},\"payload\":{\"ownerUsername\":\"u\",\"movieId\":0,\"positionSeconds\":1,\"completed\":false,\"sequence\":1}}"))
         .isInstanceOf(IllegalArgumentException.class);
  }
  @Test void acceptsCompletedEventsForTheSameWatchProjection() {
    var event = parser.parse("{\"eventId\":\"7d9f3c1e-6c2d-4d79-9c3a-7fbcd5c6a2a1\",\"eventType\":\"PlaybackCompleted\",\"eventVersion\":1,\"producer\":\"mvflix-playback\",\"aggregate\":{\"type\":\"PlaybackSession\",\"id\":\"s-1\"},\"payload\":{\"ownerUsername\":\"Javier\",\"movieId\":101,\"positionSeconds\":7200,\"completed\":true,\"sequence\":4}}");
    assertThat(event.eventType()).isEqualTo("PlaybackCompleted");
    assertThat(event.completed()).isTrue();
  }
}
